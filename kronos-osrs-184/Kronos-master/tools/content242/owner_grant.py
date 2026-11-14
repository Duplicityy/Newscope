"""Prepare a curated242 owner bank kit; stopped-deployment application is opt-in.

No credentials are copied into a payload/report. All original account fields and
bank records are preserved. A private write-ahead journal resolves interrupted
atomic replacement by exact before/after hashes; unknown outcomes never regrant.
Use Apply-OwnerGrant242.ps1 for deployment guards and restricted receipt ACLs.
"""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import sys

_script_folder=Path(__file__).resolve().parent
_helpers=next((folder for folder in (_script_folder/'content-tools',_script_folder,
              Path(__file__).resolve().parents[3]/'content-tools')
              if all((folder/name).is_file() for name in ('cache_store.py','legacy_assets.py','gear_metadata.py'))),None)
if _helpers is None:raise ImportError('Verified content-tools helpers are missing')
sys.path.insert(0,str(_helpers))
from cache_store import Store
from legacy_assets import read_item
from gear_metadata import load_json

BANK_SIZE = 800
MAX_INT = 2147483647
GRANT_ID = 'content242-owner-supported-kit-v1'
REQUIRED_FEATURES = ['fang', 'voidwaker', 'shadow', 'bowfa', 'crystal_armour',
                     'soulreaper', 'virtus', 'inquisitor', 'ancient_sceptre',
                     'burning_claws', 'scorching_bow']
# Only canonical playable variants. No noted/broken/league-preview definitions,
# quest tools, armour-set boxes, or unsupported powered/crossbow/passive weapons.
MECHANICS = [26219,27246,27690,27275,25865,25867,28338,33335,27624,29577,29591,
             24419,24420,24421,26241,26243,26245,23971,23975,23979]
BASE_GEAR = [24417,24288,24291,24294,26382,26384,26386,28254,28256,28258,
             27226,27229,27232,27235,27238,27241,25985,27251,27253,26235,
             28307,28310,28313,28316,29801,29804,29806,28933,28936,28939,
             30073,30076,30079,30082,30750,30753,30756,30777,30779,30781,
             31088,31091,31092,31093,31094,31095,31096,31097,31106]
CHARGED = {27275,25865,23971,23975,23979}
SUPPLIES = {566:100000,562:250000,560:100000,565:100000,555:250000,556:100000,
            554:100000,557:100000,563:25000,561:25000,564:25000,9075:25000,
            21880:25000,23962:5000,11212:50000,21326:50000,892:50000,995:50000000,
            385:2000,13441:2000,391:2000,12695:2000,2434:1000,3024:2000,
            6685:2000,2444:1000,3040:1000,12625:1000,21978:1000,12913:1000}

def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('ascii')

def sha(data):
    return hashlib.sha256(data).hexdigest()

def decode(data):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('Duplicate JSON field')
            result[key] = value
        return result
    return json.loads(data.decode('utf-8-sig'), object_pairs_hook=unique)

def read(path):
    return decode(Path(path).read_bytes())

def text_field(fields, opcode):
    return fields.get(opcode, b'').rstrip(b'\0').decode('cp1252')

def marker(cache):
    values = {}
    for line in (cache / 'content242-format.properties').read_text(encoding='utf-8-sig').splitlines():
        if '=' in line and not line.lstrip().startswith(('#','!')):
            key, value = line.split('=',1); values[key.strip()] = value.strip()
    if values.get('format') != 'k242-skeletal-v1' or values.get('js5_revision') != '184242':
        raise ValueError('The grant requires the extended242 cache capability')

def stage_bindings(stage):
    stage = Path(stage); marker(stage / 'Cache')
    manifest = read(stage / 'data/content242.json')
    if manifest.get('sourceRevision') != 242 or manifest.get('clientProtocol') != 184:
        raise ValueError('Incorrect content version/protocol')
    mapping = manifest['mappings']['items']
    rows = load_json(stage / 'data/items/item_info.json')
    info = {row['id']:row for row in rows}
    return manifest, mapping, info

