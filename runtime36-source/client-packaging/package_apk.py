import hashlib
from pathlib import Path
import sys
import zipfile


mode, baseline, client_jar, target = sys.argv[1:]
client = Path(client_jar).read_bytes()
digest = hashlib.sha256(client).hexdigest()
tag = f"newscape-expedition-lobby-46-{digest[:24]}\n".encode()
replacements = {"assets/jvm/client.jar": client, "assets/jvm/runtime.tag": tag}

with zipfile.ZipFile(baseline) as original:
    if mode == "build":
        with zipfile.ZipFile(target, "x") as candidate:
            for entry in original.infolist():
                contents = replacements.get(entry.filename)
                candidate.writestr(entry, original.read(entry) if contents is None else contents)
        Path(target).with_name("runtime.tag").write_bytes(tag)
    elif mode == "verify":
        with zipfile.ZipFile(target) as candidate:
            assert candidate.testzip() is None
            assert set(original.namelist()) == set(candidate.namelist())
            for entry in original.infolist():
                contents = replacements.get(entry.filename)
                assert candidate.read(entry.filename) == (original.read(entry) if contents is None else contents), entry.filename
        print(f"APK data verified. {len(original.namelist()) - len(replacements)} original entries match, including the existing renderers.")
    else:
        raise SystemExit(f"Unexpected operation {mode}")
