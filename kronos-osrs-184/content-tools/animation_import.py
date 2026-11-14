"""Append genuine legacy-frame animations, retaining safe 184 fallbacks.

Weighted/Maya sequences cannot run on the 184 client. Their legacy skeleton
prefix can still be used by ordinary frames, but no weighted sequence is
misrepresented as converted. All writes are deferred until finish().
"""

from copy import deepcopy
import hashlib

from cache_store import Reader
from legacy_assets import sequence184, short


def skeleton184(raw, *, allow_extended_types=False):
    """Validate and return the legacy prefix plus its transform types.

    The optional OSRS skeletal trailer is a u16 bone count, u8 pose count,
    then a parent short and poseCount * (16 matrix + 3 vector) floats per bone.
    It is irrelevant to ordinary vertex-group animations and may be stripped.
    """
    r = Reader(raw)
    count = r.uint()
    types = tuple(r.take(count))
    if not allow_extended_types and any(kind not in (0, 1, 2, 3, 5) for kind in types):
        raise ValueError("Skeleton contains transforms unsupported by the legacy model renderer")
    sizes = r.take(count)
    r.take(sum(sizes))
    end = r.offset
    if end < len(raw):
        bones = r.uint(2)
        if bones:
            poses = r.uint()
            if poses == 0:
                raise ValueError("Skeletal trailer has no poses")
            r.take(bones * (2 + poses * 76))
        if r.offset != len(raw):
            raise ValueError("Unrecognized skeleton trailer")
    return raw[:end], types


def validate_frame(raw, types):
    """Mirror the bounds and short-smart consumption of 184 Animation.java."""
    r = Reader(raw)
    skeleton = r.uint(2)
    count = r.uint()
    if count > len(types):
        raise ValueError("Frame transform count exceeds its skeleton")
    flags = r.take(count)
    previous = -1
    transforms = 0
    for index, mask in enumerate(flags):
        if mask & ~7:
            raise ValueError("Frame uses unsupported transform flags")
        if not mask:
            continue
        if types[index] != 0 and any(types[i] == 0 for i in range(previous + 1, index)):
            transforms += 1
        for component in (1, 2, 4):
            if mask & component:
                first = r.uint()
                if first >= 128:
                    r.take(1)
        transforms += 1
        previous = index
    if transforms > 500 or r.offset != len(raw):
        raise ValueError("Frame exceeds legacy transform buffer or has unread bytes")
    return skeleton


def _group_hash(files):
    h = hashlib.sha256()
    for file_id, raw in sorted(files.items()):
        h.update(file_id.to_bytes(4, "big"))
        h.update(len(raw).to_bytes(4, "big"))
        h.update(raw)
    return h.digest()


def _rewrite_equipment(sequence, item_map, frame_check=None):
    """Rewrite held-item overrides in already converted legacy sequences."""
    r, out = Reader(sequence), bytearray()
    while op := r.uint():
        out.append(op)
        if op in (1, 12):
            count = r.uint(2 if op == 1 else 1)
            out.extend(short(count) if op == 1 else bytes([count]))
            lengths = r.take(count * 2) if op == 1 else b""
            frames = r.take(count * 2)
            groups = r.take(count * 2)
            if frame_check is not None:
                for i in range(count):
                    frame = int.from_bytes(frames[i * 2:i * 2 + 2], "big")
                    group = int.from_bytes(groups[i * 2:i * 2 + 2], "big")
                    if op == 12 and frame == 65535:
                        continue
                    frame_check(group, frame)
            out.extend(lengths + frames + groups)
        elif op == 3:
            count = r.uint()
            out.extend(bytes([count]) + r.take(count))
        elif op in (6, 7):
            value = r.uint(2)
            if value:
                source = value - 512
                if source not in item_map:
                    raise ValueError(f"Sequence held-item override {source} has no verified target mapping")
                target = item_map[source]
                if not 0 <= target <= 65023:
                    raise ValueError("Sequence held-item override exceeds legacy appearance range")
                value = target + 512
            out.extend(short(value))
        elif 2 <= op <= 11:
            out.extend(r.take(2 if op == 2 else 0 if op == 4 else 1))
        else:
            raise ValueError(f"Non-legacy sequence opcode {op}")
    if r.offset != len(sequence):
        raise ValueError("Unread sequence bytes")
    return bytes(out) + b"\0"


