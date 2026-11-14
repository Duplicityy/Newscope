"""Copy only clean public desktop components into a fresh242 Windows output."""
import argparse,json,shutil,struct,zipfile
from pathlib import Path
from release_bundle import files,no_private,read,safe_path,sha,write,tree_sha

def integers(data):
    if data[:4]!=b'\xca\xfe\xba\xbe':raise ValueError('Not a Java class')
    total=struct.unpack_from('>H',data,8)[0];offset=10;index=1;values=[]
    while index<total:
        tag=data[offset];offset+=1
        if tag==1:length=struct.unpack_from('>H',data,offset)[0];offset+=2+length
        elif tag in (3,4):
            if tag==3:values.append(struct.unpack_from('>i',data,offset)[0])
            offset+=4
        elif tag in (5,6):offset+=8;index+=1
        elif tag in (7,8,16,19,20):offset+=2
        elif tag in (9,10,11,12,17,18):offset+=4
        elif tag==15:offset+=3
        else:raise ValueError('Unsupported class constant-pool tag')
        index+=1
    return values
def inspect_jars(client,support):
    entry='net/runelite/standalone/Client.class';result={}
    for label,path in [('client',client),('windowsSupport',support)]:
        with zipfile.ZipFile(path) as z:
            names=set(z.namelist())
            if 184242 not in integers(z.read(entry)):raise ValueError('Old cache handshake in '+label+' Client.class')
            if any('Content242ClientSmoke' in n or 'Content242Fixture' in n or 'Content242NativeProbe' in n for n in names):raise ValueError('QA fixture class in public package')
            if label=='windowsSupport' and 'net/runelite/standalone/TaskHandler.class' in names:raise ValueError('QA socket routing must not shadow the public client')
            if label=='client' and not {'net/runelite/standalone/Content242Capabilities.class','net/runelite/standalone/Content242Models.class'}<=names:raise ValueError('Extended model/capability helper missing')
            result[label+'ClassSha256']=__import__('hashlib').sha256(z.read(entry)).hexdigest()
    return result
def main():
    p=argparse.ArgumentParser(description=__doc__)
    for key in ('baseline','client','support','support-receipt','output'):p.add_argument('--'+key,type=Path,required=True)
    a=p.parse_args();output=safe_path(a.output)
    if output.exists():raise ValueError('Choose a fresh isolated output directory')
    baseline=safe_path(a.baseline);client=safe_path(a.client);support=safe_path(a.support)
    receipt=read(a.support_receipt)
    if receipt.get('capability')!='k242-skeletal-v1' or receipt.get('clientJarSha256','').lower()!=sha(client) or receipt.get('supportSha256','').lower()!=sha(support):raise ValueError('Support overlay receipt does not bind the exact final client/support pair')
    native=inspect_jars(client,support)
    runtime_entries=files(baseline/'runtime');license_entries=files(baseline/'licenses')
    no_private(runtime_entries);no_private(license_entries)
    for name in ('Play Kronos.cmd','READ ME.txt'):
        if not (baseline/name).is_file():raise ValueError('Missing public launcher/documentation')
        safe_path(baseline/name)
    target=output/'Kronos-Windows';target.mkdir(parents=True);(target/'app').mkdir()
    for name in ('runtime','licenses'):shutil.copytree(baseline/name,target/name)
    for name in ('Play Kronos.cmd','READ ME.txt'):shutil.copy2(baseline/name,target/name)
    shutil.copy2(client,target/'app/client.jar');shutil.copy2(support,target/'app/windows-support.jar')
    note=(target/'READ ME.txt').read_text(encoding='utf-8-sig')
    note=note.replace('Approximately 300 MB free space plus downloaded game cache.','Allow space for the included runtime and the downloaded game cache.')
    note=note.replace('RuneLite-based revision-184 client','RuneLite-based revision-184 client with the242 asset capability')
    note+='\nRELEASE COMPATIBILITY\nThis client requires the matching content242 server update. Its cache handshake is184242; the game protocol and desktop controls remain184. Distribute it together with the matching server transition.\n'
    (target/'READ ME.txt').write_text(note,encoding='utf-8')
    inventory=files(target);no_private(inventory)
    if files(target/'runtime')!=runtime_entries or files(target/'licenses')!=license_entries:raise ValueError('Public runtime/licenses copy changed')
    if sha(target/'Play Kronos.cmd')!=sha(baseline/'Play Kronos.cmd'):raise ValueError('Existing launcher/profile/connection behavior changed')
    if sha(target/'app/client.jar')!=sha(client) or sha(target/'app/windows-support.jar')!=sha(support):raise ValueError('Client copy changed')
    shutil.copy2(a.support_receipt,output/'windows-support-build-receipt.json')
    result={'schemaVersion':1,'status':'prepared-not-deployed','capability':'k242-skeletal-v1','cacheHandshake':184242,'gameProtocol':184,
            'clientSha256':sha(client),'supportSha256':sha(support),'supportBuildReceiptSha256':sha(a.support_receipt),**native,
            'runtimeAndLicensesByteIdentical':True,'launcherByteIdentical':True,'userProfileModified':False,
            'qaFixturesIncluded':False,'privateStateIncluded':False,'distributionTreeSha256':tree_sha(inventory),'files':inventory}
    write(output/'windows-package-receipt.json',result)
    print(json.dumps({k:v for k,v in result.items() if k!='files'},indent=2))
if __name__=='__main__':main()
