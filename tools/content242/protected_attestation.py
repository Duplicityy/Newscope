"""Audit or derive staged protected-feature attestation without running its gameplay.

Derivation requires an exact-hash review of EVERY changed pre-existing class or
resource, including changed shared dependency entries. Protected modules and
map/collision primitives must remain byte-identical. Original proof/production files are read-only.
"""
import argparse,hashlib,json,re,struct,sys,zipfile
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[3]/'content-tools'))
from cache_store import Store
from import_content import json_read,verify_preserved

PROTECTED_PREFIXES=('com/newscape/dungeons/','io/ruin/model/activities/hollowdepths/')
PROTECTED_CLASSES={
    'io/ruin/model/map/dynamic/DynamicMap.class','io/ruin/model/map/dynamic/DynamicRegion.class',
    'io/ruin/model/map/Tile.class','io/ruin/model/map/ClipUtils.class',
    'io/ruin/model/map/route/RouteFinder.class','io/ruin/model/map/route/routes/ProjectileRoute.class',
    'io/ruin/network/PacketSender.class','io/ruin/model/entity/player/PlayerNPCUpdater.class',
    'io/ruin/utility/AtomicPlayerSave.class'}


def sha(data):return hashlib.sha256(data).hexdigest()
def file_sha(path):
    with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def properties(path):
    result={}
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        if line.strip() and not line.lstrip().startswith(('#','!')):
            k,v=line.split('=',1)
            if k.strip() in result:raise ValueError('Duplicate attestation property')
            result[k.strip()]=v.strip()
    return result
def archive(path):
    with zipfile.ZipFile(path) as z:
        names=z.namelist()
        if len(names)!=len(set(names)):raise ValueError('Duplicate jar entries')
        return {n:sha(z.read(n)) for n in names if not n.endswith('/')}
def cache_hash(path):
    digest=hashlib.sha256()
    with Store(path) as c:
        # Match the actual HollowDepthsAssets cacheSha256 implementation.
        index=c.index(255);index.seek(0,2);count=index.tell()//6
        for i in range(count):
            try:raw=c.read(255,i)
            except KeyError:continue
            digest.update(struct.pack('>II',i,len(raw)));digest.update(raw)
    return digest.hexdigest()
