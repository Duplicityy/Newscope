# Frozen world render regression

This host replay renders an archived Newscape bridge scene through the original v39 and current Mobile Ultra shaders. The capture supplies the 91,434 vertices, original animation pose, nine texture layers and native UI. The shaders, both caster paths and the resolve are read directly from the client source. The included matrices are CPU outputs for the archived camera. Its pitch and yaw are known. The historical capture did not record an absolute origin, so the replay uses (0, 0, 0) as the origin.

Supply the private capture directory, a local output directory and a Chromium browser supporting WebGL 2.

```powershell
python checks/ultra-world/check_world.py --capture "<directory containing world-mesh.bin and world-scene.json>" --output "<private output directory>" --browser "<path to msedge.exe or chrome.exe>"
```

The only Python package required is Pillow. Git must be able to read the pinned v39 source revision. The script writes three full-size PNGs, the individual shader hashes and a pixel-count report under the supplied output path. It opens a separate headless browser profile and renders entirely offline.

Each timing uses one warmup followed by five synchronized single-pixel readbacks of the full scene, resolve and UI. The software rasterizer measurements are host comparisons. The S25 Ultra uses a different GPU and driver.

To compare the final vertex phase optimization against its exact previous shader, add `--compare-phase-baseline`. You can instead pass `--compare-native "<directory of pre-optimization native sources>"` to verify that only the vertex shader changed. The original comparison used all five saved native source files. The runner checks the scene, composed RGBA and 24-bit depth byte for byte before alternating 11 timed A/B pairs per quality and sky combination on the same context.

The comparison checks the following properties.

- The 24-bit scene depth at every viewport pixel matches v39 in daylight and moonlight.
- Fully opaque interface pixels retain their exact source RGB, including chat, inventory and minimap.
- Shadows affect the displayed scene. Daylight differs from moonlight. A repeated daylight frame and a return to v39 match their original frames.
- A negative control enables global back-face culling. This capture detects hundreds of lost or receding world pixels under that policy.

`results.json` contains the recorded test run. This scene predates player-face labels and frame-matched local lights. The dedicated cape and lighting checks exercise those two features. Android input, on-device performance, frame presentation and gameplay require separate validation.

The staged-header A/B recorded median complete-frame times of 160.2 → 121.4 ms in Ultra daylight, 158.9 → 123.7 ms in Ultra moonlight and 114.2 → 90.9 ms at single-cascade quality. All 33 pairs improved. A second run from the archived vertex shader also improved in all 33 pairs. Both comparisons produced identical resolved scene, composited frame and depth bytes in all three configurations. Of the 30,478 captured triangles, 29,298 could be skipped in the translucent pass and 68 in the opaque pass. These timings came from the host SwiftShader software rasterizer.
