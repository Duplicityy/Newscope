"""Replay genuine wearable capes from the local JS5 cache and signed Android client.

Example arguments: --cache CACHE --cache-tools CONTENT_TOOLS --client CANDIDATE_JVM_ASSETS
--v39-apk FROZEN_APK --jdk JDK8 --browser EDGE_EXE --out EMPTY_SCRATCH_DIRECTORY
The browser runs headless. Output contains private cache-derived pictures for local QA.
"""
import argparse
import base64
import hashlib
import html
import json
import math
from pathlib import Path
import re
import struct
import subprocess
import sys
from zipfile import ZipFile

HERE = Path(__file__).resolve().parent
CPP = HERE.parents[2] / "kronos" / "src" / "main" / "cpp"


def shader(filename, symbol):
    source = (CPP / filename).read_text()
    source = re.sub(r'#include "(ultra_shadow_filter\.inc)"', lambda m: (CPP / m[1]).read_text(), source)
    declaration = re.search(r'const char\*\s+' + symbol + r'\s*=((?:\s*R"\(.*?\)")+)\s*;', source, re.S)
    if not declaration:
        raise ValueError("Missing shader " + symbol)
    return "".join(re.findall(r'R"\((.*?)\)"', declaration[1], re.S))


def check_presentation_policy():
    # This fixture replays both policies deliberately. Also bind the fixed expectation
    # to the actual device code, including where it relinquishes state to the UI.
    native = re.sub(r'\s+', '', (CPP / "mobile_hd.cpp").read_text())
    selection = "if(premium)glFrontFace(GL_CCW);glDisable(GL_CULL_FACE);"
    start = native.index(selection)
    opaque = native.index('glUniform1i(glGetUniformLocation(scene,"phase"),0)', start)
    transparent = native.index('glUniform1i(glGetUniformLocation(scene,"phase"),1)', opaque)
    resolved = native.index('ultra::finish(', transparent)
    native.index('glUseProgram(uiProgram)', resolved)
    if "glEnable(GL_CULL_FACE)" in native[start:resolved]:
        raise AssertionError("Hardware culling would remove valid scenery")


def extract_v39(apk, target):
    signatures = {
        "sceneVertex": "7d522830a72ad2ed4cd162c3c3bb04cb994ee2f749e46d1a6386d3df5b84d1d7",
        "sceneFragment": "b67566189f6341abb866ad980167fa1418b7fea0ae9ad33b6e3c56b30016b0d3",
        "resolveVertex": "0bed1886e1f19ccd383bd588e31e3ac181e463047184dd272b4f49f3333fffd6",
        "resolveFragment": "01b5a8ab11804d3a8f20e808d1b9d321512bd6fe0f934766b93c418fb7c68002"}
    target.mkdir(exist_ok=True)
    with ZipFile(apk) as z:
        native = z.read("lib/arm64-v8a/libkronosmobile.so")
        for name in ("client.jar", "bridge.jar"):
            (target / name).write_bytes(z.read("assets/jvm/" + name))
    found = {hashlib.sha256(m[0]).hexdigest(): m[0] for m in re.finditer(rb'#version 300 es[^\x00]{60,23000}', native)}
    return {n: found[h].decode() for n, h in signatures.items()}


