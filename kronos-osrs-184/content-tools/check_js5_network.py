"""Download every staged model bank through real loopback JS5 and compare disk bytes/CRCs."""
import argparse
import hashlib
import json
import socket
import struct
import time
import zlib

from pathlib import Path
from cache_store import Store, unpack_container


def exact(connection, count, xor=0):
    result = bytearray()
    while len(result) < count:
        chunk = connection.recv(count - len(result))
        if not chunk:
            raise EOFError('Updater closed an unfinished response')
        result.extend(chunk)
    return bytes(value ^ xor for value in result) if xor else bytes(result)


def connect(revision=184242, xor=0):
    connection = socket.create_connection(('127.0.0.1', 17304), timeout=15)
    connection.settimeout(30)
    connection.sendall(struct.pack('>BI', 15, revision))
    response = exact(connection, 1)
    if revision != 184242:
        connection.close()
        if response != b'\x06':
            raise AssertionError('Old client was not explicitly rejected as out of date')
        return None
    if response != b'\0':
        connection.close()
        raise AssertionError('Extended cache handshake rejected')
    connection.sendall(bytes([3, 0, 0, 0]))
    if xor:
        connection.sendall(bytes([4, xor, 0, 0]))
    return connection


def response(connection, xor=0):
    header = exact(connection, 8, xor)
    index, group, compression, compressed = struct.unpack('>BHBI', header)
    if compression not in (0, 1, 2) or compressed > 8 * 1024 * 1024:
        raise AssertionError('Invalid bounded response header')
    remaining = compressed + (4 if compression else 0)
    data = bytearray(header[3:])
    capacity = 504
    while remaining:
        count = min(remaining, capacity)
        data.extend(exact(connection, count, xor))
        remaining -= count
        if remaining and exact(connection, 1, xor) != b'\xff':
            raise AssertionError('Incorrect511-byte JS5 block delimiter')
        capacity = 511
    return index, group, bytes(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--receipt', type=Path, required=True)
    args = parser.parse_args()
    started = time.monotonic()
    with Store(args.cache) as store:
        groups = store.table(7)[3]
        banks = sorted(group for group in groups if 65408 <= group <= 65534)
        if not banks:
            raise AssertionError('No extended model banks in test cache')
        receipt = {'cache': str(args.cache.resolve()), 'wireRevision': 184, 'capability': 184242, 'banks': []}
        connect(184)
        with connect() as connection:
            connection.sendall(b''.join(struct.pack('>BBH', 1, 7, group) for group in banks))
            for expected in banks:
                index, group, data = response(connection)
                if (index, group) != (7, expected):
                    raise AssertionError('Unexpected response order or identifier')
                disk = store.read(7, group)
                if data != disk[:len(data)] or len(disk) - len(data) not in (0, 2):
                    raise AssertionError('Wire bytes differ from the staged cache')
                if zlib.crc32(data) & 0xffffffff != groups[group].crc:
                    raise AssertionError('Model bank CRC differs from its reference table')
                expanded = unpack_container(data)
                receipt['banks'].append({'group': group, 'containerBytes': len(data),
                                         'expandedBytes': len(expanded), 'sha256': hashlib.sha256(data).hexdigest()})
        largest = max(receipt['banks'], key=lambda item: item['containerBytes'])['group']
        for xor in [73, 0]:
            with connect(xor=xor) as connection:
                connection.sendall(struct.pack('>BBH', 0, 7, largest))
                index, group, data = response(connection, xor)
                if (index, group) != (7, largest) or data != store.read(7, group)[:len(data)]:
                    raise AssertionError('XOR corrupted the shared response cache')
        receipt.update(status='PASS', legacyRejected=True, xorIsolation=True,
                       totalContainerBytes=sum(item['containerBytes'] for item in receipt['banks']),
                       durationSeconds=round(time.monotonic()-started, 3))
        args.receipt.parent.mkdir(parents=True, exist_ok=True)
        args.receipt.write_text(json.dumps(receipt, indent=2)+'\n', encoding='utf8')
        print(json.dumps({key: value for key, value in receipt.items() if key != 'banks'}, indent=2))
        print(f'All {len(banks)} model banks received and verified through the real staged updater.')


if __name__ == '__main__':
    main()
