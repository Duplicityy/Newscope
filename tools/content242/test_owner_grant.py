import copy
import json
from pathlib import Path
import tempfile
import unittest

import owner_grant as grant


class OwnerGrantTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='owner-grant-fixture-')
        self.folder=Path(self.temp.name);self.player=self.folder/'players'/'fixture.json';self.player.parent.mkdir()
        self.journal=self.folder/'receipts'
        self.payload={'account':'fixture','accountId':1001,'grantId':grant.GRANT_ID,'items':[
            {'id':562,'amount':500,'attributes':{}},
            {'id':27275,'amount':1,'attributes':{'content242-charges':'20000'}},
            {'id':26382,'amount':1,'attributes':{}}]}
        self.source={'untouched':{'fixtureCredential':'synthetic-not-an-account-secret'},'stats':[42,52],
                     'inventory':{'items':[None]*28},'bank':{'custom':'preserve','items':[None]*800}}
        self.source['bank']['items'][0]={'id':562,'amount':123,'attributes':{},'tab':4}
        self.source['bank']['items'][2]={'id':27275,'amount':1,'attributes':{'content242-charges':'7'},'tab':7}
        self.before=json.dumps(self.source).encode();self.player.write_bytes(self.before)

    def tearDown(self):self.temp.cleanup()

    def test_preserve_and_native_shapes(self):
        encoded,summary=grant.compose(self.before,self.payload);result=grant.decode(encoded)
        self.assertEqual(summary['newSlots'],2);self.assertEqual(summary['mergedStacks'],1)
        self.assertEqual(result['bank']['items'][0],{'id':562,'amount':623,'attributes':{},'tab':4})
        self.assertEqual(result['bank']['items'][2],self.source['bank']['items'][2])
        self.assertEqual(result['untouched'],self.source['untouched']);self.assertEqual(result['stats'],[42,52])
        self.assertEqual(result['inventory'],self.source['inventory']);self.assertEqual(self.player.read_bytes(),self.before)

    def test_full_bank_rejects_without_partial_mutation(self):
        full=copy.deepcopy(self.source);full['bank']['items']=[{'id':999,'amount':1,'attributes':{},'tab':0} for _ in range(800)]
        before=grant.canonical(full)
        with self.assertRaisesRegex(ValueError,'Insufficient'):grant.compose(before,self.payload)
        self.assertEqual(grant.canonical(full),before)

    def test_idempotent_after_play_progresses(self):
        first=grant.transaction(self.player,self.payload,self.journal);self.assertEqual(first['status'],'committed')
        current=grant.read(self.player);current['stats'][0]=99;self.player.write_bytes(grant.canonical(current));progressed=self.player.read_bytes()
        self.assertEqual(grant.transaction(self.player,self.payload,self.journal)['status'],'already-committed')
        self.assertEqual(self.player.read_bytes(),progressed)
        self.assertEqual((self.journal/'player-before.json').read_bytes(),self.before)

    def interrupt(self,where):
        def fault(point):
            if point==where:raise RuntimeError('synthetic interruption')
        with self.assertRaisesRegex(RuntimeError,'synthetic'):grant.transaction(self.player,self.payload,self.journal,fault)

    def test_crash_after_prepare_replays_once(self):
        self.interrupt('prepared');self.assertEqual(self.player.read_bytes(),self.before)
        self.assertEqual(grant.transaction(self.player,self.payload,self.journal)['status'],'committed')
        self.assertEqual(grant.read(self.player)['bank']['items'][0]['amount'],623)

    def test_crash_after_player_write_recovers_without_regrant(self):
        self.interrupt('player-written');after=self.player.read_bytes()
        self.assertEqual(grant.transaction(self.player,self.payload,self.journal)['status'],'recovered-committed')
        self.assertEqual(self.player.read_bytes(),after)

    def test_uncertain_save_change_fails_closed(self):
        self.interrupt('prepared');changed=copy.deepcopy(self.source);changed['stats'][0]=81;self.player.write_bytes(grant.canonical(changed));before=self.player.read_bytes()
        with self.assertRaisesRegex(ValueError,'Uncertain'):grant.transaction(self.player,self.payload,self.journal)
        self.assertEqual(self.player.read_bytes(),before)

    def test_receipt_rejects_other_payload_or_identity(self):
        grant.transaction(self.player,self.payload,self.journal)
        for field,value in [('accountId',1002),('grantId','different')]:
            changed=copy.deepcopy(self.payload);changed[field]=value
            with self.assertRaisesRegex(ValueError,'another'):grant.transaction(self.player,changed,self.journal)

    def test_overflow_never_wraps_existing_stack(self):
        original=copy.deepcopy(self.source);original['bank']['items'][0]['amount']=grant.MAX_INT
        result,summary=grant.compose(grant.canonical(original),self.payload)
        self.assertEqual(grant.decode(result)['bank']['items'][0]['amount'],grant.MAX_INT)
        self.assertEqual(summary['newSlots'],3)

    def test_duplicate_json_field_rejected(self):
        with self.assertRaisesRegex(ValueError,'Duplicate'):grant.decode(b'{"bank":{},"bank":{}}')

    def test_concurrent_or_unclean_lock_fails_closed(self):
        self.journal.mkdir();(self.journal/'grant.lock').write_bytes(b'')
        with self.assertRaises(FileExistsError):grant.transaction(self.player,self.payload,self.journal)
        self.assertEqual(self.player.read_bytes(),self.before)

    def test_rollback_check_is_read_only_and_restore_is_exact(self):
        grant.transaction(self.player,self.payload,self.journal)
        after=self.player.read_bytes();journal=(self.journal/'grant-journal.json').read_bytes()
        self.assertEqual(grant.rollback(self.player,self.payload,self.journal,True)['status'],'safe-after')
        self.assertEqual(self.player.read_bytes(),after);self.assertEqual((self.journal/'grant-journal.json').read_bytes(),journal)
        self.assertEqual(grant.rollback(self.player,self.payload,self.journal)['status'],'restored-before')
        self.assertEqual(self.player.read_bytes(),self.before)
        self.assertEqual(grant.rollback(self.player,self.payload,self.journal)['status'],'already-before')
        self.assertEqual(self.player.read_bytes(),self.before)

    def test_rollback_refuses_any_progress_without_touching_either_file(self):
        grant.transaction(self.player,self.payload,self.journal)
        current=grant.read(self.player);current['stats'][1]+=1;self.player.write_bytes(grant.canonical(current))
        after=self.player.read_bytes();journal=(self.journal/'grant-journal.json').read_bytes()
        for check_only in (True,False):
            with self.assertRaisesRegex(ValueError,'progressed'):grant.rollback(self.player,self.payload,self.journal,check_only)
            self.assertEqual(self.player.read_bytes(),after);self.assertEqual((self.journal/'grant-journal.json').read_bytes(),journal)

    def test_rollback_recovers_both_interruption_points(self):
        for point in ('rollback-prepared','rollback-player-written'):
            if (self.journal/'grant-journal.json').exists():
                self.assertEqual(grant.read(self.journal/'grant-journal.json')['status'],'rolled-back')
            grant.transaction(self.player,self.payload,self.journal)
            def fault(event):
                if event==point:raise RuntimeError('synthetic rollback interruption')
            with self.assertRaisesRegex(RuntimeError,'synthetic'):grant.rollback(self.player,self.payload,self.journal,False,fault)
            grant.rollback(self.player,self.payload,self.journal)
            self.assertEqual(self.player.read_bytes(),self.before)

    def test_rollback_rejects_modified_backup(self):
        grant.transaction(self.player,self.payload,self.journal);after=self.player.read_bytes()
        (self.journal/'player-before.json').write_bytes(b'{}')
        with self.assertRaisesRegex(ValueError,'before-image'):grant.rollback(self.player,self.payload,self.journal)
        self.assertEqual(self.player.read_bytes(),after)

    def test_rollback_rejects_modified_journal_and_payload(self):
        grant.transaction(self.player,self.payload,self.journal);after=self.player.read_bytes()
        changed=copy.deepcopy(self.payload);changed['items'][0]['amount']+=1
        with self.assertRaisesRegex(ValueError,'exact payload'):grant.rollback(self.player,changed,self.journal)
        journal=grant.read(self.journal/'grant-journal.json');journal['afterSha256']='0'*64
        (self.journal/'grant-journal.json').write_bytes(grant.canonical(journal))
        with self.assertRaisesRegex(ValueError,'reproduced'):grant.rollback(self.player,self.payload,self.journal)
        self.assertEqual(self.player.read_bytes(),after)


if __name__=='__main__':unittest.main()
