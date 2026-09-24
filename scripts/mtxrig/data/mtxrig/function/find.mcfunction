# Leaves a marker on every [mtxrig] sign in the box whatever group it names, for finding where the
# rig's strips actually are. Markers stay until the next spawn or reset clears them.
kill @e[tag=mtx_found]
scoreboard players set #pass mtxrig 3
function mtxrig:scan_start with storage mtxrig:box
