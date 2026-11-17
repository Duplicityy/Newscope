# Newscape Windows test launcher

[Download Newscape-Launcher.exe](https://github.com/Duplicityy/Newscope/raw/refs/heads/main/windows-launcher/Newscape-Launcher.exe)

Run this 13 KB executable on 64-bit Windows with .NET Framework 4 installed. It downloads the matching Java runtime, client and cache, verifies file sizes and SHA-256 hashes, then opens Newscape. Later launches reuse verified files and download changed or missing files. The current payload expands to approximately 392 MiB, with additional space used by the client's managed cache.

Installation lives in %LOCALAPPDATA%/NewscapeLauncher. The client uses the existing %LOCALAPPDATA%/Kronos-Windows profile and server address 51.91.58.148. Run Build.ps1 to compile the launcher source.

This repository is private, so downloading the executable from GitHub requires repository access. The executable currently retrieves its manifest and payload from the temporary HTTPS test service embedded in Launcher.cs. That service must remain available for installation and startup. Uploading the executable to GitHub does not move the payload service.

Compilation and integrity checks for all 209 payload files passed. The hosted executable and representative payload downloads were verified. Automatic approval review blocked executing the launcher in the development environment without specifying a reason. First installation, repeat launch, repair and gameplay testing remain pending with the owner. The test executable is unsigned.

SHA-256 of the executable is ff0e3cc286e13a8febe78788f89d3d8d8f5a0a9dc715eb6e9cf4b842acd45303.
