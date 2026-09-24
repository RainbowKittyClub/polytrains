# A sign standing on top of a wool marker.
data modify storage mtxrig:sign line0 set from block ~ ~2 ~ front_text.messages[0]
data modify storage mtxrig:sign line1 set from block ~ ~2 ~ front_text.messages[1]
function mtxrig:seed_match with storage mtxrig:args
