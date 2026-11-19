"""Stage the signed v42 runtime with the updated owner Slayer transport classes."""
import argparse
import hashlib
import io
from pathlib import Path
import zipfile

BASE_SHA256 = "9a3407f1df9335cc584871fabde9e0af7d88403cea8e4093193100e23c6e3583"
PREFIX = "assets/jvm/"
OWNER_TRANSPORT = "net/runelite/standalone/OwnerAutomationClient"
CLASSES = {OWNER_TRANSPORT + suffix + ".class" for suffix in ("", "$Request")}


def stage(apk, assets, classes):
    with apk.open("rb") as file:
        if hashlib.file_digest(file, "sha256").hexdigest() != BASE_SHA256:
            raise ValueError("Expected the signed Newscape owner v42 APK")
    replacements = {name: (classes / name).read_bytes() for name in CLASSES}
    with zipfile.ZipFile(apk) as original:
        for name in original.namelist():
            if not name.startswith(PREFIX) or name.endswith("/"):
                continue
            relative = name[len(PREFIX):]
            if relative in {"client.jar", "runtime.tag"}:
                continue
            target = assets / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(original.read(name))
        with zipfile.ZipFile(io.BytesIO(original.read(PREFIX + "client.jar"))) as base:
            if not CLASSES <= set(base.namelist()):
                raise ValueError("Missing v42 transport classes")
            temporary = assets / "patched-client.jar"
            with zipfile.ZipFile(temporary, "w") as updated:
                for entry in base.infolist():
                    data = replacements.get(entry.filename)
                    updated.writestr(entry, base.read(entry) if data is None else data)
            temporary.replace(assets / "client.jar")
    digest = hashlib.sha256((assets / "client.jar").read_bytes()).hexdigest()
    (assets / "runtime.tag").write_bytes(("newscape-slayer-settings-43-" + digest[:24] + "\n").encode("ascii"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("assets", type=Path)
    parser.add_argument("classes", type=Path)
    args = parser.parse_args()
    stage(args.apk, args.assets, args.classes)
