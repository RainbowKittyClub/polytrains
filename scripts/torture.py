#!/usr/bin/env python3
"""Sampler and verdict for the ten-minutes-clean bar (docs/plan/ten-minutes-clean.md, stage M5).

    python3 scripts/torture.py run            rig the torture track, sample 12,000 ticks, judge it
    python3 scripts/torture.py --selfcheck    90 s on abtestloop-a with a sever and a stall injected
    python3 scripts/torture.py --roster       place a group, print its trains, clear it again
    python3 scripts/torture.py --parse-test   the log parser and the clauses on synthetic input

Python 3, standard library only. It speaks rcon through mtxrcon.py beside it and reads the dev
server's logs/debug.log from a mark taken just before the rig call, so it runs from any checkout
against the main one's server. Series and verdict go to <run-dir>/torture/ as JSON.

Definitions this file settles beyond the plan's clause table:
  train     the carts joined by link slots; its id is the first 8 hex digits of the car whose
            UUID sorts lowest by java.util.UUID.compareTo, as the mod's Train.id() names it
  sever     a coupling present at the first sample and missing at a later one, or an event=sever
            line; a line naming a cart of a missing coupling within a sample of it is the same sever
  stall     a train with a furnace car whose every car stays within STALL_RADIUS of where it stood
            STALL_WINDOW ticks earlier, at every sample across that window; the window must hold
            throughout, or a train lapping a short loop at the sampling period reads as still
  queued    a stalled train with a car within QUEUE_REACH blocks of a car of another stalled train
            whose stall began at an earlier sample; it is named with the train it is queued behind
  lead end  the end car furthest along the train's drift over the stall window, else along its
            engines' push, else along its motion; "none" when all three are zero
  travel    per car, speed integrated over time from each sample's Motion; a train's travel is the
            median of its cars'. A chord sum between samples is kept as a lower bound, but at up to
            50 blocks a sample on loops of 26 it says nothing about distance run.
"""

import argparse
import collections
import datetime
import gzip
import json
import math
import os
import re
import statistics
import subprocess
import sys
import time
from pathlib import Path

# No __pycache__ beside a tracked script.
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
import mtxrcon  # noqa: E402

MOD_PACKAGE = "club.rainbowkitty.minecarttweaks"

TORTURE_GROUP = "torturetrack"
SELFCHECK_GROUP = "abtestloop-a"
TORTURE_BOX = (-24, -53, -30, 28, 32)
YARD_BOX = (-44, -53, -4, 25, 25)

RUN_TICKS = 12_000
SELFCHECK_TICKS = 1_800
SAMPLE_TICKS = 20

STALL_WINDOW = 600
STALL_RADIUS = 5.0
QUEUE_REACH = 3.0
STRIKE_WINDOW = 40

MIN_STRIKES = 10
MIN_TRAVEL = 100.0
MIN_CARTS = 35

# The plan cuts the coupling at 30 s and the fuel at 45 s. Measured on abtestloop-a, a train cut off
# at 2.5 b/t sheds 0.9 % a tick and takes about 450 ticks to settle inside STALL_RADIUS, so a stall
# from a 45 s cut would first show at 1950 ticks, after the run ends. The fuel goes first; the
# coupling is cut once the train is all but still, so the halves cannot run into each other even
# where the mod notices a link removed over NBT (at a6ccc2a it does not: Train is only invalidated
# by addNeighbour, removeNeighbour or a removed car).
FUEL_CUT_AT = 300
UNLINK_AT = 900

RCON_TIMEOUT = 30.0
# Game time standing still this long while rcon still answers means the server is not ticking.
FROZEN_SECONDS = 60.0

RULES = {"minecraft:max_minecart_speed": "100", "minecarttweaks:realistic_physics": "false"}

LINK_PATH = '"fabric:attachments"."minecarttweaks:link"'

UNCOUPLED = ("the rig left %d neighbouring pair(s) uncoupled (#unlinked), so the first sample's "
             "trains are not the consists the strips describe")


# ---- identities ----------------------------------------------------------------------------------

def signed64(value):
    return value - (1 << 64) if value >= 1 << 63 else value


def uuid_text(ints):
    """The UUID string of a Minecraft [I; a, b, c, d] int array."""
    a, b, c, d = (v & 0xFFFFFFFF for v in ints)
    digits = f"{a:08x}{b:08x}{c:08x}{d:08x}"
    return f"{digits[:8]}-{digits[8:12]}-{digits[12:16]}-{digits[16:20]}-{digits[20:]}"


def java_order(uuid):
    """Sort key of java.util.UUID.compareTo: the signed high half, then the signed low half."""
    digits = uuid.replace("-", "")
    return signed64(int(digits[:16], 16)), signed64(int(digits[16:], 16))


def short(uuid):
    """A cart's id in the log lines: the first 8 hex digits of its UUID."""
    return uuid[:8]


def train_id(uuids):
    """Train.id(): the id of the car whose UUID sorts lowest by UUID.compareTo."""
    return short(min(uuids, key=java_order))


# ---- the world, as rcon reads it -----------------------------------------------------------------

QUERY = " has the following entity data: "
NUMBER = r"[-+]?(?:NaN|Infinity|\d+(?:\.\d+)?(?:[eE][-+]?\d+)?)"
INT_ARRAY = r"\[I; (-?\d+), (-?\d+), (-?\d+), (-?\d+)\]"
RE_UUID = re.compile(r"\bUUID: " + INT_ARRAY)
RE_POS = re.compile(r"\bPos: \[(%s)d, (%s)d, (%s)d\]" % (NUMBER, NUMBER, NUMBER))
RE_MOTION = re.compile(r"\bMotion: \[(%s)d, (%s)d, (%s)d\]" % (NUMBER, NUMBER, NUMBER))
RE_LINK = re.compile(r'"minecarttweaks:link": \{([^{}]*)\}')
RE_SLOT = re.compile(r"(slot[AB]): " + INT_ARRAY)
RE_FUEL = re.compile(r"\bRealFuel: (-?\d+)")
RE_PUSH_X = re.compile(r"\bPushX: (%s)d" % NUMBER)
RE_PUSH_Z = re.compile(r"\bPushZ: (%s)d" % NUMBER)

KINDS = {"Minecart with Furnace": "F", "Minecart with Chest": "C", "Minecart with Hopper": "H",
         "Minecart": "M"}


class Cart:
    """One cart as a data get entity dump shows it."""

    def __init__(self, uuid, kind, pos, motion, slots, fuel=None, push=None):
        self.uuid = uuid
        self.kind = kind
        self.pos = pos
        self.motion = motion
        self.slots = slots
        self.fuel = fuel
        self.push = push

    def speed(self):
        return math.hypot(self.motion[0], self.motion[2])

    def cell(self):
        return tuple(math.floor(v) if math.isfinite(v) else 0 for v in self.pos)


def top_level(pattern, body):
    # The first match sitting directly in the entity's own compound, not in a nested one.
    for match in pattern.finditer(body):
        prefix = body[:match.start()]
        if prefix.count("{") - prefix.count("}") == 1:
            return match
    return None


def vector(match):
    return tuple(float(v) for v in match.groups()) if match else (math.nan,) * 3


def parse_dump(reply):
    """Carts by UUID from the reply to `execute as <carts> run data get entity @s`."""
    carts = {}
    # Rcon joins the per-entity replies with no separator; each data compound ends in "}" and the
    # next entity's display name follows it.
    pieces = reply.split(QUERY)
    for i in range(1, len(pieces)):
        before = pieces[i - 1]
        name = before if i == 1 else before[before.rfind("}") + 1:]
        body = pieces[i] if i == len(pieces) - 1 else pieces[i][:pieces[i].rfind("}") + 1]
        uuid_match = top_level(RE_UUID, body)
        if uuid_match is None:
            continue
        uuid = uuid_text(int(v) for v in uuid_match.groups())
        slots = {}
        link = RE_LINK.search(body)
        if link:
            for slot, *ints in RE_SLOT.findall(link.group(1)):
                slots[slot] = uuid_text(int(v) for v in ints)
        fuel = RE_FUEL.search(body)
        push_x, push_z = RE_PUSH_X.search(body), RE_PUSH_Z.search(body)
        carts[uuid] = Cart(
            uuid, KINDS.get(name.strip(), "X"), vector(top_level(RE_POS, body)),
            vector(top_level(RE_MOTION, body)), slots,
            fuel=int(fuel.group(1)) if fuel else None,
            push=(float(push_x.group(1)), float(push_z.group(1))) if push_x and push_z else None)
    return carts


def edges_of(carts):
    """The couplings the carts' link slots name, as unordered UUID pairs."""
    return frozenset(frozenset((uuid, other)) for uuid, cart in carts.items()
                     for other in cart.slots.values() if other != uuid)


def chains(carts, edges):
    """The trains among carts, each ordered along its chain from its north-west end."""
    adjacent = {uuid: set() for uuid in carts}
    for edge in edges:
        a, b = tuple(edge)
        if a in adjacent and b in adjacent:
            adjacent[a].add(b)
            adjacent[b].add(a)
    seen, found = set(), []
    for start in sorted(carts, key=java_order):
        if start in seen:
            continue
        group, stack = [], [start]
        while stack:
            uuid = stack.pop()
            if uuid not in seen:
                seen.add(uuid)
                group.append(uuid)
                stack.extend(adjacent[uuid] - seen)
        found.append(order_chain(group, adjacent, carts))
    return found


