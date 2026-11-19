# Android renderer quality

## 117 HD render resolution

Open **RuneLite plugins → 117 HD → Render resolution**. **Standard** is the default and uses the original canvas-sized rendering. **Sharp** renders up to 1.5 times that width and height. **Display** uses the fitted Android SurfaceView size with a 4096-pixel edge and 4,608,000-pixel allocation bound. The plugin status reports the actual presented buffer size and FPS. Higher resolutions require the direct EGL display path. The older buffered path uses the original logical resolution. Display has higher GPU fill cost, especially with multisampling.

The option scales the raster viewport and optional MSAA target while retaining the exact client projection, logical UI texture, input dimensions and model data. It uses the existing 117 shaders, material assets, shadow settings and 60 FPS scheduling. A failed high-resolution allocation returns to the original resolution for that context. A failed optional enlarged MSAA target disables MSAA at that size and leaves the frame renderable.

Compile the four runtime Java sources in graphics117 with JDK 8, using the owner client, bridge, owner graphics and plugin JARs, JOCL 2.0.2 and Lombok 1.18.12. Compile graphics117/newscape117.cpp with NDK 27 targeting ARM64 Android 24. Link EGL, GLESv3 and log, and use 16 KiB alignment. Extract the matching owner APK's assets/jvm payload, then run graphics117/stage_quality_runtime.py with the baseline APK, compiled classes, library and assets path. The staging command refreshes the runtime tag and preserves every other embedded component.

## Newscape HD status

**Graphics → Status** reports the custom renderer that successfully presented and whether its shadow resources are active. The first status distinguishes loading from failure. Android can report a successful fallback from Ultra to Newscape HD independently of a failure that returns to Classic.
