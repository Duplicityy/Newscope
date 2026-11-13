# Newscope — Kronos with staged 242 content

Complete project snapshot: Kronos server and revision-184 RuneLite client, RuneMobile Android sources, Windows client support, game cache/data, autonomous adventurers, and build/import/test tools.

**Status: staged and tested locally; not deployed to the live server.** The original client protocol, home and mobile interface are preserved.

The content backport adds 10,001 item records (including notes/placeholders), 1,647 basic equippable records, 5,976 models, 131 legacy animation sequences, 669 standard-gear visual refreshes, and six connected Varlamore scenery regions. This is not a full revision-242 engine or gameplay port. New special attacks, passives, set effects, quests and boss scripts are not implemented. Bowfa and Tumeken’s shadow remain inventory-only.

## Preview and verification

- [Feature page with real screenshots](content-release/Kronos-242-Features.html) — download and open locally.
- [Checks and limitations](content-release/VERIFICATION.txt)
- [Build prerequisites and private configuration](PUBLISH-AND-BUILD.txt)
- [Isolated staging instructions](tools/content242/STAGING.txt)

## Restore assets, then build

After cloning, run `python tools/content242/restore_assets.py` from the repository root. Binary assets are included losslessly in `assets-payload/`; restoration verifies their hashes. Java/Kotlin/C++ sources remain directly readable in Git.

Install the JDK/Android prerequisites listed in `PUBLISH-AND-BUILD.txt`, configure a private local server configuration and RSA key, then run `Build-Kronos.cmd`. Android and Windows builds use `Build-Kronos-Mobile.ps1` and `Build-Kronos-Windows.ps1`.

Live account/player data, passwords, private keys, signing material, SDK installations and build outputs are intentionally excluded. Existing private RSA configuration is required to preserve compatibility when deploying this copy; never commit it.

The production VM has a separately sealed one-step installer. Nothing in this repository automatically deploys the staged content.
