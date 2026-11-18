#!/usr/bin/env python3
"""Stage the verified Android Java payload and an iOS-only runtime."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
ARM64 = 0x0100000C
BUILD_VERSION = 0x32
PLATFORMS = {"device": 2, "simulator": 7}
ENGINE_CLASSPATH = (
    "owner-plugins.jar", "owner-graphics.jar", "bridge.jar",
    "client.jar", "cacio-tta-1.18.jar", "cacio-shared-1.18.jar",
)


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def mach_platform(binary: Path) -> int:
    with binary.open("rb") as stream:
        header = stream.read(32)
        if len(header) != 32 or header[:4] != bytes.fromhex("cffaedfe"):
            raise ValueError(f"Expected an arm64 Mach-O binary in {binary.name}")
        cpu, _, _, commands, size, _, _ = struct.unpack_from("<7I", header, 4)
        if cpu != ARM64 or size > 4 * 1024 * 1024:
            raise ValueError(f"Invalid arm64 Mach-O header in {binary.name}")
        table = stream.read(size)
    if len(table) != size:
        raise ValueError(f"Truncated Mach-O load commands in {binary.name}")
    offset = 0
    platform = None
    for _ in range(commands):
        if offset + 8 > size:
            raise ValueError(f"Invalid Mach-O load commands in {binary.name}")
        command, length = struct.unpack_from("<2I", table, offset)
        if length < 8 or offset + length > size:
            raise ValueError(f"Invalid Mach-O load command size in {binary.name}")
        if command == BUILD_VERSION:
            if length < 24 or platform is not None:
                raise ValueError(f"Invalid Mach-O platform record in {binary.name}")
            platform = struct.unpack_from("<I", table, offset + 8)[0]
        offset += length
    if platform is None or offset != size:
        raise ValueError(f"Could not establish the Apple platform of {binary.name}")
    return platform


def validate_jre(jre: Path, platform: str) -> None:
    for name in ("release", "lib/modules", "lib/server/libjvm.dylib", "lib/libawt_xawt.dylib"):
        if not (jre / name).is_file():
            raise ValueError(f"The configured iOS JRE is missing {name}")
    if not (jre / "legal").is_dir():
        raise ValueError("The iOS JRE must include its legal notices")
    release = (jre / "release").read_text(encoding="ascii")
    if 'JAVA_VERSION="21.' not in release:
        raise ValueError("Newscape is pinned to Java 21")
    for path in jre.rglob("*"):
        if path.is_symlink() and not path.resolve().is_relative_to(jre):
            raise ValueError(f"JRE symbolic link leaves its runtime directory: {path.name}")
        if path.is_file() and path.suffix in (".so", ".dll"):
            raise ValueError(f"Non-iOS library in configured JRE: {path.name}")
        if path.suffix == ".dylib" and path.is_file() and mach_platform(path) != PLATFORMS[platform]:
            raise ValueError(f"{path.name} is for the wrong Apple platform")


def tool(jdk: Path, name: str) -> str:
    return str(jdk / "bin" / (name + ".exe" if os.name == "nt" else name))


def install_117_variant(directory: Path, bundle: Path, baseline_sha: str) -> dict:
    manifest = json.loads((directory / "variant-manifest.json").read_text(encoding="utf-8"))
    if manifest.get("variant") != "ios-gles3-117hd-1.0.11" or manifest.get("baselineSha256") != baseline_sha:
        raise ValueError("The iOS 117 variant uses a different Android baseline")
    plugin = directory / "owner-plugins.jar"
    if digest(plugin) != manifest.get("pluginSha256"):
        raise ValueError("The iOS 117 variant JAR does not match its manifest")
    staged = bundle / plugin.name
    with ZipFile(staged) as original, ZipFile(plugin) as candidate:
        before = set(original.namelist())
        after = set(candidate.namelist())
        if len(before) != len(original.namelist()) or len(after) != len(candidate.namelist()):
            raise ValueError("Duplicate entry in the 117 plugin JAR")
        changed = {name for name in before & after if original.read(name) != candidate.read(name)}
        added = after - before
        if before - after or changed != set(manifest["changedEntries"]) or added != set(manifest["addedEntries"]):
            raise ValueError("The actual iOS plugin delta differs from its manifest")
        if "rs117/hd/HdPlugin.class" not in changed or "rs117/hd/IosSceneGeometry.class" not in added:
            raise ValueError("The 117 plugin is missing the iOS renderer or geometry backend")
        if not all(name.startswith("rs117/hd/") for name in changed | added):
            raise ValueError("The iOS variant contains changes to an unrelated plugin")
    shutil.copy2(plugin, staged)
    return manifest

def stage(apk: Path, jdk: Path, output: Path, jre: Path | None, platform: str, plugin: Path | None) -> None:
    pin = json.loads((ROOT / "Runtime/android-26-engine.json").read_text(encoding="utf-8"))
    if digest(apk) != pin["apkSha256"]:
        raise ValueError("The input APK does not match the pinned Android owner baseline")
    if output.exists():
        raise ValueError("Choose a fresh output directory")
    if jre is not None:
        validate_jre(jre, platform)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".newscape-runtime-", dir=output.parent) as tmp:
        work = Path(tmp)
        bundle = work / "Runtime"
        bundle.mkdir()
        jars = pin["runtimeJarSha256"]
        with ZipFile(apk) as archive:
            names = archive.namelist()
            for name, expected in jars.items():
                entry = "assets/jvm/" + name
                if names.count(entry) != 1:
                    raise ValueError(f"The pinned APK must contain one {name}")
                contents = archive.read(entry)
                if hashlib.sha256(contents).hexdigest() != expected:
                    raise ValueError(f"The APK contains a different {name}")
                (bundle / name).write_bytes(contents)
            legal = bundle / "licenses"
            legal.mkdir()
            for entry in names:
                relative = entry.removeprefix("assets/jvm/")
                if entry.startswith("assets/jvm/") and "/" not in relative and "LICENSE" in relative.upper():
                    (legal / relative).write_bytes(archive.read(entry))
            shutil.copy2(ROOT / "Licenses/RuneMobile.txt", legal / "RuneMobile-BSD.txt")

        variant = install_117_variant(plugin, bundle, jars["owner-plugins.jar"]) if plugin else None
        classes = work / "classes"
        classes.mkdir()
        classpath = os.pathsep.join(str(bundle / name) for name in ENGINE_CLASSPATH)
        sources = [str(path) for path in sorted((ROOT / "Runtime").glob("*.java"))]
        subprocess.run([tool(jdk, "javac"), "-J-Xmx128m", "-encoding", "UTF-8", "-cp", classpath,
                        "-d", str(classes), *sources], check=True)
        checks = work / "checks"
        checks.mkdir()
        check_path = os.pathsep.join((str(classes), classpath))
        checks_sources = [str(ROOT / "Checks" / filename)
                          for filename in ("IosInputCheck.java", "IosEngineAbiCheck.java")]
        subprocess.run([tool(jdk, "javac"), "-J-Xmx96m", "-encoding", "UTF-8", "-cp", check_path,
                        "-d", str(checks), *checks_sources], check=True)
        for check in ("IosInputCheck", "IosEngineAbiCheck"):
            subprocess.run([tool(jdk, "java"), "-Xverify:all", "-Xmx96m", "-cp",
                            os.pathsep.join((str(checks), check_path)), check], check=True)
        bridge = bundle / "newscape-ios-bridge.jar"
        subprocess.run([tool(jdk, "jar"), "cf", str(bridge), "-C", str(classes), "."], check=True)
        if jre is not None:
            shutil.copytree(jre, bundle / "jre", symlinks=True)
        report = {
            "engineBaseline": pin["apkSha256"],
            "classpath": [bridge.name, *ENGINE_CLASSPATH],
            "jarSha256": {name: digest(bundle / name) for name in (bridge.name, *ENGINE_CLASSPATH)},
            "ios117Variant": variant,
            "jrePlatform": platform if jre is not None else None,
            "jvmSha256": digest(jre / "lib/server/libjvm.dylib") if jre is not None else None,
        }
        (bundle / "stage.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        bundle.rename(output)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--host-jdk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--ios-jre", type=Path)
    parser.add_argument("--platform", choices=PLATFORMS, default="device")
    parser.add_argument("--ios-117-variant", type=Path, help="Verified variant output from Graphics117/build_ios_plugin.py")
    args = parser.parse_args()
    stage(args.apk.resolve(), args.host_jdk.resolve(), args.output.resolve(),
          args.ios_jre.resolve() if args.ios_jre else None, args.platform,
          args.ios_117_variant.resolve() if args.ios_117_variant else None)
    print(f"Staged {'iOS runtime' if args.ios_jre else 'verified portable payload'} at {args.output}")


if __name__ == "__main__":
    main()