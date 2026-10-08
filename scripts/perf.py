#!/usr/bin/env python3
"""Spark legs and fixtures for docs/plan/performance.md.

    python3 scripts/perf.py leg <name> <seconds>   profile the server thread, decode it locally
    python3 scripts/perf.py tracks [n]             n torture-track copies 1024 apart, fuelled
    python3 scripts/perf.py yard                   32 parked 7-car trains on flat, unpowered rail
    python3 scripts/perf.py loose                  the yard as 176 unlinked carts, none touching
    python3 scripts/perf.py pin                    whether a parked train holds chunks loaded

Python 3, standard library only, rcon through mtxrcon.py beside it. Needs spark (any 1.10.x Fabric
build) in the dev server's run/mods with backgroundProfiler off in run/config/spark/config.json, and
for `tracks` the rig pack synced (scripts/rig-sync.sh). Each leg's profile, tick samples and report
land in <run-dir>/perf/. Profile with the debug log off, as the plan's testing section says: a Loom
dev server logs DEBUG, which runs TrainMeter every tick, and the live server never does.
"""

import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

# No __pycache__ beside a tracked script.
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
import mtxrcon  # noqa: E402

WORKSPACE = mtxrcon.main_checkout().parents[2]
REPORT = WORKSPACE / "scripts" / "loadtest" / "SparkReport.java"
# Mod code, mixin handlers merged into vanilla classes, vanilla cart code, the tick's big buckets.
MATCH = (r"^club\.rainbowkitty\.|polytrains\$|^net\.minecraft\.world\.entity\.vehicle\."
         r"|^net\.minecraft\.server\.level\."
         r"(ServerLevel\.tick|ChunkMap\.tick|ServerEntity\.sendChanges)")
TRACK_STEP = 1024             # x between torture-track copies, each in its own 3x3 forceload
YARD_SITES = (992, 2016)      # x of the yard's two sites, beside track copies 2 and 3
YARD_ROWS = (5, 8, 11, 14)    # z of each east-west line, 3 apart so lines never join
YARD_TRAINS = (2, 14, 26, 38)  # x offset of each train's first car; 5 bare rail between trains
YARD_CARS = ("green", "yellow", "gray", "yellow", "magenta", "yellow", "gray")
KINDS = {"green": "furnace_minecart", "yellow": "minecart", "gray": "chest_minecart",
         "magenta": "hopper_minecart"}
RAIL_Y, WOOL_Y, SIGN_Y = -60, -59, -58
PIN_SITE = 3040               # track copy 4's site; the line runs east out of it


def count(rcon, selector):
    match = re.search(r"(?i)count: (\d+)", rcon.cmd(f"execute if entity {selector}"))
    return int(match.group(1)) if match else 0


def tick_query(rcon):
    reply = rcon.cmd("tick query")
    avg = re.search(r"Average time per tick: ([\d.]+)ms", reply)
    pct = re.search(r"P50: ([\d.]+)ms P95: ([\d.]+)ms P99: ([\d.]+)ms", reply)
    if not (avg and pct):
        return None
    return {"avg": float(avg.group(1)), "p50": float(pct.group(1)),
            "p95": float(pct.group(2)), "p99": float(pct.group(3))}


def profile(rcon, run_dir, seconds):
    """Runs spark for seconds, sampling tick query every 5 s; returns (profile path, samples)."""
    rcon.cmd("spark profiler cancel")
    start = time.time()
    # spark answers rcon asynchronously, so its replies are empty: wait for the file it saves.
    rcon.cmd("spark profiler start --interval 1 --ignore-sleeping")
    samples = []
    while time.time() - start < seconds:
        time.sleep(min(5, max(0.1, seconds - (time.time() - start))))
        sample = tick_query(rcon)
        if sample:
            samples.append(sample)
    rcon.cmd("spark profiler stop --save-to-file")
    for _ in range(60):
        fresh = [p for p in (run_dir / "config" / "spark").glob("*.sparkprofile")
                 if p.stat().st_mtime > start]
        if fresh:
            time.sleep(1)
            return max(fresh, key=lambda p: p.stat().st_mtime), samples
        time.sleep(1)
    sys.exit("spark saved no profile; is it in run/mods?")