def check_world_tiles(preview):
    """Compare actual captured grid triangles with the facing of their 3-D world normals."""
    params = json.loads((preview / "world-scene.json").read_text())["parameters"]
    mesh = struct.iter_unpack("<13f", (preview / "world-mesh.bin").read_bytes())
    pitch = (params[9] & 2047) * math.pi / 1024
    yaw = (params[9] >> 11) * math.pi / 1024
    sp, cp, sy, cy = math.sin(pitch), math.cos(pitch), math.sin(yaw), math.cos(yaw)
    front = back = 0
    for triangle in zip(mesh, mesh, mesh):
        if any(v[2] <= 50 for v in triangle):
            continue
        x = [v[0] / v[2] * params[7] + params[5] for v in triangle]
        y = [v[1] / v[2] * params[7] + params[6] for v in triangle]
        if min(x) >= params[3] or max(x) <= 0 or min(y) >= params[4] or max(y) <= 0:
            continue
        normal = triangle[0][7:10]
        if normal[1] > -0.5:
            continue
        world = []
        for v in triangle:
            forward = v[2] * cp - v[1] * sp
            world.append((v[0] * cy - forward * sy, v[1] * cp + v[2] * sp, forward * cy + v[0] * sy))
        # Complete up-facing square-grid triangles, regardless of camera origin.
        axes = ([v[j] for v in world] for j in (0, 2))
        if any(abs(max(a) - min(a) - 128) > .1 or
               any(min(abs(v - min(a)), abs(v - max(a))) > .1 for v in a) for a in axes):
            continue
        ray_dot = sum(a * b for a, b in zip(normal, world[0]))
        if abs(ray_dot) < .05:
            continue
        area = (x[0] - x[1]) * (y[2] - y[1]) - (x[2] - x[1]) * (y[0] - y[1])
        if (area > 0) != (ray_dot < 0):
            raise AssertionError("Captured world tile disagrees with the device front-face convention")
        if area > 0:
            front += 1
        else:
            back += 1
    if front == 0:
        raise AssertionError("World fixture contained no visible grid triangles")
    return {"visibleGridTriangles": front, "backFacingGridTriangles": back}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("cache", "cache-tools", "client", "v39-apk", "jdk", "browser", "out"):
        parser.add_argument("--" + arg, type=Path, required=True)
    parser.add_argument("--world-preview", type=Path, help="Optional frozen world-scene.json/world-mesh.bin directory")
    args = parser.parse_args()
    check_presentation_policy()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    old_shaders = extract_v39(args.v39_apk, out / "v39")
    sys.path.insert(0, str(args.cache_tools))
    from cache_store import Store
    with Store(args.cache) as cache:
        for model in (72204, 72205, 70827, 70828):
            group, file = divmod(model - 65536, 512)
            (out / f"model-{model}.bin").write_bytes(cache.files(7, 65408 + group)[file])
        records = cache.files(9, 0)
        for texture in (59, 40):
            record = records[texture]
            assert record[3] == 1
            sprite = int.from_bytes(record[4:6], "big")
            (out / f"texture-{texture}.bin").write_bytes(record)
            (out / f"sprite-{texture}.bin").write_bytes(cache.files(8, sprite)[0])

    import os
    classpath = os.pathsep.join(str(args.client / name) for name in ("bridge.jar", "client.jar"))
    subprocess.run([args.jdk / "bin" / "javac", "-J-Xmx128m", "-cp", classpath, "-d", out,
                    HERE / "UltraCapeCapture.java"], check=True)
    for mode, jars in [("v39", out / "v39"), ("player", args.client), ("scenery", args.client)]:
        runtime = os.pathsep.join(str(jars / n) for n in ("bridge.jar", "client.jar"))
        subprocess.run([args.jdk / "bin" / "java", "-Xmx128m", "-Djava.awt.headless=true", "-Duser.home=" + str(out),
                        "-cp", str(out) + os.pathsep + runtime, "net.runelite.standalone.UltraCapeCapture", out, mode], check=True)

    def encode(file):
        return base64.b64encode(file.read_bytes()).decode("ascii")
    fixtures = {
        "atlases": {k: encode(out / f"atlas-{k}.rgba") for k in (59, 40)},
        "meshes": {},
        "oldMeshes": {},
    }
    for model in sorted(out.glob("player-*.mesh")):
        name = model.stem[7:]
        old = out / ("v39-" + name + ".mesh")
        prior = old.read_bytes()
        if prior != (out / ("scenery-" + name + ".mesh")).read_bytes():
            raise AssertionError("Changed vertex capture for non-player " + name)
        new = model.read_bytes()
        if len(new) != len(prior) or any(new[i:i+48] != prior[i:i+48] for i in range(0, len(new), 52)):
            raise AssertionError("Candidate changed cape geometry, light, UVs or opacity " + name)
        fixtures["meshes"][name] = encode(model)
        fixtures["oldMeshes"][name] = encode(old)
    programs = {key: shader(file, symbol) for key, file, symbol in (
        ("sceneVertex","ultra_surface.h","ultraVertex"), ("sceneFragment","ultra_surface.h","ultraFragment"),
        ("resolveVertex","ultra.h","vertex"), ("resolveFragment","ultra.h","fragment"),
        ("casterVertex","mobile_hd.cpp","shadowVertex"), ("casterFragment","mobile_hd.cpp","shadowFragment"))}
    (out / "fixtures.js").write_text("const fixture=" + json.dumps(fixtures) + ";const shaders=" + json.dumps(programs)
                                     + ";const previous=" + json.dumps(old_shaders) + ";")
    (out / "replay.js").write_bytes((HERE / "replay.js").read_bytes())
    page = out / "cape-check.html"
    page.write_text('<!doctype html><meta charset="utf-8"><canvas id="canvas" width="320" height="360"></canvas>'
                    '<pre id="result"></pre><script src="fixtures.js"></script><script src="replay.js"></script>')
    browser = subprocess.run([args.browser, "--headless", "--use-gl=angle", "--use-angle=swiftshader",
                              "--enable-unsafe-swiftshader", "--disable-gpu-sandbox", "--no-first-run",
                              "--disable-extensions", "--disable-background-networking",
                              "--user-data-dir=" + str(out / "browser"), "--dump-dom", page.as_uri()],
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=75, check=True)
    dom = browser.stdout.decode("utf-8", "replace")
    result = re.search(r'<pre id="result">(.*?)</pre>', dom, re.S)
    if not result or not result[1]:
        raise RuntimeError(browser.stderr.decode("utf-8", "replace")[-1500:] + dom[:600])
    data = json.loads(html.unescape(result[1]))
    if "error" in data:
        raise AssertionError(data)
    if args.world_preview:
        data["worldTiles"] = check_world_tiles(args.world_preview)
    for key, url in data.pop("images").items():
        (out / (key + ".png")).write_bytes(base64.b64decode(url.split(",")[1]))
    (out / "cape-results.json").write_text(json.dumps(data, indent=2))
    print("48 views passed. Per-view material changes and texture-motion checks:", out / "cape-results.json")


if __name__ == "__main__":
    main()
