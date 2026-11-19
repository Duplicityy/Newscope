Android code27 targets the owner S25 Ultra 60 FPS request after code26 was reported working at approximately 35 FPS.

The normal 117 display path now blits the complete scene/UI framebuffer directly to an EGL window surface. Pixel readback, channel conversion, Java image-array publication and CPU Surface copies are bypassed for ordinary direct frames. Explicit screenshots retain an on-demand readback. The plugin footer identifies GPU display or Buffered display and retains its completed-frame FPS counter.

The Android Activity recreates only its display Surface for producer handoff. The host prevents CPU presentation while that handoff is pending or the direct producer is enabled. The engine owns EGL resource creation/destruction. Surface generations cover window changes, background/resume and Activity replacement. Native context generations permit a fresh attempt after a plugin restart. A five-second preparation deadline cancels an incomplete handoff. A direct presentation failure releases the EGL producer before returning to the code26 asynchronous readback path.

The actual client GameShell class is overlaid in bridge.jar. One virtual clock call in its run method now delegates to an owner 117 render clock. Foreground 117 rendering follows the existing plugin FPS target, default 60. The existing 20ms simulation interval and ten-cycle maximum catch-up are retained. Inactive 117 and background execution delegate to the original clock. Restoring the single invocation reproduces the original canonical bytecode exactly. The original client.jar and other bridge entries are preserved.

Checks passed for Java and arm64 compilation, signature/metadata, 30/60/90/120/240 FPS scheduling with independent tick counts, the handoff state machine, repeated cleanup, 200 coherent buffered frames, 100 direct publication notifications and recovery to buffered frames, 100 busy-render callbacks, public/protected API compatibility and JVM verification without initialization.

The 60 FPS scheduling checks are synthetic clock tests. Physical Android FPS, real EGL window recreation, background/resume, disabling/re-enabling 117, teleport and combat acceptance of this build remain with the owner. No phone is attached. The previous 35 FPS figure is the owner's code26 observation. No measured code27 phone improvement is claimed.

Working code26 APK and all original modified payloads are backed up in D:/Newscape-117-Direct/backup. Source is on perf/android-117-direct-60. New runtime.tag forces extraction of updated embedded components at next launch.
