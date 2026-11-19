"""Overlay the checked native local-map tap classes onto the owner v35 bridge."""
import argparse
import hashlib
import io
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

PACKAGE = "net/runelite/standalone/"


def stage(assets, baseline, classes, entries):
    runtime = "assets/jvm/"
    replacement = {PACKAGE + name + ".class": (classes / (PACKAGE + name + ".class")).read_bytes()
                   for name in entries}

    with ZipFile(baseline) as owner:
        originals = sorted(n for n in owner.namelist() if n.startswith(runtime) and not n.endswith("/"))
        for source in originals:
            relative = source.removeprefix(runtime)
            if relative in ("bridge.jar", "runtime.tag"):
                continue
            if (assets / relative).read_bytes() != owner.read(source):
                raise ValueError(f"Unexpected staged runtime difference: {relative}")
        output = assets / "bridge.map-pending"
        with ZipFile(io.BytesIO(owner.read(runtime + "bridge.jar"))) as original, ZipFile(output, "w") as patched:
            for item in original.infolist():
                replacement_bytes = replacement.pop(item.filename, None)
                patched.writestr(item, original.read(item) if replacement_bytes is None else replacement_bytes)
            for name, value in sorted(replacement.items()):
                patched.writestr(name, value, compress_type=ZIP_DEFLATED)
        output.replace(assets / "bridge.jar")

    digest = hashlib.sha256((assets / "bridge.jar").read_bytes()).hexdigest()
    (assets / "runtime.tag").write_text(f"newscape-mobile-map-taps-36-{digest[:24]}\n", encoding="ascii")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("assets", "baseline", "classes"):
        parser.add_argument(f"--{name}", type=Path, required=True)
    parser.add_argument("--entries", nargs="+", required=True,
                        choices=("MobileMap", "MobileTap", "MusicPatchNode"))
    stage(**vars(parser.parse_args()))
