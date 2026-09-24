# One neighbouring cell, anchored if it continues the strip. Claimed only once: without that check
# two anchors would hand the same cell back and forth and spend the generation budget without ever
# reaching the far end of the strip.
execute unless entity @e[tag=mtx_anchor,distance=..0.4] if block ~ ~ ~ #minecraft:rails if block ~ ~1 ~ #mtxrig:markers run summon minecraft:marker ~ ~ ~ {Tags:["mtx_anchor"]}
