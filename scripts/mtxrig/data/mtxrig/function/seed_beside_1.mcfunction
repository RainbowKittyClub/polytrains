# A sign at marker level but clear of the track.
data modify storage mtxrig:sign line0 set from block ~ ~1 ~ front_text.messages[0]
data modify storage mtxrig:sign line1 set from block ~ ~1 ~ front_text.messages[1]
function mtxrig:seed_beside
