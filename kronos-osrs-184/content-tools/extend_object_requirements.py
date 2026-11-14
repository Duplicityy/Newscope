"""Append explicit scripted-object dependencies to a NEW copy of a verified stage.

This bounded extension reuses existing item, NPC, region, sequence and texture
maps. It refuses missing dependencies in those namespaces, never changes source
stage files, and leaves every mechanic disabled pending runtime verification.
"""
import argparse,hashlib,json,shutil
from pathlib import Path

from cache_store import Store,join_files,pack_container
from import_content import Allocator,json_read,save_json,verify_preserved
from legacy_assets import short,write_fields
from modern_definitions import (BANK_FIRST,BANK_FILES,BANK_COUNT,FIRST_MODEL,MAX_BANK_BYTES,
                                read_object,object_models,retextures)
from modern_model import weighted_model184
from bulk_import import load_requirements,canonical


def sha(path):
    with path.open('rb') as stream: return hashlib.file_digest(stream,'sha256').hexdigest()


def extend(source,output,donor_path,original,requirements):
    if output.exists(): raise ValueError('Extension output must be a new directory')
    manifest=json_read(source/'data/content242.json');report=json_read(source/'import-report.json')
    if manifest.get('schemaVersion')!=2 or manifest.get('requiredClientCapability')!='k242-skeletal-v1':
        raise ValueError('Expected an explicitly extended242 stage')
    maps={k:{int(i):v for i,v in table.items()} for k,table in manifest['mappings'].items()}
    if len(set(maps['items'].values()))!=len(maps['items']): raise ValueError('Item aliases must remain one-to-one')
    if (source/'Cache/content242-format.properties').read_text()!='format=k242-skeletal-v1\njs5_revision=184242\n':
        raise ValueError('Unexpected cache capability marker')
    requested=set()
    for feature,groups in requirements.items():
        for kind,ids in groups.items():
            missing=set(ids)-maps[kind].keys()
            if kind=='objects': requested.update(missing)
            elif missing: raise ValueError(f'Full importer required for {feature}/{kind}: {sorted(missing)}')
    receipt={'parentStage':str(source.resolve()),'parentManifestSha256':sha(source/'data/content242.json'),
             'parentReportSha256':sha(source/'import-report.json'),'addedObjects':[],'addedModels':[],
             'scope':'Explicit missing scripted objects and model dependencies; all existing records retained'}
    shutil.copytree(source,output)
    model_map={int(i):v for i,v in report['model_mapping'].items()}
    texture_map={int(i):v for i,v in report['texture_mapping'].items()}
    metadata=json_read(output/'data/content242/object-metadata.json')
    with Store(donor_path) as donor,Store(source/'Cache') as previous,Store(output/'Cache',writable=True) as out:
        objects=dict(out.files(2,6));allocator=Allocator(objects,0,65534)
        banks={};bank_ids=sorted(i for i in out.table(7)[3] if BANK_FIRST<=i<BANK_FIRST+BANK_COUNT)
        bank=bank_ids[-1] if bank_ids else BANK_FIRST
        def texture(i):
            if i not in texture_map: raise ValueError(f'Full importer required for new texture {i}')
            return texture_map[i]
        def model(i):
            nonlocal bank
            if i in model_map: return model_map[i]
            raw=weighted_model184(donor.files(7,i)[0],texture)
            # Only new banks can change, and only by appending new file IDs.
            while bank<BANK_FIRST+BANK_COUNT:
                if bank not in banks:
                    banks[bank]=dict(out.files(7,bank)) if bank in out.table(7)[3] else {}
                files=banks[bank]
                if len(files)<BANK_FILES:
                    file=max(files,default=-1)+1
                    if file<BANK_FILES and len(join_files({**files,file:raw}))<=MAX_BANK_BYTES:
                        files[file]=raw;target=FIRST_MODEL+(bank-BANK_FIRST)*BANK_FILES+file
                        model_map[i]=target;receipt['addedModels'].append({'source':i,'target':target,'sha256':hashlib.sha256(raw).hexdigest()})
                        return target
                bank+=1
            raise ValueError('Model bank capacity exhausted')
        source_objects=donor.files(2,6)
        semantic={}
        for target,raw in objects.items():
            fields,meta=read_object(raw,modern=False)
            if meta['animation']!=-1: fields[24]=short(meta['animation'])
            if not meta['morphs']: semantic.setdefault(canonical(fields,meta['models'],types=meta['modelTypes']),target)
        for source_id in sorted(requested):
            fields,meta=read_object(source_objects[source_id])
            if meta['morphs']: raise ValueError('Full importer required for additional morph closure')
            fields=retextures(fields,texture)
            if meta['animation'] not in (-1,65535):
                if meta['animation'] not in maps['animations']: raise ValueError('Unmapped object animation')
                fields[24]=short(maps['animations'][meta['animation']])
            models=[model(i) for i in meta['models']]
            key=canonical(fields,models,types=meta['modelTypes'])
            target=semantic.get(key)
            if target is None:
                target=allocator.get(source_id)
                fields[250]=object_models(meta,model)
                objects[target]=write_fields(fields);semantic[key]=target
            maps['objects'][source_id]=target;meta['targetId']=target;metadata[str(source_id)]=meta
            receipt['addedObjects'].append({'source':source_id,'target':target,'sha256':hashlib.sha256(objects[target]).hexdigest()})
        for group,files in banks.items():
            if len(pack_container(join_files(files),0))>MAX_BANK_BYTES:
                raise ValueError('Bank exceeds native8MiB compressed-container bound')
            if group in previous.table(7)[3]:
                old=previous.files(7,group)
                if any(files[i]!=raw for i,raw in old.items()): raise ValueError('Existing bank file changed')
            out.put(7,group,files)
        if banks: out.commit_table(7)
        out.put(2,6,objects);out.commit_table(2)
        written_objects=out.files(2,6)
        if any(written_objects[i]!=raw for i,raw in previous.files(2,6).items()): raise ValueError('Existing object changed')
        groups=[g for g in out.table(7)[3] if BANK_FIRST<=g<BANK_FIRST+BANK_COUNT]
        report['model_banks'].update(groups=len(groups),models=sum(len(out.table(7)[3][g].files) for g in groups),
                                    maxUncompressedBytes=max(len(join_files(out.files(7,g))) for g in groups),
                                    maxContainerBytes=max(len(out.read(7,g)) for g in groups))
    report['model_mapping']=model_map
    report['scenery_definitions']=len(maps['objects'])
    report['mechanics_asset_requirements']={feature:{'available':True,'missing':{}} for feature in requirements}
    report.setdefault('extensions',[]).append(receipt)
    manifest['mappings']=maps
    manifest['mechanics']={'enabled':False,'features':{feature:False for feature in requirements}}
    save_json(output/'data/content242.json',manifest)
    save_json(output/'data/content242/object-metadata.json',metadata)
    report['manifestBytes']=(output/'data/content242.json').stat().st_size
    report['preservation']=verify_preserved(original,output/'Cache',{v['id'] for v in report['visual_refreshes']},report['kit_visual_refreshes'])
    save_json(output/'import-report.json',report)
    receipt['resultManifestSha256']=sha(output/'data/content242.json')
    receipt['resultReportSha256']=sha(output/'import-report.json')
    save_json(output/'extension-receipt.json',receipt)
    print(json.dumps(receipt,indent=2))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for option in ('source','output','donor','base'):p.add_argument('--'+option,type=Path,required=True)
    p.add_argument('--requirements',type=Path,action='append',required=True)
    a=p.parse_args();extend(a.source,a.output,a.donor,a.base,load_requirements(a.requirements))


if __name__=='__main__':main()
