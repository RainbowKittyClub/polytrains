# Called once per marker cell, whether or not a cart was placed there, so it does nothing at all
# unless there is a new cart to couple. Without this guard an empty cell would clear mtx_prev and
# break the chain between two markers that are only separated by unmarked track.
execute if entity @e[tag=mtx_new] run function mtxrig:couple_now
