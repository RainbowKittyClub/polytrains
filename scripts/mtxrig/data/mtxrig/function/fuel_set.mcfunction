# Written to RealFuel, not Fuel. The mod keeps burn time in its own child compound and reads that
# back over whatever vanilla's short holds, so a merge to Fuel alone is overwritten as it lands and
# the cart keeps the fuel it already had. Fuel needs no write of its own: vanilla saves it from the
# same field on the next save.
$execute as @e[tag=mtx,tag=mtx_$(group),type=minecraft:furnace_minecart] run data merge entity @s {serversidetrains:{RealFuel:$(fuel)}}
