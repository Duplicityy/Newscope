from hashlib import sha256, file_digest
from pathlib import Path
import json
import subprocess
import sys
from tempfile import TemporaryDirectory
import zipfile

out, app, published, cache, legacy_source, jdk, csc, asm = map(Path, sys.argv[1:])
source = Path(__file__).resolve().parent
exe_name = "Newscape-Windows-Expedition-v46.exe"
cache_release = "184729-expedition-v46"
client_hash = "4c54782bc0206c718df561d13180bdeefd61f9b73a46bbc030cf852bfeca3df0"


def file_sha(path):
    with path.open("rb") as stream:
        return file_digest(stream, "sha256").hexdigest()


def run(executable, *args):
    subprocess.run([str(executable), *map(str, args)], check=True)


assert file_sha(app) == client_hash
assert file_sha(published / "app/windows-support.jar") == "ddbad9b181603bf413578ae638b51b51bcdcc4d9695489bc048b5173f52dd9b3"
assert (cache / "content242-format.properties").read_text().splitlines() == [
    "format=k242-d727-lobby-v3", "js5_revision=184729"
]
cache_files = sorted(path for path in cache.iterdir() if path.is_file())
assert len(cache_files) == 24
assert all(path.name == "content242-format.properties" or path.name.startswith("main_file_cache.") for path in cache_files)
runtime_files = sorted(path for path in (published / "runtime").rglob("*") if path.is_file())
license_files = sorted(path for path in (published / "licenses").rglob("*") if path.is_file())
assert runtime_files and license_files

# A new cache name keeps the previous seed available, and the client connects directly to Ubuntu.
bootstrap = legacy_source.read_text(encoding="utf-8-sig")
old_release = "public static final String RELEASE = \"184728-fresh1\";"
old_host = 'System.setProperty("kronos.host", "51.91.58.148");'
assert bootstrap.count(old_release) == bootstrap.count(old_host) == 1
bootstrap = bootstrap.replace(old_release, f'public static final String RELEASE = "{cache_release}";')
bootstrap = bootstrap.replace(old_host, 'System.setProperty("kronos.host", "51.38.80.2");')
bootstrap_source = out / "WindowsFreshCacheLauncher.java"
bootstrap_source.write_text(bootstrap, encoding="utf-8", newline="\n")
launcher = out / "newscape-launcher.jar"
with TemporaryDirectory(dir=out) as scratch:
    classes = Path(scratch) / "classes"
    tools = Path(scratch) / "tools"
    classes.mkdir()
    tools.mkdir()
    run(jdk / "javac.exe", "-proc:none", "-encoding", "UTF-8", "-d", classes,
        bootstrap_source, source / "ManagedCacheDirectory.java")
    run(jdk / "javac.exe", "-proc:none", "-cp", asm, "-d", tools, source / "PatchStandaloneClient.java")
    run(jdk / "java.exe", "-cp", f"{tools};{asm}", "PatchStandaloneClient", app, classes)
    run(jdk / "jar.exe", "cf", launcher, "-C", classes, ".")

entries = [
    ("app/client.jar", app),
    ("app/newscape-launcher.jar", launcher),
    ("app/windows-support.jar", published / "app/windows-support.jar"),
]
entries += [(path.relative_to(published).as_posix(), path) for path in runtime_files + license_files]
entries += [(f"cache/Cache/{path.name}", path) for path in cache_files]
assert len({name.lower() for name, _ in entries}) == len(entries)
manifest = [f"release={cache_release}", f"count={len(cache_files)}"]
for index, path in enumerate(cache_files):
    manifest.extend([f"file.{index}={path.name}", f"sha256.{index}={file_sha(path)}",
                     f"bytes.{index}={path.stat().st_size}"])
seed_manifest = "\n".join(manifest) + "\n"
payload = out / "payload.zip"
with zipfile.ZipFile(payload, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as bundle:
    for name, path in entries:
        bundle.write(path, name)
    bundle.writestr("cache/cache-manifest.properties", seed_manifest)
with zipfile.ZipFile(payload) as bundle:
    assert set(bundle.namelist()) == {name for name, _ in entries} | {"cache/cache-manifest.properties"}
    assert bundle.testzip() is None

hashes = {
    "ArchiveHash": file_sha(payload), "ClientHash": client_hash, "LauncherHash": file_sha(launcher),
    "SupportHash": file_sha(published / "app/windows-support.jar"),
    "JavaHash": file_sha(published / "runtime/bin/java.exe"),
    "JavaWindowHash": file_sha(published / "runtime/bin/javaw.exe"),
}
metadata = out / "BundleMetadata.cs"
metadata.write_text("internal static class BundleMetadata {\n" +
                    "".join(f'    public const string {name} = "{value}";\n' for name, value in hashes.items()) +
                    "}\n", encoding="utf-8")
exe = out / exe_name
run(csc, "/nologo", "/optimize+", "/target:winexe", "/platform:x64",
    "/reference:System.IO.Compression.dll", f"/out:{exe}", f"/resource:{payload},NewscapeBundle",
    source / "StandaloneLauncher.cs", metadata)
result = {
    "exe": exe_name, "bytes": exe.stat().st_size, "sha256": file_sha(exe),
    "files": len(entries) + 1, "cacheFiles": len(cache_files), "clientSha256": client_hash,
    "embeddedZipSha256": hashes["ArchiveHash"], "cacheRelease": cache_release,
    "embeddedCacheManifestSha256": sha256(seed_manifest.encode()).hexdigest(),
}
(out / "BUILD-RECEIPT.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
print(json.dumps(result, indent=2))
