#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: collision-rig.sh [options]

Builds a disposable minecart collision course in empty sky on this mod's dev server, runs one
cart down it, prints a sampled table of what happened, and tears the whole thing down again.
Everything goes over rcon against run/server.properties, so the dev server must already be up
(scripts/devserver.sh rkc/mods/serverside-trains start).

Course:
  straight  one east-west rail on a stone platform            (default)
  corner    the same run, plus a 90-degree turn to the south at its far end and a second rail
            running parallel one block north of it

Options:
  -c, --course NAME     straight | corner (default: straight)
      --cart ID         cart entity id, without namespace (default: minecart)
      --cart-nbt SNBT   extra NBT merged into the cart, e.g. '{Items:[...]}' (default: none)
      --train N         couple N carts into one train (default: 1); the table follows its front car
      --speed BPT       cart's starting speed in blocks per tick (default: 0.5)
      --opposing BPT    also send a cart the other way from the far end at this speed
  -t, --target ID       entity summoned in the cart's path, or 'none' (default: chicken)
      --target-nbt SNBT extra NBT merged into the target, e.g. '{NoAI:1b}' (default: none)
      --target-at N     blocks down the rail to put the target (default: 10)
  -n, --samples N       how many times to poll (default: 24)
  -i, --interval SECS   seconds between polls (default: 0.5)
      --length N        length of the main run in blocks (default: 100)
      --platform N      stone extending N blocks either side of the rails (default: 3)
      --keep            leave the course and its entities standing afterwards
      --teardown        tear down a kept course and exit, building nothing
  -h, --help            this message

Every car of a train has to be driven to get the train up to a speed: the coupling averages one
shared speed over all of them, so setting one car moving leaves the train at a fraction of it.

A coasting cart sheds speed fast - an empty one launched at 0.5 bpt covers about 16 blocks before
it stops - so keep --target-at short, and read the impact speed off the table rather than
assuming the cart still carries its starting one.

--target-nbt '{NoAI:1b}' pins a target that would otherwise wander off before a slow cart
arrives. Only for legs that do not measure the throw: a frozen mob cannot act on knockback, so
a working throw reads as a failure.
EOF
}

WORKSPACE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
MOD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROPERTIES="$MOD_DIR/run/server.properties"

course=straight
cart=minecart
cart_nbt=""
target_nbt=""
train=1
speed=0.5
opposing=""
target=chicken
target_at=10
samples=24
interval=0.5
length=100
platform=3
keep=0
teardown_only=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        -c|--course)    course=$2; shift 2 ;;
        --cart)         cart=$2; shift 2 ;;
        --cart-nbt)     cart_nbt=$2; shift 2 ;;
        --train)        train=$2; shift 2 ;;
        --speed)        speed=$2; shift 2 ;;
        --opposing)     opposing=$2; shift 2 ;;
        -t|--target)    target=$2; shift 2 ;;
        --target-at)    target_at=$2; shift 2 ;;
        --target-nbt)   target_nbt=$2; shift 2 ;;
        -n|--samples)   samples=$2; shift 2 ;;
        -i|--interval)  interval=$2; shift 2 ;;
        --length)       length=$2; shift 2 ;;
        --platform)     platform=$2; shift 2 ;;
        --keep)         keep=1; shift ;;
        --teardown)     teardown_only=1; shift ;;
        -h|--help)      usage; exit 0 ;;
        *)              echo "unknown option: $1" >&2; usage >&2; exit 1 ;;
    esac
done

case "$course" in
    straight|corner) ;;
    *) echo "unknown course: $course" >&2; exit 1 ;;
esac

[[ -f $PROPERTIES ]] || {
    echo "no $PROPERTIES - start the dev server once first:" >&2
    echo "  $WORKSPACE_ROOT/scripts/devserver.sh rkc/mods/serverside-trains start" >&2
    exit 2
}

rcon_port=$(grep -m1 '^rcon.port=' "$PROPERTIES" | cut -d= -f2-)
rcon_password=$(grep -m1 '^rcon.password=' "$PROPERTIES" | cut -d= -f2-)
[[ -n $rcon_port && -n $rcon_password ]] || {
    echo "no rcon.port/rcon.password in $PROPERTIES" >&2
    exit 2
}

exec python3 - "$rcon_port" "$rcon_password" <<'PY' \
    "$course" "$cart" "$cart_nbt" "$speed" "$opposing" "$target" "$target_at" "$target_nbt" \
    "$samples" "$interval" "$length" "$platform" "$keep" "$teardown_only" "$train"
import re
import socket
import struct
import sys
import time