def account_identity(registry, account, require_owner=False):
    if not re.fullmatch('[a-z0-9_]{3,12}',account):
        raise ValueError('This grant tool requires an unescaped canonical account name')
    # Read only the two role/identity fields; never emit registry contents or credentials.
    found={}
    for line in Path(registry).read_text(encoding='ascii').splitlines():
        match=re.fullmatch(r'\s*'+re.escape(account)+r'\.(id|owner)\s*[=:]\s*(.*?)\s*',line)
        if match:
            if match[1] in found:raise ValueError('Duplicate account identity property')
            found[match[1]]=match[2]
    ident=int(found.get('id','0'))
    if ident<1000 or ident>MAX_INT:raise ValueError('Account has no stable registry ID')
    if require_owner and found.get('owner','').lower()!='true':raise ValueError('Deployment account is not explicitly owner')
    return ident

def prepare(stage, account, account_id):
    if type(account_id) is not int or account_id<1000:raise ValueError('Stable account ID required')
    manifest, mapping, info = stage_bindings(stage)
    rows = []
    sources = MECHANICS + BASE_GEAR + list(SUPPLIES)
    if len(sources) != len(set(sources)):
        raise ValueError('Duplicate grant source')
    with Store(Path(stage) / 'Cache') as store:
        definitions = store.files(2,10)
        for source in sources:
            target = mapping.get(str(source))
            if not isinstance(target,int) or not 0 <= target <= 65023 or target not in definitions:
                raise ValueError('Missing verified mapped item '+str(source))
            metadata = {}; fields = read_item(definitions[target],metadata)
            name = text_field(fields,2)
            if not name or name.lower() == 'null' or 98 in fields or 149 in fields:
                raise ValueError('Note/placeholder/unnamed item in grant '+str(source))
            equipment = source not in SUPPLIES
            options = [text_field(fields,i).lower() for i in range(35,40)]
            item_info = info.get(target,{})
            if equipment and (item_info.get('equip_slot',-1) < 0 or not {'wield','wear','equip'} & set(options)):
                raise ValueError('Equipment cannot be ordinarily equipped '+str(source))
            if equipment and item_info.get('equip_slot') == 3 and not item_info.get('weapon_type'):
                raise ValueError('Weapon has no native profile '+str(source))
            attributes = {'content242-charges':'20000'} if source in CHARGED else {}
            rows.append({'sourceId':source,'id':target,'name':name,'amount':SUPPLIES.get(source,1),
                         'attributes':attributes,'category':'implemented-mechanics' if source in MECHANICS else 'base-stats-equipment' if equipment else 'supplies',
                         'rawDefinitionSha256':sha(definitions[target]),'itemInfoSha256':sha(canonical(item_info))})
    return {'schemaVersion':1,'grantId':GRANT_ID,'account':account.lower(),'accountId':account_id,
            'sourceRevision':242,'clientProtocol':184,'requiredFeatures':REQUIRED_FEATURES,
            'itemMappingsSha256':sha(canonical(mapping)), 'items':rows,
            'limitations':['Base-stats equipment promises only native equip/stats/appearance; no unimplemented special/passive effects are granted.',
                'Unsupported Nightmare/Sanguinesti/Zaryte/Venator weapons, moon-set effects, quiver ammo mechanics and cosmetic variants without implemented effects are omitted.',
                'No levels, progression, quests, existing equipment/inventory, passwords or account roles are changed by this grant.',
                'All remaining catalogue assets stay accessible through staff tools; this is not a dump of every raw cache item.']}

def validate_payload(payload, stage, require_enabled=False):
    expected = prepare(stage,payload['account'],payload['accountId'])
    if payload != expected:
        raise ValueError('Payload differs from the verified curated stage bindings')
    if require_enabled:
        manifest, _, _ = stage_bindings(stage)
        mechanics = manifest.get('mechanics',{})
        if not manifest.get('enabled') or not mechanics.get('enabled') or not all(mechanics.get('features',{}).get(key) is True for key in REQUIRED_FEATURES):
            raise ValueError('Required native242 mechanics are not enabled in deployment data')

