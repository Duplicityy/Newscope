"""Build the raid-click runtime over the exact signed v41 Android runtime."""

import argparse
import hashlib
from pathlib import Path
import shutil
import zipfile


BASELINE_SHA256 = "a59c2d561bca0a394e8f1541c8a838e304309ca8cadd024a590bf8bd16af9f25"
ASSETS = "assets/jvm/"
PKG = "net/runelite/standalone/"
REPLACED = ("WorldMapSection0", "OwnerAutomationClient", "OwnerAutomationClient$Request")
ADDED = ("RaidClickIndicator", "RaidClickIndicator$Click")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("stage", type=Path)
    parser.add_argument("--classes", type=Path)
    parser.add_argument("--assets", type=Path)
    args = parser.parse_args()
    if (args.classes is None) != (args.assets is None):
        parser.error("--classes and --assets must be specified together")
    with args.apk.open("rb") as apk_file:
        if hashlib.file_digest(apk_file, "sha256").hexdigest() != BASELINE_SHA256:
            raise ValueError("Expected the verified signed owner v41 APK")

    client_jar = args.stage / "patch-input" / "client.jar"
    with zipfile.ZipFile(args.apk) as apk:
        with apk.open(ASSETS + "client.jar") as src, client_jar.open("wb") as dst:
            shutil.copyfileobj(src, dst)
        if args.classes is not None:
            for entry in apk.infolist():
                if not entry.filename.startswith(ASSETS) or entry.is_dir():
                    continue
                relative = entry.filename[len(ASSETS):]
                if relative == "client.jar" or relative == "runtime.tag":
                    continue
                target = args.assets / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                with apk.open(entry) as src, target.open("wb") as dst:
                    shutil.copyfileobj(src, dst)

    with zipfile.ZipFile(client_jar) as original:
        if args.classes is None:
            (args.stage / "patch-input" / "WorldMapSection0.class").write_bytes(original.read(PKG + "WorldMapSection0.class"))
            return
        replaces = {PKG + name + ".class" for name in REPLACED}
        adds = {PKG + name + ".class" for name in ADDED}
        if not replaces <= set(original.namelist()) or adds & set(original.namelist()):
            raise ValueError("Unexpected client class layout")
        with zipfile.ZipFile(args.assets / "client.jar", "w") as updated:
            for entry in original.infolist():
                data = (args.classes / entry.filename).read_bytes() if entry.filename in replaces else original.read(entry)
                updated.writestr(entry, data)
            for entry in sorted(adds):
                updated.write(args.classes / entry, arcname=entry, compress_type=zipfile.ZIP_DEFLATED)

    digest = hashlib.sha256((args.assets / "client.jar").read_bytes()).hexdigest()
    tag = "newscape-raid-clicks-42-" + digest[:24]
    (args.assets / "runtime.tag").write_bytes((tag + "\n").encode("ascii"))
    print(tag)


if __name__ == "__main__":
    main()