def order_chain(group, adjacent, carts):
    ends = [uuid for uuid in group if len(adjacent[uuid]) <= 1] or group
    first = min(ends, key=lambda u: (carts[u].cell()[2], carts[u].cell()[0], java_order(u)))
    order, seen = [first], {first}
    while True:
        after = sorted(v for v in adjacent[order[-1]] if v not in seen)
        if not after:
            break
        order.append(after[0])
        seen.add(after[0])
    # A branch is not a chain: listed in UUID order, so it still reads as one group.
    return order if len(order) == len(group) else sorted(group, key=java_order)


class Train:
    """One train in one sample."""

    def __init__(self, uuids, carts):
        self.uuids = uuids
        self.id = train_id(uuids)
        self.kinds = "".join(carts[u].kind for u in uuids)
        self.driven = "F" in self.kinds


class Sample:
    """Every cart of the group at one game tick."""

    def __init__(self, tick, wall, carts):
        self.tick = tick
        self.wall = wall
        self.carts = carts
        self.edges = edges_of(carts)
        self.trains = [Train(chain, carts) for chain in chains(carts, self.edges)]


def gametime(rcon):
    reply = rcon.cmd("time query gametime")
    match = re.search(r"(\d+)", reply)
    if match is None:
        raise mtxrcon.RconDead(f"unreadable game time: {reply!r}")
    return int(match.group(1))


def take_sample(rcon, group, tick):
    reply = rcon.cmd(f"execute as @e[tag=mtx_{group}] run data get entity @s")
    return Sample(tick, time.time(), parse_dump(reply))


def passed(reply):
    return reply.startswith("Test passed")


RAIL_BLOCKS = ("minecarttweaks:switch_rail", "minecarttweaks:junction_rail",
               "minecraft:activator_rail", "minecraft:powered_rail", "minecraft:detector_rail",
               "minecraft:rail")
RAIL_SHAPES = ("north_south", "east_west", "ascending_east", "ascending_west", "ascending_north",
               "ascending_south", "south_east", "south_west", "north_west", "north_east")


def rail_at(rcon, pos):
    """The rail a cart at pos stands on, as <block>[(on)|(off)] <shape> at <x,y,z>."""
    if not all(math.isfinite(v) for v in pos):
        return "no position"
    x, y, z = (math.floor(v) for v in pos)
    for dy in (0, -1):
        where = f"{x} {y + dy} {z}"
        reply = rcon.cmd(f"execute if block {where} #minecraft:rails")
        if "not loaded" in reply:
            return f"unloaded at {x},{y + dy},{z}"
        if not passed(reply):
            continue
        block = next((b for b in RAIL_BLOCKS
                      if passed(rcon.cmd(f"execute if block {where} {b}"))), "rail?")
        shape = next((s for s in RAIL_SHAPES
                      if passed(rcon.cmd(f"execute if block {where} #minecraft:rails[shape={s}]"))),
                     "?")
        # A block without POWERED answers with a parse error, which is neither result.
        powered = rcon.cmd(f"execute if block {where} {block}[powered=true]")
        state = "(on)" if passed(powered) else "(off)" if powered.startswith("Test failed") else ""
        return f"{block}{state} {shape} at {x},{y + dy},{z}"
    return f"no rail at {x},{y},{z}"


# ---- what the samples show -----------------------------------------------------------------------

class SeverWatch:
    """Couplings that differ from the first sample's."""

    def __init__(self):
        self.first = None
        self.first_kinds = {}
        self.last_tick = None
        self.vanished = {}
        self.added = {}

    def add(self, sample):
        if self.first is None:
            self.first = sample.edges
            self.first_kinds = {uuid: cart.kind for uuid, cart in sample.carts.items()}
            self.last_tick = sample.tick
            return
        for edge in self.first - sample.edges:
            if edge not in self.vanished:
                a, b = sorted(edge, key=java_order)
                self.vanished[edge] = {
                    "cars": [short(a), short(b)], "uuids": [a, b],
                    "kinds": self.first_kinds.get(a, "?") + self.first_kinds.get(b, "?"),
                    "last_seen": self.last_tick, "tick": sample.tick, "restored": None}
        for edge, record in self.vanished.items():
            if record["restored"] is None and edge in sample.edges:
                record["restored"] = sample.tick
        for edge in sample.edges - self.first:
            if edge not in self.added:
                a, b = sorted(edge, key=java_order)
                self.added[edge] = {"cars": [short(a), short(b)], "uuids": [a, b],
                                    "tick": sample.tick}
        self.last_tick = sample.tick


class StallWatch:
    """Driven trains that have stood inside STALL_RADIUS for STALL_WINDOW ticks."""

    def __init__(self, probe=None):
        self.window = collections.deque()
        self.active = {}
        self.episodes = []
        self.probe = probe

    def add(self, sample):
        self.window.append(sample)
        while len(self.window) > 1 and self.window[1].tick <= sample.tick - STALL_WINDOW:
            self.window.popleft()
        stalled = {}
        if sample.tick - self.window[0].tick >= STALL_WINDOW:
            for train in sample.trains:
                if train.driven and self.held_still(train):
                    stalled[train.id] = train
        for tid in [t for t in self.active if t not in stalled]:
            self.active.pop(tid)["end_tick"] = sample.tick
        for tid, train in stalled.items():
            if tid not in self.active:
                episode = self.open(train, sample, stalled)
                self.active[tid] = episode
                self.episodes.append(episode)

    def held_still(self, train):
        origin = self.window[0]
        for uuid in train.uuids:
            start = origin.carts.get(uuid)
            if start is None:
                return False
            for later in list(self.window)[1:]:
                cart = later.carts.get(uuid)
                # Written as "not within" so a NaN position counts as moved, not as still.
                if cart is None or not math.dist(cart.pos, start.pos) <= STALL_RADIUS:
                    return False
        return True

    def open(self, train, sample, stalled):
        queued_behind = sorted(
            other_id for other_id, other in stalled.items()
            if other_id != train.id and other_id in self.active
            and self.active[other_id]["start_tick"] < sample.tick
            and gap(train, other, sample) <= QUEUE_REACH)
        lead, heading = leading_end(train, sample, self.window[0])
        ends = [lead] if lead else sorted({train.uuids[0], train.uuids[-1]})
        rails = {short(u): self.probe(sample.carts[u].pos) for u in ends} if self.probe else {}
        return {"train": train.id, "kinds": train.kinds, "cars": list(train.uuids),
                "start_tick": sample.tick, "end_tick": None, "queued_behind": queued_behind,
                "lead": short(lead) if lead else None, "heading": heading, "rails": rails}


def gap(train, other, sample):
    return min(math.dist(sample.carts[a].pos, sample.carts[b].pos)
               for a in train.uuids for b in other.uuids)


def leading_end(train, sample, origin):
    if len(train.uuids) == 1:
        return train.uuids[0], "single car"
    ends = (train.uuids[0], train.uuids[-1])
    cars = [sample.carts[u] for u in train.uuids]
    centre = [statistics.fmean(c.pos[i] for c in cars) for i in (0, 2)]
    drift = [statistics.fmean(sample.carts[u].pos[i] - origin.carts[u].pos[i]
                              for u in train.uuids) for i in (0, 2)]
    push = [sum(c.push[i] for c in cars if c.push) for i in (0, 1)]
    motion = [statistics.fmean(c.motion[i] for c in cars) for i in (0, 2)]
    for heading, towards, least in (("drift", drift, 0.1), ("push", push, 1e-6),
                                    ("motion", motion, 1e-4)):
        if math.hypot(*towards) >= least:
            def along(uuid, towards=towards):
                pos = sample.carts[uuid].pos
                return (pos[0] - centre[0]) * towards[0] + (pos[2] - centre[1]) * towards[1]
            return max(ends, key=along), heading
    return None, "none"


def travel(samples, uuids):
    """Per car: speed integrated over the run, and the chord sum between samples."""
    out = {}
    for uuid in uuids:
        run = chord = 0.0
        previous = None
        for sample in samples:
            cart = sample.carts.get(uuid)
            if cart is None:
                continue
            if previous is not None:
                ticks = sample.tick - previous[0]
                speeds = (previous[1].speed(), cart.speed())
                if all(math.isfinite(s) for s in speeds):
                    run += (speeds[0] + speeds[1]) / 2 * ticks
                step = math.dist(previous[1].pos, cart.pos)
                if math.isfinite(step):
                    chord += step
            previous = (sample.tick, cart)
        out[uuid] = (run, chord)
    return out


# ---- the log -------------------------------------------------------------------------------------

# Thread names hold "/" (RCON Client /127.0.0.1) but never "]", and logger names never ")".
LOG_LINE = re.compile(r"^\[(\d\d:\d\d:\d\d)\] \[([^\]]*)/([A-Z]+)\] \(([^)]*)\) (.*)$")
TOKEN = re.compile(r"^([a-z][a-z_]*)=(\S*)$")
FRAME = re.compile(r"^\s+at |^Caused by: ")
MOD_FRAME = re.compile(r"club\.rainbowkitty\.minecarttweaks|minecarttweaks\$")