def compose(original, payload):
    before = decode(original)
    after = copy.deepcopy(before)
    bank = after.get('bank')
    if not isinstance(bank,dict) or not isinstance(bank.get('items'),list) or len(bank['items']) != BANK_SIZE:
        raise ValueError('Expected existing native800-slot bank; no account schema will be invented')
    slots = bank['items']; new_slots = 0; merges = 0
    for item in slots:
        if item is not None and (not isinstance(item,dict) or type(item.get('amount')) is not int or not 0 <= item['amount'] <= MAX_INT):
            raise ValueError('Invalid existing bank record')
    for row in payload['items']:
        amount, ident, attributes = row['amount'],row['id'],row['attributes']
        if type(amount) is not int or not 0 < amount <= MAX_INT or not isinstance(attributes,dict):
            raise ValueError('Invalid grant item')
        match = next((i for i in slots if i is not None and i.get('id')==ident and not i.get('attributes') and i['amount']<=MAX_INT-amount),None) if not attributes else None
        if match is not None:
            match['amount'] += amount; merges += 1
        else:
            try: slot = slots.index(None)
            except ValueError: raise ValueError('Insufficient free bank slots; no partial grant')
            slots[slot] = {'id':ident,'amount':amount,'attributes':copy.deepcopy(attributes),'tab':0}; new_slots += 1
    # Prove all unrelated fields, occupied slots, tabs and existing attributes survived.
    unchanged = copy.deepcopy(after); unchanged['bank']['items'] = copy.deepcopy(before['bank']['items'])
    if unchanged != before:
        raise ValueError('Unexpected account mutation')
    for index, item in enumerate(before['bank']['items']):
        if item is None: continue
        actual = copy.deepcopy(slots[index]); actual['amount'] = item['amount']
        if actual != item or slots[index]['amount'] < item['amount']:
            raise ValueError('Existing bank item was replaced or reduced')
    data = json.dumps(after,indent=2,ensure_ascii=True).encode('utf-8')+b'\n'
    return data, {'grantRows':len(payload['items']),'newSlots':new_slots,'mergedStacks':merges,
                  'occupiedBefore':sum(i is not None for i in before['bank']['items']),
                  'occupiedAfter':sum(i is not None for i in slots),'freeAfter':slots.count(None)}

def preserve_acl(source, target):
    """Give replacement bytes the existing file's Windows DACL before writing them."""
    if os.name != 'nt' or not source.exists():
        return
    import ctypes
    from ctypes import wintypes
    security=ctypes.WinDLL('advapi32',use_last_error=True)
    get=security.GetFileSecurityW;get.argtypes=[wintypes.LPCWSTR,wintypes.DWORD,ctypes.c_void_p,wintypes.DWORD,ctypes.POINTER(wintypes.DWORD)];get.restype=wintypes.BOOL
    put=security.SetFileSecurityW;put.argtypes=[wintypes.LPCWSTR,wintypes.DWORD,ctypes.c_void_p];put.restype=wintypes.BOOL
    control=security.GetSecurityDescriptorControl;control.argtypes=[ctypes.c_void_p,ctypes.POINTER(wintypes.WORD),ctypes.POINTER(wintypes.DWORD)];control.restype=wintypes.BOOL
    size=wintypes.DWORD();get(str(source),4,None,0,ctypes.byref(size))
    if not size.value:raise ctypes.WinError(ctypes.get_last_error())
    descriptor=ctypes.create_string_buffer(size.value)
    if not get(str(source),4,descriptor,size.value,ctypes.byref(size)):raise ctypes.WinError(ctypes.get_last_error())
    bits=wintypes.WORD();revision=wintypes.DWORD()
    if not control(descriptor,ctypes.byref(bits),ctypes.byref(revision)):raise ctypes.WinError(ctypes.get_last_error())
    flags=4 | (0x80000000 if bits.value & 0x1000 else 0x20000000)
    if not put(str(target),flags,descriptor):raise ctypes.WinError(ctypes.get_last_error())

def atomic(path, data):
    path = Path(path); temporary = path.with_name(path.name+'.grant-tmp')
    # A leftover temp is diagnostic state, not permission to overwrite it.
    with temporary.open('xb') as out:
        preserve_acl(path,temporary)
        out.write(data);out.flush();os.fsync(out.fileno())
    os.replace(temporary,path)

