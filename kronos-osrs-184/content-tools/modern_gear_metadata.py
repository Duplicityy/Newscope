"""Exact metadata/actions for the charged weapons implemented by ModernCharges."""
from copy import deepcopy
from gear_metadata import _requirements,_bonuses,_lookup

CHARGED = {27275:'shadow',27277:'shadow',25865:'bowfa',25862:'bowfa',25867:'bowfa'}
ALLOWED = {27275:{'wield','check','uncharge'},27277:{'wield'},
           25865:{'wield','check'},25862:{'wield'},25867:{'wield'}}


def charged_catalogue(equipment):
    """Catalogue appearance/basic stats separately from the required mechanic."""
    return [{'id':e['target_id'],'name':e['name'],'status':'basic-equipment',
             'mechanic':CHARGED[e['donor_id']]}
            for e in equipment if e.get('donor_id') in CHARGED and
            e['status']=='implemented-charged-equipment']


def actions(source,fields):
    """Keep only genuine source slots backed by installed handlers; invent none."""
    return {op:raw for op,raw in fields.items() if 35<=op<=39 and
            raw[:-1].decode('cp1252').lower() in ALLOWED[source]}


def metadata(source,target,fields,meta,gameplay,types):
    if source not in CHARGED or meta.get(13)!=3:raise ValueError('Unexpected charged weapon slot')
    notes=[];params=meta.get('params',{})
    requirements,origin=_requirements(params,gameplay,notes)
    bonuses,magic=_bonuses(params,notes)
    family=CHARGED[source];base='TRIDENT' if family=='shadow' else 'CRYSTAL_BOW'
    profile=deepcopy(types[base]);profile.update(attackTicks=5,maxDistance=8 if family=='shadow' else 10)
    key='CONTENT242_CHARGED_'+family.upper()
    if key in types and types[key]!=profile:raise ValueError('Charged weapon profile collision')
    row={'equip_slot':3,'two_handed':True,'weapon_type':key,**requirements,**bonuses}
    if family=='bowfa':row['ranged_weapon']='CRYSTAL_BOW'
    if 5 not in {meta.get(14),meta.get(27)}:raise ValueError('Expected authoritative two-handed metadata')
    if not actions(source,fields):raise ValueError('Source has no supported charged weapon actions')
    return row,{key:profile},{'donor_id':source,'target_id':target,'status':'implemented-charged-equipment',
            'requirements_source':origin,'bonuses_source':'cache params','source_magic_damage_percent':magic,
            'notes':['Equipping, charge checks and configured rune/shard use require the matching enabled native mechanic.',*notes],
            'remove_inventory_options':[op for op in range(35,40) if op in fields and op not in actions(source,fields)]}
