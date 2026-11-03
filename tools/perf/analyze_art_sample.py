"""Attribute a dual-clock ART sample inside compiler.prepare; not a latency benchmark."""
import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import struct


def analyze(path):
    blob = path.read_bytes()
    start = blob.index(b'*end\n') + 5
    methods = {}
    section = ''
    for line in blob[:start].decode('utf-8', errors='replace').splitlines():
        if line.startswith('*'):
            section = line
        elif section == '*methods':
            fields = line.split('\t')
            if len(fields) >= 3:
                methods[int(fields[0], 16)] = fields[1] + '.' + fields[2]
    magic, version, offset = struct.unpack_from('<IHH', blob, start)
    size = struct.unpack_from('<H', blob, start + 16)[0]
    if magic != 0x574f4c53 or version != 3 or size != 14:
        raise ValueError('Expected ART v3 dual-clock 14-byte records')
    events = defaultdict(list)
    for at in range(start + offset, len(blob) - size + 1, size):
        tid, word, cpu, wall = struct.unpack_from('<HIII', blob, at)
        events[tid].append((cpu, wall, word & ~3, word & 3))
    inclusive, exclusive = Counter(), Counter()
    total = errors = 0
    for thread in events.values():
        stack = []
        previous = None
        for cpu, wall, method, action in sorted(thread, key=lambda event: event[:2]):
            names = [methods.get(item, hex(item)) for item in stack]
            if previous is not None and any(name.endswith('NativeGlossaryCompiler.prepare') for name in names):
                delta = cpu - previous
                total += delta
                exclusive[names[-1]] += delta
                root = next(index for index, name in enumerate(names) if name.endswith('NativeGlossaryCompiler.prepare'))
                for name in set(names[root:]):
                    inclusive[name] += delta
            if action == 0:
                stack.append(method)
            elif action in (1, 2):
                if stack and stack[-1] == method:
                    stack.pop()
                else:
                    errors += 1
                    if method in stack:
                        del stack[len(stack) - 1 - stack[::-1].index(method):]
            previous = cpu

    def ranked(counter):
        return [dict(method=name, cpuMs=value / 1000, percent=100 * value / total if total else 0)
                for name, value in counter.most_common(40)]
    return dict(scope='Sampled thread CPU within compiler.prepare, not removable time or popup latency',
                cpuMs=total / 1000, stackErrors=errors, topSelf=ranked(exclusive), topInclusive=ranked(inclusive))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('trace', type=Path)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    result = analyze(args.trace)
    args.out.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
