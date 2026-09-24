# One neighbouring block, marked if it is rail and nothing has marked it yet.
execute unless entity @e[tag=mtx_trace,distance=..0.4] if block ~ ~ ~ #minecraft:rails run summon minecraft:marker ~ ~ ~ {Tags:["mtx_trace"]}
