"""Resolve role-specific evidence for NPC combat sequences from the pinned source.

The observed-sequence lists contain no role ordering. Exact authored roles
take precedence. Captures only distinguish timing-only candidates when death
or blocking evidence is strong, and named sequences are never swapped across roles.
"""

import re

from gear_metadata import load_json


ROLES = ("attack", "block", "death")
LOCOMOTION = re.compile(r"(?:^|_)(?:walk\w*|run\w*|ready\w*|idle\w*|turn\w*|stand\w*)(?:_|$)")
OTHER = re.compile(r"(?:^|_)(?:frozen|emote|special|spec|cutscene|fake|spawn|transition|chathead|quick|reverse|react|pre|start|loop|end)(?:_|$)")
DEATH = re.compile(r"(?:^|_)(?:death\d*|die|dies|dying|dead)(?:_|$)")
BLOCK = re.compile(r"(?:^|_)(?:block\d*|blocking|defend\d*|defending|defence|defense|def|parry)(?:_|$)")
ATTACK = re.compile(r"(?:^|_)(?:\w*attack\w*|att\d*|\w*punch\d*|kick\d*|strike\d*|bit[eing]+\w*|peck\d*|cast\w*|\w*slash\d*|stab\d*|smash\d*|shoot\d*|sting\d*|spit\d*)(?:_|$)")


def sequence_role(name):
    name = (name or "").lower()
    if LOCOMOTION.search(name) or OTHER.search(name):
        return None
    if DEATH.search(name):
        return "death"
    if BLOCK.search(name):
        return "block"
    if ATTACK.search(name):
        return "attack"
    return None


def normalize(name):
    return " ".join((name or "").casefold().split())


class RoleEvidence:
    def __init__(self, definitions, combat_defs):
        def optional(name):
            file = definitions / name
            return load_json(file) if file.is_file() else {}

        self.names = {int(seq): name for seq, name in optional("sequence-names.json").items()}
        self.observed = optional("npc-animations.json")
        self.captures = optional("npc-captured-animations.json").get("npcs", {})
        self.explicit = {
            str(row[0]): (None, dict(zip(ROLES, row[1:])))
            for row in combat_defs.get("refs", {}).get("npcs", [])
        }
        for npc, entry in combat_defs.get("npcs", {}).items():
            if "anims" in entry:
                _, prior = self.explicit.get(npc, (None, {}))
                self.explicit[npc] = (entry.get("name"), {**prior, **entry["anims"]})
        self.family = {}
        for seq, name in self.names.items():
            if sequence_role(name):
                self.family.setdefault(name.lower().split("_", 1)[0], []).append((seq, name.lower()))

    def roles(self, npc, name):
        """Return (source sequence or -1, evidence) per proven role for this NPC."""
        npc = str(npc)
        authored_name, authored = self.explicit.get(npc, (None, {}))
        resolved = {
            role: (value, "authored")
            for role, value in authored.items()
            if role in ROLES and type(value) is int and value >= -1
            and (not authored_name or normalize(authored_name) == normalize(name))
        }
        captures = self.captures.get(npc, {}).get("seen", {})
        ids = set(self.observed.get(npc, [])) | {int(seq) for seq in captures}
        candidates = {role: [] for role in ROLES}
        for seq in ids:
            if type(seq) is not int or seq < 0:
                continue
            count = (captures.get(str(seq)) or [0, 0, 0, 0])
            death, block, attack, _ = count
            role = sequence_role(self.names.get(seq))
            source = "same-npc-role-name"
            if not self.names.get(seq):
                if death >= 3 and death > 2 * (block + attack):
                    role, source = "death", "same-npc-death-capture"
                elif block >= 9 and block > 9 * (attack + death):
                    role, source = "block", "same-npc-block-capture"
            if role:
                evidence = {"attack": attack, "block": block, "death": death}
                candidates[role].append((seq, evidence[role], source))
        for role, options in candidates.items():
            if role in resolved or not options:
                continue
            options.sort(key=lambda option: (-option[1], option[0]))
            seq, count, source = options[0]
            if len(options) == 1 or count >= 3 and count >= 3 * max(1, options[1][1]):
                resolved[role] = (seq, source)
        return resolved

    def unique_family(self, movement_ids, role):
        """Find one labeled role in the same specific named locomotion family."""
        prefixes = []
        for seq in movement_ids:
            name = (self.names.get(seq) or "").lower()
            motion = LOCOMOTION.search(name)
            if motion:
                prefix = name[:motion.start()]
                if len(prefix) >= 4 and prefix not in ("human", "npc", "emote", "human2", "human_duel"):
                    prefixes.append(prefix)
        if not prefixes:
            return None
        prefix = max(prefixes, key=len)
        if any(not prefix.startswith(other + "_") and prefix != other for other in prefixes):
            return None
        candidates = [
            seq for seq, name in self.family.get(prefix.split("_", 1)[0], [])
            if name.startswith(prefix + "_") and sequence_role(name) == role
        ]
        return candidates[0] if len(candidates) == 1 else None
