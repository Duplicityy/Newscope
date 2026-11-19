# Newscape owner automation test report

## Expanded private candidate, build-06

| Check | Result |
|---|---|
| Java 8 compile and Kilim verification | Passed with selected private cache and baseline overlays |
| Verified native methods | 353 preserved instruction sequences after normalizing constant-pool indices and branch offsets. Commands, manual-input and legacy starts gained narrow hooks. Existing Vorkath methods only received observations. Zulrah gained five getters. |
| Policy | 428,370 assertions for authorization, thresholds, bounded recovery and item conservation. 200,000 simple policy pairs took 8.921 ms. This is arithmetic timing, not game tick profiling. |
| Action ownership and persistence | Passed exact event identity, persistent-event exclusion, save annotation, modern preference round-trip, old-save defaults and no active-session persistence |
| Cache, routing and skills | 113 checks covering native Mining ore and XP, real bank transfer and full capacity rejection, utility skill bindings, and registration of all 23 base skills |
| Native gathering | 31 detached checks for Woodcutting, Fishing and Hunter, including native items and XP |
| Native production | 74 detached checks across nine skills. Verified paid flatpack, exact three-seed planting, mature harvest, native recipe resources and XP. |
| PvE and Slayer | 27 detached checks for actual target and style dispatch, food, task matching, and Thermonuclear Smoke Devil entry gates and kill-counter tracking |
| Vorkath | 5,711 detached checks. Exercised native fireball and acid observations, frozen spawn, queued Crumble Undead, clipped movement decisions and isolation. |
| Zulrah | 215 detached checks over all native phases, Jad alternation, melee observation and fume geometry |
| Existing iDungeon | 6,523 recovery/watchdog/settings checks and 275,014 existing owner-bot math checks |
| Android | Gradle release build and owner signature verified. Transport checks cover command allowlist, bounded message assembly, duplicate chunks and malformed response handling. APK v38 keeps identical 117 HD runtime and graphics bytes. |

The tests initialize the exact detached cache and selected server classes, with fixture actors and no live profiles. Native actions were exercised where listed. The cache and overlay passed the established launcher validation. No live game or cache service was changed.

## Remaining verification and limits

- Physical S25 Ultra portrait UI and a full authenticated round trip from the APK remain pending.
- Full Vorkath, Zulrah and Thermonuclear Smoke Devil clears, retreat and re-entry need live validation. The native hazard and action tests establish those components only.
- Utility skill bindings and ordered Gnome steps passed their detached checks. Complete trips and laps remain pending.
- Sustained tick profiling, PIN, long-running progression, and an authenticated reconnect with preferences still need in-game validation.

## Reproduce

Build source with content-tools/Build-OwnerAutomation.ps1, the selected Java 8 tools, the Map Walk compiler classpath, Bound owner overlay manifest, and local ASM 7.2 core and tree JARs. Use a fresh output directory. The candidate overlay and paired APK are staged in build-06 and android-build on the VM.

An authorized private activation uses the checksum overlay plus -Dnewscape.ownerAutomation=true on the game JVM. Current owner accounts and settings still use normal player saves. Existing Android and PC clients use the server dialogue. Install the separately staged v38 APK for the portrait panel after activation.
