# Windows full cache bundle

`Build-WindowsRaidsFull.ps1` creates a new directory containing the Windows distribution and ZIP. It takes the audited paired-client manifest, the preserved NR06 cache root, and the previously released Windows bundle as explicit inputs. It copies only the latter's bundled Java runtime and licenses; the app jars come from the supplied paired-client manifest. It does not rebuild the native client or cache selector.

```powershell
./tools/content242/Build-WindowsRaidsFull.ps1 `
  -Workspace $Workspace `
  -BaselineBundle $PreviousWindowsBundle `
  -ClientManifest "$Work/loot-client-checks/overlay-v7/paired/CLIENT-OVERLAY-MANIFEST.json" `
  -CacheRoot "$Work/native-cache-nr06" `
  -Output "$Work/windows-full-release"

./tools/content242/Check-WindowsFullArchive.ps1 -Stage "$Work/windows-full-release"
```

The output must not already exist. All 24 cache files must match `PRESERVED.json`, including its confirmation that previous native payloads remain unchanged. The two Windows app jars must match the paired manifest. The managed cache release is `184728-nr06-raids1`, matching the recompiled Java bootstrap; this gives NR06 an isolated installed directory beneath the existing profile's `newscape-cache` folder.

`Play Newscape.cmd` retains the existing `%LOCALAPPDATA%\Kronos-Windows` profile, settings and login details. A JVM-lifetime file lock protects the managed cache from concurrent launches and resets. `Fresh Cache.cmd` reinstalls only this managed cache and archives the previous copy. Legacy cache directories are retained.

`Check-WindowsFullArchive.ps1` reads every ZIP entry and compares its decompressed SHA-256 and length with the staged distribution. It permits only app jars, all cache files and manifest, the bundled runtime and licenses, and the three user launch/readme files. Verification helpers, profiles, saves and logs are outside the distribution.

`WindowsFreshCacheLauncherChecks.java` exercises installation, settings retention, reset/archive, corrupt input, and independent JVM session locking. `WindowsCacheSelectionChecks.java` executes the actual packaged selector for all four native attempts. `WindowsFreshCacheLoginChecks.java` captures a completed native login frame without an account login. The native client still requires an available matching cache updater even when the full seed is present. A loopback verification helper may call the exact packaged install/lock methods before starting the unchanged native client against the local updater; the packaged launcher's public default remains unchanged.

The builder only stages artifacts. Active selection, download publication, server startup, and gameplay verification are separate release steps.
