"""Create/verify an immutable local deployment bundle. Never operates a live server.

The final QA review is an explicit evidence review, not an automatically invented
test result. Its exact artifact bindings and referenced files are checked here.
"""
import argparse,hashlib,io,json,os,re,shutil,struct,subprocess,sys,tempfile,zipfile
from pathlib import Path

CAPABILITY='k242-skeletal-v1'
TREE_INPUTS={'game':'gameLib','updater':'updaterLib','windows':'windowsDirectory'}
DATA_ROOTS={'content242','hollow-depths','items','logins','npcs','objects','shops',
            'content242.json','content242-world.json','help_eco.json','help_pvp.json',
            'region_keys.json','slayer_tasks.json','teleports_eco.json','teleports_pvp.json'}
PRIVATE_NAMES={'accounts.properties','accounts.json','server.properties','network.json',
               'player-data','players','profiles','signing.properties','key.properties',
               'keystore.properties','local.properties','id_rsa','id_ed25519'}
CHECKS={'cacheProtocol','nativeGear','bossEncounters','windowsControls','androidPackaging',
        'homePreserved','normalInventoryActions','originalCachePreserved','worldAccess'}
EVIDENCE_ROLES={'foundation','cacheProtocol','nativeGear','bossEncounters','desktopSmoke','windowsBuild',
                'androidPackaging','protectedAttestation','sharedDependencyReview'}

def read(path):return json.loads(Path(path).read_text(encoding='utf-8-sig'))
def write(path,value):Path(path).write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8')
def sha(path):
    with Path(path).open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
def relative(value):
    path=Path(value)
    if path.is_absolute() or not path.parts or any(p in ('.','..') for p in path.parts) or ':' in value or '\\' in value:
        raise ValueError('Expected a safe slash-separated relative file path')
    return path
def safe_path(path):
    original=Path(path).absolute();path=Path(os.path.abspath(path))
    for item in (original,*original.parents,path,*path.parents):
        if item.exists() and (item.is_symlink() or getattr(item.stat(),'st_file_attributes',0)&0x400):
            raise ValueError('Junctions and symbolic links are not permitted: '+str(item))
    return path
def files(root):
    root=safe_path(root)
    if not root.is_dir():raise ValueError('Missing artifact directory: '+str(root))
    result=[];seen=set()
    for p in sorted(root.rglob('*')):
        safe_path(p)
        if not p.is_file():continue
        name=p.relative_to(root).as_posix()
        if name.lower() in seen:raise ValueError('Case-insensitive duplicate artifact path')
        seen.add(name.lower());result.append({'path':name,'bytes':p.stat().st_size,'sha256':sha(p)})
    if not result:raise ValueError('Empty artifact directory: '+str(root))
    return result
def tree_sha(entries):
    return hashlib.sha256(json.dumps(entries,sort_keys=True,separators=(',',':')).encode()).hexdigest()
def no_private(entries):
    for e in entries:
        parts=[p.lower() for p in relative(e['path']).parts]
        if any(p in PRIVATE_NAMES for p in parts) or Path(parts[-1]).suffix in {'.jks','.keystore','.p12','.pfx','.pem','.key'}:
            raise ValueError('Private state/signing material is not a release input: '+e['path'])
def class_integer_constants(data):
    if data[:4]!=b'\xca\xfe\xba\xbe':raise ValueError('Invalid packaged Java class')
    count=struct.unpack_from('>H',data,8)[0];offset=10;index=1;values=[]
    while index<count:
        tag=data[offset];offset+=1
        if tag==1:offset+=2+struct.unpack_from('>H',data,offset)[0]
        elif tag in (3,4):
            if tag==3:values.append(struct.unpack_from('>i',data,offset)[0])
            offset+=4
        elif tag in (5,6):offset+=8;index+=1
        elif tag in (7,8,16,19,20):offset+=2
        elif tag in (9,10,11,12,17,18):offset+=4
        elif tag==15:offset+=3
        else:raise ValueError('Unknown Java constant-pool tag')
        index+=1
    return values
