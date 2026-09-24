# A sign a block higher, on a post or a wall rather than on one of the strip's own markers.
data modify storage mtxrig:sign line0 set from block ~ ~2 ~ front_text.messages[0]
data modify storage mtxrig:sign line1 set from block ~ ~2 ~ front_text.messages[1]
function mtxrig:seed_beside
