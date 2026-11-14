"""Prepare checked GitHub create_tree arguments without publishing or Git auth.

Run after prepare_publish.py and pack_repository_assets.py --replace. Batch JSON
files must be kept outside the snapshot. Read a batch privately in functions.exec,
parse its JSON, set base_tree_sha to the preceding returned tree SHA, and invoke
the GitHub connector create_tree tool. Only create/update the branch after all
batches succeed and the final tree inventory is verified.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path

def encoded(value):
    return json.dumps(value, ensure_ascii=True, separators=(",", ":")).encode("ascii")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repository", default="Duplicityy/Newscope")
    parser.add_argument("--max-bytes", type=int, default=1536 * 1024)
    parser.add_argument("--max-entries", type=int, default=250)
    args = parser.parse_args()
    root = args.snapshot.resolve()
    output = args.output.resolve()
    if not (root / "publish-manifest.json").is_file():
        payload_manifest = root / "assets-payload/manifest.json"
        if not payload_manifest.is_file() or not any(item["path"] == "publish-manifest.json" for item in json.loads(payload_manifest.read_text(encoding="utf-8"))["files"]):
            parser.error("Snapshot is missing its direct or packed publish manifest")
    if output.exists() or output.is_relative_to(root) or root.is_relative_to(output):
        parser.error("Output must be a new directory outside the snapshot")
    if args.max_bytes < 1100000 or args.max_entries < 1:
        parser.error("Allow at least 1,100,000 bytes per batch for payload chunks")
    paths = []
    for directory, dirs, names in os.walk(root, followlinks=False):
        dirs[:] = sorted(d for d in dirs if d != ".git")
        paths.extend(Path(directory) / name for name in sorted(names))
    files = []
    batches = []
    batch = []
    overhead = len(encoded({"repository_full_name": args.repository, "base_tree_sha": None, "tree_elements": []})) + 100
    used = overhead
    output.mkdir(parents=True)
    def finish():
        nonlocal batch, used
        if not batch:
            return
        body = encoded({"repository_full_name": args.repository, "base_tree_sha": None, "tree_elements": batch})
        name = f"batch-{len(batches) + 1:05d}.json"
        (output / name).write_bytes(body)
        batches.append({"path": name, "bytes": len(body), "entries": len(batch), "sha256": hashlib.sha256(body).hexdigest()})
        batch = []
        used = overhead
    for path in sorted(paths):
        if path.is_symlink() or not path.resolve().is_relative_to(root):
            raise RuntimeError("Unsafe snapshot path")
        relative = path.relative_to(root).as_posix()
        raw = path.read_bytes()
        try:
            content = raw.decode("utf-8")
        except UnicodeDecodeError:
            raise RuntimeError("Binary asset still present; pack it first: " + relative)
        if "\0" in content:
            raise RuntimeError("NUL-containing asset still present; pack it first: " + relative)
        mode = "100755" if path.name == "gradlew" or path.suffix == ".sh" else "100644"
        entry = {"path": relative, "mode": mode, "type": "blob", "content": content}
        size = len(encoded(entry)) + 1
        if size + overhead > args.max_bytes:
            raise RuntimeError("Single text file exceeds batch budget; increase --max-bytes or pack data asset: " + relative)
        if len(batch) >= args.max_entries or used + size > args.max_bytes:
            finish()
        batch.append(entry)
        used += size
        blob = hashlib.sha1(b"blob " + str(len(raw)).encode("ascii") + b"\0" + raw).hexdigest()
        files.append({"path": relative, "mode": mode, "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest(), "gitBlobSha": blob})
    finish()
    index = {"repository": args.repository, "fileCount": len(files), "totalFileBytes": sum(item["bytes"] for item in files), "batchCount": len(batches), "maxBatchBytes": max((item["bytes"] for item in batches), default=0), "batches": batches}
    (output / "batches-index.json").write_text(json.dumps(index, indent=2) + "\n", encoding="utf-8")
    (output / "expected-tree.json").write_text(json.dumps({"files": files}, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in index.items() if key != "batches"}, indent=2))

if __name__ == "__main__":
    main()
