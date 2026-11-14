Binary project assets and large JSON/data assets are preserved byte-for-byte in this ZIP/DEFLATE archive, split into base64 text chunks for GitHub connector transport. After cloning, run:

  python tools/content242/restore_assets.py

Each chunk, the archive, and each restored file has a SHA-256 integrity check. Matching assets are skipped. Changed existing files are never overwritten unless --force is explicitly provided. All Java/Kotlin/native/script source files remain ordinary files in the repository. See manifest.json for every original path, size and checksum.