def transaction(player, payload, receipt_folder, fault=None):
    player = Path(player).resolve(); receipt_folder = Path(receipt_folder).resolve()
    if player.stem.lower() != payload['account'] or player.suffix.lower() != '.json':
        raise ValueError('Grant account does not match player save')
    receipt_folder.mkdir(parents=True,exist_ok=True)
    journal = receipt_folder / 'grant-journal.json'
    lock = receipt_folder / 'grant.lock'
    with lock.open('xb'):
        pass
    try:
        before = player.read_bytes(); digest=sha(canonical(payload))
        if journal.exists():
            saved = read(journal)
            if saved['payloadSha256'] != digest or saved['player'] != str(player) or saved['grantId'] != payload['grantId'] or saved.get('accountId')!=payload['accountId']:
                raise ValueError('Grant journal belongs to another payload/account')
            if saved['status'] not in ('prepared','committed','rollback-prepared','rolled-back'):
                raise ValueError('Unknown grant journal state')
            if saved['status'] == 'committed':
                return {**saved['summary'],'status':'already-committed'}
            if sha(before) == saved['afterSha256']:
                saved['status']='committed';atomic(journal,canonical(saved));return {**saved['summary'],'status':'recovered-committed'}
            if sha(before) != saved['beforeSha256']:
                raise ValueError('Uncertain prior grant outcome; refuse to regrant')
        after, summary = compose(before,payload)
        if journal.exists():
            if sha(after) != saved['afterSha256']:
                raise ValueError('Prepared grant no longer reproduces exactly')
        else:
            saved={'schemaVersion':1,'grantId':payload['grantId'],'accountId':payload['accountId'],'payloadSha256':digest,'player':str(player),
                   'status':'prepared','beforeSha256':sha(before),'afterSha256':sha(after),'summary':summary}
            # Caller has already restricted ACLs; retain exact original for operator rollback.
            with (receipt_folder/'player-before.json').open('xb') as backup:
                backup.write(before);backup.flush();os.fsync(backup.fileno())
            if (receipt_folder/'player-before.json').read_bytes() != before:
                raise ValueError('Private backup verification failed')
            atomic(journal,canonical(saved))
        if fault: fault('prepared')
        if player.read_bytes() != before:
            raise ValueError('Player changed during stopped-world grant')
        atomic(player,after)
        if fault: fault('player-written')
        if player.read_bytes() != after:
            raise ValueError('Player replacement could not be confirmed')
        saved['status']='committed';atomic(journal,canonical(saved))
        return {**summary,'status':'committed'}
    finally:
        lock.unlink()

def rollback_state(player, payload, receipt_folder):
    """Read-only verification. Only two exact player images are eligible for rollback."""
    player=Path(player).resolve();receipt_folder=Path(receipt_folder).resolve()
    if player.stem.lower()!=payload['account'] or player.suffix.lower()!='.json':
        raise ValueError('Rollback account does not match player save')
    saved=read(receipt_folder/'grant-journal.json')
    if saved.get('payloadSha256')!=sha(canonical(payload)) or saved.get('player')!=str(player) or saved.get('grantId')!=payload['grantId'] or saved.get('accountId')!=payload['accountId']:
        raise ValueError('Rollback journal does not match exact payload/account')
    if saved.get('status') not in ('prepared','committed','rollback-prepared','rolled-back'):
        raise ValueError('Unknown rollback journal state')
    original=(receipt_folder/'player-before.json').read_bytes()
    if sha(original)!=saved.get('beforeSha256'):
        raise ValueError('Rollback before-image hash differs')
    after,summary=compose(original,payload)
    if sha(after)!=saved.get('afterSha256') or summary!=saved.get('summary'):
        raise ValueError('Rollback granted image cannot be reproduced exactly')
    current=player.read_bytes();current_sha=sha(current)
    if current_sha==saved['beforeSha256']:status='already-before'
    elif current_sha==saved['afterSha256']:status='safe-after'
    else:raise ValueError('Player progressed or changed after grant; keep242 selected and stopped, do not restore an old account image')
    return saved,original,current,status

