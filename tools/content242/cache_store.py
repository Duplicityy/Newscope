"""Read JS5 disk caches and append compatible groups to a staging copy."""

import bz2
import gzip
import struct
import zlib
from dataclasses import dataclass


class Reader:
    def __init__(self, data):
        self.data = data
        self.offset = 0

    def take(self, count):
        end = self.offset + count
        if count < 0 or end > len(self.data):
            raise ValueError("Truncated cache record")
        value = self.data[self.offset:end]
        self.offset = end
        return value

    def uint(self, size=1):
        return int.from_bytes(self.take(size), "big")

    def big_smart(self):
        return self.uint(4) & 0x7fffffff if self.data[self.offset] & 128 else self.uint(2)

    def smart(self):
        return self.uint(2) - 32768 if self.data[self.offset] & 128 else self.uint()

    def string(self):
        end = self.data.index(0, self.offset)
        return self.take(end - self.offset + 1)


def unpack_container(raw):
    r = Reader(raw)
    codec, size = r.uint(), r.uint(4)
    if codec == 0:
        return r.take(size)
    expanded = r.uint(4)
    payload = r.take(size)
    if codec == 1:
        data = bz2.decompress(b"BZh1" + payload)
    elif codec == 2:
        data = gzip.decompress(payload)
    else:
        raise ValueError(f"Unsupported JS5 compression {codec}")
    if len(data) != expanded:
        raise ValueError("Wrong decompressed length")
    return data


def pack_container(data, revision=None):
    compressed = gzip.compress(data, mtime=0)
    raw = struct.pack(">BII", 2, len(compressed), len(data)) + compressed
    return raw if revision is None else raw + struct.pack(">H", revision & 65535)


@dataclass
class Group:
    name: int = 0
    crc: int = 0
    revision: int = 0
    files: tuple = (0,)
    file_names: tuple = ()


def cumulative(read, count):
    result = []
    current = 0
    for _ in range(count):
        current += read()
        result.append(current)
    return result


def read_table(data):
    r = Reader(data)
    protocol = r.uint()
    if protocol not in (5, 6, 7):
        raise ValueError(f"Unknown JS5 index protocol {protocol}")
    revision = r.uint(4) if protocol >= 6 else 0
    flags = r.uint()
    if flags & ~5:
        raise ValueError(f"Unsupported JS5 index flags {flags}")
    smart = r.big_smart if protocol == 7 else lambda: r.uint(2)
    groups = {i: Group() for i in cumulative(smart, smart())}
    if flags & 1:
        for group in groups.values():
            group.name = r.uint(4)
    for group in groups.values():
        group.crc = r.uint(4)
    if flags & 4:
        r.take(8 * len(groups))
    for group in groups.values():
        group.revision = r.uint(4)
    counts = [smart() for group in groups.values()]
    for group, count in zip(groups.values(), counts):
        group.files = tuple(cumulative(smart, count))
    if flags & 1:
        for group in groups.values():
            group.file_names = tuple(r.uint(4) for _ in group.files)
    if r.offset != len(data):
        raise ValueError("Unread JS5 index bytes")
    return protocol, revision, bool(flags & 1), groups


def encode_table(protocol, revision, named, groups):
    out = bytearray([protocol])
    if protocol >= 6:
        out.extend(struct.pack(">I", revision))
    out.append(int(named))

    def number(value):
        if protocol == 7 and value >= 32768:
            out.extend(struct.pack(">I", value | 0x80000000))
        else:
            out.extend(struct.pack(">H", value))

    def deltas(values):
        previous = 0
        for value in values:
            number(value - previous)
            previous = value

    ordered = sorted(groups)
    values = [groups[i] for i in ordered]
    number(len(ordered))
    deltas(ordered)
    if named:
        for group in values:
            out.extend(struct.pack(">I", group.name))
    for field in ("crc", "revision"):
        for group in values:
            out.extend(struct.pack(">I", getattr(group, field)))
    for group in values:
        number(len(group.files))
    for group in values:
        deltas(group.files)
    if named:
        for group in values:
            names = group.file_names or (0,) * len(group.files)
            if len(names) != len(group.files):
                raise ValueError("Incorrect file name count")
            for name in names:
                out.extend(struct.pack(">I", name))
    return bytes(out)


