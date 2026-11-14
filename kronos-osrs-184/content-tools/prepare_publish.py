"""Create a fresh, reviewable Newscope snapshot; never writes to source or pushes Git.

python tools/content242/prepare_publish.py --inventory-only
python tools/content242/prepare_publish.py --output local-runtime/newscope-publish
"""
from __future__ import annotations
import argparse
import collections
import hashlib
import json
import os
from pathlib import Path
import re
import shutil

ROOT = Path(__file__).resolve().parents[2]
SKIP_DIRS = {".git", ".gradle", ".idea", ".cxx", "__pycache__", "node_modules", ".cache", "target", ".externalNativeBuild"}
SKIP_SUFFIXES = {".class", ".pyc", ".log", ".mdmp", ".dmp", ".hprof", ".pid", ".lock", ".apk", ".aab", ".exe", ".dll", ".so", ".dylib", ".pdb", ".keystore", ".jks", ".p12", ".pfx", ".pem", ".p8", ".key", ".rar", ".7z", ".zip", ".xz", ".gz"}
PRIVATE_NAMES = {"server.properties", "local.properties", "accounts.properties", "keystore.properties", "tokens.json", "legacy_login.json", ".mcp.json", "kronos_rsa", ".ds_store"}
PRIVATE_OPERATIONS = {"Provision-Duplicity.ps1", "Verify-Duplicity.ps1", "README-LOCAL.txt", "tools/provision-remote-account.ps1", "tools/test-remote-account.ps1", "tools/TestLocalAccounts.java", "windows-client/tests/VerifyDuplicity.java"}
LOCAL_ONLY_LAUNCHERS = {"Apply-Kronos-242.cmd"}
TEXT_SUFFIXES = {".java", ".kt", ".kts", ".gradle", ".properties", ".json", ".xml", ".yaml", ".yml", ".txt", ".md", ".html", ".css", ".js", ".ts", ".ps1", ".cmd", ".bat", ".py", ".sh", ".env", ".toml", ".conf", ".c", ".h", ".cpp"}
RULES = [
    ("discord-webhook", re.compile(r"https://(?:canary\.|ptb\.)?(?:discord(?:app)?\.com)/api/webhooks/\d+/[A-Za-z0-9_-]+")),
    ("discord-token", re.compile(r"(?<![A-Za-z0-9_-])(?:mfa\.[A-Za-z0-9_-]{60,}|[A-Za-z0-9_-]{23,30}\.[A-Za-z0-9_-]{6}\.[A-Za-z0-9_-]{25,110})(?![A-Za-z0-9_-])")),
    ("github-token", re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})\b")),
    ("slack-token", re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{20,}\b")),
    ("aws-access-id", re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b")),
]
ASSIGNMENT = re.compile(r'''(?i)(\b[A-Za-z_$][\w$]{0,80}(?:password|passwd|token|secret|api_?key|client_?key|access_?key)[\w$]{0,80}["']?\s*[=:]\s*|\b(?:password|passwd|token|secret|api_?key)["']?\s*[=:]\s*)(["'])([^"'\r\n]{8,})\2''')
PROPERTY_SECRET = re.compile(r'''(?im)^([\w.-]*(?:password|passwd|secret|token|api[_-]?key)[\w.-]*\s*=\s*)([^\r\n]{8,})$''')
KNOWN_LITERAL_SECRETS = {
    "kronos-osrs-184/Kronos-master/kronos-central-server/src/main/java/io/ruin/central/model/world/WorldList.java": {"AUTH_TOKEN"},
    "kronos-osrs-184/Kronos-master/kronos-server/src/main/java/io/ruin/model/inter/handlers/BuyCredits.java": {"PAYPAL_SECRET"},
    "kronos-osrs-184/Kronos-master/kronos-server/src/main/java/io/ruin/services/discord/impl/commands/BugReport.java": {"ACCESS_TOKEN"},
}

def excluded(path: Path, base: Path) -> str | None:
    rel = path.relative_to(base)
    parts = rel.parts
    if path.is_symlink():
        return "symlink"
    if any(p in SKIP_DIRS for p in parts):
        return "generated-directory"
    if any(p in {"build", "out", "bin"} for p in parts[:-1]) and "src" not in parts[:parts.index(next(p for p in parts[:-1] if p in {"build", "out", "bin"}))]:
        return "build-output"
    if path.name.lower() in PRIVATE_NAMES or path.name.lower().startswith(("release-signing", "hs_err_pid", "replay_pid")):
        return "private-or-machine-state"
    if path.name.endswith(".env") and not path.name.endswith(".env.example"):
        return "private-environment"
    if path.suffix.lower() in SKIP_SUFFIXES:
        return "generated-binary-or-key-material"
    if path.suffix.lower() == ".jar" and path.name != "gradle-wrapper.jar" and not any(p.lower() in {"lib", "libs", "vendor", "dependencies"} for p in parts[:-1]):
        return "rebuildable-binary"
    if "src/main/assets/jvm/jre/" in rel.as_posix() or "src/main/assets/jvm/" in rel.as_posix():
        return "staged-mobile-runtime"
    return None

def walk(base: Path):
    for directory, dirs, files in os.walk(base, followlinks=False):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS and not (Path(directory) / d).is_symlink()]
        for name in files:
            yield Path(directory) / name

