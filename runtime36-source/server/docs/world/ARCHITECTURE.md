# Newscape world development

The feature worktree is based on the owner test build with the universal pet pickup fix. The installed cache combines the revision 184 protocol, revision 242 content, and the existing native Dungeoneering mappings. The running build's teleporter source was recovered from its exact class build before extending it.

## Entry and return

**Minigames → OSRS World** offers the Lumbridge arrival at `(3222,3218,0)` and a list of regional destinations, including offshore islands and maps placed away from the mainland. Each destination uses the native movement checks for activity, locks, and teleblocks. The ordinary Home spell returns to Newscape's home at `(2028,3577,0)` and honours existing player settings and activity overrides. The **Bosses** tab lists Scurrius, Amoxliatl, and Phantom Muspah by name and opens their private encounters.

## Home and custom layers

The development package verifies every copied baseline asset and class against the owner candidate manifest before applying its overlays. The cache, home data, login and respawn code, and client come from that baseline. The optional quest journal field extends the player save. The development overlay changes 13 reviewed region-key records. The [coverage audit](COVERAGE.md#home-baseline) records the home terrain and landscape hashes and static override file hashes. The home entry and spell are also exercised in the [detached test](../../content-tools/checks/World242AccessChecks.java).

The Daemonheim lobby, ToA and ToB lobby handlers, and Dungeoneering instances use the inherited layers. Older saved selections for the retired Arceuus Library teaser are cleared. Previous New lands travel opens OSRS World. The development package uses a separate process profile and account registry.

## Build and test

Use PowerShell 7 and the workspace's JDK 8 and Lombok 1.18.12. Supply a fresh output directory and the existing private candidate as a read-only baseline.

```powershell
./content-tools/Build-World242Dev.ps1 -Workspace $workspace -Baseline $ownerBaseline -Output $devBuild -Lombok $lombokJar
./content-tools/Test-World242Dev.ps1 -Build $devBuild
./content-tools/Check-World242Access.ps1 -Build $devBuild -Output $accessCheck
```

The smoke test boots and stops the development game and cache on localhost ports `14302` and `14304`. The build creates a regular fixture account with a random password in the isolated stage. The detached check uses native region, quest, NPC and shop code with a synthetic player. Its saves stay in the check output directory.

Read [map coverage](COVERAGE.md), [population reconciliation](NPC_STATUS.md), [quests](QUESTS.md), [gameplay bindings](GAMEPLAY.md), and the [source review](SOURCES.md) before extending content. Use the installed definitions and existing mechanics as the implementation base.