def check_android_overlay(apk):
    entry=apk.getinfo('assets/jvm/bridge.jar')
    if entry.file_size>16*1024*1024:raise ValueError('Unexpectedly large Android overlay')
    with zipfile.ZipFile(io.BytesIO(apk.read(entry))) as bridge:
        if 184242 not in class_integer_constants(bridge.read('net/runelite/standalone/Client.class')):
            raise ValueError('Android overlay shadows the extended client with an old cache handshake')
def bindings(trees,apk,client,windows_support,manifest):
    return {'serverJarSha256':next(e['sha256'] for e in trees['game'] if e['path']=='kronos-server-1.0.jar'),
            'gameTreeSha256':tree_sha(trees['game']),'updaterTreeSha256':tree_sha(trees['updater']),
            'windowsTreeSha256':tree_sha(trees['windows']),'cacheTreeSha256':tree_sha(trees['Cache']),
            'dataTreeSha256':tree_sha(trees['data']),'clientJarSha256':sha(client),
            'windowsSupportSha256':sha(windows_support),'androidApkSha256':sha(apk),
            'contentManifestSha256':sha(manifest)}
def owner_grant(spec):
    grant=spec.get('ownerGrant')
    if grant is None:return None,{}
    workspace=safe_path(spec['workspace']);payload=safe_path(grant['payload']);helper=safe_path(grant['helper']);wrapper=safe_path(grant['wrapper'])
    for key,path in [('payload',payload),('helper',helper),('wrapper',wrapper)]:
        if sha(path)!=grant.get(key+'Sha256'):raise ValueError('Owner grant input changed: '+key)
    parsed=read(payload)
    if parsed.get('account')!='owner' or parsed.get('grantId')!='content242-owner-supported-kit-v1':raise ValueError('This release authorizes only the reviewed owner grant')
    player=safe_path(grant['playerSave']);registry=safe_path(grant['accountRegistry'])
    if player!=workspace/'local-runtime/player-data/players/owner.json' or registry!=workspace/'local-runtime/accounts.properties':raise ValueError('Owner grant private target differs from the authorized account')
    if not player.is_file() or not registry.is_file():raise ValueError('Owner grant requires existing private account/player files')
    parsers=helper.parents[3]/'content-tools'
    sources={'owner-grant/payload.json':payload,'scripts/owner_grant.py':helper,'scripts/Apply-OwnerGrant242.ps1':wrapper}
    for name in ('cache_store.py','legacy_assets.py','gear_metadata.py'):sources['scripts/content-tools/'+name]=safe_path(parsers/name)
    receipt={'playerSave':str(player),'accountRegistry':str(registry),'files':{name:sha(path) for name,path in sources.items()}}
    return receipt,sources
