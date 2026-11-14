"""Build a new stage repairing normal charged-weapon actions and exact equipment metadata."""
import argparse,hashlib,json,shutil
from pathlib import Path
from cache_store import Store
from import_content import json_read,save_json
from legacy_assets import read_item,write_fields
from modern_gear_metadata import CHARGED,actions,metadata


def digest(path):
    with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for arg in ('source','output','donor','donor-source'):p.add_argument('--'+arg,type=Path,required=True)
    a=p.parse_args()
    if a.output.exists():raise ValueError('Choose a new staging output')
    manifest=json_read(a.source/'data/content242.json');report=json_read(a.source/'import-report.json')
    mapping={int(k):v for k,v in manifest['mappings']['items'].items()}
    if len(set(mapping.values()))!=len(mapping):raise ValueError('Item map must be one-to-one')
    receipt={'schemaVersion':1,'kind':'charged-weapon-native-action-repair',
             'parentManifestSha256':digest(a.source/'data/content242.json'),
             'parentReportSha256':digest(a.source/'import-report.json'),'items':[]}
    rows=json_read(a.source/'data/items/item_info.json');types=json_read(a.source/'data/items/weapon_types.json')
    by_id={r['id']:r for r in rows}
    gameplay=json_read(a.donor_source/'server/data/definitions/item-gameplay.json')
    if isinstance(gameplay,list):gameplay={r['id']:r for r in gameplay}
    shutil.copytree(a.source,a.output)
    with Store(a.donor) as donor,Store(a.source/'Cache') as baseline,Store(a.output/'Cache',writable=True) as out:
        source_items=donor.files(2,10);old_items=baseline.files(2,10);items=dict(old_items)
        for source in CHARGED:
            target=mapping[source];meta={};fields=read_item(source_items[source],meta)
            converted=read_item(items[target])
            before=items[target]
            for op in range(35,40):converted.pop(op,None)
            converted.update(actions(source,fields));items[target]=write_fields(converted)
            # All model/name/param data must remain exact after action repair.
            old_spans=[];new_spans=[];read_item(before,spans=old_spans);read_item(items[target],spans=new_spans)
            kept=lambda values:{op:raw for op,raw in values if not 35<=op<=39}
            if kept(old_spans)!=kept(new_spans):raise ValueError('Nonaction cache data changed')
            row,profiles,summary=metadata(source,target,fields,meta,gameplay.get(source,gameplay.get(str(source),{})),types)
            by_id[target].update(row);types.update(profiles)
            old_summary=next(r for r in report['equipment'] if r['donor_id']==source)
            old_summary.update(summary)
            receipt['items'].append({'source':source,'target':target,'oldDefinitionSha256':hashlib.sha256(before).hexdigest(),
                'newDefinitionSha256':hashlib.sha256(items[target]).hexdigest(),
                'actions':{str(op-35):raw[:-1].decode('cp1252') for op,raw in actions(source,fields).items()},'equipment':row})
        if any(items[i]!=raw for i,raw in old_items.items() if i not in {mapping[s] for s in CHARGED}):raise ValueError('Unrelated item changed')
        out.put(2,10,items);out.commit_table(2)
    save_json(a.output/'data/items/item_info.json',rows);save_json(a.output/'data/items/weapon_types.json',types)
    report.setdefault('extensions',[]).append(receipt)
    save_json(a.output/'import-report.json',report)
    receipt['resultReportSha256']=digest(a.output/'import-report.json')
    save_json(a.output/'charged-actions-receipt.json',receipt)
    print(json.dumps({'output':str(a.output),'items':receipt['items']},indent=2))


if __name__=='__main__':main()
