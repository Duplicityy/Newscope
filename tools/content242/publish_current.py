"""Export the current integrated sources and exact public content, never live state."""
from __future__ import annotations
import argparse, collections, hashlib, json, os, re, shutil
from pathlib import Path
import prepare_publish as legacy

ROOT = Path(__file__).resolve().parents[2]
def long_path(path):
    value=str(Path(path).resolve())
    return Path('\\\\?\\'+value) if os.name=='nt' and not value.startswith('\\\\?\\') else Path(value)
PRIVATE_PARTS = {'local-runtime', 'server-backups', 'player-saves', 'players', 'accounts',
                 'mailboxes', 'logs', 'crash-dumps', 'dumps', 'node_modules',
                 'fixtures-generated', 'test-output'}
PRIVATE_FILES = {'source-snapshot.json', 'source-delta-review.json', 'deployment-manifest.json',
                 'owner-payload.json', 'account-registry.json', 'legacy_login.json', 'CURRENT-KRONOS-RELEASE.txt',
                 'Enable-Kronos-Remote.ps1','Start-Kronos-Download.ps1','Start-Kronos-Secure-Download.ps1','serve-mobile-download.ps1'}

def digest(data): return hashlib.sha256(data).hexdigest()

def reason(path, base):
    rel = path.relative_to(base)
    if any(p.lower() in PRIVATE_PARTS for p in rel.parts): return 'private-runtime-state'
    if path.name in PRIVATE_FILES: return 'private-runtime-manifest'
    if path.name.startswith(('hs_err_', 'replay_pid')): return 'crash-dump'
    if any(p.lower().startswith('.gradle-home') for p in rel.parts): return 'build-cache'
    if path.name.lower() in {'credentials.json', 'secrets.json', 'key.properties', 'debug.keystore'}: return 'credential-file'
    return legacy.excluded(path, base)

def entries(server, mobile, stage, omissions):
    result = {}
    def add_tree(base, dest, skip=()):
        for p in legacy.walk(base):
            rel = p.relative_to(base)
            if any(rel.as_posix().startswith(s) for s in skip): continue
            why = reason(p, base)
            target = (dest / rel).as_posix()
            if target in legacy.PRIVATE_OPERATIONS: why = 'private-account-operation'
            if why: omissions[why] += 1
            else: result[target] = p
    add_tree(server, Path('kronos-osrs-184'), ('Kronos-master/Cache/', 'Kronos-master/kronos-server/data/', 'asset-audit/'))
    add_tree(mobile, Path('kronos-mobile'))
    add_tree(ROOT / 'windows-client', Path('windows-client'))
    for p in ROOT.iterdir():
        if p.is_file() and p.suffix.lower() in {'.ps1', '.cmd', '.txt'}:
            if p.name in legacy.PRIVATE_OPERATIONS or p.name in legacy.LOCAL_ONLY_LAUNCHERS or p.name in PRIVATE_FILES:
                omissions['private-machine-operation'] += 1
            else: result[p.name] = p
    for p in (ROOT / 'tools').iterdir():
        if p.is_file() and p.suffix.lower() in {'.java', '.ps1', '.py', '.sh', '.cmd'}:
            key=p.relative_to(ROOT).as_posix()
            if key not in legacy.PRIVATE_OPERATIONS and p.name not in PRIVATE_FILES: result[key]=p
    for directory in ('content242', 'hollow-depths', 'hollowdepths'):
        add_tree(ROOT / 'tools' / directory, Path('tools') / directory)
    # Current worktree owns the final integration/build/deployment helpers.
    add_tree(server / 'Kronos-master/tools/content242', Path('tools/content242'))
    for name in ('jdk8-package.json', 'jdk21-package.json', 'intellij-release.json'):
        p=ROOT / 'tools/downloads' / name
        if p.exists(): result['tools/downloads/'+name]=p
    add_tree(stage / 'Cache', Path('kronos-osrs-184/Kronos-master/Cache'))
    add_tree(stage / 'data', Path('kronos-osrs-184/Kronos-master/kronos-server/data'))
    showcase=ROOT / 'incoming/autonomous-adventurers/source/kronos-player-simulation/showcase'
    if showcase.exists(): add_tree(showcase, Path('showcase'))
    for p in (ROOT / 'incoming/content-242').glob('tsps-*/LICENSE'):
        result['third-party/TSPS-BSD-2-Clause-LICENSE.txt']=p
        break
    # Include the public project presentation, excluding machine-bound reports.
    for p in (ROOT / 'content-release').glob('*'):
        if p.is_file() and p.suffix.lower() in {'.html','.png','.txt','.md'}:
            result['content-release/'+p.name]=p
    # The Java 8 server/client has historical repositories. Preserve resolved
    # public artifacts, POM licenses and plugin markers in Maven layout, rather
    # than depending on a developer's opaque Gradle metadata cache.
    depbase=long_path(ROOT/'.gradle-home/caches/modules-2/files-2.1')
    for dep in depbase.glob('*/*/*/*/*'):
        if not dep.is_file() or dep.suffix not in {'.jar','.pom','.module'}: continue
        group,artifact,version,hashdir,name=dep.relative_to(depbase).parts
        target='vendor/maven/'+group.replace('.','/')+'/'+artifact+'/'+version+'/'+name
        if target in result and digest(result[target].read_bytes())!=digest(dep.read_bytes()):
            raise RuntimeError('Conflicting resolved artifact: '+target)
        result[target]=dep
    return sorted(result.items())

