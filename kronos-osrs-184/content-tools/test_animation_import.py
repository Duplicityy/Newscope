from copy import deepcopy
from types import SimpleNamespace
import unittest

from animation_import import AnimationImporter, skeleton184, upgrade_weapon_profiles, validate_frame
from legacy_assets import short


SKELETON = b"\x01\x01\x01\x00"
FRAME = b"\x00\x00\x01\x01\x40"


def sequence(group=0, frame=0, extra=b""):
    return b"\x01\x00\x01\x00\x01" + short(frame) + short(group) + extra + b"\0"


class MemoryStore:
    def __init__(self, groups):
        self.groups = deepcopy(groups)
        self.writes, self.commits = [], []

    def table(self, index):
        return (6, 0, False, {group: SimpleNamespace(files=tuple(files))
                              for (idx, group), files in self.groups.items() if idx == index})

    def files(self, index, group):
        return self.groups[index, group]

    def put(self, index, group, files):
        self.groups[index, group] = deepcopy(files)
        self.writes.append((index, group))

    def commit_table(self, index):
        self.commits.append(index)


def base_store():
    return MemoryStore({(0, 0): {0: FRAME}, (1, 0): {0: SKELETON},
                        (2, 12): {0: sequence()}, (2, 10): {0: b"original item"}})


class AnimationImportTests(unittest.TestCase):
    def test_identical_content_reuses_existing_ids_without_writes(self):
        base, out = base_store(), base_store()
        donor = MemoryStore({(0, 7): {0: short(9) + FRAME[2:]},
                             (1, 9): {0: SKELETON}, (2, 12): {20: sequence(7)}})
        importer = AnimationImporter(base, donor, out)
        self.assertEqual(importer.import_sequence(20), 0)
        self.assertEqual(out.writes, [])
        report = importer.finish()
        self.assertEqual(report["frame_group_mapping"], {7: 0})
        self.assertEqual(report["skeleton_mapping"], {9: 0})
        self.assertEqual(out.writes, [])

    def test_new_frames_append_and_preserve_latest_items_and_old_sequences(self):
        base, out, donor = base_store(), base_store(), base_store()
        donor.groups[0, 0][0] = FRAME[:-1] + b"\x41"
        importer = AnimationImporter(base, donor, out)
        target = importer.import_sequence(0)
        self.assertEqual(target, 1)
        self.assertEqual(out.writes, [])
        out.put(2, 10, {0: b"original item", 40000: b"new staged item"})
        report = importer.finish()
        self.assertEqual(report["added_frame_groups"], 1)
        self.assertEqual(out.files(2, 12)[0], sequence())
        self.assertEqual(out.files(2, 10)[40000], b"new staged item")
        self.assertEqual(out.files(0, 0)[0], FRAME)
        self.assertEqual(out.commits, [0, 2])

    def test_sparse_frame_file_ids_are_valid_for_archive_max_id_allocation(self):
        base, out, donor = base_store(), base_store(), base_store()
        donor.groups[0, 0] = {1: FRAME}
        donor.groups[2, 12] = {1: sequence(frame=1)}
        importer = AnimationImporter(base, donor, out)
        self.assertIsNotNone(importer.import_sequence(1))
        report = importer.finish()
        self.assertEqual(set(out.files(0, report["frame_group_mapping"][0])), {1})

    def test_weighted_or_missing_frame_sequence_is_explicitly_excluded(self):
        base, out, donor = base_store(), base_store(), base_store()
        donor.groups[2, 12] = {1: b"\x0d\x00\x00\x00\x01\x00", 2: sequence(frame=1)}
        importer = AnimationImporter(base, donor, out)
        self.assertIsNone(importer.import_sequence(1))
        self.assertIsNone(importer.import_sequence(2))
        report = importer.finish()
        self.assertEqual(len(report["excluded_sequences"]), 2)
        self.assertEqual(out.writes, [])

    def test_known_skeletal_trailer_is_trimmed_but_unknown_tail_fails(self):
        raw, types = skeleton184(SKELETON + b"\x00\x01\x01" + bytes(78))
        self.assertEqual((raw, types), (SKELETON, (1,)))
        with self.assertRaises(ValueError):
            skeleton184(SKELETON + b"\x00\x00\x01")
        with self.assertRaises(ValueError):
            validate_frame(FRAME + b"\x00", types)
        with self.assertRaises(ValueError):
            validate_frame(FRAME[:3] + b"\x08", types)

    def test_held_item_overrides_require_verified_mapping(self):
        base, out, donor = base_store(), base_store(), base_store()
        donor.groups[2, 12][1] = sequence(extra=b"\x06" + short(513))
        blocked = AnimationImporter(base, donor, out)
        self.assertIsNone(blocked.import_sequence(1))
        importer = AnimationImporter(base, donor, out, item_map={1: 40000})
        target = importer.import_sequence(1)
        importer.finish()
        self.assertEqual(out.files(2, 12)[target], sequence(extra=b"\x06" + short(40512)))

    def test_profile_upgrades_are_private_and_unsupported_frames_keep_fallback(self):
        class StubImporter:
            exclusions = {9: "Weighted animation"}

            def import_sequence(self, source):
                return {1: 300, 2: 301}.get(source)

        profile = {"attackAnimation": 451, "defendAnimation": 424,
                   "renderAnimations": [808, 823, 819, 820, 821, 822, 824],
                   "attackSets": [{"child": 3, "style": "STAB", "attackAnimation": 422}]}
        original = deepcopy(profile)
        additions = {"item_info": [{"id": 40000, "weapon_type": "SHARED"},
                                   {"id": 40001, "weapon_type": "SHARED"}],
                     "weapon_types": {"SHARED": profile}, "report": []}
        report = upgrade_weapon_profiles(StubImporter(), additions, {1: 40000, 2: 40001},
                                         {1: {"attackAnim": 1, "blockAnim": 9, "runAnim": 2},
                                          2: {"attackAnim": 9}})
        upgraded = additions["weapon_types"]["CONTENT242_ITEM_40000"]
        self.assertEqual(upgraded["attackAnimation"], 300)
        self.assertNotIn("attackAnimation", upgraded["attackSets"][0])
        self.assertEqual(upgraded["defendAnimation"], 424)
        self.assertEqual(upgraded["renderAnimations"][-1], 301)
        self.assertEqual(additions["item_info"][1]["weapon_type"], "SHARED")
        self.assertEqual(profile, original)
        self.assertEqual(report[0]["fallbacks"]["blockAnim"]["source_id"], 9)


if __name__ == "__main__":
    unittest.main()