def decode(run_dir, path, top=90):
    jars = sorted((run_dir / "mods").glob("spark-*.jar"))
    if not jars:
        sys.exit(f"no spark jar in {run_dir / 'mods'}")
    return subprocess.run(
        ["java", "--sun-misc-unsafe-memory-access=allow", "-cp", str(jars[-1]), str(REPORT),
         str(path), "--match", MATCH, "--top", str(top)],
        capture_output=True, text=True, check=True).stdout


def loaded_chunks(rcon, run_dir, seconds=10):
    """The level's loaded chunk count, as spark's window reports it. `execute if loaded` cannot
    stand in: it passes only for entity-ticking chunks, so one a ticket holds at FULL reads as
    not loaded."""
    path, _ = profile(rcon, run_dir, seconds)
    counts = [int(c) for c in re.findall(r"(\d+) chunks", decode(run_dir, path, top=1))]
    path.unlink()
    return counts[-1] if counts else None


def leg(rcon, run_dir, name, seconds):
    out = run_dir / "perf"
    out.mkdir(exist_ok=True)
    before = {"carts": count(rcon, "@e[tag=mtx]") + count(rcon, "@e[tag=perf_loose]"),
              "entities": count(rcon, "@e")}
    path, samples = profile(rcon, run_dir, seconds)
    report = decode(run_dir, path)
    target = out / f"{name}.sparkprofile"
    path.replace(target)
    result = {"leg": name, "seconds": seconds, "counts": before, "samples": samples,
              "profile": str(target)}
    (out / f"{name}.json").write_text(json.dumps(result, indent=1))
    (out / f"{name}.txt").write_text(report)
    print(f"[{name}] {before}")
    print(report)


def forceload(rcon, x0):
    rcon.cmd(f"forceload add {x0} -32 {x0 + 47} 15")


def rules(rcon, speed):
    for command in (f"gamerule minecraft:max_minecart_speed {speed}",
                    "gamerule polytrains:realistic_physics false",
                    "gamerule minecraft:spawn_mobs false", "weather clear"):
        rcon.cmd(command)


def tracks(rcon, copies):
    # mtxrig:rig resets its group everywhere by tag, so it would clear each copy as it rigs the
    # next: reset once, spawn per copy, then fuel the group once.
    for k in range(copies):
        forceload(rcon, -32 + TRACK_STEP * k)
    time.sleep(3)
    rcon.cmd('function mtxrig:reset {group:"torturetrack"}')
    rcon.cmd("function mtxrig:radius {r:16}")
    for k in range(copies):
        x = TRACK_STEP * k
        reply = rcon.cmd(f"place template mtxrig:torturetrack {x - 21} -58 -29")
        if "Loaded template" not in reply:
            sys.exit(f"{reply.strip()}; sync the rig pack with scripts/rig-sync.sh")
        rcon.cmd(f'execute positioned {x - 10} -54 -15 run function mtxrig:spawn '
                 f'{{group:"torturetrack"}}')
    rcon.cmd('function mtxrig:fuel {group:"torturetrack"}')
    print("carts", count(rcon, "@e[tag=mtx_torturetrack]"))


def yard(rcon):
    for site in YARD_SITES:
        forceload(rcon, site)
    time.sleep(3)
    for site in YARD_SITES:
        for z in YARD_ROWS:
            rcon.cmd(f"fill {site + 2} {RAIL_Y} {z} {site + 45} {RAIL_Y} {z} "
                     f"minecraft:rail[shape=east_west]")
            for offset in YARD_TRAINS:
                x0 = site + offset
                for i, colour in enumerate(YARD_CARS):
                    rcon.cmd(f"setblock {x0 + i} {WOOL_Y} {z} minecraft:{colour}_wool")
                rcon.cmd(f'setblock {x0} {SIGN_Y} {z} minecraft:oak_sign'
                         f'{{front_text:{{messages:["[mtxrig]","yard","",""]}}}}')
    # Parked means nothing else moving: the torture track's trains go too.
    rcon.cmd('function mtxrig:reset {group:"torturetrack"}')
    rcon.cmd('function mtxrig:reset {group:"yard"}')
    rcon.cmd("function mtxrig:radius {r:22}")
    for site in YARD_SITES:
        rcon.cmd(f'execute positioned {site + 24} {WOOL_Y} 10 run function mtxrig:spawn '
                 f'{{group:"yard"}}')
    # The rig fuels as it summons. Fuel above a short also rides in polytrains.RealFuel, which a
    # merge reads back on load, so both go to zero.
    rcon.cmd("execute as @e[type=minecraft:furnace_minecart,tag=mtx_yard] run "
             "data merge entity @s {Fuel:0s,polytrains:{RealFuel:0}}")
    print("carts", count(rcon, "@e[tag=mtx_yard]"),
          "engines", count(rcon, "@e[type=minecraft:furnace_minecart,tag=mtx_yard]"))


