import unittest

from cache_store import Group, Reader
from legacy_assets import short, landscape, landscape184, write_fields
from modern_definitions import (ModelBanks, FIRST_MODEL, BANK_FIRST, TERRAIN_MAGIC,
                                terrain242, read_kit, read_npc, read_object, retextures, animation_item_dependencies)
from bulk_import import plan_regions


class FakeStore:
    def __init__(self, groups=None): self.groups = groups or {}; self.writes = {}
    def table(self, index): return 6,0,False,self.groups.get(index,{})
    def put(self,index,group,files): self.writes[index,group] = files


class FoundationTest(unittest.TestCase):
    def test_model_bank_file_boundary_preserves_wire_group_limit(self):
        out = FakeStore();banks = ModelBanks(FakeStore(),out)
        ids = [banks.add(bytes([i%256])) for i in range(513)]
        self.assertEqual(ids[511],FIRST_MODEL+511)
        self.assertEqual(ids[512],FIRST_MODEL+512)
        result = banks.finish()
        self.assertEqual(result['groups'],2)
        self.assertEqual(out.writes[7,BANK_FIRST][511],b'\xff')
        self.assertEqual(out.writes[7,BANK_FIRST+1][0],b'\0')

    def test_model_banks_reject_existing_reserved_group(self):
        with self.assertRaises(AssertionError): ModelBanks(FakeStore({7:{BANK_FIRST:Group()}}),FakeStore())

    def test_wide_palette_and_relocated_height_are_not_truncated(self):
        # First tile has both a large overlay and underlay, then explicit height.
        raw = short(2)+short(500)+short(582)+short(1)+b'\x20'+short(0)*(16384-1)
        out = terrain242(raw,lambda i:i+1000,lambda i:i+2000,(1600,3072))
        self.assertTrue(out.startswith(TERRAIN_MAGIC))
        r = Reader(out[len(TERRAIN_MAGIC):])
        self.assertEqual([r.uint(2),r.uint(2),r.uint(2),r.uint(2),r.uint()],[2,1500,2582,1,32])
        # The following implicit plane-zero tile is now explicit source height.
        self.assertEqual(r.uint(2),1)
        self.assertGreater(r.uint(),0)

    def test_kit_wide_source_and_target_roundtrip(self):
        fields,models,heads = read_kit(b'\1\x03\5\1'+(70000).to_bytes(4,'big')+b'F'+(70001).to_bytes(4,'big')+b'\0')
        self.assertEqual(models,[70000]);self.assertEqual(heads,{0:70001})
        fields[250] = b'\1'+(80000).to_bytes(4,'big')+b'\1\0'+(80001).to_bytes(4,'big')
        self.assertEqual(read_kit(write_fields(fields))[1:],([80000],{0:80001}))

    def test_definition_wide_models_and_legacy_fields(self):
        npc = b'\2Example\0\x0c\1\xfa\1'+(65536).to_bytes(4,'big')+b'\1'+(66048).to_bytes(4,'big')+b'\0'
        f,m = read_npc(npc,modern=False)
        self.assertEqual(m['models'],[65536]);self.assertEqual(m['headModels'],[66048])
        obj = b'\2Wall\0\xfa\1\1'+(65536).to_bytes(4,'big')+b'\x0a\0'
        f,m = read_object(obj,modern=False)
        self.assertEqual(m['models'],[65536]);self.assertEqual(m['modelTypes'],[10])

    def test_retextures_remap_source_and_destination_with_sentinel(self):
        f={41:b'\2'+short(9)+short(10)+short(11)+short(65535)}
        self.assertEqual(retextures(f,lambda i:i+100)[41],b'\2'+short(109)+short(110)+short(111)+short(65535))

    def test_anonymous_equipment_dependency_is_retained(self):
        # A single legacy frame plus held item29593; no visible item name needed.
        raw=b'\7'+short(29593+512)+b'\1'+short(1)+short(1)+short(0)+short(4)+b'\0'
        self.assertEqual(animation_item_dependencies({11133:raw},{29593:{}}),{29593})

    def test_smart_delta_32767_is_unambiguous_for_client(self):
        objects = {32766:[(10,40)],65533:[(12,40)]}
        decoded = landscape(landscape184(objects))
        self.assertEqual({i:v for i,v in decoded.items() if v},objects)

    def test_region_plan_preserves_original_and_dynamic_range(self):
        from cache_store import name_hash
        base = FakeStore({5:{0:Group(name=name_hash('m25_48')),1:Group(name=name_hash('l25_48'))}})
        donor = FakeStore({5:{(25<<8)|48:Group(files=(0,1)),(110<<8)|20:Group(files=(0,1))}})
        result,excluded = plan_regions(base,donor)
        self.assertFalse(excluded)
        self.assertNotEqual(result[(25<<8)|48]['destination'],(25<<8)|48)
        self.assertTrue(all(v['destination']>>8 < 100 for v in result.values()))


if __name__ == '__main__': unittest.main()
