# The group is not read here, only the header line, so one sweep reports every group at once. Three
# heights are tried rather than the one spawn insists on, so a sign put somewhere else still shows.
execute positioned ~ ~1 ~ run function mtxrig:find_sign
execute positioned ~ ~2 ~ run function mtxrig:find_sign
execute positioned ~ ~3 ~ run function mtxrig:find_sign
