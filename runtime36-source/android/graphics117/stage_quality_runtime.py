"""Stage the scoped 117 HD additions on an already-extracted owner APK runtime."""
import argparse
import hashlib
import io
import shutil
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

CLASSES = {
    "owner-graphics.jar": (
        "rs117/hd/android/AndroidGl.class",
        "rs117/hd/android/RenderResolution.class",
    ),
    "owner-plugins.jar": (
        "rs117/hd/HdPlugin.class",
        "rs117/hd/HdPluginConfig.class",
    ),
}


def version_runtime(assets, baseline, classes, native):
    prefix = "assets/jvm/"
    library = "jre/lib/libnewscape117.so"
    tag = "runtime.tag"
    with ZipFile(baseline) as apk:
        for name, replacements in CLASSES.items():
            remaining = {entry: (classes / entry).read_bytes() for entry in replacements}
            target = assets / name
            pending = target.with_suffix(".pending")
            with ZipFile(io.BytesIO(apk.read(prefix + name))) as old, ZipFile(pending, "w") as jar:
                for entry in old.infolist():
                    value = remaining.pop(entry.filename, None)
                    jar.writestr(entry, old.read(entry) if value is None else value)
                for entry, value in remaining.items():
                    jar.writestr(entry, value, compress_type=ZIP_DEFLATED)
            pending.replace(target)
        shutil.copyfile(native, assets / library)
        fingerprint = hashlib.sha256()
        for name in (*CLASSES, library):
            fingerprint.update((assets / name).read_bytes())
        (assets / tag).write_text(f"newscape-117-quality-32-{fingerprint.hexdigest()[:24]}\n", encoding="ascii")
        allowed = (*CLASSES, library, tag)
        originals = {entry.filename[len(prefix):] for entry in apk.infolist()
                     if entry.filename.startswith(prefix) and not entry.is_dir()}
        if {file.relative_to(assets).as_posix() for file in assets.rglob("*") if file.is_file()} != originals:
            raise ValueError("The asset directory does not match the exact owner runtime inventory")
        for name in originals.difference(allowed):
            if (assets / name).read_bytes() != apk.read(prefix + name):
                raise ValueError(f"Unrelated runtime payload changed: {name}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("assets", "baseline", "classes", "native"):
        parser.add_argument(f"--{arg}", type=Path, required=True)
    version_runtime(**vars(parser.parse_args()))
