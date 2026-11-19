"""Validate the shipped Mobile Ultra update against the signed owner v39 APK."""
import argparse
import hashlib
from io import BytesIO
import json
from pathlib import Path
import re
import zipfile

BASE = "8625404e44c9f4d7a94d8da3d0997172a3ac4e0d51df3c34eb732200ccbdca82"
PREFIX = "assets/jvm/"
ACTOR = "net/runelite/standalone/MobileHdRenderer.class"
LIGHTS = "net/runelite/standalone/UltraSceneLights.class"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def glsl(file, name):
    source = file.read_text()
    source = re.sub(r'^\s*#include "([^"]+\.inc)"', lambda m: (file.parent / m[1]).read_text(), source, flags=re.M)
    matches = re.search(r'const char\*\s+' + name + r'\s*=\s*((?:R"\(.*?\)"\s*)+);', source, re.S)
    if not matches: raise ValueError(f"Missing shader {name}")
    return "".join(re.findall(r'R"\((.*?)\)"', matches[1], re.S)).encode("ascii")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--classes", type=Path, required=True)
    parser.add_argument("--runtime", type=Path, help="Extract verified JARs for offline JVM tests")
    parser.add_argument("--receipt", type=Path)
    args = parser.parse_args()
    assert sha(args.baseline.read_bytes()) == BASE
    cpp = Path(__file__).resolve().parents[1] / "kronos/src/main/cpp"
    with zipfile.ZipFile(args.baseline) as old, zipfile.ZipFile(args.apk) as new:
        names = set(old.namelist())
        assert names == set(new.namelist()) and len(names) == len(new.namelist())
        differences = {n for n in names if old.read(n) != new.read(n)}
        allowed = {"AndroidManifest.xml","classes.dex","lib/arm64-v8a/libkronosmobile.so",PREFIX+"bridge.jar",PREFIX+"runtime.tag"}
        assert differences == allowed, differences
        with zipfile.ZipFile(BytesIO(old.read(PREFIX+"bridge.jar"))) as b, zipfile.ZipFile(BytesIO(new.read(PREFIX+"bridge.jar"))) as c:
            assert set(c.namelist()) == set(b.namelist()) | {LIGHTS} and len(c.namelist()) == len(set(c.namelist()))
            assert {n for n in b.namelist() if b.read(n) != c.read(n)} == {ACTOR}
            for n in (ACTOR,LIGHTS):
                assert c.read(n) == (args.classes / n).read_bytes()
        runtimeTag = new.read(PREFIX+"runtime.tag").decode("ascii").strip()
        assert runtimeTag == "newscape-mobile-ultra-40-" + sha(new.read(PREFIX+"bridge.jar"))[:24]
        native = new.read("lib/arm64-v8a/libkronosmobile.so")
        assert native[:4] == b"\x7fELF" and native[4] == 2 and int.from_bytes(native[18:20],"little") == 183
        shaders = {}
        for file,symbol in (("ultra_surface.h","ultraVertex"),("ultra_surface.h","ultraFragment"),("ultra.h","vertex"),("ultra.h","fragment"),
                            ("mobile_hd.cpp","sceneVertex"),("mobile_hd.cpp","sceneFragment"),("mobile_hd.cpp","shadowVertex"),("mobile_hd.cpp","shadowFragment"),
                            ("mobile_hd.cpp","uiVertex"),("mobile_hd.cpp","uiFragment"),("ultra_shadow_maps.h","casterFragment")):
            shader = glsl(cpp/file,symbol)
            assert shader+b"\0" in native, f"Compiled GLES shader differs from working source: {symbol}"
            shaders[symbol] = sha(shader)
        if args.runtime is not None:
            args.runtime.mkdir(parents=True,exist_ok=True)
            for n in names:
                if n.startswith(PREFIX) and n.endswith(".jar") and "/" not in n[len(PREFIX):]:
                    (args.runtime / Path(n).name).write_bytes(new.read(n))
    receipt = dict(versionCode=40, versionName="0.11.0-mobile-ultra-lighting", basedOnApkSha256=BASE,
                   sha256=sha(args.apk.read_bytes()), bytes=args.apk.stat().st_size, runtimeTag=runtimeTag,
                   changedApkEntries=sorted(differences), embeddedShaders=shaders, physicalDeviceVerified=False)
    if args.receipt: args.receipt.write_text(json.dumps(receipt,indent=2)+"\n")
    print(f"Verified {len(names)} APK entries, {len(shaders)} compiled GLES shaders, embedded renderer classes and runtime tag. SHA-256 {receipt['sha256']}")


if __name__ == "__main__":
    main()