def split_files(data, ids):
    if len(ids) == 1:
        return {ids[0]: data}
    chunks = data[-1]
    footer = len(data) - 1 - 4 * chunks * len(ids)
    if chunks == 0 or footer < 0:
        raise ValueError("Invalid JS5 file table")
    files = [bytearray() for _ in ids]
    offset = 0
    sizes = struct.iter_unpack(">i", data[footer:-1])
    for _ in range(chunks):
        size = 0
        for file in files:
            size += next(sizes)[0]
            if size < 0 or offset + size > footer:
                raise ValueError("Invalid JS5 file size")
            file.extend(data[offset:offset + size])
            offset += size
    if offset != footer:
        raise ValueError("Unread JS5 group bytes")
    return {i: bytes(f) for i, f in zip(ids, files)}


def join_files(files):
    ordered = [files[i] for i in sorted(files)]
    if len(ordered) == 1:
        return ordered[0]
    sizes = [len(f) for f in ordered]
    return b"".join(ordered) + b"".join(
        struct.pack(">i", size - previous)
        for size, previous in zip(sizes, [0] + sizes)
    ) + b"\x01"


class Store:
    def __init__(self, root, writable=False):
        self.root = root
        self.writable = writable
        self.data = (root / "main_file_cache.dat2").open("r+b" if writable else "rb")
        self.indexes = {}
        self.tables = {}

    def __enter__(self):
        return self

    def __exit__(self, *_):
        for index in self.indexes.values():
            index.close()
        self.data.close()

    def index(self, index):
        if index not in self.indexes:
            self.indexes[index] = (self.root / f"main_file_cache.idx{index}").open(
                "r+b" if self.writable else "rb"
            )
        return self.indexes[index]

    def read(self, index, group):
        table = self.index(index)
        table.seek(group * 6)
        entry = table.read(6)
        length, sector = int.from_bytes(entry[:3], "big"), int.from_bytes(entry[3:], "big")
        if not length or not sector:
            raise KeyError((index, group))
        result = bytearray()
        header = 10 if group > 65535 else 8
        part = 0
        while len(result) < length:
            if sector == 0:
                raise ValueError(f"Premature sector end for {index}/{group}")
            self.data.seek(sector * 520)
            r = Reader(self.data.read(520))
            stored_group, stored_part, sector, stored_index = (
                r.uint(header - 6), r.uint(2), r.uint(3), r.uint()
            )
            if (stored_index, stored_group, stored_part) != (index, group, part):
                raise ValueError(f"Invalid sector for {index}/{group}/{part}")
            result.extend(r.take(min(520 - header, length - len(result))))
            part += 1
        return bytes(result)

    def table(self, index):
        if index not in self.tables:
            self.tables[index] = read_table(unpack_container(self.read(255, index)))
        return self.tables[index]

    def files(self, index, group):
        definition = self.table(index)[3][group]
        return split_files(unpack_container(self.read(index, group)), definition.files)

    def write(self, index, group, data):
        if not self.writable:
            raise ValueError("Read-only JS5 store")
        self.data.seek(0, 2)
        sector = (self.data.tell() + 519) // 520
        start = sector
        self.data.seek(sector * 520)
        id_size = 4 if group > 65535 else 2
        payload_size = 514 - id_size
        for part, offset in enumerate(range(0, len(data), payload_size)):
            chunk = data[offset:offset + payload_size]
            next_sector = sector + 1 if offset + len(chunk) < len(data) else 0
            self.data.write(group.to_bytes(id_size, "big") + part.to_bytes(2, "big")
                            + next_sector.to_bytes(3, "big") + bytes([index]) + chunk)
            sector += 1
        table = self.index(index)
        table.seek(group * 6)
        table.write(len(data).to_bytes(3, "big") + start.to_bytes(3, "big"))

    def put(self, index, group_id, files, *, name=None):
        groups = self.table(index)[3]
        old = groups.get(group_id, Group())
        revision = old.revision + 1
        ids = tuple(sorted(files))
        old_names = dict(zip(old.files, old.file_names))
        packed = pack_container(join_files(files), revision)
        self.write(index, group_id, packed)
        groups[group_id] = Group(old.name if name is None else name, zlib.crc32(packed[:-2]),
                                 revision, ids, tuple(old_names.get(i, 0) for i in ids))

    def commit_table(self, index):
        protocol, revision, named, groups = self.table(index)
        self.write(255, index, pack_container(encode_table(protocol, revision + 1, named, groups)))


def name_hash(name):
    value = 0
    for letter in name.lower().encode("cp1252"):
        value = (value * 31 + letter) & 0xffffffff
    return value
