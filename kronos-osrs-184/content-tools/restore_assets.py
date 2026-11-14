"""Restore the repository's exact binary assets; existing changed files are protected."""
from __future__ import annotations
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import tempfile
import zipfile

def digest_file(path: Path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()

def safe_path(root: Path, relative: str):
    name = PurePosixPath(relative)
    if name.is_absolute() or ".." in name.parts or "\\" in relative or ":" in relative:
        raise RuntimeError("Unsafe asset path in manifest")
    destination = root.joinpath(*name.parts)
    if not destination.resolve().is_relative_to(root) or destination.is_symlink():
        raise RuntimeError("Asset destination escapes repository")
    return destination

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--force", action="store_true", help="Replace changed existing assets after verifying payload integrity")
    parser.add_argument("--verify-only", action="store_true", help="Check all restored files without writing")
    args = parser.parse_args()
    root = args.root.resolve()
    payload = root / "assets-payload"
    manifest = json.loads((payload / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("version") != 1 or manifest.get("format") != "zip-deflate-base64-chunks":
        raise RuntimeError("Unsupported binary asset manifest")
    pending = []
    skipped = 0
    seen = set()
    for entry in manifest["files"]:
        if entry["path"] in seen:
            raise RuntimeError("Duplicate asset in manifest")
        seen.add(entry["path"])
        destination = safe_path(root, entry["path"])
        if destination.is_file() and destination.stat().st_size == entry["bytes"] and digest_file(destination) == entry["sha256"]:
            skipped += 1
        else:
            if args.verify_only:
                raise RuntimeError("Asset missing or changed: " + entry["path"])
            if destination.exists() and not args.force:
                raise RuntimeError("Changed existing asset protected; inspect it before --force: " + entry["path"])
            pending.append((entry, destination))
    if args.verify_only:
        print(json.dumps({"verifiedAssets": skipped, "changedFiles": 0}))
        return
    with tempfile.TemporaryDirectory(prefix="newscope-assets-") as temp:
        archive_path = Path(temp) / "payload.zip"
        with archive_path.open("wb") as archive_output:
            for chunk in manifest["chunks"]:
                path = safe_path(payload.resolve(), chunk["path"])
                encoded = path.read_bytes()
                if len(encoded) != chunk["characters"] or hashlib.sha256(encoded).hexdigest() != chunk["sha256"]:
                    raise RuntimeError("Asset chunk checksum mismatch: " + chunk["path"])
                archive_output.write(base64.b64decode(encoded, validate=True))
        if archive_path.stat().st_size != manifest["archiveBytes"] or digest_file(archive_path) != manifest["archiveSha256"]:
            raise RuntimeError("Reconstructed archive checksum mismatch")
        with zipfile.ZipFile(archive_path) as archive:
            names = archive.namelist()
            if len(names) != len(seen) or set(names) != seen:
                raise RuntimeError("Archive members differ from asset manifest")
            for entry, destination in pending:
                destination.parent.mkdir(parents=True, exist_ok=True)
                temporary = None
                try:
                    with tempfile.NamedTemporaryFile(dir=destination.parent, prefix=".asset-restore-", delete=False) as target:
                        temporary = Path(target.name)
                        digest = hashlib.sha256()
                        count = 0
                        with archive.open(entry["path"]) as source:
                            for block in iter(lambda: source.read(1024 * 1024), b""):
                                target.write(block)
                                digest.update(block)
                                count += len(block)
                    if count != entry["bytes"] or digest.hexdigest() != entry["sha256"]:
                        raise RuntimeError("Restored asset checksum mismatch: " + entry["path"])
                    os.replace(temporary, destination)
                    temporary = None
                finally:
                    if temporary is not None:
                        temporary.unlink(missing_ok=True)
    print(json.dumps({"restoredAssets": len(pending), "matchingAssetsSkipped": skipped, "checksumsVerified": True}))

if __name__ == "__main__":
    main()