PORT, PASSWORD = int(sys.argv[1]), sys.argv[2]
(COURSE, CART, CART_NBT, SPEED, OPPOSING, TARGET, TARGET_AT, TARGET_NBT,
 SAMPLES, INTERVAL, LENGTH, PLATFORM, KEEP, TEARDOWN_ONLY, TRAIN) = sys.argv[3:18]

SPEED = float(SPEED)
TARGET_AT, SAMPLES, LENGTH, PLATFORM, TRAIN = (
    int(x) for x in (TARGET_AT, SAMPLES, LENGTH, PLATFORM, TRAIN))
INTERVAL = float(INTERVAL)
KEEP, TEARDOWN_ONLY = KEEP == "1", TEARDOWN_ONLY == "1"

# Empty sky in the dev world, well clear of spawn and of any pre-existing track: an earlier attempt
# to test collision against the world's own rails was unreadable because carts hit terrain.
OX, OY, OZ = 100, 150, 200
RAIL_Y = OY + 1
CORNER_LEN = 32 if COURSE == "corner" else 0
TAG = "mtrig"

# Centre-to-centre spacing the coupling settles a train to, so cars start where they will sit.
CAR_SPACING = 2.0

X0, X1 = OX, OX + LENGTH - 1
Z_MAIN = OZ
Z_PARALLEL = OZ - 1                       # corner course only
Z_LEG_END = OZ + CORNER_LEN               # corner course only

# Bounding box the rig owns: everything cleared, platformed, forceloaded and torn down again.
BX0, BX1 = X0 - PLATFORM, X1 + PLATFORM
BZ0, BZ1 = Z_PARALLEL - PLATFORM, Z_LEG_END + PLATFORM
CLEAR_TOP = OY + 3