EVENT_KEYS = {
    "sever": ("tick", "train", "at", "reason", "into"),
    "stall_reverse": ("tick", "train", "cart", "pos", "rail"),
    "strike": ("tick", "striker", "struck", "trains", "closing", "shared", "gained"),
    "overlap": ("tick", "cart", "other", "trains", "distance"),
    "overspeed": ("tick", "cart", "train", "speed", "ceiling", "clamped"),
    "switch": ("tick", "pos", "from", "to", "spanned", "applied"),
    "refusal": ("tick", "train", "why", "car"),
}
METER_KEYS = {
    "train": ("tick", "train", "cars", "kinds", "lead", "pos", "speed", "maxspeed", "fuel", "rail",
              "seated", "refusal", "packing", "mass", "pairs", "gap_worst", "gap_mean", "turn",
              "nodes", "span", "checked", "offset_worst", "resets", "revisits"),
    "cart": ("tick", "cart", "kind", "pos", "speed", "maxspeed", "rail"),
}


def num(text):
    """A contract number: %.2f, %.3e, NaN or +-Infinity; None for n/a or a missing token."""
    try:
        return float(text)
    except (TypeError, ValueError):
        return None


class LogMark:
    """Where debug.log stood just before the rig call."""

    def __init__(self, path):
        self.path = Path(path)
        self.inode, self.offset, self.line = None, 0, 0
        try:
            stat = self.path.stat()
            self.inode, self.offset = stat.st_ino, stat.st_size
            # Counted in blocks: debug.log only rolls at 200 MB, too much to hold for a line number.
            with open(self.path, "rb") as f:
                left = self.offset
                while left > 0:
                    block = f.read(min(left, 1 << 20))
                    if not block:
                        break
                    self.line += block.count(b"\n")
                    left -= len(block)
        except FileNotFoundError:
            pass

    def read(self):
        """The log written since the mark, and whether debug.log had rolled over in between."""
        try:
            stat = self.path.stat()
        except FileNotFoundError:
            stat = None
        if stat is not None and stat.st_ino == self.inode and stat.st_size >= self.offset:
            with open(self.path, "rb") as f:
                f.seek(self.offset)
                return f.read().decode("utf-8", "replace"), False
        # Rolled at startup or at 200 MB: Loom's appender keeps the marked file as debug-1.log.gz.
        data = b""
        rolled = self.path.with_name("debug-1.log.gz")
        if rolled.exists():
            with gzip.open(rolled, "rb") as f:
                data = f.read()[self.offset:]
        if stat is not None:
            data += self.path.read_bytes()
        return data.decode("utf-8", "replace"), True


class LogReport:
    """The contract lines, errors and stack traces in a stretch of debug.log."""

    def __init__(self):
        self.lines = 0
        self.meters = {"train": [], "cart": []}
        self.events = {kind: [] for kind in EVENT_KEYS}
        self.malformed = []
        self.unknown = []
        self.mod_errors = []
        self.other_errors = []
        self.mod_traces = []
        self.other_traces = []

    def summary(self):
        return {
            "lines": self.lines,
            "meter_lines": {kind: len(v) for kind, v in self.meters.items()},
            "events": {kind: len(v) for kind, v in self.events.items()},
            "malformed": self.malformed[:50], "unknown": self.unknown[:50],
            "mod_errors": self.mod_errors[:50], "other_errors": self.other_errors[:50],
            "mod_traces": self.mod_traces[:20], "other_traces": self.other_traces[:20]}


def tokens_of(words):
    tokens = {}
    for i, word in enumerate(words):
        match = TOKEN.match(word)
        if match is None:
            return tokens, " ".join(words[i:])
        tokens[match.group(1)] = match.group(2)
    return tokens, ""


def parse_log(text):
    """Reads meter and event lines, ERROR lines and stack traces out of debug.log text."""
    report = LogReport()
    owner, block = None, []

    def close_block():
        if owner is None or not any(FRAME.match(line) for line in block):
            return
        trace = {"line": owner["line"], "logger": owner["logger"], "level": owner["level"],
                 "message": owner["message"][:300], "trace": block[:12]}
        through_mod = owner["logger"].startswith(MOD_PACKAGE) or any(
            MOD_FRAME.search(line) for line in block)
        (report.mod_traces if through_mod else report.other_traces).append(trace)

    for number, line in enumerate(text.splitlines(), 1):
        report.lines += 1
        match = LOG_LINE.match(line)
        if match is None:
            if owner is not None:
                block.append(line)
            continue
        close_block()
        _, _, level, logger, message = match.groups()
        owner, block = {"line": number, "logger": logger, "level": level, "message": message}, []
        from_mod = logger.startswith(MOD_PACKAGE)
        if level in ("ERROR", "FATAL"):
            (report.mod_errors if from_mod else report.other_errors).append(
                {"line": number, "logger": logger, "message": message[:300]})
        if not from_mod:
            continue
        if message.startswith("meter "):
            read_meter(report, number, message)
        elif message.startswith("event="):
            read_event(report, number, message)
    close_block()
    return report


def read_meter(report, number, message):
    tokens, _ = tokens_of(message.split(" ")[1:])
    kind = "train" if "train" in tokens else "cart" if "cart" in tokens else None
    missing = [k for k in METER_KEYS[kind] if k not in tokens] if kind else ["train|cart"]
    tick = tokens.get("tick", "")
    if missing or not tick.isdigit():
        report.malformed.append({"line": number, "kind": f"meter {kind}", "missing": missing,
                                 "text": message[:200]})
    if kind and tick.isdigit():
        report.meters[kind].append({"line": number, **tokens, "tick": int(tick)})


def read_event(report, number, message):
    tokens, prose = tokens_of(message.split(" "))
    kind = tokens.get("event")
    if kind not in EVENT_KEYS:
        report.unknown.append({"line": number, "text": message[:200]})
        return
    missing = [k for k in EVENT_KEYS[kind] if k not in tokens]
    tick = tokens.get("tick", "")
    if missing or not tick.isdigit():
        report.malformed.append({"line": number, "kind": kind, "missing": missing,
                                 "text": message[:200]})
    if tick.isdigit():
        report.events[kind].append({"line": number, **tokens, "tick": int(tick), "prose": prose})


# ---- the verdict ---------------------------------------------------------------------------------

class Run:
    """Everything one sampling session collected, for judging and for the JSON."""

    def __init__(self, mode, group, box, duration):
        self.mode = mode
        self.group = group
        self.box = box
        self.duration = duration
        self.start_tick = None
        self.last_tick = None
        self.start_wall = None
        self.samples = []
        self.severs = SeverWatch()
        self.stalls = StallWatch()
        self.rcon_error = None
        self.frozen = False
        self.interrupted = False
        self.crash_reports = []
        self.injections = []
        self.log = LogReport()
        self.log_rolled = False
        self.meta = {}

    def add(self, sample):
        self.samples.append(sample)
        self.last_tick = sample.tick
        self.severs.add(sample)
        self.stalls.add(sample)

    def rel(self, tick):
        return f"+{tick - self.start_tick}" if tick is not None else "?"


def clause(problems, passing, details=None):
    return {"verdict": "FAIL" if problems else "PASS",
            "summary": "; ".join(problems) if problems else passing, "details": details or []}


