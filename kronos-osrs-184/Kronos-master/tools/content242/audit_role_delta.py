"""Read-only exact build delta for inheriting242 QA across a role-guard fix.

This records byte identity and original evidence; it does not relabel prior tests
as fresh execution on the new server. Semantic acceptance belongs in final QA.
"""
import argparse,hashlib,json,zipfile
from pathlib import Path

ALLOWED={'io/ruin/model/entity/player/Player.class','io/ruin/utility/OfflineMode.class'}
def sha(path):
    with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def entries(path):
    with zipfile.ZipFile(path) as z:
        names=z.namelist()
        if len(names)!=len(set(names)):raise ValueError('Duplicate JAR entries')
        return {name:hashlib.sha256(z.read(name)).hexdigest() for name in names if not name.endswith('/')}
def audit(before,after,evidence):
    old_lib={p.name:p for p in before.glob('*.jar')};new_lib={p.name:p for p in after.glob('*.jar')}
    if old_lib.keys()!=new_lib.keys():raise ValueError('Dependency library set changed')
    name='kronos-server-1.0.jar';old,new=entries(old_lib[name]),entries(new_lib[name])
    if old.keys()!=new.keys():raise ValueError('Role-only build added/removed server entries')
    changed={n:{'oldSha256':old[n],'newSha256':new[n]} for n in old if old[n]!=new[n]}
    if not changed or not set(changed)<=ALLOWED:raise ValueError('Build contains changes beyond the reviewed role guard: '+str(sorted(changed)))
    libraries={}
    for library in sorted(old_lib):
        if library==name:continue
        if entries(old_lib[library])!=entries(new_lib[library]):raise ValueError('Shared library content changed: '+library)
        libraries[library]={'oldJarSha256':sha(old_lib[library]),'newJarSha256':sha(new_lib[library]),'entryContentIdentical':True}
    qa=json.loads(evidence.read_text(encoding='utf-8-sig'))
    if qa.get('serverSha256')!=sha(old_lib[name]) or qa.get('result')!='PASS_SCOPED':raise ValueError('QA receipt does not belong to the tested source server')
    return {'schemaVersion':1,'kind':'content242-role-only-qa-inheritance-audit',
            'scope':'Exact byte/dependency delta only; prior QA remains scoped to its original server; final semantic acceptance and role regression are separate evidence',
            'testedServerSha256':sha(old_lib[name]),'targetServerSha256':sha(new_lib[name]),
            'changedEntries':changed,'unchangedServerEntries':len(old)-len(changed),'sharedLibraries':libraries,
            'sourceQaReceiptSha256':sha(evidence),'sourceQaResult':qa['result'],'freshFullQaClaimed':False}
def main():
    p=argparse.ArgumentParser(description=__doc__)
    for key in ('before-lib','after-lib','qa-receipt','output'):p.add_argument('--'+key,type=Path,required=True)
    a=p.parse_args()
    if a.output.exists():raise ValueError('Preserve existing proof; choose a new output')
    report=audit(a.before_lib,a.after_lib,a.qa_receipt)
    a.output.parent.mkdir(parents=True,exist_ok=True)
    with a.output.open('x',encoding='utf-8') as f:json.dump(report,f,indent=2);f.write('\n')
    print(json.dumps({k:v for k,v in report.items() if k not in ('sharedLibraries','changedEntries')},indent=2))
if __name__=='__main__':main()
