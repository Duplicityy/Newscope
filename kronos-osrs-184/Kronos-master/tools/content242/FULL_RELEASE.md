# Full revision242 asset/content release on the existing184 architecture

This package is prepared for an explicit operator deployment. Package creation
does not apply it to production. The example spec deliberately contains missing prerequisites. A source build or asset import
alone is not a deployable release. Existing home, desktop controls, mobile controls,
game protocol184, private accounts and player files are retained.

`release_bundle.py` creates an immutable private server bundle containing the exact
game libraries, updater libraries, portable Windows client, Android APK, Cache,
server data, reviewed evidence, and future apply/rollback scripts. Share only its
`windows/` directory and `android/Kronos-242.apk` with players. Do not publish the
server bundle, cache keys, private backups or signing material.

## Prepare and seal

Run from the workspace root with the supplied Android SDK Python interpreter.
Copy `full-release-spec.example.json` to a new local runtime file and replace every
required value. Use immutable final outputs, not a running QA directory.

1. Build the final server, updater, Windows support overlay and both clients.
   Android `assets/jvm/client.jar` must match Windows `app/client.jar` exactly.
   Preserve Android package/signing identity and existing RuneMobile control/layout
   storage; do not install/uninstall the app as part of server deployment.
2. Finish native new-content QA and review actual desktop captures. Record the
   unsupported mechanics, withheld records, and whether a physical handset was
   tested. The existing protected minigame/bot/hitsplat implementation is unchanged;
   its old proof is inherited by exact module/cache comparison, not claimed as a
   fresh gameplay run.
3. Run `protected_attestation.py --derive` with the exact final server, original
   baseline, final Cache, import report and exact shared dependency review. Copy
   only its two derived properties files into an **offline final data copy**. Never
   change data beneath a running QA or production server.
4. Run `release_bundle.py inspect --spec SPEC.json` to compute exact artifact
   bindings. Write the explicit final QA review below. The review is a human/agent
   assessment of named evidence, not a substitute for running the checks.
5. Run `release_bundle.py prepare --spec SPEC.json --output
   local-runtime/content242-releases/RELEASE-NAME`. It re-runs the read-only
   protected byte/cache audit, validates every proof/artifact hash, and copies
   everything into a new bundle. Any missing artifact, stale hash or incomplete
   review fails before a bundle is sealed. No live process or file is changed.

Final QA review shape (all bindings come from `inspect`; no placeholders accepted):

```json
{
  "schemaVersion": 1,
  "scope": "content242-final-release",
  "approved": true,
  "bindings": {"COPY_EVERY_INSPECT_FIELD": "EXACT_HASH"},
  "checks": {
    "cacheProtocol": true, "nativeGear": true, "bossEncounters": true,
    "windowsControls": true, "androidPackaging": true, "homePreserved": true,
    "normalInventoryActions": true, "originalCachePreserved": true, "worldAccess": true
  },
  "androidPhysicalTested": false,
  "testedMechanics": {"EVERY_ENABLED_FEATURE_NAME": true},
  "limitations": ["Concrete remaining limits, including handset status."],
  "evidence": [
    {"role": "foundation", "path": "EXACT_FILE", "sha256": "EXACT_HASH"}
  ]
}
```

Required evidence roles: `foundation`, `cacheProtocol`, `nativeGear`,
`bossEncounters`, `desktopSmoke`, `windowsBuild`, `androidPackaging`,
`protectedAttestation`, `sharedDependencyReview`. Multiple evidence files per role
are permitted except the single exact shared dependency review. `windowsBuild`
must be the real `Build-WindowsSupport.ps1` receipt for this client/support pair.
When `ownerGrant` is present, the exact payload, Python helper, PowerShell wrapper,
three parser dependencies and private target paths contribute an additional QA
binding; an `ownerGrant` evidence role is mandatory. The seal also runs its
read-only bank-capacity/payload preflight. Remove that optional spec member only
when no grant is authorized for a different release.
Keep original reports and scope: stage04 native foundation results plus the bounded
stage05/06 lineage are not renamed into a fresh final-cache full decoder run.
Likewise, read-only inherited protected proof is never fresh gameplay evidence.

## Future operation — not executed during preparation

Read-only default:

```powershell
& BUNDLE/scripts/Full-Content242.ps1 -Bundle BUNDLE
```

Only after deployment is explicitly authorized:

```powershell
& BUNDLE/scripts/Full-Content242.ps1 -Mode Apply -Bundle BUNDLE
```

Apply re-verifies the bundle and unchanged original Cache/data, checks current
process identities, original game/updater binaries and launcher, and capacity,
prepares verified candidate copies, then requests
the normal game save/logout/countdown and normal updater shutdown. It privately
backs up current accounts, players, home state and configured actor storage after
save. It swaps Cache/data, installs a versioned server launcher selecting both
libraries, and checks cache revision184242 accepted, legacy184 rejected, and game
handshake184. It never replaces server.properties, network/account settings,
Android user data, Windows user profiles, or an open client. The old launcher and
selection are retained. The explicitly authorized owner grant, when bound in
the sealed spec, runs once after the new Cache/data and server selection are in
place, while both servers are stopped and before the game starts. Its existing
explicit owner flag is required; passwords and roles are never rewritten. The
helper's private before/after journal prevents duplicate grants and uncertain
retries. This grant is part of the single explicit Apply command.

The operator publishes the separately prepared public Windows directory/ZIP and
mobile APK after the new server passes its health checks, retaining the old public
release roots for rollback. This server transaction does not rename those public
distribution roots itself. Keep both client distributions aligned with the chosen
server/cache capability before handing the service back to players.

The new launcher defaults to game5120MB/updater512MB. `-GameHeapMb 4096` is the
verified lower game allocation. Both physical memory and commit headroom must
cover requested heaps plus1024MB; failure stops before overcommitting. This does
not change Windows RAM/pagefile settings. Existing live allocation is unchanged
until an eventual explicit apply.
The existing stable account registry is required for both public and localhost
binds. Public registration remains bind-specific; a second launch while a recorded
server PID is still starting is rejected rather than spawning another JVM.

Exceptions after swaps trigger normal stop and restoration of original Cache/data
and launcher selection. No force-kill or recursive deletion is used. A private
`TRANSACTION.json` records phases for manual recovery if power is lost between
filesystem moves; this is not a claim of filesystem-wide atomicity. All candidate,
old and failed directories are retained. Normal completed rollback:

```powershell
& BUNDLE/scripts/Full-Content242.ps1 -Mode Rollback -Bundle BUNDLE `
  -RollbackReceipt PRIVATE_BACKUP/APPLIED.json
```

Rollback first stops both servers and requires player files and account registry
to match the pre-start checkpoint. The owner grant is restored only from its
verified before-image when the current save exactly matches its after-image (or
already matches the before-image). If any player has progressed or the outcome
is uncertain, rollback refuses and leaves the242 selection stopped for reviewed
recovery; it does not start an old cache with new242 items or overwrite progress.
Private backups remain available. The old client is incompatible with the new cache, so distribute the
new Windows and Android artifacts together with any eventual server transition.

## Current verification of this tooling

Python policy tests exercise stale artifact/overlay rejection, missing QA checks,
untested enabled mechanics, modified evidence, private material and path traversal.
PowerShell files are syntax-checked. Apply, rollback, normal production restart and
the final combined bundle have **not** been executed by the packaging task.