def patch_properties(raw,changes,extras=None):
    text=raw.decode('utf-8-sig')
    for key,value in changes.items():
        text,count=re.subn(r'(?m)^'+re.escape(key)+r'=[^\r\n]*',key+'='+value,text)
        if count!=1:raise ValueError('Ambiguous attestation field '+key)
    if extras:
        text+='\n# Explicit inherited proof; no new protected-feature execution claimed.\n'
        for key,value in extras.items():text+=key+'='+value+'\n'
    return text.encode('utf-8')


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('baseline-lib','target-lib','baseline-cache','target-stage','baseline-profile-dir','output'):
        p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--review',type=Path);p.add_argument('--derive',action='store_true')
    p.add_argument('--import-report',type=Path,help='Explicit asset lineage report when a playable stage retains only Cache/data')
    a=p.parse_args()
    if a.output.exists():raise ValueError('Choose a new audit/derivation output directory')
    baseline=a.baseline_lib/'kronos-server-1.0.jar';target=a.target_lib/'kronos-server-1.0.jar'
    old_hash,new_hash=file_sha(baseline),file_sha(target)
    profile_path=a.baseline_profile_dir/'revision-184.properties';proof_path=a.baseline_profile_dir/'collision-audit.properties'
    profile,proof=properties(profile_path),properties(proof_path)
    old_cache=cache_hash(a.baseline_cache);new_cache=cache_hash(a.target_stage/'Cache')
    for values in (profile,proof):
        if values.get('server.build-id')!=old_hash or values.get('cache.sha256')!=old_cache:
            raise ValueError('The supplied baseline proof/profile is not bound to the actual baseline jar/cache')
    if proof.get('audit.version')!='2' or proof.get('maps.passed')!='true' or proof.get('client.source-archives.passed')!='true':
        raise ValueError('Baseline has no successful retained map/source-archive proof')
    old,new=archive(baseline),archive(target)
    removed=sorted(old.keys()-new.keys())
    if removed:raise ValueError('Pre-existing server entries removed: '+str(removed))
    protected=sorted(n for n in old if n.startswith(PROTECTED_PREFIXES) or 'adventurer' in n.lower() or n in PROTECTED_CLASSES)
    if not PROTECTED_CLASSES <= old.keys():raise ValueError('Missing protected baseline primitive')
    if any(old[n]!=new[n] for n in protected):raise ValueError('Protected module/primitive bytes changed')
    changed={n:{'oldSha256':old[n],'newSha256':new[n]} for n in sorted(old) if old[n]!=new[n]}
    # ZIP timestamps can change after a build. Compare each dependency's content.
    dependencies={}
    old_lib={x.name:x for x in a.baseline_lib.glob('*.jar') if x.name!=baseline.name}
    new_lib={x.name:x for x in a.target_lib.glob('*.jar') if x.name!=target.name}
    if old_lib.keys()!=new_lib.keys():raise ValueError('Shared dependency set changed; additional review policy required')
    for name,path in sorted(old_lib.items()):
        left,right=archive(path),archive(new_lib[name])
        if left.keys()!=right.keys():raise ValueError('Shared dependency entry set changed: '+name)
        for entry in left:
            if left[entry]!=right[entry]:changed['lib/'+name+'!/'+entry]={'oldSha256':left[entry],'newSha256':right[entry]}
        dependencies[name]={'baselineJarSha256':file_sha(path),'targetJarSha256':file_sha(new_lib[name]),'entryContentIdentical':left==right}
    report_path=a.import_report or a.target_stage/'import-report.json'
    report=json_read(report_path)
    preserved=verify_preserved(a.baseline_cache,a.target_stage/'Cache',{v['id'] for v in report['visual_refreshes']},report['kit_visual_refreshes'])
    # Source keys are private runtime bindings: compare without emitting their values.
    baseline_keys=json_read(a.baseline_profile_dir.parent/'region_keys.json')
    target_keys=json_read(a.target_stage/'data/region_keys.json')
    source_regions={int(re.match(r'source\.region\.(\d+)\.',k).group(1)) for k in profile if k.startswith('source.region.')}
    if any(baseline_keys.get(str(i))!=target_keys.get(str(i)) for i in source_regions):raise ValueError('Protected source XTEA binding changed')
    receipt={'schemaVersion':1,'kind':'content242-protected-attestation','scope':'Inherited original map/collision proof; no new protected gameplay/map/lifecycle execution',
             'baselineJarSha256':old_hash,'targetJarSha256':new_hash,'baselineCacheSha256':old_cache,'targetCacheSha256':new_cache,
             'baselineProfileSha256':file_sha(profile_path),'baselineProofSha256':file_sha(proof_path),
             'importReportSha256':file_sha(report_path),
             'protectedEntryHashes':{n:old[n] for n in protected},'sharedDependencyLibraries':dependencies,
             'changedExistingEntries':changed,'addedEntries':sorted(new.keys()-old.keys()),
             'originalCachePreservation':preserved,'protectedSourceRegionKeysUnchanged':sorted(source_regions),
             'baselineTemplateProofs':{k:v for k,v in proof.items() if k.startswith('template.')},'derived':False}
    a.output.mkdir(parents=True)
    if a.derive:
        if a.review is None:raise ValueError('Derivation requires exact shared-dependency review receipt')
        review=json_read(a.review)
        if review.get('baselineJarSha256')!=old_hash or review.get('targetJarSha256')!=new_hash:
            raise ValueError('Shared-dependency review belongs to different jars')
        entries=review.get('reviewedEntries',{})
        if set(entries)!=set(changed):raise ValueError('Every changed pre-existing entry must be reviewed, with no stale extras')
        for name,hashes in changed.items():
            entry=entries[name]
            if any(entry.get(k)!=v for k,v in hashes.items()) or entry.get('acceptedForInheritedMapProof') is not True or not entry.get('reason','').strip():
                raise ValueError('Incomplete exact shared-dependency review: '+name)
        changes={'server.build-id':new_hash,'cache.sha256':new_cache}
        extras={'audit.content242.parent-build-id':old_hash,'audit.content242.parent-cache-sha256':old_cache,
                'audit.content242.parent-proof-sha256':file_sha(proof_path),
                'audit.content242.scope':'Inherited original source maps and collision corpus; exact protected-module and original-cache bytes; shared242 class changes reviewed separately; no fresh protected tests'}
        (a.output/'revision-184.properties').write_bytes(patch_properties(profile_path.read_bytes(),changes))
        (a.output/'collision-audit.properties').write_bytes(patch_properties(proof_path.read_bytes(),changes,extras))
        receipt.update(derived=True,sharedReviewSha256=file_sha(a.review),
                       derivedProfileSha256=file_sha(a.output/'revision-184.properties'),derivedProofSha256=file_sha(a.output/'collision-audit.properties'))
    (a.output/'attestation-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps({'protectedEntriesIdentical':len(protected),'changedExistingEntriesRequiringReview':len(changed),
                      'cachePreservation':preserved,'derived':receipt['derived'],'output':str(a.output)},indent=2))


if __name__=='__main__':main()
