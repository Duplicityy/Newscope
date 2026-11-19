from pathlib import Path
import zipfile,shutil,hashlib,json
r=Path('D:/Newscape-117-Direct');s=Path('D:/Newscape-Ultra/source');a=s/'kronos/src/main/assets/jvm'
scopes={
 'owner-graphics.jar':lambda n:n.startswith('rs117/hd/android/AndroidGl') and n.endswith('.class'),
 'owner-plugins.jar':lambda n:((n.startswith('rs117/hd/HdPlugin') or n.startswith('net/runelite/standalone/AndroidRenderClock')) and n.endswith('.class'))}
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
  receipt[name]=sorted(actual|set(new.namelist())-set(old.namelist()))
 shutil.copyfile(target,a/name)
with zipfile.ZipFile(r/'backup/bridge.jar') as old,zipfile.ZipFile(r/'bridge.jar','w') as new:
 for i in old.infolist():new.writestr(i,(r/'GameShell-after.class').read_bytes() if i.filename=='net/runelite/standalone/GameShell.class' else old.read(i))
with zipfile.ZipFile(r/'backup/bridge.jar') as old,zipfile.ZipFile(r/'bridge.jar') as new:
 assert [n for n in old.namelist() if old.read(n)!=new.read(n)]==['net/runelite/standalone/GameShell.class']
shutil.copyfile(r/'bridge.jar',a/'bridge.jar')
shutil.copyfile(r/'libnewscape117.so',a/'jre/lib/libnewscape117.so')
receipt['bridge.jar']=['net/runelite/standalone/GameShell.class']
tag='newscape-117-direct-27-'+hashlib.sha256(b''.join((a/n).read_bytes() for n in ['owner-graphics.jar','owner-plugins.jar','bridge.jar','jre/lib/libnewscape117.so'])).hexdigest()[:24]
(a/'runtime.tag').write_text(tag+'\n',encoding='ascii')
(r/'JAR-SCOPE.json').write_text(json.dumps(receipt,indent=2))
print(json.dumps(receipt,indent=2))
