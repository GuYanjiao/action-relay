"""Inspect UF2 blocks against the confirmed S140 v6 application partition."""
import argparse
import json
import pathlib
import struct

APP_START = 0x26000
APP_END = 0xEC000
FAMILY = 0xADA52840


def inspect(data):
    if not data or len(data) % 512:
        raise ValueError('UF2 must contain complete 512-byte blocks')
    count = len(data) // 512
    numbers = set()
    ranges = []
    vectors = None
    for offset in range(0, len(data), 512):
        block = data[offset:offset + 512]
        magic0, magic1, flags, address, size, number, total, family = struct.unpack_from('<8I', block)
        if (magic0, magic1, struct.unpack_from('<I', block, 508)[0]) != (0x0A324655, 0x9E5D5157, 0x0AB16F30):
            raise ValueError('Invalid UF2 magic')
        if flags != 0x2000 or family != FAMILY:
            raise ValueError('Expected ordinary nRF52840 family-tagged flash blocks')
        if not 0 < size <= 476 or size % 4 or address % 4:
            raise ValueError('Invalid payload size or alignment')
        if address < APP_START or address + size > APP_END:
            raise ValueError('Payload lies outside the S140 v6 application partition')
        if total != count or number >= count or number in numbers:
            raise ValueError('Inconsistent or duplicate UF2 block number')
        numbers.add(number)
        ranges.append((address, address + size))
        if address == APP_START and size >= 8:
            vectors = struct.unpack_from('<2I', block, 32)
    ranges.sort()
    for previous, current in zip(ranges, ranges[1:]):
        if previous[1] > current[0]:
            raise ValueError('Overlapping flash payloads')
    if ranges[0][0] != APP_START:
        raise ValueError('Application vector table is not at the expected start')
    if vectors is None:
        raise ValueError('Application vector table is missing')
    stack, reset = vectors
    if not 0x20000000 < stack <= 0x20040000 or stack % 8:
        raise ValueError('Initial stack pointer is outside aligned nRF52840 RAM')
    target = reset & ~1
    if not reset & 1 or not any(start <= target < end for start, end in ranges):
        raise ValueError('Thumb reset handler is not inside an application payload')
    return {'blocks': count, 'family': hex(FAMILY), 'start': hex(ranges[0][0]),
            'endExclusive': hex(ranges[-1][1]), 'allowedEndExclusive': hex(APP_END),
            'initialStack': hex(stack), 'resetHandler': hex(reset),
            'partitionCheckPassed': True, 'hardwareTested': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('uf2', type=pathlib.Path)
    parser.add_argument('--report', type=pathlib.Path)
    args = parser.parse_args()
    report = json.dumps(inspect(args.uf2.read_bytes()), indent=2) + '\n'
    if args.report:
        args.report.write_text(report, encoding='utf-8')
    print(report, end='')
