#!/usr/bin/env bash
set -euo pipefail
umask 022

root=/srv/newscape
old="$root/releases/20261119-runtime35"
next="$root/releases/20261119-runtime36-expedition-world"
archive=${1:?missing verified archive}
expected=c3ac03014cd2020206deea39bbf96ef365a564ac176cd73096be64afc64cfb9a

test "$(readlink -f "$root/current")" = "$old"
test ! -e "$next"
work=$(mktemp -d "$root/releases/.runtime36.XXXXXXXX")
mkdir -- "$work/release" "$work/overlay"
cp -- "$archive" "$work/candidate.tar.gz"
printf '%s  %s\n' "$expected" "$work/candidate.tar.gz" | sha256sum --check --status

python3 - "$work/candidate.tar.gz" "$work/overlay" <<'PY'
from pathlib import Path, PurePosixPath
import hashlib
import sys
import tarfile

path, destination = sys.argv[1:]
root = Path(destination)
required_data = {
    'data/world-examines.json',
    'data/world-vertical-travel.json',
    'data/npcs/combat/zz_world_combat_animations.json',
    'data/npcs/spawns/charter_crews.json',
}
with tarfile.open(path, 'r:gz') as archive:
    manifest = archive.extractfile('MANIFEST.sha256').read().decode('ascii')
    expected = {}
    for line in manifest.splitlines():
        digest, name = line.split('  ', 1)
        parts = PurePosixPath(name).parts
        assert len(parts) >= 2 and parts[0] in ('classes', 'data')
        assert '..' not in parts and name not in expected
        assert name.endswith('.class') if parts[0] == 'classes' else name in required_data
        assert len(digest) == 64
        expected[name] = digest
    assert set(name for name in expected if name.startswith('data/')) == required_data
    assert len(expected) == 84
    all_files = [entry for entry in archive.getmembers() if not entry.isdir()]
    assert len(all_files) == 85 and all(entry.isfile() for entry in all_files)
    assert {entry.name for entry in all_files} == set(expected) | {'MANIFEST.sha256'}
    for name, digest in expected.items():
        raw = archive.extractfile(name).read()
        assert hashlib.sha256(raw).hexdigest() == digest, name
        target = root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open('xb') as stream:
            stream.write(raw)
        target.chmod(0o644)
print('PASS staged archive, class allowlist, four data files and all hashes')
PY

cp -a --reflink=auto "$old/." "$work/release/"
cd -- "$work/release"
test ! -e game-overlay/expedition-world
mv -- "$work/overlay/classes" game-overlay/expedition-world
while IFS= read -r -d '' file; do
    relative=${file#"$work/overlay/"}
    test ! -e "$relative"
    install -m 644 -D -- "$file" "$relative"
done < <(find "$work/overlay/data" -type f -print0)
python3 - <<'PY'
from pathlib import Path

script = Path('run-newscape.sh')
text = script.read_text()
before = 'cp="$release/game-overlay/slayer-fury:'
after = 'cp="$release/game-overlay/expedition-world:$release/game-overlay/slayer-fury:'
assert text.count(before) == 1
script.write_text(text.replace(before, after))
PY
bash -n run-newscape.sh
sha256sum --check --strict --status COMPATIBILITY.sha256
grep -v '  run-newscape.sh$' "$old/MANIFEST.sha256" | sha256sum --check --strict --status
find . -type f ! -name MANIFEST.sha256 -printf '%P\0' | sort -z | xargs -0 sha256sum > MANIFEST.sha256
sha256sum --check --strict --status MANIFEST.sha256
runuser -u newscape -- test -r game-overlay/expedition-world/io/ruin/model/entity/npc/actions/traveling/CharterTravel.class
runuser -u newscape -- test -r data/npcs/spawns/charter_crews.json
mv -T -- "$work/release" "$next"
printf 'Prepared %s\n' "$next"
printf 'Release manifest '
sha256sum "$next/MANIFEST.sha256"
printf 'Release files '
wc -l < "$next/MANIFEST.sha256"
