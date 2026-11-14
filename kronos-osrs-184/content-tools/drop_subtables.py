"""Convert pinned shared loot tables with exact rational main-roll probabilities."""
from fractions import Fraction
import math
import re


def convert(name, tables, item_map, excluded, ancestors=()):
    if name in ancestors or len(ancestors) >= 6:
        raise ValueError("Cyclic or excessively deep shared drop table")
    source = tables.get(name)
    if not source or not source.get('entries'):
        excluded.append({'table': name, 'reason': 'Shared table unavailable'})
        return None
    entries, denominator = [], 1
    for entry in source['entries']:
        raw = str(entry.get('rarity_raw', ''))
        if not re.fullmatch(r'\d+/\d+', raw):
            excluded.append({'table': name, 'reason': 'Shared reward odds unavailable'})
            continue
        rate = Fraction(raw)
        if not 0 <= rate <= 1:
            raise ValueError('Invalid shared reward probability')
        denominator = math.lcm(denominator, rate.denominator)
        if denominator > 2_147_483_647:
            raise ValueError('Shared reward denominator exceeds native integer')
        entries.append((entry, rate))
    if sum((rate for _, rate in entries), Fraction(0)) > 1:
        raise ValueError('Shared reward probabilities exceed one')
    group = {'denominator': denominator, 'guaranteed': [], 'main': [], 'independent': []}
    for entry, rate in entries:
        if entry.get('nothing'):
            continue
        if 'shared_table' in entry:
            nested = convert(entry['shared_table'], tables, item_map, excluded, (*ancestors, name))
            if nested is None:
                continue
            reward = {'table': nested}
        else:
            source_id = entry.get('item_id')
            quantity = re.fullmatch(r'(\d+)(?:\s*[-–]\s*(\d+))?', str(entry.get('quantity_raw', '')))
            if source_id not in item_map or quantity is None:
                excluded.append({'table': name, 'item': source_id, 'reason': 'Unmapped item or unsupported shared quantity'})
                continue
            low, high = int(quantity[1]), int(quantity[2] or quantity[1])
            if not 1 <= low <= high <= 2_147_483_647:
                raise ValueError('Invalid shared quantity')
            reward = {'id': item_map[source_id], 'min': low, 'max': high}
        reward['weight'] = rate.numerator * (denominator // rate.denominator)
        group['main'].append(reward)
    return group
