"""Stage the Mobile Ultra capture onto the exact v39 owner APK while retaining its complete embedded client."""
import argparse
import hashlib
from pathlib import Path
import shutil
import zipfile

BASE_SHA = "8625404e44c9f4d7a94d8da3d0997172a3ac4e0d51df3c34eb732200ccbdca82"
RENDERER_SHA = "5005aa31261a285740ce0d3d359c4a61363e3befb76d306bea141c4369a823d1"
PREFIX = "assets/jvm/"
PACKAGE = "net/runelite/standalone/"
CLASSES = (PACKAGE + "MobileHdRenderer.class", PACKAGE + "UltraSceneLights.class")


def digest(path):
    with path.open("rb") as data:
        return hashlib.file_digest(data, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("assets", type=Path)
    parser.add_argument("--classes", type=Path, help="Two capture classes built against the extracted v39 runtime")
    args = parser.parse_args()
    if digest(args.apk) != BASE_SHA:
        raise ValueError("Expected the verified signed owner v39 APK")
    with zipfile.ZipFile(args.apk) as apk:
        for name in apk.namelist():
            if name.startswith(PREFIX) and not name.endswith("/"):
                dest = args.assets / name[len(PREFIX):]
                dest.parent.mkdir(parents=True, exist_ok=True)
                with apk.open(name) as src, dest.open("wb") as output:
                    shutil.copyfileobj(src, output)
    if args.classes is None:
        return
    bridge = args.assets / "bridge.jar"
    temp = bridge.with_suffix(".tmp")
    with zipfile.ZipFile(bridge) as old, zipfile.ZipFile(temp, "w") as new:
        if hashlib.sha256(old.read(CLASSES[0])).hexdigest() != RENDERER_SHA or CLASSES[1] in old.namelist():
            raise ValueError("Unexpected embedded renderer version")
        for entry in old.infolist():
            data = (args.classes / CLASSES[0]).read_bytes() if entry.filename == CLASSES[0] else old.read(entry)
            new.writestr(entry, data)
        info = zipfile.ZipInfo(CLASSES[1], old.getinfo(CLASSES[0]).date_time)
        info.compress_type = zipfile.ZIP_DEFLATED
        new.writestr(info, (args.classes / CLASSES[1]).read_bytes())
    temp.replace(bridge)
    tag = "newscape-mobile-ultra-40-" + digest(bridge)[:24]
    (args.assets / "runtime.tag").write_bytes((tag + "\n").encode("ascii"))
    print(tag)


if __name__ == "__main__":
    main()