def judge(run):
    """The five clauses, and for a run the three vacuity checks, as the plan's table has them."""
    log = run.log
    elapsed = (run.last_tick - run.start_tick) if run.last_tick is not None else 0
    verdict = {}

    problems = []
    if run.rcon_error:
        problems.append(f"rcon stopped answering at +{elapsed} of {run.duration} ticks: "
                        f"{run.rcon_error}")
    if run.frozen:
        problems.append(f"game time stood at +{elapsed} for {FROZEN_SECONDS:.0f} s")
    if run.interrupted:
        problems.append(f"interrupted at +{elapsed} of {run.duration} ticks")
    if run.crash_reports:
        problems.append("crash report " + ", ".join(run.crash_reports))
    if log.mod_errors:
        problems.append(f"{len(log.mod_errors)} ERROR line(s) from the mod")
    if log.mod_traces:
        problems.append(f"{len(log.mod_traces)} stack trace(s) through the mod")
    note = (f"; {len(log.other_errors)} ERROR line(s) elsewhere, not judged"
            if log.other_errors else "")
    verdict["Alive"] = clause(problems, f"ran to +{elapsed} ticks; no crash report; no ERROR or "
                                        f"stack trace from the mod{note}",
                              log.mod_errors[:10] + log.mod_traces[:5])

    severs = [dict(record, source="links") for record in run.severs.vanished.values()]
    for event in log.events["sever"]:
        match = next((s for s in severs if s["source"] == "links" and "event" not in s
                      and event.get("at") in s["cars"]
                      and s["last_seen"] - SAMPLE_TICKS <= event["tick"]
                      <= s["tick"] + SAMPLE_TICKS), None)
        facts = {k: event.get(k) for k in ("tick", "train", "at", "reason", "into")}
        if match is not None:
            match["event"] = facts
        else:
            severs.append(dict(facts, source="event"))
    severs.sort(key=lambda s: s["tick"])
    added = sorted(run.severs.added.values(), key=lambda a: a["tick"])
    problems = []
    if severs:
        problems.append(f"{len(severs)} sever(s): "
                        + ", ".join(describe_sever(run, s) for s in severs[:6]))
    if added:
        problems.append(f"{len(added)} coupling(s) not present at the first sample: " + ", ".join(
            f"{'-'.join(a['cars'])} at {run.rel(a['tick'])}" for a in added[:6]))
    held = len(run.severs.first or ())
    verdict["Sever"] = clause(problems, f"{held} couplings held at every sample; no event=sever",
                              severs + [dict(a, source="new coupling") for a in added])
    verdict["Sever"]["count"] = len(severs)

    episodes = run.stalls.episodes
    for episode in episodes:
        attach_meter(episode, log)
    problems = []
    primary = [e for e in episodes if not e["queued_behind"]]
    queued = [e for e in episodes if e["queued_behind"]]
    if primary:
        problems.append(f"{len(primary)} stalled: "
                        + ", ".join(describe_stall(run, e) for e in primary[:6]))
    if queued:
        problems.append(f"{len(queued)} queued: " + ", ".join(
            f"{describe_stall(run, e)} behind {'+'.join(e['queued_behind'])}" for e in queued[:6]))
    verdict["Stall"] = clause(problems, f"no driven train stood within {STALL_RADIUS:g} blocks for "
                                        f"{STALL_WINDOW} ticks", episodes)
    verdict["Stall"]["count"] = len(episodes)

    strikes = log.events["strike"]
    near = []
    for sever in log.events["sever"]:
        close = [s for s in strikes if abs(sever["tick"] - s["tick"]) <= STRIKE_WINDOW]
        if close:
            strike = min(close, key=lambda s: abs(sever["tick"] - s["tick"]))
            trains = (strike.get("trains") or "").split(",")
            near.append({"sever_tick": sever["tick"], "sever_train": sever.get("train"),
                         "reason": sever.get("reason"), "strike_tick": strike["tick"],
                         "strike_trains": strike.get("trains"),
                         "same_train": sever.get("train") in trains})
    problems = []
    if log.events["overlap"]:
        problems.append(f"{len(log.events['overlap'])} overlap(s)")
    if log.events["overspeed"]:
        worst = max((num(e.get("speed")) for e in log.events["overspeed"]),
                    key=lambda v: -1 if v is None else (math.inf if math.isnan(v) else v))
        problems.append(f"{len(log.events['overspeed'])} overspeed(s), worst {worst}")
    if near:
        problems.append(f"{len(near)} sever(s) within {STRIKE_WINDOW} ticks of a strike")
    verdict["Bounce"] = clause(problems, f"0 overlap, 0 overspeed, 0 sever within {STRIKE_WINDOW} "
                                         f"ticks of a strike; {len(strikes)} strike(s)",
                               log.events["overlap"][:20] + log.events["overspeed"][:20] + near)

    thrown = [e for e in log.events["switch"]
              if e.get("spanned") == "true" and e.get("applied") == "true"]
    diverted = [e for e in log.events["sever"] if e.get("reason") == "DIVERTED"]
    deferred = [e for e in log.events["switch"]
                if e.get("spanned") == "true" and e.get("applied") == "false"]
    problems = []
    if thrown:
        problems.append(f"{len(thrown)} switch(es) thrown under a spanning train")
    if diverted:
        problems.append(f"{len(diverted)} DIVERTED sever(s)")
    verdict["Switch-safe"] = clause(
        problems, f"no switch thrown under a spanning train, no DIVERTED sever; "
                  f"{len(deferred)} spanned change(s) held", thrown + diverted)

    if run.mode == "run":
        verdict["vacuity"] = judge_vacuity(run, strikes)
    return verdict


def judge_vacuity(run, strikes):
    between = [s for s in strikes if len(set((s.get("trains") or "").split(","))) == 2]
    first, last = (run.samples[0], run.samples[-1]) if run.samples else (None, None)
    driven = [t for t in first.trains if t.driven] if first else []
    ran = {}
    for train in driven:
        per_car = travel(run.samples, train.uuids)
        ran[train.id] = {"kinds": train.kinds,
                         "travel": statistics.median(v[0] for v in per_car.values()),
                         "chord": statistics.median(v[1] for v in per_car.values())}
    short_runs = {tid: v for tid, v in ran.items() if v["travel"] < MIN_TRAVEL}
    carts = len(last.carts) if last else 0
    problems = []
    if len(between) < MIN_STRIKES:
        problems.append(f"{len(between)} strike(s) between trains, under {MIN_STRIKES}")
    if not driven:
        problems.append("no driven train at the first sample")
    if short_runs:
        problems.append(f"{len(short_runs)} driven train(s) under {MIN_TRAVEL:g} blocks: "
                        + ", ".join(f"{tid} {v['kinds']} {v['travel']:.0f}"
                                    for tid, v in short_runs.items()))
    if carts < MIN_CARTS:
        problems.append(f"{carts} cart(s) at the end, under {MIN_CARTS}")
    low = min((v["travel"] for v in ran.values()), default=0.0)
    high = max((v["travel"] for v in ran.values()), default=0.0)
    return clause(problems, f"{len(between)} strikes between trains; driven trains ran "
                            f"{low:.0f}-{high:.0f} blocks; {carts} carts at the end",
                  [dict(v, train=tid) for tid, v in ran.items()])


def describe_sever(run, sever):
    if sever["source"] == "links":
        text = f"{'-'.join(sever['cars'])} ({sever['kinds']}) gone at {run.rel(sever['tick'])}"
        if "event" in sever:
            text += f" [{sever['event'].get('reason')}]"
        return text
    return f"event at {run.rel(sever['tick'])} train {sever.get('train')} [{sever.get('reason')}]"


def describe_stall(run, episode):
    until = f"..{run.rel(episode['end_tick'])}" if episode["end_tick"] else " to the end"
    rails = "; ".join(f"{cart}: {rail}" for cart, rail in episode["rails"].items())
    refusal = episode.get("meter_refusal") or "n/a"
    return (f"{episode['train']} {episode['kinds']} {run.rel(episode['start_tick'])}{until} "
            f"(lead {episode['lead'] or 'none'} by {episode['heading']}; {rails or 'rail n/a'}; "
            f"meter refusal {refusal})")


def attach_meter(episode, log):
    meters = [m for m in log.meters["train"]
              if m.get("train") == episode["train"] and m["tick"] <= episode["start_tick"]]
    if meters:
        last = meters[-1]
        episode["meter_refusal"] = last.get("refusal")
        episode["meter_rail"] = last.get("rail")
        episode["meter_lead"] = last.get("lead")
        episode["meter_tick"] = last["tick"]
    refusals = [e for e in log.events["refusal"]
                if e.get("train") == episode["train"] and e["tick"] <= episode["start_tick"]]
    if refusals:
        episode["last_refusal"] = {k: refusals[-1].get(k) for k in ("tick", "why", "car", "prose")}


def overall(run, verdict):
    clauses = ("Alive", "Sever", "Stall", "Bounce", "Switch-safe")
    if any(verdict[c]["verdict"] == "FAIL" for c in clauses):
        return "FAIL"
    if run.mode == "run" and verdict["vacuity"]["verdict"] == "FAIL":
        return "NOT A TORTURE"
    return "PASS"


def judge_selfcheck(run, verdict):
    """Whether the verdict reports the injected sever and stall and nothing else."""
    problems = []
    for name in ("Alive", "Bounce", "Switch-safe"):
        if verdict[name]["verdict"] != "PASS":
            problems.append(f"{name} {verdict[name]['summary']}")
    injected = {i["what"]: i for i in run.injections}
    unlink, fuel = injected.get("unlink"), injected.get("fuel")
    if unlink is None or fuel is None:
        problems.append("injection missing: " + ", ".join(
            w for w in ("fuel", "unlink") if w not in injected))
    severs = [d for d in verdict["Sever"]["details"] if d.get("source") != "new coupling"]
    if len(severs) != 1 or len(verdict["Sever"]["details"]) != 1:
        problems.append(f"expected 1 sever, got {len(severs)} and "
                        f"{len(verdict['Sever']['details']) - len(severs)} new coupling(s)")
    elif unlink and sorted(severs[0].get("uuids", [])) != sorted(unlink["uuids"]):
        problems.append(f"the sever is {describe_sever(run, severs[0])}, not the injected "
                        f"{'-'.join(unlink['cars'])}")
    stalls = verdict["Stall"]["details"]
    if len(stalls) != 1:
        problems.append(f"expected 1 stall, got {len(stalls)}")
    elif fuel and fuel["uuid"] not in stalls[0]["cars"]:
        problems.append(f"the stalled train {stalls[0]['train']} does not hold the starved engine "
                        f"{short(fuel['uuid'])}")
    elif stalls[0]["queued_behind"]:
        problems.append("the stall is reported as queued")
    return problems


# ---- output --------------------------------------------------------------------------------------

def roster_lines(sample):
    lines = []
    def order(train):
        cell = sample.carts[train.uuids[0]].cell()
        return len(train.uuids), cell[2], cell[0]

    for train in sorted(sample.trains, key=order):
        cells = " ".join("%d,%d" % (sample.carts[u].cell()[0], sample.carts[u].cell()[2])
                         for u in train.uuids)
        lines.append(f"  {len(train.uuids)}  {'-'.join(train.kinds):<16} train {train.id}  {cells}")
    return lines


