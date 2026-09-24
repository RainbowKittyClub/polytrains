# Moves or resizes the box the scan walks, e.g.
# /function mtxrig:box {x:-64,y:-53,z:-32,w:96,d:80}
# A bigger box costs commands per cell in every pass, so keep it to the yard in use.
#
# Storage keeps whatever box was last set here: load.mcfunction's default applies only to a world
# that has none, so a changed default reaches an existing world only once this is called again.
$data modify storage mtxrig:box x set value $(x)
$data modify storage mtxrig:box y set value $(y)
$data modify storage mtxrig:box z set value $(z)
$data modify storage mtxrig:box w set value $(w)
$data modify storage mtxrig:box d set value $(d)
