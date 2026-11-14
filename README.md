# NewScope — ready-to-play clients

Download the clients for the live revision 242 content update:

| Device | Download | Install |
|---|---|---|
| Windows | [Kronos-Windows.zip](https://github.com/Duplicityy/Newscope/releases/download/clients-0.4.0-242/Kronos-Windows.zip) — 78.5 MB | Extract the entire ZIP, then run the included launcher. Java is bundled. |
| Android | [Kronos-Mobile.apk](https://github.com/Duplicityy/Newscope/releases/download/clients-0.4.0-242/Kronos-Mobile.apk) — 92.7 MB | Install version 0.4.0 as an update to preserve existing app data and preferences. Includes the chat tap-through fix. |

**This repository and its downloads are private.** Sign in with a GitHub account that has repository access. The owner must invite your friend as a collaborator if they do not already have access.

No source build or asset-restoration step is needed to play. Both downloads are the existing deployed client packages, verified by SHA256 and file size before publication; they were not rebuilt by the upload workflow.

[Release page, checksums and instructions](https://github.com/Duplicityy/Newscope/releases/tag/clients-0.4.0-242)

- Android SHA256: `e125c9ffc1b35bbb63a10c13032c981e926c3387840204a03b4dd0bc8b0f64e4`
- Windows SHA256: `1626dfc35ea96316eea13f3390fef924a54b2dc7e2bfff9febc8b30235133c08`

The update retains the existing revision-184 game protocol and controls. It adds revision-242 content; it is not a claim of complete OSRS gameplay parity.

## Project source

The full sanitized Kronos project is in this private repository: server source under `kronos-osrs-184/Kronos-master/`, Android client source under `kronos-mobile/`, Windows client files under `windows-client/`, build scripts at the repository root, and the revision 242 asset payload under `assets-payload/`. See `PUBLISH-AND-BUILD.txt` and `SOURCE_EXPORT_CHANGES.txt` for setup and export notes. The cache payload is split into text parts for GitHub transport; restore it before building or editing models.

Private player saves, account passwords, local keys, and live server configuration are intentionally excluded. The Soulbound Ancestral model generator and its outputs are not included.
