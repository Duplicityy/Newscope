"""Stage the automation tick fix over the exact signed owner v38 runtime."""

import argparse
import hashlib
from pathlib import Path
import shutil
import zipfile

BASELINE_SHA256 = "f702ad0ed60ab116f5b6be6bec35dad9cfc6f98f30890001b0ac7cbfefa256d1"
ASSETS = "assets/jvm/"
PACKAGE = "net/runelite/standalone/"
MOBILE_FRAME = PACKAGE + "MobileFrame.class"
TRANSPORT = PACKAGE + "OwnerAutomationClient"


def copy_entry(archive, name, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    with archive.open(name) as source, target.open("wb") as output:
        shutil.copyfileobj(source, output)


def update_jar(source, destination, changes):
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(destination, "w") as updated:
        if not changes.keys() <= set(original.namelist()):
            raise ValueError(f"Missing patch entries in {source.name}")
        for entry in original.infolist():
            data = changes[entry.filename].read_bytes() if entry.filename in changes else original.read(entry)
            updated.writestr(entry, data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="Original signed owner v38 APK")
    parser.add_argument("stage", type=Path, help="Local packaging work directory")
    parser.add_argument("--classes", type=Path, help="Patched MobileFrame and compiled transport classes")
    parser.add_argument("--assets", type=Path, help="Destination JVM assets directory")
    args = parser.parse_args()
    if (args.classes is None) != (args.assets is None):
        parser.error("--classes and --assets must be specified together")

    with args.apk.open("rb") as source:
        if hashlib.file_digest(source, "sha256").hexdigest() != BASELINE_SHA256:
            raise ValueError("Expected the verified signed owner v38 APK")
    base = args.stage / "baseline"
    with zipfile.ZipFile(args.apk) as apk:
        for name in ("bridge.jar", "client.jar", "owner-plugins.jar", "owner-graphics.jar"):
            copy_entry(apk, ASSETS + name, base / name)
        if args.classes is None:
            with zipfile.ZipFile(base / "bridge.jar") as bridge:
                copy_entry(bridge, MOBILE_FRAME, base / "MobileFrame.class")
            return
        for name in apk.namelist():
            if name.startswith(ASSETS) and not name.endswith("/"):
                copy_entry(apk, name, args.assets / name[len(ASSETS):])

    client = {entry + ".class": args.classes / (entry + ".class") for entry in (TRANSPORT, TRANSPORT + "$Request")}
    update_jar(base / "client.jar", args.assets / "client.jar", client)
    update_jar(base / "bridge.jar", args.assets / "bridge.jar", {MOBILE_FRAME: args.classes / MOBILE_FRAME})
    digest = hashlib.sha256()
    for name in ("bridge.jar", "client.jar", "owner-plugins.jar", "owner-graphics.jar"):
        digest.update((args.assets / name).read_bytes())
    tag = "newscape-owner-automation-39-" + digest.hexdigest()[:24]
    (args.assets / "runtime.tag").write_bytes((tag + "\n").encode("ascii"))
    print(tag)


if __name__ == "__main__":
    main()
