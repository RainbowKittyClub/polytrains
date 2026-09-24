#!/usr/bin/env bash
# Copies the rig datapack in scripts/mtxrig/ into a dev server's world and reloads it over rcon.
#
# Usage: scripts/rig-sync.sh [RUN_DIR | MOD_DIR]
#
# Without an argument it syncs the main checkout's run/, found through git's common directory, so it
# works the same from a worktree, which has no run/ of its own. The functions and tags replace
# <run>/<level-name>/datapacks/mtxrig/ outright, so a function deleted here goes there too. Templates
# in scripts/mtxrig/structures/ go to <run>/<level-name>/generated/mtxrig/structure/ (singular), the
# directory /place template reads a world's own templates from.
#
# Restoring the torture track from its snapshot, with the dev server up:
#   scripts/rig-sync.sh
#   /place template mtxrig:torturetrack -21 -58 -29
# By hand: copy scripts/mtxrig/structures/torturetrack.nbt to run/world/generated/mtxrig/structure/,
# /reload, then place. The template covers x -21..2, y -58..-45, z -29..0 and sets every block there,
# air included. It holds no entities: the trains come from mtxrig:rig as usual.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ $# -gt 1 || ${1:-} == -h || ${1:-} == --help ]]; then
    sed -n '2,17p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit $(( $# > 1 ? 1 : 0 ))
fi

if [[ $# -eq 1 ]]; then
    if [[ -f $1/server.properties ]]; then
        run_dir=$1
    elif [[ -f $1/run/server.properties ]]; then
        run_dir=$1/run
    else
        echo "no server.properties in $1 or $1/run" >&2
        exit 2
    fi
else
    common=$(git -C "$here" rev-parse --path-format=absolute --git-common-dir)
    run_dir="$(dirname "$common")/run"
fi

[[ -f $run_dir/server.properties ]] || {
    echo "no $run_dir/server.properties - start the dev server once first" >&2
    exit 2
}
run_dir=$(cd "$run_dir" && pwd)

level=$(grep -m1 '^level-name=' "$run_dir/server.properties" | cut -d= -f2- || true)
world="$run_dir/${level:-world}"
[[ -d $world ]] || { echo "no world at $world" >&2; exit 2; }

pack="$world/datapacks/mtxrig"
mkdir -p "$pack"
# Excluded paths are also spared by --delete, so a session's .claude/ state there survives.
rsync -a --delete --exclude .claude/ --exclude structures/ "$here/mtxrig/" "$pack/"
echo "datapack -> $pack"

shopt -s nullglob
templates=("$here"/mtxrig/structures/*.nbt)
if (( ${#templates[@]} )); then
    mkdir -p "$world/generated/mtxrig/structure"
    cp "${templates[@]}" "$world/generated/mtxrig/structure/"
    echo "templates -> $world/generated/mtxrig/structure/ (${#templates[@]})"
fi

exec python3 -B - "$here" "$run_dir" <<'PY'
import sys
import time

sys.path.insert(0, sys.argv[1])
import mtxrcon

try:
    rcon = mtxrcon.connect(sys.argv[2])
except mtxrcon.RconDead as e:
    sys.exit(f"files copied, not reloaded: {e}")

with rcon:
    rcon.cmd("data modify storage mtxrig:sync pending set value 1b")
    print(rcon.cmd("reload"))
    deadline = time.monotonic() + 60
    while not rcon.cmd("data get storage mtxrig:sync pending").startswith("Found no elements"):
        if time.monotonic() > deadline:
            sys.exit("reload did not finish in 60 s, or mtxrig:load failed to run; "
                     "check logs/latest.log for 'Failed to load function'")
        time.sleep(0.1)
    print("reloaded")
PY
