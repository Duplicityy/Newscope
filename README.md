# NewScope — ready-to-play clients

Download the updated clients for the live six-system release:

| Device | Download | Install |
|---|---|---|
| Windows | [Kronos-Windows.zip](https://github.com/Duplicityy/Newscope/releases/download/clients-0.5.2-session-close/Kronos-Windows.zip) — 78.6 MB | Extract the entire ZIP, then run Play Kronos.cmd. Java is bundled. |
| Android | [Kronos-Mobile.apk](https://github.com/Duplicityy/Newscope/releases/download/clients-0.5.2-session-close/Kronos-Mobile.apk) — 92.8 MB | Install version 0.5.2-session-close (versionCode 14) over the existing app to retain its data and controls. |

**Updated clients are required for the live movement subrevision 6.** Open `::progress` in game for mastery, armour condition and Horvik repairs, Legend, boss pets, Athletics, and community thanks. Android retains the recent rendering, background-session, and optional experimental Mobile HD work. Version 0.5.2 also closes the game engine and network process when the app is swiped away from Recents; ordinary app switching keeps the session.

**This repository and its downloads are private.** Sign in with a GitHub account that has repository access. The owner must invite your friend as a collaborator if they do not already have access.

No source build or asset-restoration step is needed to play. Both downloads are the exact deployed packages, verified by SHA256 and file size before publication; the upload workflow does not rebuild them.

[Release page, checksums and instructions](https://github.com/Duplicityy/Newscope/releases/tag/clients-0.5.2-session-close)

- Android SHA256: `436ca9d7ca339eb3cab713c01ed2ff369b47feb0bd984ce7b6ca9826efcb6a7a`
- Windows SHA256: `94a027c880775769e8943c820e4c92cfdb4cd6fed0cb5ba3262a2540c55569f5`

The project uses the existing revision-184 client architecture with revision-242 content and a matching movement extension. It is not a claim of complete OSRS gameplay parity.

## Project source

The full sanitized Kronos project is in this private repository: server source under `kronos-osrs-184/Kronos-master/`, Android client source under `kronos-mobile/`, Windows client files under `windows-client/`, build scripts at the repository root, and the revision 242 asset payload under `assets-payload/`. See `PUBLISH-AND-BUILD.txt` and `SOURCE_EXPORT_CHANGES.txt` for setup and export notes. The cache payload is split into text parts for GitHub transport; restore it before building or editing models.

Private player saves, account passwords, local keys, and live server configuration are intentionally excluded. The Soulbound Ancestral model generator and its outputs are not included.
