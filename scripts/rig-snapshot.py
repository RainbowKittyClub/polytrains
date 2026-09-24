#!/usr/bin/env python3
"""Snapshots a box of the dev world into a vanilla structure template, read from the region files.

    python3 scripts/rig-snapshot.py              the torture track, to scripts/mtxrig/structures/
    python3 scripts/rig-snapshot.py --from=-21,-58,-29 --to=2,-45,0 --out /tmp/track.nbt

A corner with a negative x needs the = form, or argparse reads it as an option.

A structure block triggered by redstone saves to memory only (StructureBlock.java:87-88) and its GUI
save needs a client, so this reads the chunks instead. It asks the server for save-all flush first
when rcon answers, since changes not yet saved exist only in memory. Every block of the box goes in,
air included, with its block entity (the signs and their text); entities do not. Python 3, standard
library only. Restore with scripts/rig-sync.sh, then /place template (see that script).
"""

import argparse
import gzip
import struct
import sys
import zlib
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
import mtxrcon  # noqa: E402

TRACK_FROM = (-21, -58, -29)
TRACK_TO = (2, -45, 0)
DATA_VERSION = 4903
DEFAULT_OUT = Path(__file__).resolve().parent / "mtxrig" / "structures" / "torturetrack.nbt"

END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY, \
    LONG_ARRAY = range(13)
FIXED = {BYTE: ">b", SHORT: ">h", INT: ">i", LONG: ">q", FLOAT: ">f", DOUBLE: ">d"}


# ---- NBT, kept as (tag, value) pairs with strings as raw bytes -----------------------------------
# Java writes modified UTF-8, which a str round trip can corrupt, so sign text is copied as bytes.

class Reader:
    def __init__(self, data):
        self.data = data
        self.at = 0

    def take(self, count):
        out = self.data[self.at:self.at + count]
        self.at += count
        return out

    def num(self, fmt):
        return struct.unpack(fmt, self.take(struct.calcsize(fmt)))[0]

    def payload(self, tag):
        if tag in FIXED:
            return self.num(FIXED[tag])
        if tag == STRING:
            return self.take(self.num(">H"))
        if tag == BYTE_ARRAY:
            return self.take(self.num(">i"))
        if tag in (INT_ARRAY, LONG_ARRAY):
            fmt = ">i" if tag == INT_ARRAY else ">q"
            return [self.num(fmt) for _ in range(self.num(">i"))]
        if tag == LIST:
            item = self.num(">b")
            return item, [self.payload(item) for _ in range(self.num(">i"))]
        if tag == COMPOUND:
            out = {}
            while True:
                inner = self.num(">b")
                if inner == END:
                    return out
                name = self.take(self.num(">H"))
                out[name] = (inner, self.payload(inner))
        raise ValueError(f"unknown NBT tag {tag}")

    def root(self):
        tag = self.num(">b")
        self.take(self.num(">H"))
        return tag, self.payload(tag)


def write(tag, value, parts):
    if tag in FIXED:
        parts.append(struct.pack(FIXED[tag], value))
    elif tag == STRING:
        parts.append(struct.pack(">H", len(value)) + value)
    elif tag == BYTE_ARRAY:
        parts.append(struct.pack(">i", len(value)) + value)
    elif tag in (INT_ARRAY, LONG_ARRAY):
        fmt = ">i" if tag == INT_ARRAY else ">q"
        parts.append(struct.pack(">i", len(value)) + b"".join(struct.pack(fmt, v) for v in value))
    elif tag == LIST:
        item, items = value
        parts.append(struct.pack(">bi", item, len(items)))
        for entry in items:
            write(item, entry, parts)
    elif tag == COMPOUND:
        for name, (inner, entry) in value.items():
            parts.append(struct.pack(">bH", inner, len(name)) + name)
            write(inner, entry, parts)
        parts.append(struct.pack(">b", END))
    else:
        raise ValueError(f"unknown NBT tag {tag}")


def text(value):
    return value.decode("utf-8", "replace")


# ---- region files --------------------------------------------------------------------------------