class AnimationImporter:
    def __init__(self, baseStore, donorStore, outStore, *, item_map=None, allow_weighted=False):
        self.base, self.donor, self.out = baseStore, donorStore, outStore
        self.item_map = {int(k): int(v) for k, v in (item_map or {}).items()}
        self.allow_weighted = allow_weighted
        self.cached_animations = {}
        self.weighted_sequences = []
        self.sequences, self.frames, self.skeletons = {}, {}, {}
        self.pending_sequences, self.pending_frames, self.pending_skeletons = {}, {}, {}
        self.skeleton_types = {}
        self.exclusions = {}
        self.trimmed_skeletons = []
        self._finished = False
        self._indexed = False
        self._source_sequences = None
        self._hashes = {0: {}, 1: {}, 2: {}}
        self._used = {
            0: set(outStore.table(0)[3]), 1: set(outStore.table(1)[3]),
            2: set(outStore.files(2, 12)),
        }
        self._next = {index: max(values, default=-1) + 1 for index, values in self._used.items()}

    def _index(self):
        if self._indexed:
            return
        for index in (0, 1):
            for group in self.out.table(index)[3]:
                files = self.out.files(index, group)
                self._hashes[index].setdefault(_group_hash(files), group)
        for seq, raw in self.out.files(2, 12).items():
            self._hashes[2].setdefault(hashlib.sha256(raw).digest(), seq)
        self._indexed = True

    def _allocate(self, index):
        while self._next[index] in self._used[index]:
            self._next[index] += 1
        value = self._next[index]
        if value > 65534:
            raise ValueError(f"Animation index {index} exhausted legacy short IDs")
        self._used[index].add(value)
        self._next[index] += 1
        return value

    def _skeleton(self, source):
        if source in self.skeletons:
            return self.skeletons[source]
        files = self.donor.files(1, source)
        if set(files) != {0}:
            raise ValueError("Skeleton group must contain only file zero")
        raw, types = skeleton184(files[0], allow_extended_types=self.allow_weighted)
        if self.allow_weighted:
            raw = files[0]
        digest = _group_hash({0: raw})
        target = self._hashes[1].get(digest)
        if target is None:
            target = self._allocate(1)
            self.pending_skeletons[target] = {0: raw}
            self._hashes[1][digest] = target
        if len(raw) != len(files[0]):
            self.trimmed_skeletons.append(source)
        self.skeletons[source] = target
        self.skeleton_types[source] = types
        return target

    def _cached_animation(self, source):
        if source in self.cached_animations:
            return self.cached_animations[source]
        # OSRS229+ moved cached-model curves to index22; target stays on184 index0.
        raw = self.donor.files(22, source >> 16)[source & 65535]
        r = Reader(raw)
        version, base = r.uint(), r.uint(2)
        if version not in (1, 2):
            raise ValueError(f"Unsupported cached animation version {version}")
        target_base = self._skeleton(base)
        converted = {0: bytes([version]) + short(target_base) + raw[3:]}
        digest = _group_hash(converted)
        target = self._hashes[0].get(digest)
        if target is None:
            target = self._allocate(0)
            self.pending_frames[target] = converted
            self._hashes[0][digest] = target
        self.cached_animations[source] = target << 16
        return target << 16

    def _frames(self, source):
        if source in self.frames:
            return self.frames[source]
        files = self.donor.files(0, source)
        # AbstractArchive allocates files[maxFileId + 1], and Frames uses that
        # length; sparse IDs (including donor groups starting at one) are valid.
        if not files or min(files) < 0 or max(files) > 65535:
            raise ValueError("Frame file ID exceeds the legacy sequence short")
        converted = {}
        for file_id, raw in files.items():
            if len(raw) < 3:
                raise ValueError("Truncated animation frame")
            skeleton = int.from_bytes(raw[:2], "big")
            target_skeleton = self._skeleton(skeleton)
            validate_frame(raw, self.skeleton_types[skeleton])
            converted[file_id] = short(target_skeleton) + raw[2:]
        digest = _group_hash(converted)
        target = self._hashes[0].get(digest)
        if target is None:
            target = self._allocate(0)
            self.pending_frames[target] = converted
            self._hashes[0][digest] = target
        self.frames[source] = target
        return target

    def import_sequence(self, source):
        """Return the safe target ID, or None and record why it was excluded."""
        if self._finished:
            raise RuntimeError("Cannot import after finish")
        if source in self.sequences:
            return self.sequences[source]
        if source in self.exclusions:
            return None
        try:
            if not isinstance(source, int) or source < 0:
                raise ValueError("Animation ID must be a nonnegative integer")
            if self._source_sequences is None:
                self._source_sequences = self.donor.files(2, 12)
            raw = self._source_sequences[source]
            if self.allow_weighted:
                from modern_animation import sequence242
                # Preflight structure/held references before adding dependencies.
                sequence242(raw, lambda i: i, lambda i: i, self.item_map)
                self._index()
                converted, weighted = sequence242(raw, self._frames, self._cached_animation, self.item_map)
                digest = hashlib.sha256(converted).digest()
                target = self._hashes[2].get(digest)
                if target is None:
                    target = self._allocate(2)
                    self.pending_sequences[target] = converted
                    self._hashes[2][digest] = target
                self.sequences[source] = target
                if weighted:
                    self.weighted_sequences.append(source)
                return target
            # Preflight all opcodes and held items before staging dependencies.
            def check_frame(group, frame):
                if frame not in self.donor.table(0)[3][group].files:
                    raise ValueError(f"Sequence references absent frame {group}/{frame}")
            converted = _rewrite_equipment(sequence184(raw, lambda group: group), self.item_map, check_frame)
            self._index()
            converted = sequence184(converted, self._frames)
            digest = hashlib.sha256(converted).digest()
            target = self._hashes[2].get(digest)
            if target is None:
                target = self._allocate(2)
                self.pending_sequences[target] = converted
                self._hashes[2][digest] = target
            self.sequences[source] = target
            return target
        except (KeyError, ValueError, IndexError) as error:
            self.exclusions[source] = str(error)
            return None

    def finish(self):
        """Write only appended groups/definitions, preserving current table 2."""
        if self._finished:
            raise RuntimeError("finish() may only be called once")
        changed = set()
        for index, groups in ((1, self.pending_skeletons), (0, self.pending_frames)):
            for target, files in sorted(groups.items()):
                if target in self.out.table(index)[3]:
                    raise ValueError(f"Concurrent animation group collision {index}/{target}")
                self.out.put(index, target, files)
                changed.add(index)
        if self.pending_sequences:
            # Read output, not base: importer may have just written items/maps.
            sequences = dict(self.out.files(2, 12))
            if set(sequences) & set(self.pending_sequences):
                raise ValueError("Concurrent sequence definition collision")
            sequences.update(self.pending_sequences)
            self.out.put(2, 12, sequences)
            changed.add(2)
        for index in sorted(changed):
            self.out.commit_table(index)
        self._finished = True
        return {
            "sequence_mapping": self.sequences, "frame_group_mapping": self.frames,
            "skeleton_mapping": self.skeletons,
            "cached_animation_mapping": self.cached_animations,
            "weighted_sequences": sorted(self.weighted_sequences),
            "requires_client_capability": "k242-skeletal-v1" if self.allow_weighted else None,
            "excluded_sequences": [{"source_id": i, "reason": reason} for i, reason in sorted(self.exclusions.items())],
            "trimmed_skeletal_trailers": sorted(self.trimmed_skeletons),
            "added_sequences": len(self.pending_sequences),
            "added_frame_groups": len(self.pending_frames),
            "added_skeleton_groups": len(self.pending_skeletons),
        }


