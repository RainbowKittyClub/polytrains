# Counters for the scan. Dummy objective, never shown.
scoreboard objectives add mtxrig dummy

# The box every pass walks, as its north-west corner at RAIL level plus a width (x) and depth (z).
# Markers sit one block above the rail and signs two, so only the rail level is walked. Seeded here
# rather than written into the scan, so mtxrig:box can move it without touching a function.
execute unless data storage mtxrig:box w run function mtxrig:box {x:-24,y:-53,z:-24,w:49,d:53}
