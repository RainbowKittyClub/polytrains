# Tops up one group's engines. Called as /function mtxrig:fuel {group:"<name>"}.
#
# The ceiling is read from the rule rather than written in here: vanilla's 32000 is the limit of the
# short it keeps burn time in, and furnace_max_burn_time raises past it.
$data modify storage mtxrig:args group set value "$(group)"
execute store result storage mtxrig:args fuel int 1 run gamerule polytrains:furnace_max_burn_time
function mtxrig:fuel_set with storage mtxrig:args