def upgrade_weapon_profiles(animation_importer, additions, item_map, gameplay, *, existing_weapon_types=None):
    """Upgrade generated rows/profiles in-place; return per-item provenance.

    Only new item rows in additions are touched. Every upgraded item receives a
    private cloned profile so its animations cannot affect another weapon. Each
    unsupported sequence leaves the known legacy value in place.
    """
    if isinstance(gameplay, list):
        gameplay = {entry["id"]: entry for entry in gameplay}
    rows = {row["id"]: row for row in additions["item_info"]}
    reports = {entry["target_id"]: entry for entry in additions.get("report", ())}
    result = []
    render_fields = ("standAnim", "standTurnAnim", "walkAnim", "turn180Anim", "turn90CWAnim", "turn90CCWAnim", "runAnim")
    for source, target in sorted(item_map.items(), key=lambda pair: int(pair[0])):
        source, target = int(source), int(target)
        row = rows.get(target, {})
        current = row.get("weapon_type")
        if not current:
            continue
        data = gameplay.get(source, gameplay.get(str(source), {}))
        profiles = {**(existing_weapon_types or {}), **additions["weapon_types"]}
        if current not in profiles:
            raise ValueError(f"Missing generated weapon profile {current}")
        profile = deepcopy(profiles[current])
        used, fallbacks = {}, {}

        def attempt(field, animation):
            if not isinstance(animation, int) or animation < 0:
                return None
            converted = animation_importer.import_sequence(animation)
            if converted is None:
                fallbacks[field] = {"source_id": animation, "reason": animation_importer.exclusions.get(animation, "Unsupported sequence")}
            else:
                used[field] = {"source_id": animation, "target_id": converted}
            return converted

        attack = data.get("attackAnim")
        if isinstance(attack, int):
            converted = attempt("attackAnim", attack)
            if converted is not None:
                profile["attackAnimation"] = converted
                for attack_set in profile["attackSets"]:
                    if attack_set:
                        attack_set.pop("attackAnimation", None)
        elif isinstance(attack, dict):
            for attack_set in profile["attackSets"]:
                if not attack_set:
                    continue
                style = attack_set["style"].lower()
                source_animation = attack.get(style, attack.get("range") if style == "ranged" else None)
                converted = attempt(f"attackAnim.{style}", source_animation)
                if converted is not None:
                    attack_set["attackAnimation"] = converted
        converted = attempt("blockAnim", data.get("blockAnim"))
        if converted is not None:
            profile["defendAnimation"] = converted
        for index, field in enumerate(render_fields):
            converted = attempt(field, data.get(field))
            if converted is not None:
                profile["renderAnimations"][index] = converted
        if used:
            key = f"CONTENT242_ITEM_{target}"
            if key in profiles and profiles[key] != profile:
                raise ValueError(f"Per-item animation profile collision {key}")
            additions["weapon_types"][key] = profile
            row["weapon_type"] = key
            report = reports.get(target)
            if report is not None:
                report["notes"] = [note for note in report["notes"] if "newer attack animations are not installed" not in note]
                report["notes"].append("Imported validated legacy-frame donor animations; unsupported sequences retain the legacy fallback")
                report["animation_upgrades"] = used
        if used or fallbacks:
            result.append({"donor_id": source, "target_id": target, "imported": used, "fallbacks": fallbacks})
    return result
