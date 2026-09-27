# Counters for the search. Dummy objective, never shown.
scoreboard objectives add mtxrig dummy

# How far round wherever it runs the rig looks for signs. Seeded here rather than written into the
# search, so mtxrig:radius can change it without touching a function.
execute unless data storage mtxrig:search r run function mtxrig:radius {r:16}

# rig-sync.sh sets this before a reload and waits for it to go: /reload answers before it finishes,
# and this function runs only once the new pack has loaded.
data remove storage mtxrig:sync pending
