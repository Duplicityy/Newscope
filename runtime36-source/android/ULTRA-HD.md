# Newscape Ultra HD on Android

The owner APK v40 builds on the signed v39 client, commit `f2442538e9abff1718282b8dc7492843c3f1173e`. Its Android package is `local.newscape.desktoptest`.

## Using Ultra

After login, open **Menu → Graphics → Newscape Ultra HD**. Choose a quality preset. **Time of day** switches between **Daylight** and **Moonlight** and remembers the selection independently of quality. **Custom** offers individual effects and a cast-shadows switch. Existing players keep their saved quality. A fresh installation starts with Balanced in daylight.

| Preset | Shadow atlas | Contact samples | Edge AA | Bloom | Material highlights | Distance haze |
| --- | --- | ---: | --- | --- | --- | --- |
| Performance | One 1024² map | 0 | Off | Off | On | Off |
| Balanced | One 1024² map | 4 | On | Off | On | On |
| High | Two 1024² cascades | 8 | On | On | On | On |
| Ultra | Two 1024² cascades | 12 | On | On | On | On |

## Rendering

The game thread captures the client's animated models, terrain, UVs and atlas in their existing coordinate system. Classic, HD, and the genuine 117 plugin retain their original assets. Ultra adds the following presentation data to each published frame.

- Up to four nearby light sources can illuminate the scene. Thirty-two installed object definitions cover lit torches, braziers, campfires and the server's colored firemaking logs. The selector checks object tags, keeps the nearest distinct sources and rejects unlit variants. Height, radius and color follow the emitter type. Selection runs during ordinary scene traversal, with fixed-size storage and an independent list per published frame.
- The existing vertex layout identifies player faces. Solid player faces use a negative texture layer, and textured player faces carry a fractional layer tag that resolves to the original atlas entry. The Ultra color shader respects the cache's front-face rule on those triangles. This restores the exterior and lining of the Infernal and Fire capes. World surfaces and shadow casters remain double-sided.
- Directional sun or moon, sky and ground bounce, and local lights shade the original cache colors. Player highlights use face color and surface orientation as material hints. The server cache supplies the actual animation, geometry, textures and transparency.
- High and Ultra use a stabilized near and far shadow cascade with overlap. The caster reads alpha-tested textures, and receiver filtering estimates blocker distance to vary the shadow's softness. Balanced and Performance use one map with filtered depth comparisons. Optional shadow allocation falls back to a smaller atlas or the shadowless Ultra shader when needed.
- Contact shading reconstructs positions in camera space from the scene depth buffer at the physical viewport's focal point. Tone mapping, edge AA and bloom run on the 3D scene. The original chat, minimap, inventory and widgets are composited afterwards. Before rasterization, Ultra skips triangles whose constant face opacity proves they cannot contribute to the current pass. Alpha-tested and partially transparent texture pixels still follow the original two-pass rules. Targets and optional render resources are released on renderer or surface teardown.

## Rebuilding from v39

Use the current v39 owner APK and original signer. Packaging material and game cache files are kept outside this source tree. The JAR reconstruction verifies the base APK and original renderer class by SHA-256. Run the following in the Android checkout with `$apk`, `$jdk8`, and `$stage` pointing to the baseline APK, JDK 8 installation and an empty private build directory.

```powershell
$assets = 'kronos/src/main/assets/jvm'
$src = 'client-overlays/src/net/runelite/standalone'
python client-overlays/stage_mobile_ultra.py $apk $assets
$classpath = (@('bridge.jar','client.jar','owner-plugins.jar','owner-graphics.jar') | ForEach-Object { Join-Path $assets $_ }) -join ';'
New-Item -ItemType Directory -Force "$stage/classes" | Out-Null
& "$jdk8/bin/javac" -cp $classpath -d "$stage/classes" "$src/MobileHdRenderer.java" "$src/UltraSceneLights.java"
python client-overlays/stage_mobile_ultra.py $apk $assets --classes "$stage/classes"
```

Build the normal `:kronos:assembleRelease` variant with the checkout's Gradle 8.8, JDK 21, Android SDK 35, NDK 27.0.12077973 and the existing owner signing environment. The resulting ARM64 APK can update v39 in place. The new runtime tag installs the upgraded embedded bridge on launch.

## Verification

- `checks/verify_mobile_ultra_apk.py` compares the signed packages, every embedded JAR entry in the modified bridge, and the actual ARM64 shader strings with the tested source.
- `client-overlays/UltraSceneLightsCheck.java` exercises real capture publication, stale frames, selection and legacy metadata prefixes. `checks/verify_ultra_emitters.py` decodes the local native object definitions.
- `checks/ultra-lighting/check_lighting.py` executes the GLSL on host GLES and measures pixels for lighting, materials, opacity, contact shadows and player winding.
- `client-overlays/checks/cape/check_capes.py` exercises male and female Fire and Infernal capes from the real cache, with six viewing angles and two animated texture offsets. Its untagged scenery control checks that the player fix is scoped correctly.
- The [world replay](checks/ultra-world/README.md) uses a frozen 91,434-vertex game scene with nine real texture layers. Its stored [results](checks/ultra-world/results.json) cover original world depth, every opaque UI pixel, daylight, night, shadow coverage and host rendering costs.

On the frozen scene the early opacity check produced identical color, interface and 24-bit depth bytes. Across 33 alternating pairs on Windows SwiftShader, median paired frame-time savings were 23.5% for Ultra daylight, 23.9% for Ultra moonlight and 21.4% for Balanced daylight compared with the same new shaders before this optimization.

The host tests cover offscreen graphics and embedded client behavior. Physical S25 Ultra frame rate, thermal behavior, scene switching and appearance during live play require device testing. Direct shadows are cast by the sun or moon. Local fires provide bounded, unshadowed lighting. The ledger of supported emitters is explicit so scenery can be extended against verified cache definitions.