def inspect(spec):
    paths={k:safe_path(spec[v]) for k,v in TREE_INPUTS.items()}
    stage=safe_path(spec['stage']);paths.update(Cache=stage/'Cache',data=stage/'data')
    trees={k:files(v) for k,v in paths.items()}
    for k in ('game','updater','windows'):no_private(trees[k])
    if any(relative(e['path']).parts[0] not in DATA_ROOTS for e in trees['data']):raise ValueError('Unapproved staged data root; private state is never packaged')
    no_private(trees['data'])
    marker=(paths['Cache']/'content242-format.properties').read_text()
    if 'format='+CAPABILITY not in marker or 'js5_revision=184242' not in marker:raise ValueError('Cache capability marker missing')
    client=paths['windows']/relative(spec.get('windowsClient','app/client.jar'))
    support=paths['windows']/relative(spec.get('windowsSupport','app/windows-support.jar'))
    apk=safe_path(spec['androidApk'])
    with zipfile.ZipFile(apk) as z:
        names=z.namelist()
        if len(names)!=len(set(names)):raise ValueError('Duplicate APK entries')
        no_private([{'path':n} for n in names if not n.endswith('/')])
        entry=spec.get('androidClientEntry','assets/jvm/client.jar')
        relative(entry)
        with z.open(entry) as stream:embedded=hashlib.file_digest(stream,'sha256').hexdigest()
        if embedded!=sha(client):raise ValueError('Android and Windows must embed the identical extended game client')
        check_android_overlay(z)
    manifest=read(paths['data']/'content242.json')
    if manifest.get('requiredClientCapability')!=CAPABILITY or manifest.get('clientProtocol')!=184:raise ValueError('Unsupported content manifest')
    bound=bindings(trees,apk,client,support,paths['data']/'content242.json')
    grant,_=owner_grant(spec)
    if grant is not None:bound['ownerGrantSha256']=hashlib.sha256(json.dumps(grant,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    return paths,trees,bound,manifest
def check_review(review,bound,content):
    if review.get('schemaVersion')!=1 or review.get('scope')!='content242-final-release' or review.get('approved') is not True:
        raise ValueError('A final explicit QA review is required; diagnostic stages are not releasable')
    if review.get('bindings')!=bound:raise ValueError('QA artifact bindings are stale or incomplete')
    if set(review.get('checks',{}))!=CHECKS or any(v is not True for v in review['checks'].values()):raise ValueError('Final QA checks are incomplete')
    if not isinstance(review.get('androidPhysicalTested'),bool):raise ValueError('State handset testing honestly')
    active={k for k,v in content.get('mechanics',{}).get('features',{}).items() if v}
    if active and content.get('mechanics',{}).get('enabled') is not True:raise ValueError('Ambiguous mechanic feature gates')
    tested=review.get('testedMechanics',{})
    if any(tested.get(k) is not True for k in active):raise ValueError('An enabled mechanic has no final evidence review')
    roles={e.get('role') for e in review.get('evidence',[])}
    if not EVIDENCE_ROLES<=roles:raise ValueError('Missing required evidence role')
    if 'ownerGrantSha256' in bound and 'ownerGrant' not in roles:raise ValueError('Owner grant requires its reviewed preparation/native evidence')
    for evidence in review['evidence']:
        if not re.fullmatch('[A-Za-z][A-Za-z0-9_-]*',evidence.get('role','')):raise ValueError('Invalid evidence role')
        path=safe_path(evidence['path'])
        if any(part.lower() in PRIVATE_NAMES for part in path.parts) or path.suffix.lower() in {'.jks','.keystore','.p12','.pfx','.pem','.key'}:
            raise ValueError('Private state/signing material cannot be included as QA evidence')
        if not path.is_file() or sha(path)!=evidence.get('sha256'):raise ValueError('Missing/changed evidence: '+str(path))
        if evidence['role']=='windowsBuild':
            build=read(path)
            if build.get('clientJarSha256','').lower()!=bound['clientJarSha256'] or build.get('supportSha256','').lower()!=bound['windowsSupportSha256']:
                raise ValueError('Windows support overlay was not built against the final client')
    if not review.get('limitations'):raise ValueError('Explicit supported limits are required')
def check_attestation(spec,bound,stage,review):
    receipt=read(spec['protectedReceipt'])
    if receipt.get('derived') is not True or receipt.get('targetJarSha256')!=bound['serverJarSha256']:
        raise ValueError('Missing exact inherited protected attestation')
    for key,name in [('derivedProfileSha256','revision-184.properties'),('derivedProofSha256','collision-audit.properties')]:
        if sha(stage/'data/hollow-depths'/name)!=receipt.get(key):raise ValueError('Staged protected attestation does not match derived receipt')
    if not receipt.get('protectedEntryHashes') or not receipt.get('originalCachePreservation'):raise ValueError('Incomplete inherited proof receipt')
    # Do not trust a receipt merely because it contains expected hashes. Re-run
    # the READ-ONLY byte/cache comparison and exact shared-entry review, producing
    # a temporary derived profile/proof. This never executes protected gameplay.
    reviews=[e for e in review['evidence'] if e['role']=='sharedDependencyReview']
    if len(reviews)!=1:raise ValueError('Exactly one current shared dependency review is required')
    with tempfile.TemporaryDirectory(prefix='content242-attestation-') as temp:
        output=Path(temp)/'derived'
        command=[sys.executable,'-B',str(Path(__file__).parent/'protected_attestation.py')]
        values={'baseline-lib':spec['baselineLib'],'target-lib':spec['gameLib'],
                'baseline-cache':spec['baselineCache'],'target-stage':str(stage),
                'baseline-profile-dir':spec['baselineProfileDir'],'import-report':spec['importReport'],
                'review':reviews[0]['path'],'output':str(output)}
        for key,value in values.items():command.extend(['--'+key,str(value)])
        result=subprocess.run(command+['--derive'],capture_output=True,text=True)
        if result.returncode:raise ValueError('Read-only attestation re-audit failed: '+result.stderr[-2000:])
        if read(output/'attestation-receipt.json')!=receipt:raise ValueError('Inherited receipt differs from the independent exact re-audit')
    return receipt
def prepare(spec,output):
    output=safe_path(output)
    if output.exists():raise ValueError('Choose a new immutable bundle directory')
    if spec.get('schemaVersion')!=1 or not re.fullmatch('[a-z0-9-]+',spec.get('releaseName','')):raise ValueError('Invalid release spec')
    paths,trees,bound,content=inspect(spec);review=read(spec['qaReview']);check_review(review,bound,content)
    receipt=check_attestation(spec,bound,Path(spec['stage']),review)
    grant,grant_sources=owner_grant(spec)
    if grant is not None:
        result=subprocess.run([sys.executable,'-B',str(grant_sources['scripts/owner_grant.py']),'preflight',
                               '--stage',spec['stage'],'--payload',str(grant_sources['owner-grant/payload.json']),
                               '--player',grant['playerSave']],capture_output=True,text=True)
        if result.returncode:raise ValueError('Read-only owner grant preflight failed: '+result.stderr[-2000:])
    baseline={'Cache':files(spec['baselineCache']),'data':files(spec['baselineData'])}
    workspace=safe_path(spec['workspace']);project=workspace/'kronos-osrs-184/Kronos-master'
    baseline_binaries={name:{'path':str(safe_path(spec[key])),'files':files(spec[key])}
                       for name,key in [('game','baselineLib'),('updater','baselineUpdaterLib')]}
    baseline_binaries['launcher']={'path':str(workspace/'Start-Kronos.ps1'),'sha256':sha(workspace/'Start-Kronos.ps1')}
    if safe_path(spec['baselineCache'])!=project/'Cache' or safe_path(spec['baselineData'])!=project/'kronos-server/data':raise ValueError('Baseline must be the configured original project Cache/data')
    try:output.relative_to(workspace/'local-runtime/content242-releases')
    except ValueError:raise ValueError('Bundle must be inside local-runtime/content242-releases')
    output.mkdir(parents=True)
    for key,path in paths.items():shutil.copytree(path,output/key)
    (output/'android').mkdir();shutil.copy2(spec['androidApk'],output/'android/Kronos-242.apk')
    evidence_dir=output/'evidence';evidence_dir.mkdir();evidence=[]
    for number,e in enumerate(review['evidence']):
        dest=evidence_dir/(str(number).zfill(2)+'-'+e['role']+Path(e['path']).suffix)
        shutil.copy2(e['path'],dest);evidence.append({**e,'path':dest.relative_to(output).as_posix()})
    shutil.copy2(spec['protectedReceipt'],evidence_dir/'protected-attestation.json')
    review={**review,'evidence':evidence};write(evidence_dir/'final-qa-review.json',review)
    scripts=output/'scripts';scripts.mkdir();here=Path(__file__).parent
    for name in ('release_bundle.py','Full-Content242.ps1','Start-FullContent242.ps1','Content242Shutdown.java'):
        shutil.copy2(here/name,scripts/name)
    shutil.copy2(here/'FULL_RELEASE.md',output/'README.md')
    shutil.copy2(workspace/'tools/RestartKronos.java',scripts/'RestartKronos.java')
    for name,path in grant_sources.items():
        dest=output/relative(name);dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,dest)
    manifest={'schemaVersion':1,'sealed':True,'releaseName':spec['releaseName'],'workspace':str(workspace),
              'bundle':str(output),'capability':CAPABILITY,'gameProtocol':184,'cacheRevision':184242,
              'defaultGameHeapMb':5120,'updaterHeapMb':512,'bindings':bound,'trees':trees,
              'baseline':baseline,'baselineRelease':(workspace/'local-runtime/game-server-release.txt').read_text().strip(),
              'baselineBinaries':baseline_binaries,
              'androidPhysicalTested':review['androidPhysicalTested'],'limitations':review['limitations'],
              'ownerGrant':grant,
              'inheritedProtectedProof':{'receiptSha256':sha(evidence_dir/'protected-attestation.json'),'scope':receipt['scope']},
              'files':files(output)}
    write(output/'release-manifest.json',manifest)
    (output/'release-manifest.sha256').write_text(sha(output/'release-manifest.json')+'\n',encoding='ascii')
    verify(output)
    return {'bundle':str(output),'manifestSha256':sha(output/'release-manifest.json'),'sealed':True,'applied':False}
def verify(bundle):
    bundle=safe_path(bundle);manifest=read(bundle/'release-manifest.json')
    if manifest.get('schemaVersion')!=1 or manifest.get('sealed') is not True or manifest.get('bundle')!=str(bundle):raise ValueError('Unsealed or relocated bundle')
    if sha(bundle/'release-manifest.json')!=(bundle/'release-manifest.sha256').read_text().strip():raise ValueError('Manifest hash mismatch')
    actual=[e for e in files(bundle) if e['path'] not in {'release-manifest.json','release-manifest.sha256'}]
    if actual!=manifest['files']:raise ValueError('Bundle file contents or file set changed')
    for key,entries in manifest['trees'].items():
        if files(bundle/key)!=entries:raise ValueError('Artifact tree changed: '+key)
    if sha(bundle/'android/Kronos-242.apk')!=manifest['bindings']['androidApkSha256']:raise ValueError('Android APK binding changed')
    grant=manifest.get('ownerGrant')
    if grant is not None:
        if any(sha(bundle/relative(name))!=digest for name,digest in grant['files'].items()):raise ValueError('Owner grant payload/helper changed')
        if hashlib.sha256(json.dumps(grant,sort_keys=True,separators=(',',':')).encode()).hexdigest()!=manifest['bindings'].get('ownerGrantSha256'):raise ValueError('Owner grant approval binding differs')
    review=read(bundle/'evidence/final-qa-review.json')
    review['evidence']=[{**e,'path':str(bundle/relative(e['path']))} for e in review['evidence']]
    check_review(review,manifest['bindings'],read(bundle/'data/content242.json'))
    return {'valid':True,'releaseName':manifest['releaseName'],'manifestSha256':sha(bundle/'release-manifest.json')}
def main():
    parser=argparse.ArgumentParser(description=__doc__);sub=parser.add_subparsers(dest='command',required=True)
    for command in ('inspect','prepare'):
        p=sub.add_parser(command);p.add_argument('--spec',type=Path,required=True)
        if command=='prepare':p.add_argument('--output',type=Path,required=True)
    p=sub.add_parser('verify');p.add_argument('--bundle',type=Path,required=True)
    a=parser.parse_args()
    if a.command=='verify':result=verify(a.bundle)
    elif a.command=='inspect':result=inspect(read(a.spec))[2]
    else:result=prepare(read(a.spec),a.output)
    print(json.dumps(result,indent=2))
if __name__=='__main__':main()
