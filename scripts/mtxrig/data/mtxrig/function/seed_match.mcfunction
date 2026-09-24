# Matched by position rather than against the message list, so [mtxrig] has to be the top line and
# the group the second. Matching the list itself would accept either word anywhere on the sign.
#
# Claimed only once, the same check the spread makes: two signs naming one cell — or one sign beside
# a corner, reaching it from two sides — would otherwise anchor it twice and place two carts on it.
$execute unless entity @e[tag=mtx_anchor,distance=..0.4] if data storage mtxrig:sign {line0:"[mtxrig]",line1:"$(group)"} run summon minecraft:marker ~ ~ ~ {Tags:["mtx_anchor"]}