class Rcon:
    def __init__(self, port, password):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=10)
        self._send(1, 3, password)
        if self._recv()[0] == -1:
            raise SystemExit("rcon authentication failed")

    def _send(self, req_id, req_type, payload):
        body = struct.pack("<ii", req_id, req_type) + payload.encode() + b"\x00\x00"
        self.sock.sendall(struct.pack("<i", len(body)) + body)

    def _read(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise SystemExit("rcon connection closed")
            buf += chunk
        return buf

    def _recv(self):
        length = struct.unpack("<i", self._read(4))[0]
        body = self._read(length)
        return struct.unpack("<i", body[:4])[0], body[8:-2].decode(errors="replace")

    def __call__(self, command):
        self._send(2, 2, command)
        return self._recv()[1].strip()


def run(rcon, commands, echo=True):
    for command in commands:
        reply = rcon(command)
        if echo and reply:
            print(f"  {reply}")


def build(rcon):
    print(f"building {COURSE} course at {OX} {OY} {OZ}")
    setup = [
        f"forceload add {BX0} {BZ0} {BX1} {BZ1}",
        "difficulty normal",
        f"fill {BX0} {OY} {BZ0} {BX1} {CLEAR_TOP} {BZ1} minecraft:air",
        f"fill {BX0} {OY} {BZ0} {BX1} {OY} {BZ1} minecraft:stone",
        f"fill {X0} {RAIL_Y} {Z_MAIN} {X1} {RAIL_Y} {Z_MAIN} minecraft:rail[shape=east_west]",
    ]

    if COURSE == "corner":
        setup += [
            # Vanilla re-shapes rails on placement update, so these are the intent rather than a
            # guarantee; the corner is set last so it wins over the straight fill.
            f"fill {X0} {RAIL_Y} {Z_PARALLEL} {X1} {RAIL_Y} {Z_PARALLEL} "
            f"minecraft:rail[shape=east_west]",
            f"fill {X1} {RAIL_Y} {Z_MAIN + 1} {X1} {RAIL_Y} {Z_LEG_END} "
            f"minecraft:rail[shape=north_south]",
            f"setblock {X1} {RAIL_Y} {Z_MAIN} minecraft:rail[shape=south_west]",
        ]

    run(rcon, setup)


def summon(rcon):
    tags = f'Tags:["{TAG}","%s"]'
    extra = ("," + CART_NBT.strip()[1:-1]) if CART_NBT.strip() else ""
    target_extra = ("," + TARGET_NBT.strip()[1:-1]) if TARGET_NBT.strip() else ""
    cart_x, cart_z = X0 + 0.5, Z_MAIN + 0.5
    commands = []

    # Rearmost car first, so the front one - the car the table follows - leads into the target.
    for car in range(TRAIN):
        own = "mtrig_cart" if car == TRAIN - 1 else f"mtrig_car{car}"
        commands.append(
            f"summon minecraft:{CART} {cart_x + car * CAR_SPACING} {RAIL_Y} {cart_z} "
            f'{{Tags:["{TAG}","mtrig_train","{own}"],Motion:[{SPEED}d,0.0d,0.0d]{extra}}}')

    if TARGET != "none":
        # Measured from the front of the train, not from where the rearmost car starts, or a long
        # train would be summoned straddling its own target.
        front = cart_x + (TRAIN - 1) * CAR_SPACING
        commands.append(
            f"summon minecraft:{TARGET} {front + TARGET_AT} {RAIL_Y} {cart_z} "
            f'{{{tags % "mtrig_target"},PersistenceRequired:1b{target_extra}}}'
        )

    if OPPOSING:
        commands.append(
            f"summon minecraft:{CART} {X1 + 0.5} {RAIL_Y} {cart_z} "
            f'{{{tags % "mtrig_cart2"},Motion:[-{float(OPPOSING)}d,0.0d,0.0d]{extra}}}'
        )

    run(rcon, commands)
    time.sleep(0.15)    # summoned entities only join the level at the end of the tick
    couple(rcon)


def couple(rcon):
    """Links the train's cars into one, by writing the link attachment each car persists.

    Done over NBT rather than by shift-right-clicking a chain, so a train needs no player and no
    debug hook in the mod. Each car holds its two neighbours' UUIDs; the ends hold one.
    """
    if TRAIN < 2:
        return

    names = [f"mtrig_car{car}" for car in range(TRAIN)]
    names[-1] = "mtrig_cart"
    uuids = []

    for name in names:
        reply = rcon(f"data get entity @e[tag={name},limit=1] UUID")
        uuids.append(reply.split("following entity data:", 1)[1].strip())

    for car, name in enumerate(names):
        slots = []
        if car > 0:
            slots.append(f"slotA:{uuids[car - 1]}")
        if car < TRAIN - 1:
            slots.append(f"{'slotB' if car > 0 else 'slotA'}:{uuids[car + 1]}")
        rcon(f'data merge entity @e[tag={name},limit=1] '
             f'{{"fabric:attachments":{{"polytrains:link":{{{",".join(slots)}}}}}}}')

    print(f"  coupled {TRAIN} cars")


NUMBERS = re.compile(r"-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?")


def data(rcon, tag, path):
    reply = rcon(f"data get entity @e[tag={tag},limit=1] {path}")
    if "following entity data" not in reply:
        return None
    return [float(n) for n in NUMBERS.findall(reply.split("following entity data:", 1)[1])]


def poll(rcon):
    probes = [("cart", "mtrig_cart")]
    if OPPOSING:
        probes.append(("cart2", "mtrig_cart2"))
    if TARGET != "none":
        probes.append(("target", "mtrig_target"))

    header = f"{'t':>6}"
    for label, _ in probes:
        third = "hp" if label == "target" else "bpt"
        for column in ("x", "z", third):
            header += f"{label + ' ' + column:>11}"
    print()
    print(header)

    start = time.monotonic()
    for i in range(SAMPLES):
        row = f"{time.monotonic() - start:>6.1f}"
        for label, tag in probes:
            pos = data(rcon, tag, "Pos")
            row += f"{pos[0]:>11.3f}{pos[2]:>11.3f}" if pos else f"{'-':>11}{'-':>11}"

            if label == "target":
                hp = data(rcon, tag, "Health")
                row += f"{hp[0]:>11.2f}" if hp else f"{'-':>11}"
            else:
                motion = data(rcon, tag, "Motion")
                speed = sum(c * c for c in motion) ** 0.5 if motion else None
                row += f"{speed:>11.4f}" if speed is not None else f"{'-':>11}"
        print(row, flush=True)

        remaining = start + (i + 1) * INTERVAL - time.monotonic()
        if remaining > 0:
            time.sleep(remaining)


def teardown(rcon):
    print("\ntearing down")
    run(rcon, [
        f"kill @e[tag={TAG}]",
        f"fill {BX0} {OY} {BZ0} {BX1} {CLEAR_TOP} {BZ1} minecraft:air",
        f"forceload remove {BX0} {BZ0} {BX1} {BZ1}",
    ])


rcon = Rcon(PORT, PASSWORD)

if TEARDOWN_ONLY:
    teardown(rcon)
    raise SystemExit(0)

try:
    build(rcon)
    summon(rcon)
    poll(rcon)
finally:
    if KEEP:
        print("\n--keep: course left standing; tear it down with --teardown")
    else:
        teardown(rcon)
PY
