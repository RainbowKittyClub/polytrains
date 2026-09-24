# The whole cycle for one group: clear that group, place it from its markers, fuel what burns.
# Called as /function mtxrig:rig {group:"<name>"}, where <name> is the second line of the sign
# standing on the marker strips it should place.
#
# Cars on neighbouring cells couple, so strips that touch come out as one train. A sign belongs at a
# strip's end or in its line: one standing on the rail inside a straight run is bridged, but one on
# a corner of the strip is not, and cuts it in two.
$function mtxrig:reset {group:"$(group)"}
$function mtxrig:spawn {group:"$(group)"}
$function mtxrig:fuel {group:"$(group)"}
