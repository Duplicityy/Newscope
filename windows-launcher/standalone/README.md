# Newscape standalone Windows build

The Runtime 36 release has a single Windows executable. It includes the Expedition v46 client, its compatible Java runtime, and the verified cache seed. Launching the executable installs application files under `%LOCALAPPDATA%\NewscapeStandalone`. The game reuses the existing `%LOCALAPPDATA%\Kronos-Windows` profile and connects to the Ubuntu owner server.

The build uses Windows x64 and the Windows .NET Framework launcher compiler. Neither the existing Newscape Launcher nor a separate Java installation is required. The prepared runtime's original license files are included.

## Rebuild

Run `build_bundle.py` with these arguments in order.

1. Existing directory designated for build outputs.
2. Exact `client-expedition.jar` from the v46 release.
3. Verified earlier full Windows distribution containing `app/windows-support.jar`, `licenses/` and `runtime/`.
4. Current frozen 24-file revision 184729 cache seed.
5. `legacy/WindowsFreshCacheLauncher.java`, the original bootstrap used to derive the v46 release marker and Ubuntu endpoint.
6. The Java 8 JDK's `bin/` directory.
7. Windows .NET Framework x64 `csc.exe`.
8. ASM 7.1 JAR, used by the build tool.

The script checks the expected client and support JAR hashes, verifies the cache format, and generates a new cache manifest and payload checksum. The native wrapper verifies the embedded archive before unpacking it. The shared Java launcher verifies the seed and preserves existing profile data.

The standalone launcher JAR carries three small overrides derived from v46: the native cache directory selector and the game and cache host getters. They use the values set by the launcher. The included Expedition client JAR retains its published checksum.

## Verify

`--install-only --root <isolated-install> --profile <isolated-profile>` verifies the bundled JVM, creates the managed seed, and exits before the login screen. Use a fresh test profile to verify first launch.

`StandaloneLoginCheck.java` verifies the native cache selection, endpoint and rendered login frame. Compile it with the extracted launcher, support and client JARs on the classpath, then run it with the extracted bundled JVM from the install directory. Pass the expected host and a path for the verification receipt, and use `-Duser.home` to select the isolated test profile.
