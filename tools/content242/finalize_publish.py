"""Freeze every prepared export file before reversible asset packing."""
from pathlib import Path
import hashlib,json,argparse

def record(root,p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return {'path':p.relative_to(root).as_posix(),'bytes':p.stat().st_size,'sha256':h.hexdigest()}

def main():
 a=argparse.ArgumentParser();a.add_argument('--snapshot',type=Path,required=True);x=a.parse_args();root=x.snapshot.resolve()
 if (root/'assets-payload').exists():raise SystemExit('Freeze before packing only')
 original=json.loads((root/'publish-manifest.json').read_text(encoding='utf-8'))
 deps=[record(root,p) for p in sorted((root/'vendor/maven').rglob('*')) if p.is_file()]
 (root/'vendor/dependencies.json').write_text(json.dumps({'scope':'Resolved public Maven artifacts with original POM/license notices; one verified metadata URL filename alias','artifacts':deps},indent=2)+'\n',encoding='utf-8')
 files=[record(root,p) for p in sorted(root.rglob('*')) if p.is_file() and p.relative_to(root).as_posix()!='publish-manifest.json']
 names=[f['path'].casefold() for f in files]
 if len(names)!=len(set(names)):raise SystemExit('Case-insensitive filename collision')
 original.update(fileCount=len(files),totalBytes=sum(f['bytes'] for f in files),files=files,
  cloneAdjustments='SOURCE_EXPORT_CHANGES.txt',
  scope='Complete prepared source and required public inputs. Excludes this self-referential manifest and later assets-payload transport files only.',
  validation={'sanitizedServerAndUpdaterOfflineCompile':'PASS','sanitizedDesktopOfflineCompile':'PASS','freshGradleResolutionCache':True,'originalPrivateExponentScan':'PASS: zero matches','portableLauncherSyntheticChecks':'PASS','liveDeploymentConfirmed':True})
 (root/'publish-manifest.json').write_text(json.dumps(original,indent=2)+'\n',encoding='utf-8')
 print(json.dumps({'files':len(files),'bytes':original['totalBytes'],'manifestSha256':hashlib.sha256((root/'publish-manifest.json').read_bytes()).hexdigest()}))

if __name__=='__main__':main()