def rollback(player,payload,receipt_folder,check_only=False,fault=None):
    player=Path(player).resolve();receipt_folder=Path(receipt_folder).resolve();lock=receipt_folder/'grant.lock'
    if check_only:
        if lock.exists():raise ValueError('Grant transaction lock exists')
        _,_,current,status=rollback_state(player,payload,receipt_folder)
        if lock.exists() or player.read_bytes()!=current:raise ValueError('Rollback state changed during check')
        return {'status':status,'checkOnly':True,'accountRestored':False}
    with lock.open('xb'):pass
    try:
        saved,original,current,status=rollback_state(player,payload,receipt_folder)
        journal=receipt_folder/'grant-journal.json'
        if status=='already-before':
            if saved['status']!='rolled-back':saved['status']='rolled-back';atomic(journal,canonical(saved))
            return {'status':'already-before','accountRestored':False}
        saved['status']='rollback-prepared';atomic(journal,canonical(saved))
        if fault:fault('rollback-prepared')
        if player.read_bytes()!=current:raise ValueError('Player changed during stopped rollback')
        atomic(player,original)
        if fault:fault('rollback-player-written')
        if player.read_bytes()!=original:raise ValueError('Rollback account restoration could not be confirmed')
        saved['status']='rolled-back';atomic(journal,canonical(saved))
        return {'status':'restored-before','accountRestored':True}
    finally:lock.unlink()

def main():
    parser=argparse.ArgumentParser(description=__doc__);commands=parser.add_subparsers(dest='command',required=True)
    prepare_parser=commands.add_parser('prepare');prepare_parser.add_argument('--stage',type=Path,required=True);prepare_parser.add_argument('--account',required=True);prepare_parser.add_argument('--registry',type=Path,required=True);prepare_parser.add_argument('--output',type=Path,required=True)
    for command in ('preflight','apply-stopped','check-rollback-stopped','rollback-stopped'):
        p=commands.add_parser(command);p.add_argument('--stage',type=Path,required=True);p.add_argument('--payload',type=Path,required=True);p.add_argument('--player',type=Path,required=True)
        if command!='preflight':p.add_argument('--receipt-folder',type=Path,required=True);p.add_argument('--registry',type=Path,required=True)
    args=parser.parse_args()
    if args.command=='prepare':
        account=args.account.lower();payload=prepare(args.stage,account,account_identity(args.registry,account));args.output.parent.mkdir(parents=True,exist_ok=True)
        with args.output.open('xb') as out:out.write(json.dumps(payload,indent=2).encode('utf-8')+b'\n')
        print(json.dumps({'status':'prepared-payload','rows':len(payload['items']),'chargedItems':len(CHARGED)}));return
    payload=read(args.payload);validate_payload(payload,args.stage,require_enabled=args.command=='apply-stopped')
    if args.player.stem.lower()!=payload['account']:raise ValueError('Wrong account save')
    if args.command=='preflight':
        _,summary=compose(args.player.read_bytes(),payload);print(json.dumps({'status':'read-only-preflight',**summary}));return
    # Actual use must come through the guard/ACL wrapper; detached tests call transaction directly.
    if os.environ.get('KRONOS_OWNER_GRANT_STOPPED_GATE')!='242':raise ValueError('Use the stopped-deployment PowerShell wrapper')
    if account_identity(args.registry,payload['account'],True)!=payload['accountId']:raise ValueError('Account identity changed')
    expected=args.player.resolve().parent.parent/'content242-owner-grants'/payload['account']/payload['grantId']
    if args.receipt_folder.resolve()!=expected:raise ValueError('Use the canonical account-bound receipt location')
    if args.command=='apply-stopped':print(json.dumps(transaction(args.player,payload,args.receipt_folder)))
    else:print(json.dumps(rollback(args.player,payload,args.receipt_folder,args.command=='check-rollback-stopped')))

if __name__=='__main__':
    try:main()
    except Exception as error:
        print(type(error).__name__+': '+str(error),file=sys.stderr);sys.exit(1)
