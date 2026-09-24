# The whole cycle for one group: clear that group, place it from its markers, fuel what burns.
# Called as /function mtxrig:rig {group:"<name>"}, where <name> is the second line of the sign
# standing on the marker strips it should place.
$function mtxrig:reset {group:"$(group)"}
$function mtxrig:spawn {group:"$(group)"}
$function mtxrig:fuel {group:"$(group)"}
