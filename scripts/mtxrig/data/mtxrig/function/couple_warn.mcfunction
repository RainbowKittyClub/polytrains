# A cart with a third neighbour, or one beside a cart that already has two: the marker strips branch
# or touch, and a train is a line. Counted as well, because rcon never sees a tellraw.
tellraw @a [{text:"[mtxrig] ",color:"gold"},{text:"no link slot left to couple the carts at "},{nbt:"Pos",entity:"@e[tag=mtx_new,limit=1]"},{text:" and "},{nbt:"Pos",entity:"@e[tag=mtx_mate,limit=1]"},{text:"; left uncoupled"}]
scoreboard players add #unlinked mtxrig 1
