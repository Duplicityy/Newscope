"""Fail-closed policy checks; no server process or production path is used."""
import copy,io,json,struct,tempfile,unittest,zipfile
from pathlib import Path
from unittest.mock import patch
import release_bundle as bundle

class ReleasePolicy(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.bound={'clientJarSha256':'a'*64,'windowsSupportSha256':'b'*64}
        self.content={'mechanics':{'enabled':True,'features':{'fang':True,'muspah':False}}}
        self.review={'schemaVersion':1,'scope':'content242-final-release','approved':True,'bindings':self.bound,
                     'checks':{k:True for k in bundle.CHECKS},'androidPhysicalTested':False,
                     'testedMechanics':{'fang':True},'limitations':['No physical handset test.'],'evidence':[]}
        for role in bundle.EVIDENCE_ROLES:
            p=self.root/(role+'.json')
            p.write_text(json.dumps({'clientJarSha256':'a'*64,'supportSha256':'b'*64}) if role=='windowsBuild' else '{}')
            self.review['evidence'].append({'role':role,'path':str(p),'sha256':bundle.sha(p)})
    def reject(self,review=None,content=None):
        with self.assertRaises(ValueError):bundle.check_review(review or self.review,self.bound,content or self.content)
    def test_review_must_bind_exact_artifacts(self):
        self.review['bindings']={**self.bound,'clientJarSha256':'c'*64};self.reject()
    def test_diagnostic_and_missing_checks_rejected(self):
        self.review['approved']=False;self.reject();self.review['approved']=True
        del self.review['checks']['normalInventoryActions'];self.reject()
    def test_enabled_mechanic_requires_review(self):
        self.content['mechanics']['features']['muspah']=True;self.reject()
    def test_evidence_must_remain_exact(self):
        Path(self.review['evidence'][0]['path']).write_text('changed');self.reject()
    def test_private_save_cannot_be_smuggled_as_evidence(self):
        private=self.root/'player-data/players/fixture.json';private.parent.mkdir(parents=True);private.write_text('{}')
        self.review['evidence'].append({'role':'nativeGear','path':str(private),'sha256':bundle.sha(private)})
        self.reject()
    def test_old_windows_overlay_rejected(self):
        e=next(e for e in self.review['evidence'] if e['role']=='windowsBuild');p=Path(e['path'])
        p.write_text(json.dumps({'clientJarSha256':'0'*64,'supportSha256':'b'*64}));e['sha256']=bundle.sha(p);self.reject()
    def test_unperformed_handset_test_may_be_reported_honestly(self):
        bundle.check_review(self.review,self.bound,self.content)
        del self.review['androidPhysicalTested'];self.reject()
    def test_public_material_cannot_include_private_state(self):
        for name in ('profiles/player.json','accounts.properties','app/release.jks','server.properties'):
            with self.assertRaises(ValueError):bundle.no_private([{'path':name}])
    def test_paths_cannot_escape(self):
        for name in ('../Cache','C:/Cache','app/../../secret','app\\client.jar'):
            with self.assertRaises(ValueError):bundle.relative(name)
    def test_content_tree_records_complete_file_set(self):
        (self.root/'app').mkdir();(self.root/'app/client.jar').write_bytes(b'one')
        before=bundle.tree_sha(bundle.files(self.root));(self.root/'app/extra.jar').write_bytes(b'two')
        self.assertNotEqual(before,bundle.tree_sha(bundle.files(self.root)))
    def test_android_overlay_must_not_shadow_with_old_handshake(self):
        for revision,valid in ((184,False),(184242,True)):
            bridge=io.BytesIO()
            with zipfile.ZipFile(bridge,'w') as z:z.writestr('net/runelite/standalone/Client.class',b'\xca\xfe\xba\xbe\0\0\0\x34\0\x02\x03'+struct.pack('>i',revision))
            apk=io.BytesIO()
            with zipfile.ZipFile(apk,'w') as z:z.writestr('assets/jvm/bridge.jar',bridge.getvalue())
            with zipfile.ZipFile(apk) as z:
                if valid:bundle.check_android_overlay(z)
                else:
                    with self.assertRaises(ValueError):bundle.check_android_overlay(z)
    def test_bundle_assembly_is_exact_and_rejects_later_mutation(self):
        # Synthetic artifact assembly only. The independent cache/class audit is
        # replaced here; production prepare always performs that real audit.
        workspace=self.root/'workspace';stage=workspace/'stage';project=workspace/'kronos-osrs-184/Kronos-master'
        for p in (workspace/'game',workspace/'updater',workspace/'windows/app',stage/'Cache',stage/'data',
                  project/'Cache',project/'kronos-server/data',workspace/'tools',workspace/'local-runtime'):
            p.mkdir(parents=True,exist_ok=True)
        (workspace/'game/kronos-server-1.0.jar').write_bytes(b'synthetic server')
        (workspace/'updater/updater.jar').write_bytes(b'synthetic updater')
        (workspace/'windows/app/client.jar').write_bytes(b'synthetic client')
        (workspace/'windows/app/windows-support.jar').write_bytes(b'synthetic overlay')
        (stage/'Cache/content242-format.properties').write_text('format=k242-skeletal-v1\njs5_revision=184242\n')
        bundle.write(stage/'data/content242.json',{'requiredClientCapability':bundle.CAPABILITY,'clientProtocol':184,**self.content})
        (project/'Cache/original').write_bytes(b'old cache');(project/'kronos-server/data/original').write_bytes(b'old data')
        (workspace/'local-runtime/game-server-release.txt').write_text('synthetic-original')
        (workspace/'tools/RestartKronos.java').write_text('// synthetic')
        (workspace/'Start-Kronos.ps1').write_text('# synthetic baseline launcher')
        apk=workspace/'client.apk'
        bridge=io.BytesIO()
        with zipfile.ZipFile(bridge,'w') as z:z.writestr('net/runelite/standalone/Client.class',b'\xca\xfe\xba\xbe\0\0\0\x34\0\x02\x03'+struct.pack('>i',184242))
        with zipfile.ZipFile(apk,'w') as z:
            z.writestr('assets/jvm/client.jar',b'synthetic client');z.writestr('assets/jvm/bridge.jar',bridge.getvalue())
        spec={'schemaVersion':1,'releaseName':'synthetic','workspace':str(workspace),'stage':str(stage),
              'gameLib':str(workspace/'game'),'updaterLib':str(workspace/'updater'),'windowsDirectory':str(workspace/'windows'),
              'androidApk':str(apk),'baselineCache':str(project/'Cache'),'baselineData':str(project/'kronos-server/data'),
              'baselineLib':str(workspace/'game'),'baselineUpdaterLib':str(workspace/'updater'),
              'protectedReceipt':str(workspace/'protected.json'),'qaReview':str(workspace/'review.json')}
        receipt={'scope':'synthetic test only'};bundle.write(spec['protectedReceipt'],receipt)
        bound=bundle.inspect(spec)[2];self.review['bindings']=bound
        entry=next(e for e in self.review['evidence'] if e['role']=='windowsBuild')
        bundle.write(entry['path'],{'clientJarSha256':bound['clientJarSha256'],'supportSha256':bound['windowsSupportSha256']})
        entry['sha256']=bundle.sha(entry['path']);bundle.write(spec['qaReview'],self.review)
        output=workspace/'local-runtime/content242-releases/synthetic'
        with patch.object(bundle,'check_attestation',return_value=receipt):bundle.prepare(spec,output)
        self.assertTrue(bundle.verify(output)['valid'])
        self.assertEqual((project/'Cache/original').read_bytes(),b'old cache')
        (output/'windows/app/client.jar').write_bytes(b'changed')
        with self.assertRaises(ValueError):bundle.verify(output)

if __name__=='__main__':unittest.main()