def loose(rcon):
    rcon.cmd('function mtxrig:reset {group:"yard"}')
    rcon.cmd("kill @e[tag=perf_loose]")
    n = 0
    for site in YARD_SITES:
        for z in YARD_ROWS:
            for x in range(site + 2, site + 46, 2):
                kind = KINDS[YARD_CARS[n % len(YARD_CARS)]]
                fuel = ",Fuel:0s" if kind == "furnace_minecart" else ""
                rcon.cmd(f"summon minecraft:{kind} {x}.5 {RAIL_Y} {z}.5 "
                         f"{{Tags:[\"perf_loose\"],Rotation:[0.0f,0.0f]{fuel}}}")
                n += 1
    rcon.cmd("kill @e[type=minecraft:item]")
    print("carts", count(rcon, "@e[tag=perf_loose]"))


def pin(rcon, run_dir):
    """A 20-car train parked wholly inside forceloaded chunks, on a line that runs on east into
    chunks nothing holds. Prints the loaded chunk count before, with, and after the train."""
    z = 12
    forceload(rcon, PIN_SITE)
    rcon.cmd(f"forceload add {PIN_SITE + 48} 0 {PIN_SITE + 367} 15")
    time.sleep(4)
    rcon.cmd(f"fill {PIN_SITE + 5} {RAIL_Y} {z} {PIN_SITE + 360} {RAIL_Y} {z} "
             f"minecraft:rail[shape=east_west]")
    rcon.cmd(f"fill {PIN_SITE + 6} {WOOL_Y} {z} {PIN_SITE + 25} {WOOL_Y} {z} "
             f"minecraft:yellow_wool")
    rcon.cmd(f'setblock {PIN_SITE + 6} {SIGN_Y} {z} minecraft:oak_sign'
             f'{{front_text:{{messages:["[mtxrig]","line","",""]}}}}')
    rcon.cmd(f"forceload remove {PIN_SITE + 48} 0 {PIN_SITE + 367} 15")
    time.sleep(20)
    before = loaded_chunks(rcon, run_dir)
    rcon.cmd("function mtxrig:radius {r:22}")
    rcon.cmd(f'execute positioned {PIN_SITE + 16} {WOOL_Y} {z} run function mtxrig:spawn '
             f'{{group:"line"}}')
    time.sleep(10)
    furthest = rcon.cmd("data get entity @e[tag=mtx_line,limit=1,sort=furthest,x=0,y=0,z=0] Pos")
    held = loaded_chunks(rcon, run_dir, 20)
    rcon.cmd('function mtxrig:reset {group:"line"}')
    rcon.cmd("kill @e[type=minecraft:item]")
    time.sleep(30)
    after = loaded_chunks(rcon, run_dir)
    print(f"loaded chunks: {before} before, {held} with the train, {after} after it went")
    print(f"furthest car (all inside the forceload, x < {PIN_SITE + 48}): {furthest.strip()}")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("what", choices=["leg", "tracks", "yard", "loose", "pin"])
    parser.add_argument("args", nargs="*")
    parser.add_argument("--run-dir", default=None,
                        help="the dev server's run/ (default: the main checkout's)")
    parser.add_argument("--speed", type=int, default=8,
                        help="max_minecart_speed for tracks and yard (default 8, the live value)")
    args = parser.parse_args()
    run_dir = Path(args.run_dir or mtxrcon.default_run_dir())

    with mtxrcon.connect(run_dir, timeout=60) as rcon:
        if args.what == "leg":
            if len(args.args) != 2:
                parser.error("leg takes <name> <seconds>")
            leg(rcon, run_dir, args.args[0], int(args.args[1]))
        elif args.what == "tracks":
            rules(rcon, args.speed)
            tracks(rcon, int(args.args[0]) if args.args else 4)
        elif args.what == "yard":
            rules(rcon, args.speed)
            yard(rcon)
        elif args.what == "loose":
            loose(rcon)
        else:
            pin(rcon, run_dir)


if __name__ == "__main__":
    main()