def sanitize(text: str, destination: str):
    changes = []
    review = []
    # A diagnostics sample can still contain copied account identifiers even
    # when its bearer tokens are blank. Keep its regression-test structure,
    # but make all account/session/name/hash values explicitly synthetic.
    if destination == "kronos-mobile/tools/diagnostics/tokens-sample.json":
        data = json.loads(text)
        replacements = {"account": {}, "name": {}, "hash": {}}
        def fixture_value(value, kind):
            if value not in replacements[kind]:
                index = len(replacements[kind]) + 1
                replacements[kind][value] = {
                    "account": f"synthetic-account-{index:03d}",
                    "name": f"Synthetic{index:02d}",
                    "hash": f"synthetic-user-hash-{index:03d}",
                }[kind]
            return replacements[kind][value]
        def synthetic(value, key=""):
            if isinstance(value, dict):
                return {name: synthetic(item, name) for name, item in value.items()}
            if isinstance(value, list):
                return [synthetic(item) for item in value]
            if key in {"accountId", "account_id", "chosen_account_id"} and isinstance(value, str):
                return fixture_value(value, "account")
            if key in {"displayName", "display_name", "chosen_display_name"} and isinstance(value, str):
                return fixture_value(value, "name")
            if key in {"userHash", "user_hash"} and isinstance(value, str):
                return fixture_value(value, "hash")
            if key == "session_id":
                return "00000000-0000-4000-8000-000000000001"
            if key in {"access_token", "refresh_token", "id_token", "otl_token"}:
                return ""
            if key == "fetched_at":
                return "2000-01-01T00:00:00Z"
            if key == "note":
                return "Synthetic diagnostic fixture"
            return value
        data = synthetic(data)
        data["_fixture_notice"] = "Synthetic diagnostic data only; no real account identifiers or credentials."
        text = json.dumps(data, indent=2) + "\n"
        changes.append({"kind": "diagnostic-account-fixture-synthetic", "count": 1})
    # Omit only the personal-account verification hook from the generic smoke test.
    if destination == "windows-client/tests/WindowsSmoke.java":
        text, count = re.subn(r'^\s*if\(Boolean\.getBoolean\("kronos\.smoke\.verifyDuplicity"\)\)VerifyDuplicity\.run\(client\);\r?\n', '', text, flags=re.M)
        if count:
            changes.append({"kind": "personal-account-test-hook-removed", "count": count})
    if destination == "Test-Kronos-Windows.ps1":
        anchor = ' "$root/windows-client/tests/VerifyDuplicity.java"'
        if anchor in text:
            text = text.replace(anchor, '')
            changes.append({"kind": "personal-account-test-build-reference-removed", "count": 1})
    # The server private RSA exponent must be supplied privately on a fresh deployment.
    if destination.endswith("/io/ruin/network/LoginDecoder.java"):
        pattern = re.compile(r'(BigInteger\s+EXPONENT\s*=\s*new BigInteger\()"[0-9]+"(\))')
        text, count = pattern.subn(r'\1System.getProperty("kronos.rsa.privateExponent")\2', text)
        if count:
            changes.append({"kind": "rsa-private-exponent-externalized", "count": count})
    for kind, pattern in RULES:
        text, count = pattern.subn("REDACTED_CONFIGURE_PRIVATELY", text)
        if count:
            changes.append({"kind": kind, "count": count})
    def assignment(match):
        # Preserve code-based env/property lookups and obvious documentation placeholders.
        value = match.group(3)
        if value.startswith(("${", "$env:", "REDACTED_", "YOUR_", "your-", "your_", "<")):
            return match.group(0)
        variable = re.split(r"\s*[=:]", match.group(1), maxsplit=1)[0].strip().strip("\"'")
        if variable in KNOWN_LITERAL_SECRETS.get(destination, set()):
            changes.append({"kind": "known-credential-literal", "count": 1})
            return match.group(1) + match.group(2) + "REDACTED_CONFIGURE_PRIVATELY" + match.group(2)
        review.append({"kind": "unmodified-assignment-review", "line": text.count("\n", 0, match.start()) + 1, "variable": variable})
        return match.group(0)
    text = ASSIGNMENT.sub(assignment, text)
    if Path(destination).suffix.lower() in {".properties", ".conf", ".env"}:
        def property_secret(match):
            value = match.group(2).strip()
            if value.startswith(("${", "$", "REDACTED_", "YOUR_", "your-", "your_", "<")):
                return match.group(0)
            review.append({"kind": "unmodified-property-review", "line": text.count("\n", 0, match.start()) + 1, "variable": match.group(1).split("=", 1)[0].strip()})
            return match.group(0)
        text = PROPERTY_SECRET.sub(property_secret, text)
    if re.search(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----\s+[A-Za-z0-9+/=\r\n]{80,}", text):
        raise RuntimeError("Private key block requires manual removal: " + destination)
    return text, changes, review

def source_entries(stage: Path, excluded_counts):
    for name in ("kronos-osrs-184", "kronos-mobile", "windows-client"):
        base = ROOT / name
        for path in walk(base):
            relative = path.relative_to(ROOT)
            if relative.as_posix().startswith(("kronos-osrs-184/Kronos-master/Cache/", "kronos-osrs-184/Kronos-master/kronos-server/data/")):
                continue
            if relative.as_posix() in PRIVATE_OPERATIONS:
                excluded_counts["private-account-operation"] += 1
                continue
            reason = excluded(path, base)
            if reason:
                excluded_counts[reason] += 1
            else:
                yield path, relative
    for path in ROOT.iterdir():
        if path.is_file() and path.suffix.lower() in {".ps1", ".cmd", ".txt"}:
            if path.name in LOCAL_ONLY_LAUNCHERS:
                excluded_counts["machine-specific-launcher"] += 1
                continue
            if path.relative_to(ROOT).as_posix() in PRIVATE_OPERATIONS:
                excluded_counts["private-account-operation"] += 1
                continue
            yield path, path.relative_to(ROOT)
    for name in ("jdk8-package.json", "jdk21-package.json", "intellij-release.json"):
        path = ROOT / "tools/downloads" / name
        if path.is_file():
            yield path, path.relative_to(ROOT)
    for path in (ROOT / "tools").iterdir():
        if path.is_file() and path.suffix.lower() in {".java", ".ps1", ".py", ".sh", ".cmd"}:
            if path.relative_to(ROOT).as_posix() in PRIVATE_OPERATIONS:
                excluded_counts["private-account-operation"] += 1
                continue
            yield path, path.relative_to(ROOT)
    for path in walk(ROOT / "tools/content242"):
        if path.suffix.lower() in {".py", ".ps1", ".java", ".txt", ".json", ".html", ".md"} and not excluded(path, ROOT / "tools/content242"):
            yield path, path.relative_to(ROOT)
    release = ROOT / "content-release"
    if release.exists():
        for path in walk(release):
            if path.suffix.lower() in {".html", ".png", ".txt", ".json", ".md"} and not excluded(path, release):
                yield path, path.relative_to(ROOT)
    report = stage / "import-report.json"
    if report.is_file() and not (release / "import-report.json").exists():
        yield report, Path("content-release/import-report.json")
    license_path = ROOT / "incoming/content-242/tsps-1ccb548be725c70900b604968354dd3147b839ea/LICENSE"
    if license_path.is_file():
        yield license_path, Path("third-party/TSPS-BSD-2-Clause-LICENSE.txt")
    showcase = ROOT / "incoming/autonomous-adventurers/source/kronos-player-simulation/showcase"
    if showcase.exists():
        for path in walk(showcase):
            if not excluded(path, showcase):
                yield path, Path("showcase") / path.relative_to(showcase)
    for source, destination in ((stage / "Cache", Path("kronos-osrs-184/Kronos-master/Cache")), (stage / "data", Path("kronos-osrs-184/Kronos-master/kronos-server/data"))):
        if source.exists():
            for path in walk(source):
                reason = excluded(path, source)
                if reason:
                    excluded_counts[reason] += 1
                else:
                    yield path, destination / path.relative_to(source)

def redact_report_paths(text: str):
    """Keep report structure while removing machine/user absolute path prefixes."""
    def walk_value(value):
        if isinstance(value, dict):
            return {key: walk_value(item) for key, item in value.items()}
        if isinstance(value, list):
            return [walk_value(item) for item in value]
        if isinstance(value, str):
            normal = value.replace("\\", "/")
            prefix = ROOT.as_posix().rstrip("/") + "/"
            if normal.lower().startswith(prefix.lower()):
                return normal[len(prefix):]
            if re.match(r"^[A-Za-z]:/", normal):
                return "local-input/" + normal.rsplit("/", 1)[-1]
        return value
    return json.dumps(walk_value(json.loads(text)), indent=2, ensure_ascii=False) + "\n"

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", type=Path, default=ROOT / "local-runtime/content242-stage")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--inventory-only", action="store_true")
    args = parser.parse_args()
    stage = args.stage.resolve()
    output = args.output.resolve() if args.output else None
    ready = (stage / "Cache/main_file_cache.dat2").is_file() and (stage / "data").is_dir()
    if not args.inventory_only:
        if not output or output.exists():
            parser.error("--output must name a new directory; existing outputs are never overwritten")
        if not ready:
            parser.error("Staged Cache/data must exist before exporting")
        if output == ROOT or ROOT.is_relative_to(output):
            parser.error("Output cannot contain the source workspace")
        if any(output.is_relative_to(ROOT / part) for part in ("kronos-osrs-184", "kronos-mobile", "windows-client", "tools", "incoming")):
            parser.error("Output must be separate from source trees")
        output.mkdir(parents=True)
    omissions = collections.Counter()
    files = []
    redactions = []
    review_findings = []
    for source, relative in source_entries(stage, omissions):
        data = None
        if source.suffix.lower() in TEXT_SUFFIXES or source.name in {"gradlew", ".gitignore", ".gitattributes"}:
            data = source.read_bytes()
            try:
                text = data.decode("utf-8-sig")
            except UnicodeDecodeError:
                text = None
            if text is not None:
                clean, changes, review = sanitize(text, relative.as_posix())
                if relative.parts[0] == "content-release" and relative.suffix.lower() == ".json":
                    clean = redact_report_paths(clean)
                if review:
                    review_findings.append({"path": relative.as_posix(), "findings": review})
                if changes:
                    redactions.append({"path": relative.as_posix(), "changes": changes})
                if clean != text:
                    data = clean.encode("utf-8")
        digest = hashlib.sha256()
        if data is None:
            with source.open("rb") as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(chunk)
            size = source.stat().st_size
        else:
            digest.update(data)
            size = len(data)
        record = {"path": relative.as_posix(), "bytes": size, "sha256": digest.hexdigest()}
        files.append(record)
        if not args.inventory_only:
            destination = output / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            if data is None:
                shutil.copy2(source, destination)
            else:
                destination.write_bytes(data)
                shutil.copymode(source, destination)
    manifest = {"stageReady": ready, "fileCount": len(files), "totalBytes": sum(f["bytes"] for f in files), "omissions": dict(omissions), "sanitizedFiles": redactions, "reviewFindings": review_findings, "largeFiles": [f for f in files if f["bytes"] >= 50 * 1024 * 1024], "files": files}
    if not args.inventory_only:
        (output / "publish-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        (output / ".gitignore").write_text("local-runtime/\nserver-backups/\n.gradle-home*/\ntools/jdk*/\ntools/android-sdk/\ntools/android-user/\ntools/intellij*/\n**/.gradle/\n**/build/\n**/.cxx/\n**/__pycache__/\n*.log\n*.mdmp\n*.hprof\n*.pid\n*.keystore\n*.jks\n*.pem\n*.pfx\n*.p12\n*.key\n**/server.properties\n**/local.properties\n**/accounts.properties\n**/*.env\n!**/*.env.example\n", encoding="utf-8")
        (output / "PUBLISH-AND-BUILD.txt").write_text(BUILD_NOTES, encoding="utf-8")
        # Keep source layouts intact because the existing build scripts resolve these paths.
        safe_keys = re.compile(r"^(world_id|world_name|world_stage|world_type|world_flag|world_settings|halloween|christmas|login_set)=")
        original = ROOT / "kronos-osrs-184/Kronos-master/server.properties"
        config = [line for line in original.read_text(encoding="utf-8-sig").splitlines() if safe_keys.match(line)]
        config += ["offline_mode=true", "world_address=127.0.0.1:13302", "central_address=127.0.0.1:1", "cache_path=Cache", "data_path=kronos-server/data"]
        (output / "kronos-osrs-184/Kronos-master/server.properties.example").write_text("\n".join(config) + "\n", encoding="utf-8")
    summary = {key: value for key, value in manifest.items() if key != "files"}
    print(json.dumps(summary, indent=2))

BUILD_NOTES = """NEWSCOPE SOURCE SNAPSHOT

Layout preserves the existing build scripts:
  kronos-osrs-184/Kronos-master/  server, update server, API, legacy RuneLite client,
                                 staged upgraded Cache and matching server data
  kronos-mobile/                 Android/iOS source and Kronos mobile bridge
  windows-client/                Windows endpoint/identity overlay and smoke tests
  tools/content242/              import, verification and isolated staging tools
  showcase/                     autonomous-adventurers HTML presentation
  root *.ps1/*.cmd               existing build, start, backup and test scripts

This is an export of current working files, including uncommitted source changes.
It does not copy either repository's Git metadata or modify the running server.
publish-manifest.json contains content hashes, sizes and sanitization kinds/paths.
Review sanitizedFiles before committing. Automated sanitization is a focused
credential scan, not a guarantee that every possible credential is recognized.

Machine/runtime state is excluded: accounts/player saves, local-runtime, backups,
SDK/JDK/IDE installations, build caches, APK/ZIP bundles, generated mobile JVM
assets, signing material and private config. Existing public map region_keys.json
is game content and is retained. Obtain builds from source; do not copy old player
profiles or credentials into the repository.

Prerequisites: Windows x64; PowerShell 7 (pwsh) for content242 staging/deployment;
Python 3.10+ for import, verification and asset restoration; JDK 8 at
tools/jdk8/jdk8u512-b09; JDK 21 below tools/jdk21; Android SDK under tools/android-sdk
with API 35, NDK 27.0.12077973 and CMake 3.22.1. The root Gradle 6.4.1 wrapper builds
both server and legacy desktop client via tools/gradle.ps1 with JDK 8. Android
uses its Gradle 8.8 wrapper with JDK 21. Build-Kronos-Mobile.ps1
records exact SHA-256 checks for its Android JRE 21 ARM64 and Cacio 1.18 downloads;
provide those verified third-party downloads in tools/downloads. Third-party
runtime distributions are not embedded in this source snapshot.
Public JDK/IDE download metadata is included under tools/downloads. The optional
tools/install-toolchain.ps1 installs JDK 8 and IntelliJ from that metadata.
IntelliJ is optional for core builds; tools/mobile-sdk.ps1 specifically expects
its bundled JBR, so use sdkmanager directly with JDK 21 if you do not install it.
The Android app targets API 35 and supports Android 9/API 28 or newer on ARM64.

If assets-payload/ is present, restore the exact Cache, media, wrapper JARs and
other binary assets first: python tools/content242/restore_assets.py
The payload is ZIP/DEFLATE split into base64 text chunks for connector transport.
The restorer verifies every chunk/archive/file SHA-256, skips identical files,
and refuses to overwrite changed files unless --force is explicitly requested.
All source files remain directly readable in the repository.

Copy server.properties.example to server.properties and configure local paths.
Supply the original compatible server RSA private exponent privately using
-Dkronos.rsa.privateExponent=... in a protected local launcher, or rotate both
server/client RSA configuration for a separate deployment. The private literal
is removed only from this publishing copy. Never commit the private value.
The local VM's already-built immutable server release retains its compatible
private configuration; exporting source does not change that running release.
Legacy third-party integration tokens and webhook literals were replaced in the
copy; configure fresh credentials privately if enabling those integrations.

Build the server and original revision-184 client using Build-Kronos.cmd and the
repository Gradle tasks, then use Build-Kronos-Mobile.ps1 and
Build-Kronos-Windows.ps1. Existing test launchers may assume local state; use the
tools/content242 isolated scripts first, following STAGING.txt. No live restart,
cache swap or production deployment is performed by the export operation.
The VM-specific Apply-Kronos-242.cmd one-click launcher is intentionally excluded:
it targets a private local-runtime stage and sealed process identities. A clone
must build, test and seal its own deployment before using the generic helper.

Before Git push: inspect the manifest, run a second secret scan, ensure files
above the hosting service's per-file limit use Git LFS or a release asset, verify
the private remote URL, and include all source/data/tool changes in the commit.
No credentials should be present in Git history. Do not add the whole workspace.
"""

if __name__ == "__main__":
    main()
