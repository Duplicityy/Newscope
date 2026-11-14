# Public build dependencies

`maven/` preserves the resolved Java 8 server and desktop build artifacts in Maven layout, including POM license metadata and embedded JAR notices. No Gradle credentials, private repository configuration or opaque resolution-cache state is included. `dependencies.json` records every file hash. Use `Build-Source.ps1` after restoring the asset payload.

The exact Android ARM64 OpenJDK21 download and Cacio1.18 jars are retained under `tools/downloads/` because an upstream moving release URL no longer guarantees the original pinned bytes. The mobile builder verifies their SHA256 and materializes OpenJDK licenses into the APK. RuneMobile's `THIRD-PARTY-NOTICES.md` and runtime legal notices apply. JDK8/JDK21 for Windows, Gradle distributions, Android SDK/NDK/CMake and current Android Gradle dependencies are installed/downloaded separately.
