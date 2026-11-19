"""Install the first-person overlay on the exact signed owner v34 runtime."""
import argparse
import hashlib
import io
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

PACKAGE = "net/runelite/standalone/"


def stage(assets, baseline, classes, patches):
    overlays = {
        "bridge.jar": {PACKAGE + name + ".class": (patches / (name + ".class")).read_bytes()
                       for name in ("Friend", "Scene", "MobileLayout")},
        "owner-plugins.jar": {name: (classes / name).read_bytes()
                              for name in ("rs117/hd/HdPlugin.class", "rs117/hd/HdPluginConfig.class")},
    }
    overlays["bridge.jar"][PACKAGE + "FirstPersonCamera.class"] = (
        classes / PACKAGE / "FirstPersonCamera.class").read_bytes()

    with ZipFile(baseline) as apk:
        for name, entries in overlays.items():
            remaining = entries.copy()
            target = assets / name
            pending = target.with_suffix(".pending")
            with ZipFile(io.BytesIO(apk.read("assets/jvm/" + name))) as original, ZipFile(pending, "w") as new:
                for info in original.infolist():
                    value = remaining.pop(info.filename, None)
                    new.writestr(info, original.read(info) if value is None else value)
                for entry, value in remaining.items():
                    new.writestr(entry, value, compress_type=ZIP_DEFLATED)
            pending.replace(target)
        for item in apk.infolist():
            if item.is_dir() or not item.filename.startswith("assets/jvm/"):
                continue
            relative = item.filename.removeprefix("assets/jvm/")
            if relative not in overlays and relative != "runtime.tag":
                assert (assets / relative).read_bytes() == apk.read(item), relative

    digest = hashlib.sha256()
    for name in overlays:
        digest.update((assets / name).read_bytes())
    (assets / "runtime.tag").write_text(f"newscape-first-person-35-{digest.hexdigest()[:24]}\n",
                                      encoding="ascii")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("assets", "baseline", "classes", "patches"):
        parser.add_argument(f"--{key}", type=Path, required=True)
    stage(**vars(parser.parse_args()))