def clean(text, relative):
    # Most cache JSON and Java files contain no credential vocabulary. Avoid
    # running assignment expressions over every byte of those large files.
    changes=[]; findings=[]
    if re.search(r'password|passwd|secret|token|api.?key|webhooks/|PRIVATE KEY|EXPONENT|verifyDuplicity|VerifyDuplicity|AKIA|ASIA', text, re.I) or relative.endswith('tokens-sample.json'):
        text, changes, findings = legacy.sanitize(text, relative)
    # Login allowlists are deployer configuration, never account exports.
    if '/data/logins/' in relative and relative.endswith('.json'):
        try:
            obj=json.loads(text)
            if isinstance(obj, dict) and obj.get('allowed_names'):
                obj['allowed_names']=[]
                text=json.dumps(obj,indent=2)+'\n'
                changes.append({'kind':'private-login-allowlist-cleared','count':1})
        except json.JSONDecodeError: pass
    # Proofs from this machine do not authorize a different build/deployment.
    if relative.endswith('/collision-audit.properties'):
        text='# Generate a fresh collision audit for your own exact built server and cache.\n'
        changes.append({'kind':'machine-attestation-removed','count':1})
    if relative.endswith('/revision-184.properties'):
        text=re.sub(r'(?m)^production\.enabled=.*$', 'production.enabled=false', text)
        changes.append({'kind':'local-attestation-disabled','count':1})
    return text, changes, findings

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--server',type=Path,default=ROOT/'local-runtime/content242-worktree')
    p.add_argument('--mobile',type=Path,default=Path('C:/K242-Mobile-QA'))
    p.add_argument('--stage',type=Path,default=ROOT/'local-runtime/content242-playable-final-01')
    p.add_argument('--output',type=Path)
    p.add_argument('--inventory',type=Path,required=True)
    a=p.parse_args()
    if a.output and a.output.exists(): p.error('Output must be new; source and old exports are never overwritten')
    if a.output: a.output.mkdir(parents=True)
    omissions=collections.Counter(); files=[]; redactions=[]; reviews=[]
    for relative,source in entries(long_path(a.server),long_path(a.mobile),long_path(a.stage),omissions):
        if len(files)%2000==0: print('Scanned',len(files),relative,flush=True)
        data=None
        if source.suffix.lower() in legacy.TEXT_SUFFIXES or source.name in {'gradlew','.gitignore','.gitattributes','LICENSE'}:
            data=source.read_bytes()
            try: text=data.decode('utf-8-sig')
            except UnicodeDecodeError: text=None
            if text is not None:
                text,changes,findings=clean(text,relative)
                data=text.encode('utf-8')
                if changes: redactions.append({'path':relative,'changes':changes})
                if findings: reviews.append({'path':relative,'findings':findings})
        if data is None:
            h=hashlib.sha256()
            with source.open('rb') as f:
                for block in iter(lambda:f.read(1024*1024),b''): h.update(block)
            size=source.stat().st_size; hashed=h.hexdigest()
        else: size=len(data); hashed=digest(data)
        files.append({'path':relative,'bytes':size,'sha256':hashed})
        if a.output:
            target=a.output/relative; target.parent.mkdir(parents=True,exist_ok=True)
            if data is None: shutil.copyfile(source,target)
            else: target.write_bytes(data)
    manifest={'format':2,'scope':'complete working source plus required public Cache/data; private runtime state excluded',
              'fileCount':len(files),'totalBytes':sum(f['bytes'] for f in files),'omissions':dict(omissions),
              'sanitizedFiles':redactions,'reviewFindings':reviews,'files':files}
    a.inventory.parent.mkdir(parents=True,exist_ok=True)
    a.inventory.write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
    if a.output:
        (a.output/'publish-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in manifest.items() if k not in {'files','sanitizedFiles','reviewFindings'}},indent=2))
    print('Sanitized files:',len(redactions),'Review files:',len(reviews))

if __name__=='__main__': main()