def read_chunk(region_dir, cx, cz, regions):
    """The chunk's root compound, or None if it was never generated. regions caches file bytes."""
    path = region_dir / f"r.{cx >> 5}.{cz >> 5}.mca"
    if path not in regions:
        regions[path] = path.read_bytes() if path.exists() else None
    data = regions[path]
    if data is None:
        return None
    index = (cx & 31) + (cz & 31) * 32
    offset = int.from_bytes(data[index * 4:index * 4 + 3], "big")
    if offset == 0:
        return None
    start = offset * 4096
    length = int.from_bytes(data[start:start + 4], "big")
    version = data[start + 4]
    if version & 0x80:
        payload = (region_dir / f"c.{cx}.{cz}.mcc").read_bytes()
    else:
        payload = data[start + 5:start + 4 + length]
    kind = version & 0x7F
    if kind == 2:
        raw = zlib.decompress(payload)
    elif kind == 1:
        raw = gzip.decompress(payload)
    elif kind == 3:
        raw = payload
    else:
        # LZ4 (4) is what region-file-compression=lz4 writes; the dev world uses deflate.
        raise SystemExit(f"chunk {cx},{cz} uses compression {kind}; this reader knows deflate, "
                         f"gzip and none (see scripts/idmigrate/anvil.py for LZ4)")
    tag, root = Reader(raw).root()
    return root


