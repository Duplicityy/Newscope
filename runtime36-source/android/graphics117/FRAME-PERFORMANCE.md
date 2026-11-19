# Android 117 frame presentation repair

The Android owner build could display a frozen 117 HD frame while touch input continued moving the character. KronosBoot.copyFrame returns early for 117 frames and leaves the custom Mobile HD copiedSequence unchanged. The Android worker subsequently passed that sequence into MobileHdRenderer.copyGeometry. A retained custom renderer can reject the old sequence and return HOLD, retaining the displayed image indefinitely.

Owner117Frames now records the source of each successful copy. The Android worker presents completed 117 scene/UI frames directly through the existing pixel presentation path. The source tag survives a plugin disable racing after the copy and resets when the CPU publisher takes over.

The 117 GLES backend now uses three bounded pixel-pack buffers. It polls fences with timeout zero and publishes the latest completed coherent frame. Rendering skips submission when the queue is full, while the native scene/menu traversal and DrawManager completion callbacks continue. Frame transfer errors use the existing plugin failure cleanup. Five seconds without a completed queued transfer reports a diagnostic and requests the existing CPU fallback. Driver calls that block internally cannot be interrupted by that watchdog.

Interface uploads receive fresh storage before mapping. Dynamic model, camera, animation and light uploads rename storage to avoid reusing buffers still referenced by the GPU. The complete camera sine/cosine table is retained in its CPU staging buffer. A missing draw-distance preference now defaults to 32 tiles. Explicit existing settings are retained. The plugin status includes the rate of completed published frames. Slow frame submission/transfer timings are logged at most once per five seconds.

Verification completed
- Android Java and arm64 native compilation, APK metadata and same-owner signature.
- The production queue exercised under WebAssembly with fake GL completion boundaries, including saturation, reuse, delayed fences, failed waits, resizing and 100 cleanup cycles.
- Real Java publication exercised for 200 frames with dimension changes and a deliberately stale custom-HD sequence. Verified coherent pixels, route ownership, duplicate suppression, refresh and renderer-disable races.
- The actual HdPlugin.draw busy path exercised 100 times with a real DrawManager, verifying every-frame callbacks still run while GL services are absent.
- Existing shader resources and unrelated JAR payloads match the backed-up code25 release.
- Packaged Android native presentation routing compiled and included.

Physical Android presentation, touch response, gameplay and frame rate remain pending with the owner. No device is attached to adb. Host checks cannot establish device throughput or a GPU driver performance result.

The code25 APK, original graphics/plugin JARs, native 117 library, bridge and runtime tag are backed up under D:/Newscape-117-Performance/backup. The new runtime tag refreshes the installed embedded graphics files on next launch.