def print_verdict(run, verdict, result, selfcheck_problems=None):
    print()
    print(f"torture.py {run.mode}: group {run.group}, box {fmt_box(run.box)}, "
          f"commit {run.meta.get('commit', '?')}")
    if run.start_tick is not None:
        print(f"  rig at tick {run.start_tick}; {len(run.samples)} samples to "
              f"{run.rel(run.last_tick)} of {run.duration} ticks "
              f"({run.samples[-1].wall - run.start_wall:.0f} s)" if run.samples else "  no samples")
    if run.meta.get("coupling_warnings"):
        print(f"  WARNING: {UNCOUPLED % run.meta['coupling_warnings']}")
    for injection in run.injections:
        print(f"  injected at {run.rel(injection['tick'])}: {injection['text']}")
    print()
    print(f"  {'clause':<12} {'verdict':<8} detail")
    for name in ("Alive", "Sever", "Stall", "Bounce", "Switch-safe", "vacuity"):
        if name in verdict:
            print(f"  {name:<12} {verdict[name]['verdict']:<8} {verdict[name]['summary']}")
    print(f"  {'overall':<12} {result}")
    if selfcheck_problems is not None:
        print()
        if selfcheck_problems:
            print("  SELFCHECK FAIL: " + "; ".join(selfcheck_problems))
        else:
            print("  SELFCHECK PASS: exactly the injected sever and the injected stall")

    log = run.log
    counts = ", ".join(f"{k} {len(v)}" for k, v in log.events.items() if v) or "no event lines"
    print()
    print(f"  log: {log.lines} lines from the mark; meter train {len(log.meters['train'])}, "
          f"meter cart {len(log.meters['cart'])}; {counts}"
          + ("; debug.log had rolled over" if run.log_rolled else ""))
    if log.malformed or log.unknown:
        print(f"  WARNING: {len(log.malformed)} contract line(s) missing keys and "
              f"{len(log.unknown)} unknown event kind(s); Bounce and Switch-safe may be incomplete")
        for bad in (log.malformed + log.unknown)[:5]:
            print(f"    line {bad['line']}: {bad.get('kind', '?')} missing {bad.get('missing')}: "
                  f"{bad['text'][:120]}")
    for error in log.mod_errors[:5] + log.other_errors[:5]:
        print(f"  ERROR line {error['line']} ({error['logger']}): {error['message'][:150]}")
    for trace in log.mod_traces[:3]:
        print(f"  stack trace at line {trace['line']} ({trace['logger']}): "
              f"{trace['message'][:120]}")
        for frame in trace["trace"][:6]:
            print(f"      {frame.strip()[:150]}")

    strikes = log.events["strike"]
    if strikes:
        print()
        print(f"  strikes ({len(strikes)}; closing, shared, gained reported, not judged):")
        for strike in strikes[:50]:
            print(f"    {run.rel(strike['tick']):>7} {strike.get('striker')} -> "
                  f"{strike.get('struck')}  trains {strike.get('trains') or '?':<17} closing "
                  f"{strike.get('closing')} shared {strike.get('shared')} gained "
                  f"{strike.get('gained')}")
        if len(strikes) > 50:
            print(f"    ... {len(strikes) - 50} more in the JSON")


def fmt_box(box):
    return "{x:%d,y:%d,z:%d,w:%d,d:%d}" % box


