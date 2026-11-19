"""Replay the archived game capture with the old and current production Ultra shaders.

Requires Python 3, Pillow, Git and a Chromium browser with WebGL 2. No server or client process.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess

from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
NATIVE = Path("kronos/src/main/cpp")
V39 = "f2442538e9abff1718282b8dc7492843c3f1173e"


def shader_file(revision, relative):
    if isinstance(revision, Path):
        return (revision / relative).read_text(encoding="utf-8")
    if revision:
        return subprocess.check_output(["git", "-C", str(ROOT), "show", revision + ":" + (NATIVE / relative).as_posix()]).decode()
    return (ROOT / NATIVE / relative).read_text(encoding="utf-8")


def shader(revision, path, name):
    code = shader_file(revision, path)
    pattern = r'const char\*\s+' + name + r'\s*=\s*R"\((.*?)\)";'
    match = re.search(pattern, code, re.DOTALL)
    if not match:
        raise ValueError(f"Shader {path}/{name} missing")
    def include(match):
        raw = shader_file(revision, match.group(1)).strip()
        assert raw.startswith('R"(') and raw.endswith(')"')
        return raw[3:-2]
    return re.sub(r'\)"\s*#include\s+"([^"]+)"\s*R"\(', include, match.group(1))


def encoded(raw):
    return base64.b64encode(raw).decode("ascii")


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--capture", type=Path, required=True, help="Archive containing world-scene.json and world-mesh.bin")
    ap.add_argument("--output", type=Path, required=True, help="Outside the source tree; writes local cache-derived PNGs")
    ap.add_argument("--browser", type=Path, required=True)
    comparisons = ap.add_mutually_exclusive_group()
    comparisons.add_argument("--compare-native", type=Path, help="Staged native source from immediately before the optimization")
    comparisons.add_argument("--compare-phase-baseline", action="store_true", help="Use the archived pre-optimization vertex shader")
    opts = ap.parse_args()
    cap, out = opts.capture, opts.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    metadata = json.loads((cap / "world-scene.json").read_text())
    ui = Image.open(cap / "world-ui.png").convert("RGBA")
    assert ui.size == (metadata["width"], metadata["height"])
    mesh = (cap / "world-mesh.bin").read_bytes()
    assert len(mesh) == metadata["vertices"] * 52
    sources = {}
    revisions = [("old", V39), ("current", None)]
    if opts.compare_native:
        revisions.append(("preclip", opts.compare_native))
    for key, version in revisions:
        sources[key] = {n: shader(version, f, n) for f, n in (
            ("ultra_surface.h", "ultraVertex"), ("ultra_surface.h", "ultraFragment"),
            ("ultra.h", "vertex"), ("ultra.h", "fragment"), ("mobile_hd.cpp", "shadowVertex"),
            ("mobile_hd.cpp", "uiVertex"), ("mobile_hd.cpp", "uiFragment"))}
        sources[key]["caster"] = shader(version, "mobile_hd.cpp", "shadowFragment") if key == "old" else shader(version, "ultra_shadow_maps.h", "casterFragment")
    if opts.compare_phase_baseline:
        sources["preclip"] = dict(sources["current"], ultraVertex=(HERE / "preclip.vert").read_text(encoding="utf-8"))
    if "preclip" in sources:
        changed = [name for name, old in sources["preclip"].items() if old != sources["current"][name]]
        if changed != ["ultraVertex"]:
            raise RuntimeError("A/B requires exactly one changed surface vertex shader, found " + repr(changed))
    fixtures = {"metadata": metadata, "mesh": encoded(mesh), "ui": encoded(ui.tobytes()),
                "layers": [encoded((cap / name).read_bytes()) for name in metadata["textureFiles"]],
                "matrices": json.loads((HERE / "camera-matrices.json").read_text()), "source": sources}
    (out / "fixtures.js").write_text("const capture = " + json.dumps(fixtures, separators=(",", ":")) + ";", encoding="utf-8")
    (out / "world.js").write_text((HERE / "replay.js").read_text(), encoding="utf-8")
    page = out / "world.html"
    page.write_text('<!doctype html><meta charset="utf-8"><canvas></canvas><pre id="result">pending</pre><pre id="images"></pre><script src="fixtures.js"></script><script src="world.js"></script>')
    result = subprocess.run([str(opts.browser), "--headless", "--disable-gpu-sandbox", "--use-gl=angle", "--use-angle=swiftshader",
                             "--enable-unsafe-swiftshader", "--disable-component-update", "--disable-extensions", "--disable-sync",
                             "--disable-background-networking", "--no-first-run", "--no-default-browser-check", "--renderer-process-limit=1",
                             "--user-data-dir=" + str(out / "browser-profile"), "--dump-dom", page.as_uri()],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180, check=True)
    dom = result.stdout.decode("utf-8", errors="replace")
    def output(name):
        match = re.search(r'<pre id="' + name + r'">(.*?)</pre>', dom, re.DOTALL)
        if not match or not match[1].startswith("{"):
            raise RuntimeError("Incomplete browser run: " + result.stderr.decode(errors="replace")[-1200:])
        return json.loads(match.group(1))
    report = output("result")
    report["snapshotBytesSha256"] = hashlib.sha256(mesh).hexdigest()
    report["previousShaderRevision"] = V39
    report["shaderSha256"] = {rev: {k: hashlib.sha256(v.encode()).hexdigest() for k, v in values.items()} for rev, values in sources.items()}
    (out / "world-results.json").write_text(json.dumps(report, indent=2) + "\n")
    if "error" in report:
        raise RuntimeError(report["error"])
    for name, png in output("images").items():
        (out / (name + ".png")).write_bytes(base64.b64decode(png))
    print(json.dumps({k: v for k, v in report.items() if k != "shaderSha256"}, indent=2))
    if not report["pass"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
