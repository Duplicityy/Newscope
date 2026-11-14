"""Pack a publishing snapshot's binary/large data assets into UTF-8 transport chunks.

Sources are untouched. --replace removes binary files from this separate snapshot
only after a full archive round-trip hash check; restore_assets.py recreates them.
"""
from __future__ import annotations
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile
import zipfile

BINARY_SUFFIXES = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".bmp", ".ttf", ".otf", ".woff", ".woff2", ".eot", ".jar", ".class", ".dat2", ".zip", ".gz", ".pdf", ".mp3", ".ogg", ".wav", ".mp4", ".bin", ".icns", ".keystore"}
MAX_CHARS = 1024 * 1024
RAW_CHUNK = MAX_CHARS // 4 * 3

def sha256(path: Path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

def binary(path: Path):
    # Large cache metadata/reports are data assets; keep source code directly readable.
    if path.suffix.lower() in {".json", ".csv", ".tsv", ".dat"} and path.stat().st_size > 512 * 1024:
        return True
    if path.suffix.lower() in BINARY_SUFFIXES or path.suffix.lower().startswith(".idx"):
        return True
    # Examine every byte, because UTF-8 must survive the connector unchanged.
    import codecs
    decoder = codecs.getincrementaldecoder("utf-8")("strict")
    try:
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(64 * 1024), b""):
                if b"\0" in chunk:
                    return True
                decoder.decode(chunk)
        decoder.decode(b"", final=True)
        return False
    except UnicodeDecodeError:
        return True

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", type=Path, required=True)
    parser.add_argument("--replace", action="store_true")
    args = parser.parse_args()
    root = args.snapshot.resolve()
    source_root = Path(__file__).resolve().parents[2]
    if root == source_root or source_root.is_relative_to(root):
        parser.error("Only a separate publishing snapshot may be packed")
    if not (root / "publish-manifest.json").is_file():
        parser.error("Snapshot is missing prepare_publish.py's manifest")
    output = root / "assets-payload"
    if output.exists():
        parser.error("assets-payload already exists; use a new publishing snapshot")
    entries = []
    for directory, dirs, names in os.walk(root, followlinks=False):
        dirs[:] = [d for d in dirs if d != ".git"]
        for name in sorted(names):
            path = Path(directory) / name
            if path.is_symlink() or not path.resolve().is_relative_to(root):
                parser.error("Snapshot contains an unsafe path")
            if binary(path):
                entries.append({"path": path.relative_to(root).as_posix(), "bytes": path.stat().st_size, "sha256": sha256(path)})
    entries.sort(key=lambda item: item["path"])
    output.mkdir()
    with tempfile.TemporaryDirectory(prefix="content242-assets-") as temp:
        archive_path = Path(temp) / "payload.zip"
        with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6, allowZip64=True) as archive:
            for entry in entries:
                info = zipfile.ZipInfo(entry["path"], date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                with (root / entry["path"]).open("rb") as source, archive.open(info, "w", force_zip64=True) as target:
                    shutil.copyfileobj(source, target, 1024 * 1024)
        # Verify all exact bytes before replacing any snapshot file.
        with zipfile.ZipFile(archive_path) as archive:
            for entry in entries:
                digest = hashlib.sha256()
                size = 0
                with archive.open(entry["path"]) as source:
                    for chunk in iter(lambda: source.read(1024 * 1024), b""):
                        digest.update(chunk)
                        size += len(chunk)
                if digest.hexdigest() != entry["sha256"] or size != entry["bytes"]:
                    raise RuntimeError("Archive verification failed: " + entry["path"])
        chunks = []
        with archive_path.open("rb") as source:
            while True:
                data = source.read(RAW_CHUNK)
                if not data:
                    break
                encoded = base64.b64encode(data)
                name = f"part-{len(chunks) + 1:05d}.b64"
                (output / name).write_bytes(encoded)
                chunks.append({"path": name, "characters": len(encoded), "sha256": hashlib.sha256(encoded).hexdigest()})
        manifest = {"version": 1, "format": "zip-deflate-base64-chunks", "archiveBytes": archive_path.stat().st_size, "archiveSha256": sha256(archive_path), "files": entries, "chunks": chunks}
        (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    if args.replace:
        for entry in entries:
            path = root / entry["path"]
            if sha256(path) != entry["sha256"]:
                raise RuntimeError("Snapshot file changed during packaging; refusing removal: " + entry["path"])
        for entry in entries:
            (root / entry["path"]).unlink()
    (output / "README.txt").write_text("Binary project assets and large JSON/data assets are preserved byte-for-byte in this ZIP/DEFLATE archive, split into base64 text chunks for GitHub connector transport. After cloning, run:\n\n  python tools/content242/restore_assets.py\n\nEach chunk, the archive, and each restored file has a SHA-256 integrity check. Matching assets are skipped. Changed existing files are never overwritten unless --force is explicitly provided. All Java/Kotlin/native/script source files remain ordinary files in the repository. See manifest.json for every original path, size and checksum.\n", encoding="utf-8")
    print(json.dumps({"binaryFiles": len(entries), "originalBytes": sum(e["bytes"] for e in entries), "archiveBytes": manifest["archiveBytes"], "chunks": len(chunks), "maxChunkCharacters": max((c["characters"] for c in chunks), default=0), "snapshotBinariesReplaced": args.replace}, indent=2))

if __name__ == "__main__":
    main()