def jsonable(value):
    if isinstance(value, float) and not math.isfinite(value):
        return str(value).replace("inf", "Infinity").replace("nan", "NaN")
    if isinstance(value, dict):
        return {str(k): jsonable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [jsonable(v) for v in value]
    return value


def write_json(run_dir, run, verdict, result, selfcheck_problems):
    out_dir = Path(run_dir) / "torture"
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    path = out_dir / f"{run.mode}-{run.group}-{stamp}.json"
    first = run.samples[0] if run.samples else None
    document = {
        "mode": run.mode, "group": run.group, "box": fmt_box(run.box), "meta": run.meta,
        "start_tick": run.start_tick, "last_tick": run.last_tick, "duration": run.duration,
        "rcon_error": run.rcon_error, "frozen": run.frozen, "interrupted": run.interrupted,
        "crash_reports": run.crash_reports, "injections": run.injections,
        "roster": [{"train": t.id, "kinds": t.kinds, "cars": t.uuids,
                    "cells": [first.carts[u].cell() for u in t.uuids]}
                   for t in first.trains] if first else [],
        "carts": {u: c.kind for u, c in first.carts.items()} if first else {},
        "first_links": sorted(sorted(e, key=java_order) for e in run.severs.first or ()),
        "log": dict(run.log.summary(), rolled=run.log_rolled),
        "strikes": run.log.events["strike"],
        "verdict": verdict, "overall": result, "selfcheck_problems": selfcheck_problems,
        "samples": [{"tick": s.tick, "wall": round(s.wall, 3),
                     "trains": [t.uuids for t in s.trains],
                     "carts": {u: [round(v, 4) for v in c.pos + c.motion]
                               for u, c in s.carts.items()}} for s in run.samples],
    }
    path.write_text(json.dumps(jsonable(document), indent=1))
    return path


# ---- the live session ----------------------------------------------------------------------------

class Frozen(Exception):
    """Game time stopped advancing while rcon still answered."""


def listing(directory):
    try:
        return set(os.listdir(directory))
    except FileNotFoundError:
        return set()


def main_commit():
    # The main checkout's HEAD, the code the dev server builds from, read through the shared git
    # dir so a run started from a worktree names the same commit.
    try:
        head = (mtxrcon.git_common_dir() / "HEAD").read_text().strip()
        ref = head[5:] if head.startswith("ref: ") else head
        here = str(Path(__file__).resolve().parent)
        return subprocess.run(["git", "-C", here, "rev-parse", "--short", ref], check=True,
                              capture_output=True, text=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "?"


def read_rules(rcon):
    rules = {}
    for rule in RULES:
        match = re.search(r"is currently set to: (\S+)", rcon.cmd(f"gamerule {rule}"))
        rules[rule] = match.group(1) if match else None
    return rules


def count(rcon, selector):
    match = re.search(r"Count: (\d+)", rcon.cmd(f"execute if entity {selector}"))
    return int(match.group(1)) if match else 0


def set_box(rcon, box):
    rcon.cmd("function mtxrig:box " + fmt_box(box))


def find_signs(rcon):
    """Every [mtxrig] sign in the box, as (x, y, z, group)."""
    rcon.cmd("kill @e[tag=mtx_found]")
    rcon.cmd("function mtxrig:find")
    reply = rcon.cmd("execute as @e[tag=mtx_found] run data get entity @s Pos")
    signs = []
    for pos in re.findall(r"\[(%s)d, (%s)d, (%s)d\]" % (NUMBER, NUMBER, NUMBER), reply):
        x, y, z = (math.floor(float(v)) for v in pos)
        lines = re.findall(r'"((?:[^"\\]|\\.)*)"',
                           rcon.cmd(f"data get block {x} {y} {z} front_text.messages"))
        signs.append((x, y, z, lines[1] if len(lines) > 1 else "?"))
    rcon.cmd("kill @e[tag=mtx_found]")
    return sorted(signs)


def coupling_warnings(rcon):
    """How many neighbouring pairs the last rig left uncoupled (couple_warn), or None."""
    match = re.search(r"has (-?\d+)", rcon.cmd("scoreboard players get #unlinked mtxrig"))
    return int(match.group(1)) if match else None


def rig(rcon, group):
    """Places the group; returns why it could not, or None."""
    reply = rcon.cmd(f'function mtxrig:rig {{group:"{group}"}}')
    if "Unknown" in reply or "Can't find" in reply:
        return f"mtxrig:rig failed ({reply.strip()}); sync the pack with scripts/rig-sync.sh"
    return None


def clear_group(rcon, group):
    rcon.cmd(f'function mtxrig:reset {{group:"{group}"}}')
    set_box(rcon, YARD_BOX)
    left = count(rcon, f"@e[tag=mtx_{group}]")
    return f"reset {group} ({left} left) and restored the yard box {fmt_box(YARD_BOX)}"


def sample_loop(rcon, run, on_sample=None):
    next_tick = run.start_tick
    moved_at, seen_tick = time.monotonic(), None
    while True:
        now = gametime(rcon)
        if seen_tick is None or now > seen_tick:
            moved_at, seen_tick = time.monotonic(), now
        elif time.monotonic() - moved_at > FROZEN_SECONDS:
            raise Frozen()
        if now < next_tick:
            time.sleep(max(0.005, (next_tick - now) * 0.04))
            continue
        sample = take_sample(rcon, run.group, now)
        run.add(sample)
        if len(run.samples) == 1:
            print(f"  roster at the first sample ({len(sample.carts)} carts):")
            print("\n".join(roster_lines(sample)), flush=True)
        new = sorted(listing(run.meta["crash_dir"]) - run.meta["crash_before"])
        if new:
            run.crash_reports = new
        if on_sample is not None:
            on_sample(rcon, run, sample)
        elapsed = now - run.start_tick
        if elapsed // 600 != (elapsed - SAMPLE_TICKS) // 600 or elapsed >= run.duration:
            print(f"  {run.rel(now):>7}: {len(sample.carts)} carts in {len(sample.trains)} trains, "
                  f"{len(sample.edges)} couplings, {len(run.stalls.active)} stalled", flush=True)
        if elapsed >= run.duration:
            return
        next_tick = run.start_tick + min(run.duration, (elapsed // SAMPLE_TICKS + 1) * SAMPLE_TICKS)


class Injector:
    """The selfcheck's two faults: an engine starved of fuel, then a coupling cut on both ends."""

    def __init__(self):
        self.engine = None

    def __call__(self, rcon, run, sample):
        elapsed = sample.tick - run.start_tick
        # A failed attempt is recorded under its own name, so it is neither retried nor mistaken
        # for the injection; judge_selfcheck then reports that injection as missing.
        tried = {i["what"].split(" ")[0] for i in run.injections}
        if "fuel" not in tried and elapsed >= FUEL_CUT_AT:
            self.starve(rcon, run, sample)
        if "unlink" not in tried and elapsed >= UNLINK_AT:
            self.unlink(rcon, run, sample)

    def starve(self, rcon, run, sample):
        engines = [u for u, c in sample.carts.items() if c.kind == "F"]
        if len(sample.trains) != 1 or len(engines) != 1:
            run.injections.append({"what": "fuel failed", "tick": sample.tick,
                                   "text": f"not starved: selfcheck wants one train with one "
                                           f"engine, found {len(sample.trains)} train(s) and "
                                           f"{len(engines)} engine(s)"})
            return
        self.engine = engines[0]
        rcon.cmd(f"data merge entity {self.engine} {{minecarttweaks:{{RealFuel:0}}}}")
        after = rcon.cmd(f"data get entity {self.engine} minecarttweaks.RealFuel")
        run.injections.append({"what": "fuel", "tick": sample.tick, "uuid": self.engine,
                               "text": f"RealFuel 0 on engine {short(self.engine)} "
                                       f"(reads back {after.rsplit(':', 1)[-1].strip()})"})

    def unlink(self, rcon, run, sample):
        train = max(sample.trains, key=lambda t: len(t.uuids))
        if len(train.uuids) < 2:
            run.injections.append({"what": "unlink failed", "tick": sample.tick,
                                   "text": "not cut: no coupling left in the group"})
            return
        # The most even split, so neither half is a lone car the other could sweep aside.
        cut = min(range(1, len(train.uuids)), key=lambda i: abs(len(train.uuids) - 2 * i))
        a, b = train.uuids[cut - 1], train.uuids[cut]
        for this, other in ((a, b), (b, a)):
            for slot, held in sample.carts[this].slots.items():
                if held == other:
                    rcon.cmd(f"data remove entity {this} {LINK_PATH}.{slot}")
        after = rcon.cmd(f"data get entity {a} {LINK_PATH}") + rcon.cmd(
            f"data get entity {b} {LINK_PATH}")
        still = [s for s in RE_SLOT.findall(after) if uuid_text(int(v) for v in s[1:]) in (a, b)]
        run.injections.append({
            "what": "unlink", "tick": sample.tick, "uuids": [a, b], "cars": [short(a), short(b)],
            "text": f"cut the coupling {short(a)}-{short(b)} ({train.kinds[cut - 1]}"
                    f"{train.kinds[cut]}, between cars {cut} and {cut + 1} of "
                    f"{'-'.join(train.kinds)})" + (f"; still linked: {still}" if still else "")})


def prepare(rcon, run, run_dir):
    """Checks the rules and the box, marks the log and rigs the group; returns a refusal or None,
    and the log mark."""
    rules = read_rules(rcon)
    wrong = {rule: value for rule, value in rules.items() if value != RULES[rule]}
    if wrong:
        return ("refusing to run: the bar is measured at "
                + ", ".join(f"{rule}={want}" for rule, want in RULES.items()) + "\n"
                + "".join(f"  {rule} is {value}\n" for rule, value in wrong.items())
                + "set them with:\n"
                + "\n".join(f"  gamerule {rule} {RULES[rule]}" for rule in wrong)), None

    others = count(rcon, f"@e[tag=mtx,tag=!mtx_{run.group}]")
    if others and run.mode == "selfcheck":
        return (f"refusing to selfcheck: {others} cart(s) of other rig groups stand in the world; "
                f"clear them with: function mtxrig:reset_all"), None

    set_box(rcon, run.box)
    signs = [s for s in find_signs(rcon) if s[3] == run.group]
    if not signs:
        set_box(rcon, YARD_BOX)
        return f"refusing to run: no [mtxrig] sign naming {run.group} in {fmt_box(run.box)}", None

    crash_dir = run_dir / "crash-reports"
    run.meta = {"commit": main_commit(), "run_dir": str(run_dir), "rules": rules,
                "players": rcon.cmd("list"), "other_rig_carts": others,
                "signs": [list(s) for s in signs], "crash_dir": str(crash_dir),
                "started": datetime.datetime.now().isoformat(timespec="seconds")}
    print(f"torture.py {run.mode}: {run.group} over {fmt_box(run.box)} for {run.duration} ticks; "
          f"{len(signs)} sign(s) name it"
          + (f"; {others} other rig cart(s) stand" if others else ""))

    mark = LogMark(run_dir / "logs" / "debug.log")
    run.meta["log_mark"] = {"path": str(mark.path), "offset": mark.offset, "line": mark.line}
    run.meta["crash_before"] = listing(crash_dir)
    run.stalls.probe = lambda pos: rail_at(rcon, pos)

    failure = rig(rcon, run.group)
    if failure:
        set_box(rcon, YARD_BOX)
        return failure, None
    run.start_tick = gametime(rcon)
    run.start_wall = time.time()
    run.meta["coupling_warnings"] = coupling_warnings(rcon)
    if run.meta["coupling_warnings"]:
        print(f"  WARNING: {UNCOUPLED % run.meta['coupling_warnings']}")
    return None, mark


def session(args, mode):
    run_dir = Path(args.run_dir)
    group = args.group or (SELFCHECK_GROUP if mode == "selfcheck" else TORTURE_GROUP)
    box = tuple(args.box) if args.box else (YARD_BOX if mode == "selfcheck" else TORTURE_BOX)
    duration = args.ticks or (SELFCHECK_TICKS if mode == "selfcheck" else RUN_TICKS)
    run = Run(mode, group, box, duration)

    try:
        rcon = mtxrcon.connect(run_dir, timeout=RCON_TIMEOUT)
    except mtxrcon.RconDead as e:
        print(f"torture.py: {e}", file=sys.stderr)
        return 2
    try:
        refusal, mark = prepare(rcon, run, run_dir)
    except mtxrcon.RconDead as e:
        print(f"torture.py: rcon died before sampling began: {e}", file=sys.stderr)
        return 2
    if refusal:
        print(refusal, file=sys.stderr)
        rcon.close()
        return 2

    crash_dir = Path(run.meta["crash_dir"])
    alive = True
    try:
        sample_loop(rcon, run, Injector() if mode == "selfcheck" else None)
    except mtxrcon.RconDead as e:
        run.rcon_error, alive = str(e), False
    except Frozen:
        run.frozen = True
    except KeyboardInterrupt:
        run.interrupted = True

    if not alive:
        # A crashing server writes its report before the socket closes, but not always first.
        deadline = time.monotonic() + 10
        while not run.crash_reports and time.monotonic() < deadline:
            run.crash_reports = sorted(listing(crash_dir) - run.meta["crash_before"])
            time.sleep(0.5)
    run.meta["crash_before"] = sorted(run.meta["crash_before"])

    text, run.log_rolled = mark.read()
    run.log = parse_log(text)
    verdict = judge(run)
    result = overall(run, verdict)
    problems = judge_selfcheck(run, verdict) if mode == "selfcheck" else None
    print_verdict(run, verdict, result, problems)
    path = write_json(run_dir, run, verdict, result, problems)
    print(f"\n  json: {path}")

    if alive and run.interrupted:
        # Ctrl-C can land between a request and its reply, leaving this socket out of step.
        rcon.close()
        try:
            rcon = mtxrcon.connect(run_dir, timeout=RCON_TIMEOUT)
        except mtxrcon.RconDead as e:
            alive = False
            print(f"  rcon refused a fresh connection: {e}")
    if alive:
        try:
            print("  " + clear_group(rcon, group))
        except mtxrcon.RconDead as e:
            alive = False
            print(f"  rcon died during cleanup: {e}")
    if not alive:
        print(f"  rcon is dead: skipped the reset of {group} and the yard box restore. After "
              f"scripts/devserver.sh <mod> restart, run at once, before the reloaded carts "
              f"re-accelerate: kill @e[tag=mtx], then function mtxrig:box {fmt_box(YARD_BOX)}")
    rcon.close()
    if mode == "selfcheck":
        return 0 if not problems else 1
    return 0 if result == "PASS" else 1


def roster(args):
    run_dir = Path(args.run_dir)
    group = args.group or TORTURE_GROUP
    box = tuple(args.box) if args.box else TORTURE_BOX
    try:
        rcon = mtxrcon.connect(run_dir, timeout=RCON_TIMEOUT)
    except mtxrcon.RconDead as e:
        print(f"torture.py: {e}", file=sys.stderr)
        return 2
    try:
        with rcon:
            return place_and_print(rcon, group, box)
    except mtxrcon.RconDead as e:
        print(f"torture.py: {e}; if the server is still up it may be left frozen (tick unfreeze), "
              f"with {group} placed and the box moved", file=sys.stderr)
        return 2


def place_and_print(rcon, group, box):
    set_box(rcon, box)
    # Frozen so the dump reads the carts where the rig stood them: the placer respaces a train on
    # its first tick, which can move a car into the next cell.
    rcon.cmd("tick freeze")
    try:
        failure = rig(rcon, group)
        sample = take_sample(rcon, group, gametime(rcon))
        warnings = coupling_warnings(rcon)
    finally:
        rcon.cmd("tick unfreeze")
    if failure:
        set_box(rcon, YARD_BOX)
        print(failure, file=sys.stderr)
        return 2
    sizes = " ".join(str(n) for n in sorted(len(t.uuids) for t in sample.trains))
    print(f"{group} over {fmt_box(box)}: {len(sample.carts)} carts in {len(sample.trains)} "
          f"trains ({sizes}); coupling warnings {'?' if warnings is None else warnings}")
    print("\n".join(roster_lines(sample)))
    print(clear_group(rcon, group))
    return 0


def main():
    parser = argparse.ArgumentParser(
        description="Sample the rig and judge the ten-minutes-clean bar.",
        formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__.split("\n\n", 2)[2])
    parser.add_argument("mode", nargs="?", choices=["run"],
                        help="the bar's run on the torture track")
    parser.add_argument("--selfcheck", action="store_true", help="the injected sever and stall")
    parser.add_argument("--roster", action="store_true", help="place a group and print its trains")
    parser.add_argument("--parse-test", action="store_true", help="the parser on synthetic lines")
    parser.add_argument("--run-dir", default=None, help="the dev server's run/ (default: the main "
                                                       "checkout's)")
    parser.add_argument("--group", default=None, help="rig group (default: torturetrack, or "
                                                      "abtestloop-a for --selfcheck)")
    parser.add_argument("--box", default=None, type=lambda s: [int(v) for v in s.split(",")],
                        help="scan box as --box=x,y,z,w,d, the = form since x is negative "
                             "(default: the torture box, or the yard box for --selfcheck)")
    parser.add_argument("--ticks", default=None, type=int, help="game ticks to sample (default: "
                                                                "12000, or 1800 for --selfcheck)")
    args = parser.parse_args()

    modes = [m for m, on in (("run", args.mode == "run"), ("selfcheck", args.selfcheck),
                             ("roster", args.roster), ("parse-test", args.parse_test)) if on]
    if len(modes) != 1:
        parser.error("give exactly one of: run, --selfcheck, --roster, --parse-test")
    if args.box is not None and len(args.box) != 5:
        parser.error("--box takes x,y,z,w,d")
    if modes[0] == "parse-test":
        return parse_test()
    args.run_dir = args.run_dir or str(mtxrcon.default_run_dir())
    if modes[0] == "roster":
        return roster(args)
    return session(args, modes[0])


# ---- synthetic test ------------------------------------------------------------------------------

SYNTHETIC_LOG = """\
[12:00:00] [Server thread/INFO] (net.minecraft.server.MinecraftServer) [Rcon: Running function mtxrig:rig]
[12:00:01] [Server thread/DEBUG] (club.rainbowkitty.minecarttweaks.train.TrainMeter) meter tick=1000 train=0a1b2c3d cars=8 kinds=FCHCCHCF lead=0a1b2c3d pos=-1.50,-53.00,-20.50 speed=0.40 maxspeed=0.45 fuel=71000 rail=minecraft:activator_rail(on) seated=8 refusal=none packing=false mass=8.00 pairs=7/7 gap_worst=0.02 gap_mean=0.01 turn=0.00 nodes=12 span=10.50 checked=7 offset_worst=0.01 resets=0 revisits=0
[12:00:01] [Server thread/DEBUG] (club.rainbowkitty.minecarttweaks.train.TrainMeter) meter tick=1000 train=99990000 cars=4 kinds=CCFF lead=none pos=-5.50,-53.00,-6.50 speed=0.00 maxspeed=n/a fuel=none rail=minecraft:rail seated=0 refusal=ORDER_UNREADABLE packing=false mass=4.00 pairs=0/3 gap_worst=n/a gap_mean=n/a turn=n/a nodes=0 span=n/a checked=0 offset_worst=n/a resets=1 revisits=0
[12:00:01] [Server thread/DEBUG] (club.rainbowkitty.minecarttweaks.train.TrainMeter) meter tick=1000 cart=f0e1d2c3 kind=C pos=-14.50,-53.00,-14.50 speed=0.00 maxspeed=0.00 rail=minecraft:rail
[12:00:02] [Server thread/DEBUG] (club.rainbowkitty.minecarttweaks.collision.CartImpact) event=strike tick=1010 striker=0a1b2c3d struck=11223344 trains=0a1b2c3d,11223344 closing=0.80 shared=0.40 gained=0.40
[12:00:02] [Server thread/DEBUG] (club.rainbowkitty.minecarttweaks.collision.CartImpact) event=strike tick=1011 striker=0a1b2c3d struck=0a1b2c3e trains=0a1b2c3d,0a1b2c3d closing=0.10 shared=0.05 gained=0.05
[12:00:02] [Server thread/DEBUG] (club.rainbowkitty.minecarttweaks.collision.CartImpact) event=overlap tick=1012 cart=11223344 other=0a1b2c3d trains=11223344,0a1b2c3d distance=0.31
[12:00:02] [Server thread/DEBUG] (club.rainbowkitty.minecarttweaks.collision.CartImpact) event=overspeed tick=1013 cart=11223344 train=11223344 speed=4.850e+150 ceiling=2.50 clamped=true
[12:00:02] [Server thread/DEBUG] (club.rainbowkitty.minecarttweaks.mixin.AbstractMinecartMixin) event=overspeed tick=1014 cart=22334455 train=11223344 speed=NaN ceiling=5.00 clamped=true
[12:00:03] [Server thread/DEBUG] (club.rainbowkitty.minecarttweaks.train.TrainCoupling) event=sever tick=1030 train=0a1b2c3d at=0a1b2c3e reason=SEAT_FOULED into=0a1b2c3d,0a1b2c3f
[12:00:04] [Server thread/DEBUG] (club.rainbowkitty.minecarttweaks.block.SwitchedRailBlock) event=switch tick=1500 pos=-14.00,-53.00,-2.00 from=south_east to=east_west spanned=true applied=false
[12:00:04] [Server thread/DEBUG] (club.rainbowkitty.minecarttweaks.block.SwitchedRailBlock) event=switch tick=1600 pos=-14.00,-53.00,-2.00 from=south_east to=east_west spanned=true applied=true
[12:00:04] [Server thread/DEBUG] (club.rainbowkitty.minecarttweaks.block.SwitchedRailBlock) event=switch tick=1700 pos=-1.00,-53.00,-12.00 from=south_east to=north_south spanned=false applied=true
[12:00:05] [Server thread/DEBUG] (club.rainbowkitty.minecarttweaks.mixin.MinecartFurnaceMixin) event=stall_reverse tick=1800 train=99990000 cart=99990001 pos=-4.50,-53.00,-8.50 rail=minecraft:rail
[12:00:05] [Server thread/DEBUG] (club.rainbowkitty.minecarttweaks.train.TrainPlacer) event=refusal tick=990 train=99990000 why=ORDER_UNREADABLE car=3 leader at 1.38 of -8.71..1.71, next car not far enough along it to say
[12:00:05] [Server thread/DEBUG] (club.rainbowkitty.minecarttweaks.train.TrainPlacer) event=refusal tick=1950 train=99990000 why=none car=none
[12:00:06] [Server thread/DEBUG] (club.rainbowkitty.minecarttweaks.train.TrainCoupling) event=sever tick=2000 train=55667788 at=55667789 reason=DIVERTED into=55667788,5566778a
[12:00:06] [Server thread/DEBUG] (club.rainbowkitty.minecarttweaks.collision.CartImpact) event=strike tick=1020 striker=0a1b2c3d
[12:00:06] [Server thread/DEBUG] (club.rainbowkitty.minecarttweaks.collision.CartImpact) event=teleport tick=1021 cart=0a1b2c3d
[12:00:07] [Server thread/ERROR] (club.rainbowkitty.minecarttweaks.train.Train) ribbon walk failed
[12:00:08] [Server thread/ERROR] (net.minecraft.server.MinecraftServer) Encountered an unexpected exception
java.lang.IllegalArgumentException: Invalid section range
\tat net.minecraft.world.level.entity.EntitySectionStorage.getEntities(EntitySectionStorage.java:120)
\tat club.rainbowkitty.minecarttweaks.collision.CartImpact.strikeNearby(CartImpact.java:84)
\tat net.minecraft.server.MinecraftServer.tickServer(MinecraftServer.java:900)
[12:00:09] [Server thread/WARN] (net.minecraft.server.level.ChunkMap) Failed to save chunk
java.io.IOException: disk full
\tat java.base/java.io.FileOutputStream.write(FileOutputStream.java:1)
[12:00:10] [Server thread/ERROR] (net.minecraft.world.level.storage.LevelStorageSource) unrelated
[12:00:11] [Server thread/INFO] (Minecraft) event=strike tick=5 not the mod's logger
[12:00:12] [RCON Client /127.0.0.1 #30/INFO] (net.minecraft.server.rcon.thread.RconClient) Thread RCON Client /127.0.0.1 shutting down
[12:00:13] [Server thread/DEBUG] (club.rainbowkitty.minecarttweaks.train.TrainPlacer) event=refusal tick=2100 train=99990000 why=SEAT_FOULED car=1 a cart of [x/DEBUG] (y) lies across
"""


def synthetic_cart(uuid, kind, pos, links=(), motion=(0.0, 0.0, 0.0), push=None):
    slots = {("slotA", "slotB")[i]: other for i, other in enumerate(links)}
    return Cart(uuid, kind, tuple(float(v) for v in pos), tuple(float(v) for v in motion), slots,
                push=push)


SYNTHETIC = {name: [f"{name}100000{i}-0000-4000-8000-000000000000" for i in range(1, count + 1)]
             for name, count in (("a", 3), ("b", 2), ("c", 2), ("d", 1))}


def synthetic_run():
    """Four trains over 1500 ticks: a runs and loses its rear coupling at 500, b never moves, c runs
    up to b's rear and stops two blocks short at 183, and d (no engine) stands still throughout."""
    a, b, c, d = (SYNTHETIC[k] for k in "abcd")
    run = Run("run", "synthetic", TORTURE_BOX, 1500)
    run.start_tick, run.start_wall = 0, 0.0
    for tick in range(0, 1501, SAMPLE_TICKS):
        x = tick * 0.5
        c_front = min(96.5, 60 + tick * 0.2)
        c_motion = (0.2, 0, 0) if c_front < 96.5 else (0, 0, 0)
        carts = {
            a[0]: synthetic_cart(a[0], "F", (x, -53, 0), [a[1]], (0.5, 0, 0)),
            a[1]: synthetic_cart(a[1], "C", (x - 1.5, -53, 0),
                                 [a[0]] + ([a[2]] if tick < 500 else []), (0.5, 0, 0)),
            a[2]: synthetic_cart(a[2], "H", (x - 3.0, -53, 0), [a[1]] if tick < 500 else [],
                                 (0.5, 0, 0)),
            b[0]: synthetic_cart(b[0], "F", (100, -53, 50), [b[1]], push=(1.0, 0.0)),
            b[1]: synthetic_cart(b[1], "C", (98.5, -53, 50), [b[0]]),
            c[0]: synthetic_cart(c[0], "F", (c_front, -53, 50), [c[1]], c_motion, push=(1.0, 0.0)),
            c[1]: synthetic_cart(c[1], "C", (c_front - 1.5, -53, 50), [c[0]], c_motion),
            d[0]: synthetic_cart(d[0], "C", (0, -53, 80)),
        }
        run.add(Sample(tick, float(tick) / 20, carts))
    return run


def parse_test():
    failures = []
    checks = 0

    def check(what, got, want):
        nonlocal checks
        checks += 1
        if got != want:
            failures.append(f"{what}: got {got!r}, want {want!r}")

    log = parse_log(SYNTHETIC_LOG.replace("\\t", "\t"))
    check("meter train lines", len(log.meters["train"]), 2)
    check("meter cart lines", len(log.meters["cart"]), 1)
    check("meter rail", log.meters["train"][0]["rail"], "minecraft:activator_rail(on)")
    check("meter n/a", num(log.meters["train"][1]["maxspeed"]), None)
    # The malformed strike still counts: its tick is readable, and a strike it hides could matter.
    check("event counts", {k: len(v) for k, v in log.events.items()},
          {"sever": 2, "stall_reverse": 1, "strike": 3, "overlap": 1, "overspeed": 2, "switch": 3,
           "refusal": 3})
    check("bracketed prose", (log.events["refusal"][-1]["why"], log.events["refusal"][-1]["prose"]),
          ("SEAT_FOULED", "a cart of [x/DEBUG] (y) lies across"))
    check("strike figures",
          [num(log.events["strike"][0][k]) for k in ("closing", "shared", "gained")],
          [0.8, 0.4, 0.4])
    check("overspeed 1e150", num(log.events["overspeed"][0]["speed"]), 4.85e150)
    check("overspeed NaN", math.isnan(num(log.events["overspeed"][1]["speed"])), True)
    check("refusal prose", log.events["refusal"][0]["prose"],
          "leader at 1.38 of -8.71..1.71, next car not far enough along it to say")
    check("malformed", [(m["kind"], m["missing"]) for m in log.malformed],
          [("strike", ["struck", "trains", "closing", "shared", "gained"])])
    check("unknown", len(log.unknown), 1)
    check("mod errors", len(log.mod_errors), 1)
    check("other errors", len(log.other_errors), 2)
    check("mod traces", [t["line"] for t in log.mod_traces], [21])
    check("other traces", [t["line"] for t in log.other_traces], [26])

    check("java order", train_id(["7fffffff-0000-0000-0000-000000000000",
                                  "80000000-0000-0000-0000-000000000000"]), "80000000")
    # Equal high halves leave the ids equal too, so the low half is checked on the full UUIDs.
    check("java order low half", min(["00000000-0000-0000-0000-000000000001",
                                      "00000000-0000-0000-8000-000000000000"], key=java_order),
          "00000000-0000-0000-8000-000000000000")
    check("uuid text", uuid_text([600411764, 1355105286, -1452623686, -1511729286]),
          "23c98e74-50c5-4406-a96a-b8baa5e4d77a")

    dump = ('Minecart with Furnace has the following entity data: {PushX: 0.5d, Motion: [NaNd, '
            '0.0d, 1.0E-5d], minecarttweaks: {RealFuel: 72000}, "fabric:attachments": {'
            '"minecarttweaks:link": {slotB: [I; 1, 2, 3, 4], slotA: [I; 5, 6, 7, 8]}}, '
            'Pos: [-35.5d, -52.9d, 4.5d]'
            ', UUID: [I; 600411764, 1355105286, -1452623686, -1511729286], PushZ: -0.5d}Minecart wi'
            'th Chest has the following entity data: {Motion: [0.0d, 0.0d, 0.4d], Items: [], Pos: ['
            '-35.5d, -53.0d, 6.5d], UUID: [I; 5, 6, 7, 8], "fabric:attachments": {"minecarttweaks:'
            'link": {}}}')
    carts = parse_dump(dump)
    engine = carts.get("23c98e74-50c5-4406-a96a-b8baa5e4d77a")
    check("dump carts", sorted(c.kind for c in carts.values()), ["C", "F"])
    check("dump slots", engine and sorted(engine.slots),
          ["slotA", "slotB"])
    check("dump fuel and push", engine and (engine.fuel, engine.push), (72000, (0.5, -0.5)))
    check("dump NaN motion", engine and math.isnan(engine.motion[0]), True)
    check("dump pos", engine and engine.pos, (-35.5, -52.9, 4.5))

    run = synthetic_run()
    run.log = log
    verdict = judge(run)
    a, b, c = (SYNTHETIC[k] for k in "abc")
    check("Alive", verdict["Alive"]["verdict"], "FAIL")
    check("Sever count", verdict["Sever"]["count"], 3)
    links = [d for d in verdict["Sever"]["details"] if d.get("source") == "links"]
    check("Sever from links", [(d["cars"], d["last_seen"], d["tick"]) for d in links],
          [([short(a[1]), short(a[2])], 480, 500)])
    check("Stall count", verdict["Stall"]["count"], 2)
    by_start = sorted(verdict["Stall"]["details"], key=lambda e: e["start_tick"])
    # c's front reaches 92 at 160, inside 5 blocks of where it stops, so 760 is its first window.
    check("Stall primary", [(e["train"], e["kinds"], e["start_tick"], e["queued_behind"])
                            for e in by_start],
          [(train_id(b), "CF", 600, []), (train_id(c), "CF", 760, [train_id(b)])])
    check("Stall lead by push", (by_start[0]["lead"], by_start[0]["heading"]),
          (short(b[0]), "push"))
    check("Stall lead by drift", (by_start[1]["lead"], by_start[1]["heading"]),
          (short(c[0]), "drift"))
    check("Bounce", (verdict["Bounce"]["verdict"], len(verdict["Bounce"]["details"])), ("FAIL", 4))
    check("Bounce near strike", [(n["sever_tick"], n["strike_tick"]) for n in
                                 verdict["Bounce"]["details"] if "sever_tick" in n], [(1030, 1020)])
    switch = verdict["Switch-safe"]
    check("Switch-safe", (switch["verdict"], len(switch["details"])), ("FAIL", 2))
    check("vacuity", verdict["vacuity"]["verdict"], "FAIL")
    ran = {d["train"]: round(d["travel"]) for d in verdict["vacuity"]["details"]}
    # c: 0.2 b/t to its last moving sample at 180, then half of it across the next 20 ticks.
    check("vacuity travel", ran, {train_id(a): 750, train_id(b): 0, train_id(c): 38})
    check("overall", overall(run, verdict), "FAIL")

    clean = Run("selfcheck", "synthetic", YARD_BOX, 1500)
    clean.start_tick, clean.start_wall = 0, 0.0
    clean.log = parse_log("")
    verdict = judge(clean)
    check("clean Alive", verdict["Alive"]["verdict"], "PASS")
    check("clean Bounce", verdict["Bounce"]["verdict"], "PASS")
    check("clean Switch-safe", verdict["Switch-safe"]["verdict"], "PASS")
    check("clean no vacuity", "vacuity" in verdict, False)

    if failures:
        print(f"parse-test: {len(failures)} of {checks} checks failed")
        for failure in failures:
            print("  " + failure)
        return 1
    print(f"parse-test: {checks} checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
