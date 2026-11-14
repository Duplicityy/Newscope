"""Append missing metadata for six verified existing crystal armour definitions.

Creates a fresh stage; cache bytes and all pre-existing metadata are unchanged.
"""
import argparse,hashlib,json,shutil
from pathlib import Path
from cache_store import Store
from import_content import json_read,save_json,name
from legacy_assets import read_item
from gear_metadata import build_metadata
from modern_gear_metadata import CHARGED,charged_catalogue

IDS=(23971,23973,23975,23977,23979,23981)
def digest(path):
    with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()

def complete_catalogue(source,output):
    """Complete this data-only extension; also supports an unfinished new copy."""
    manifest=json_read(output/'data/content242.json');report=json_read(output/'import-report.json')
    receipt=json_read(output/'crystal-metadata-receipt.json')
    entries=charged_catalogue(report['equipment'])
    expected={manifest['mappings']['items'][str(i)] for i in CHARGED}
    if len(entries)!=5 or {e['id'] for e in entries}!=expected:raise ValueError('Incomplete charged equipment catalogue')
    if any(e['id'] in expected for e in manifest['equipment']):raise ValueError('Catalogue already contains charged equipment')
    manifest['equipment'].extend(entries)
    save_json(output/'data/content242.json',manifest)
    delta={'kind':'implemented-charged-equipment-catalogue','cacheChanged':False,
           'entries':entries,'parentManifestSha256':digest(source/'data/content242.json'),
           'resultManifestSha256':digest(output/'data/content242.json')}
    report.setdefault('extensions',[]).append(delta)
    report['manifestBytes']=(output/'data/content242.json').stat().st_size
    save_json(output/'import-report.json',report)
    receipt['catalogue']=delta;receipt['resultReportSha256']=digest(output/'import-report.json')
    receipt['dataFileChanges']={str(p.relative_to(output)).replace('\\','/'):{'beforeSha256':digest(source/p.relative_to(output)),'afterSha256':digest(p)}
                              for p in (output/'data').rglob('*') if p.is_file() and digest(p)!=digest(source/p.relative_to(output))}
    if set(receipt['dataFileChanges'])!={'data/items/item_info.json','data/content242.json'}:raise ValueError('Unexpected data change')
    save_json(output/'crystal-metadata-receipt.json',receipt)
    return entries
def main():
    p=argparse.ArgumentParser(description=__doc__)
    for key in ('source','output','donor','donor-source'):p.add_argument('--'+key,type=Path,required=True)
    a=p.parse_args()
    if a.output.exists():raise ValueError('Choose a new stage')
    m=json_read(a.source/'data/content242.json');report=json_read(a.source/'import-report.json')
    info=json_read(a.source/'data/items/item_info.json');existing={r['id'] for r in info}
    mapping={i:m['mappings']['items'][str(i)] for i in IDS}
    if any(i!=target or target in existing for i,target in mapping.items()):raise ValueError('Only missing verified identity metadata may be appended')
    fields,metadata={},{}
    with Store(a.donor) as donor,Store(a.source/'Cache') as source:
        donor_items=donor.files(2,10);target_items=source.files(2,10)
        for i in IDS:
            metadata[i]={};fields[i]=read_item(donor_items[i],metadata[i]);target=read_item(target_items[i])
            if name(target)!=name(fields[i]) or target.get(36)!=b'Wear\0':raise ValueError('Crystal identity/Wear action mismatch')
    result=build_metadata(mapping,fields,metadata,json_read(a.source/'data/items/weapon_types.json'),
        gameplay=json_read(a.donor_source/'server/data/definitions/item-gameplay.json'),
        weapon_stats=json_read(a.donor_source/'server/data/definitions/weapon-stats.json'))
    if result['weapon_types']:raise ValueError('Armour must not introduce weapon types')
    for row in result['item_info']:
        if row['equip_slot']!={23971:0,23973:0,23975:4,23977:4,23979:7,23981:7}[row['id']]:raise ValueError('Unexpected crystal slot')
    shutil.copytree(a.source,a.output)
    save_json(a.output/'data/items/item_info.json',info+result['item_info'])
    report['equipment'].extend(result['report'])
    receipt={'schemaVersion':1,'kind':'missing-original-crystal-equipment-metadata','parentReportSha256':digest(a.source/'import-report.json'),
             'cacheChanged':False,'existingInventoryActionsChanged':False,'rows':result['item_info']}
    report.setdefault('extensions',[]).append(receipt)
    save_json(a.output/'import-report.json',report)
    # Full cache file hashes, not merely table hashes, independently bind the data-only delta.
    cache_hashes={p.name:digest(p) for p in (a.source/'Cache').iterdir() if p.is_file()}
    if cache_hashes!={p.name:digest(p) for p in (a.output/'Cache').iterdir() if p.is_file()}:raise ValueError('Cache file changed in metadata-only extension')
    receipt['cacheFileSha256']=cache_hashes;receipt['resultReportSha256']=digest(a.output/'import-report.json')
    save_json(a.output/'crystal-metadata-receipt.json',receipt)
    complete_catalogue(a.source,a.output)
    print(json.dumps({'output':str(a.output),'appendedEquipmentRows':len(result['item_info']),'cacheFilesIdentical':len(cache_hashes),'slots':{r['id']:r['equip_slot'] for r in result['item_info']}},indent=2))
if __name__=='__main__':main()
