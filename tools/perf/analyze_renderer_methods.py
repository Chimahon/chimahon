"""Summarize sampled ART CPU inside NativeGlossaryCompiler.prepare, not benchmark latency."""
import argparse
import collections
import json
from pathlib import Path
import struct


def analyze(path):
    blob = path.read_bytes()
    start = blob.index(b"*end\n") + 5
    methods, section = {}, ""
    for line in blob[:start].decode("utf-8", errors="replace").splitlines():
        if line.startswith("*"):
            section = line
        elif section == "*methods":
            fields = line.split("\t")
            if len(fields) >= 3:
                methods[int(fields[0], 16)] = fields[1] + "." + fields[2]
    _, version, offset = struct.unpack_from("<IHH", blob, start)
    size = struct.unpack_from("<H", blob, start + 16)[0]
    if version != 3 or size != 14:
        raise ValueError(f"Unsupported ART trace version={version} record={size}")
    stacks, previous = {}, {}
    inclusive, own, parents = collections.Counter(), collections.Counter(), collections.Counter()
    total, errors = 0, 0
    for at in range(start + offset, len(blob) - size + 1, size):
        tid, word, cpu, _ = struct.unpack_from("<HIII", blob, at)
        method, action = word & ~3, word & 3
        stack = stacks.setdefault(tid, [])
        if tid in previous and any(methods.get(m, "").endswith("NativeGlossaryCompiler.prepare") for m in stack):
            dt = cpu - previous[tid]
            if dt < 0:
                raise ValueError("Non-monotonic thread CPU")
            total += dt
            own[methods.get(stack[-1], str(stack[-1]))] += dt
            for m in set(stack):
                inclusive[methods.get(m, str(m))] += dt
            for i in range(len(stack) - 1, -1, -1):
                name = methods.get(stack[i], "")
                if ".compose." in name:
                    parents[name] += dt
                    break
        if action == 0:
            stack.append(method)
        elif action in (1, 2):
            if stack and stack[-1] == method:
                stack.pop()
            else:
                errors += 1
        previous[tid] = cpu
    def rows(counter, limit=40):
        return [dict(method=n, cpuMs=round(us / 1000, 3), percent=round(us * 100 / total, 2))
                for n, us in counter.most_common(limit)] if total else []
    return dict(scope="Sampled compiler CPU only; NOT unsampled timing or exact function call counts",
                compilerCpuMs=total / 1000, stackErrors=errors, topSelf=rows(own),
                nearestRenderer=rows(parents), inclusiveRenderer=rows(collections.Counter({
                    n: us for n, us in inclusive.items() if ".compose." in n})))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        parser.error("Output exists")
    result = analyze(args.trace)
    args.out.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))
