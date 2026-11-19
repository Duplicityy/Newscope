from pathlib import Path
import zipfile,shutil,hashlib,json
r=Path('D:/Newscape-117-Performance');s=Path('D:/Newscape-Ultra/source');a=s/'kronos/src/main/assets/jvm'
scopes={
 'owner-graphics.jar':lambda n:n.startswith('rs117/hd/android/AndroidGl') and n.endswith('.class'),
 'owner-plugins.jar':lambda n:(n.startswith('rs117/hd/HdPlugin') and n.endswith('.class')) or n=='Owner117Frames.class'}
receipt={}
for name,belongs in scopes.items():
 changed={p.relative_to(r/'classes').as_posix():p.read_bytes() for p in (r/'classes').rglob('*.class') if belongs(p.relative_to(r/'classes').as_posix())}
 assert changed,name
 target=r/name
 with zipfile.ZipFile(r/'backup'/name) as src,zipfile.ZipFile(target,'w') as dst:
  old=set(src.namelist())
  for entry in src.infolist():dst.writestr(entry,changed.get(entry.filename,src.read(entry)))
  for n in sorted(changed.keys()-old):dst.writestr(n,changed[n],compress_type=zipfile.ZIP_DEFLATED)
 with zipfile.ZipFile(r/'backup'/name) as old,zipfile.ZipFile(target) as new:
  actual={n for n in old.namelist() if old.read(n)!=new.read(n)}
  assert all(belongs(n) for n in actual)
  assert all(old.read(n)==new.read(n) for n in old.namelist() if not belongs(n))
  receipt[name]=sorted(actual)
 shutil.copyfile(target,a/name)
shutil.copyfile(r/'libnewscape117.so',a/'jre/lib/libnewscape117.so')
tag='newscape-117-async-26-'+hashlib.sha256((r/'libnewscape117.so').read_bytes()+(a/'owner-plugins.jar').read_bytes()).hexdigest()[:24]
(a/'runtime.tag').write_text(tag+'\n',encoding='ascii')
(r/'JAR-SCOPE.json').write_text(json.dumps(receipt,indent=2))
print('Patched',sum(len(x) for x in receipt.values()),'owned 117 classes only; all shaders and other JAR entries unchanged')

