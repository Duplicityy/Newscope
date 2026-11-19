# Runtime 36 source

This directory preserves the current server changes and counterpart client source used for the owner release. It sits alongside the original repository source and its earlier cache assets.

| Path | Contents |
| --- | --- |
| `server/` | Tracked gameplay, integration, data, test, and build changes from the maintained post-Dungeoneering baseline to server commit `1c18cb7dd4465fdf36da8e6afb14bd36272353f0`. Includes pets, world and quest work, navigation, owner automation, Slayer, the Expedition Ledger, and the world traversal and charter updates. |
| `android/` | Current Android app, renderer and client overlay source at `2a5bd4a850a15497a768a6e90314297831d58337`. Local diagnostic capture JSON is omitted. |
| `client-packaging/` | Windows update launcher, shared client overlay build and Android packaging scripts. The client overlay requires its compatible base client JARs. |
| `ubuntu/` | Server and cache service definitions, owner gateway, endpoint check, and release assembly and cutover scripts. Build roots and host deployment parameters come from the existing installation. |
| `raid-source-reference/` | Separately reviewed earlier raid and compendium integration source for reference. Use the runtime service and build receipts to identify later local overlay changes. |

The [matching release assets](https://github.com/Duplicityy/Newscope/releases/tag/runtime36-expedition-v46) include the exact Android APK, Windows update, and Runtime 36 server overlay. The overlay expects the verified runtime35 foundation and its installed mixed-revision cache. The source archive and existing repository provide development inputs. A clean build from the published snapshot alone has not been verified.

For a separate deployment, set the owner roster through `newscape.slayer.allowedAccounts` and the bound item through `newscape.boundBloodFury.accountId` and `newscape.boundBloodFury.accountName`. These settings are private and empty by default in the published source. Account-specific provisioning and fixture inputs are omitted.

Ubuntu boot and game/cache handshakes passed on the deployed release. Detached server checks covered all 48 charter legs plus the route and expedition regressions. The Windows and Android client packages passed offline checks and SHA-256 verification. Phone and Windows gameplay acceptance remain open.