def section_states(section):
    """The section's 4096 palette indices in y, z, x order, and its palette."""
    states = section.get(b"block_states", (COMPOUND, {}))[1]
    palette = states.get(b"palette", (LIST, (COMPOUND, [])))[1][1]
    if b"data" not in states:
        return [0] * 4096, palette
    longs = [v & 0xFFFFFFFFFFFFFFFF for v in states[b"data"][1]]
    # Entries never straddle a long, so the width follows from how many longs hold 4096 of them.
    bits = next(b for b in range(1, 33) if -(-4096 // (64 // b)) == len(longs)
                and (1 << b) >= len(palette))
    per_long, mask = 64 // bits, (1 << bits) - 1
    return [(longs[i // per_long] >> ((i % per_long) * bits)) & mask for i in range(4096)], palette


def snapshot(region_dir, low, high):
    """Palette, blocks and block entities of the box, relative to low."""
    palette, keys, blocks, versions = [], {}, {}, set()
    entities, regions = {}, {}
    for cx in range(low[0] >> 4, (high[0] >> 4) + 1):
        for cz in range(low[2] >> 4, (high[2] >> 4) + 1):
            chunk = read_chunk(region_dir, cx, cz, regions)
            if chunk is None:
                raise SystemExit(f"chunk {cx},{cz} is not in {region_dir}")
            versions.add(chunk.get(b"DataVersion", (INT, None))[1])
            sections = {s[b"Y"][1]: s for s in chunk[b"sections"][1][1]}
            for entity in chunk.get(b"block_entities", (LIST, (COMPOUND, [])))[1][1]:
                pos = tuple(entity[k][1] for k in (b"x", b"y", b"z"))
                if all(low[i] <= pos[i] <= high[i] for i in range(3)):
                    entities[pos] = {k: v for k, v in entity.items()
                                     if k not in (b"x", b"y", b"z", b"keepPacked")}
            for sy in range(low[1] >> 4, (high[1] >> 4) + 1):
                if sy not in sections:
                    raise SystemExit(f"chunk {cx},{cz} has no section {sy}")
                indices, local = section_states(sections[sy])
                for x in range(max(low[0], cx * 16), min(high[0], cx * 16 + 15) + 1):
                    for z in range(max(low[2], cz * 16), min(high[2], cz * 16 + 15) + 1):
                        for y in range(max(low[1], sy * 16), min(high[1], sy * 16 + 15) + 1):
                            state = local[indices[((y & 15) * 16 + (z & 15)) * 16 + (x & 15)]]
                            key = state_key(state)
                            if key not in keys:
                                keys[key] = len(palette)
                                palette.append(state)
                            blocks[(x, y, z)] = keys[key]
    return palette, blocks, entities, versions


def state_key(state):
    properties = state.get(b"Properties", (COMPOUND, {}))[1]
    return state[b"Name"][1], tuple(sorted((k, v[1]) for k, v in properties.items()))


def template(palette, blocks, entities, low, high):
    entries = []
    for (x, y, z), state in sorted(blocks.items(), key=lambda item: (item[0][1], item[0][0],
                                                                     item[0][2])):
        entry = {b"pos": (LIST, (INT, [x - low[0], y - low[1], z - low[2]])),
                 b"state": (INT, state)}
        if (x, y, z) in entities:
            entry[b"nbt"] = (COMPOUND, entities[(x, y, z)])
        entries.append(entry)
    size = [high[i] - low[i] + 1 for i in range(3)]
    return {b"DataVersion": (INT, DATA_VERSION),
            b"size": (LIST, (INT, size)),
            b"palette": (LIST, (COMPOUND, list(palette))),
            b"blocks": (LIST, (COMPOUND, entries)),
            b"entities": (LIST, (END, []))}


def signs(entities):
    found = []
    for pos, data in sorted(entities.items()):
        front = data.get(b"front_text", (COMPOUND, {}))[1]
        messages = front.get(b"messages", (LIST, (STRING, [])))[1][1]
        # A plain line is a string; a styled one is a compound with its words under "text".
        lines = [text(m) if isinstance(m, bytes)
                 else text(m.get(b"text", (STRING, b"?"))[1]) if isinstance(m, dict) else "?"
                 for m in messages]
        found.append((pos, text(data.get(b"id", (STRING, b"?"))[1]), lines))
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    point = lambda s: tuple(int(v) for v in s.split(","))  # noqa: E731
    parser.add_argument("--from", dest="low", type=point, default=TRACK_FROM,
                        help="x,y,z corner, as --from=x,y,z when x is negative")
    parser.add_argument("--to", dest="high", type=point, default=TRACK_TO,
                        help="x,y,z corner, as --to=x,y,z when x is negative")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help="template file to write")
    parser.add_argument("--run-dir", type=Path, default=None,
                        help="the dev server's run/ (default: the main checkout's)")
    parser.add_argument("--no-flush", action="store_true", help="skip save-all flush over rcon")
    args = parser.parse_args()

    low = tuple(min(a, b) for a, b in zip(args.low, args.high))
    high = tuple(max(a, b) for a, b in zip(args.low, args.high))
    if any(high[i] - low[i] + 1 > 48 for i in range(3)):
        raise SystemExit("a structure template is at most 48 blocks on a side")
    run_dir = args.run_dir or mtxrcon.default_run_dir()
    try:
        props = mtxrcon.read_properties(run_dir)
    except OSError as e:
        raise SystemExit(f"cannot read {run_dir}/server.properties: {e}")
    region_dir = run_dir / props.get("level-name", "world") / "dimensions" / "minecraft" / \
        "overworld" / "region"

    if not args.no_flush:
        try:
            with mtxrcon.connect(run_dir) as rcon:
                print(rcon.cmd("save-all flush").strip() or "save-all flush sent")
        except mtxrcon.RconDead as e:
            print(f"no save-all flush ({e}); reading what is on disk")

    palette, blocks, entities, versions = snapshot(region_dir, low, high)
    if versions != {DATA_VERSION}:
        print(f"WARNING: chunk DataVersion {sorted(versions)}, template written as {DATA_VERSION}")
    parts = []
    write(COMPOUND, template(palette, blocks, entities, low, high), parts)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    root = struct.pack(">bH", COMPOUND, 0) + b"".join(parts)
    # mtime 0 keeps the bytes identical for identical blocks, so an unchanged track diffs clean.
    args.out.write_bytes(gzip.compress(root, mtime=0))

    counts = {}
    for index in blocks.values():
        name = text(palette[index][b"Name"][1])
        counts[name] = counts.get(name, 0) + 1
    size = "x".join(str(high[i] - low[i] + 1) for i in range(3))
    print(f"{args.out}: {size} from {','.join(map(str, low))}, {len(blocks)} blocks, "
          f"{len(palette)} states, {len(entities)} block entities")
    for name, number in sorted(counts.items(), key=lambda item: -item[1]):
        print(f"  {number:>6}  {name}")
    for pos, kind, lines in signs(entities):
        print(f"  {kind} at {pos}: {lines}")


if __name__ == "__main__":
    main()
