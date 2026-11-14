"""Create a new release with exactly one independently checked class-content change."""
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "local-runtime/server-releases/hollow-depths-184-candidate-09"
DEST = ROOT / "local-runtime/server-releases/hollow-depths-184-hitsplats-10"
EVIDENCE = ROOT / "local-runtime/hitsplat-hotfix-10"
ENTRY = "io/ruin/model/combat/Hit.class"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inventory(root):
    return {p.relative_to(root).as_posix(): sha(p) for p in root.rglob("*") if p.is_file()}


def main():
    assert BASE.is_dir() and BASE.parent == DEST.parent and not DEST.exists()
    old_schema = (EVIDENCE / "old-schema.txt").read_bytes()
    new_schema = (EVIDENCE / "new-schema.txt").read_bytes()
    assert old_schema == new_schema, "Class schemas differ"
    patched = (EVIDENCE / "classes" / ENTRY).read_bytes()
    old_jar = BASE / "lib/kronos-server-1.0.jar"
    with zipfile.ZipFile(old_jar) as archive:
        old_entries = {info.filename: archive.read(info.filename) for info in archive.infolist()}
        assert len(old_entries) == len(archive.infolist()), "Duplicate ZIP entries"
    assert ENTRY in old_entries and old_entries[ENTRY] != patched
    before = inventory(BASE)
    shutil.copytree(BASE, DEST)
    new_jar = DEST / "lib/kronos-server-1.0.jar"
    temporary = new_jar.with_suffix(".jar.building")
    with zipfile.ZipFile(old_jar) as source, zipfile.ZipFile(temporary, "w") as target:
        target.comment = source.comment
        for info in source.infolist():
            target.writestr(info, patched if info.filename == ENTRY else old_entries[info.filename])
    temporary.replace(new_jar)
    with zipfile.ZipFile(new_jar) as archive:
        assert archive.testzip() is None
        new_entries = {info.filename: archive.read(info.filename) for info in archive.infolist()}
        assert len(new_entries) == len(archive.infolist())
    assert old_entries.keys() == new_entries.keys()
    changed = sorted(name for name in old_entries if old_entries[name] != new_entries[name])
    assert changed == [ENTRY], changed
    after = inventory(DEST)
    assert before.keys() == after.keys()
    changed_files = sorted(name for name in before if before[name] != after[name])
    assert changed_files == ["lib/kronos-server-1.0.jar"], changed_files
    receipt = {
        "schemaVersion": 1, "oldRelease": str(BASE), "newRelease": str(DEST),
        "oldJarSha256": sha(old_jar), "newJarSha256": sha(new_jar),
        "changedEntries": changed, "addedEntries": [], "removedEntries": [],
        "unchangedEntryCount": len(old_entries) - 1, "totalEntryCount": len(old_entries),
        "changedFiles": changed_files, "unchangedReleaseFileCount": len(before) - 1,
        "oldHitSha256": hashlib.sha256(old_entries[ENTRY]).hexdigest(),
        "newHitSha256": hashlib.sha256(patched).hexdigest(),
        "identicalClassSchemas": True, "classSchemaSha256": hashlib.sha256(old_schema).hexdigest(),
        "releaseFilesSha256": after,
        "testProvenance": "This receipt proves packaging and class-schema equivalence only. Map-proof provenance and targeted combat/client verification are recorded separately; this receipt does not claim new gameplay tests."
    }
    (EVIDENCE / "delta-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf8")
    print(json.dumps({key: receipt[key] for key in ("oldJarSha256", "newJarSha256", "changedEntries", "unchangedEntryCount", "unchangedReleaseFileCount", "identicalClassSchemas")}))


if __name__ == "__main__":
    main()
