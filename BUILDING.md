# Building

```
./gradlew build     # versions/<v>/build/libs/polytrains-<version>+<v>.jar, one per version
```

The house build is documented once for every first-party mod in the workspace's `docs/STYLE.md`.
This file covers only where this mod departs from it.

- **Multi-version build** (Stonecutter): 26.2 and 26.3, with 26.2 committed. Each version's keys
  are in `versions/<v>/gradle.properties`.
  - 26.3 builds against Polymer 0.18.4 from Maven.
  - Dev servers run under `versions/<v>/run`, each on the first two free ports in
    `dev_server_ports`, written to its `run/server.properties`.
  - `scripts/mtxrcon.py` (and through it `torture.py`, `perf.py` and `rig-snapshot.py`),
    `scripts/rig-sync.sh` and `scripts/collision-rig.sh` take `DEV_MC=26.3` for the 26.3 server.
  - The two rail loot tables hold both 26.2's `conditions` list and 26.3's single `condition`,
    since JSON has no version blocks. Each version ignores the other's key.
- **`mavenLocal()` dependency.** kalic0re's `display`, `aim`, `glow`, `vehicle`, `rule`, `config`,
  `text` and `command` packages are relocated into the jar from
  `club.rainbowkitty.kalic0re:kalic0re:<kalic0re_version>` in `~/.m2`: `0.31.0` for 26.2 and
  `0.31.0+26.3` for 26.3.
  - A fresh clone first needs `./gradlew publishToMavenLocal` in kalic0re: its single-version
    0.31.0 build for 26.2, and its multi-version build for 26.3.
  - A stale copy shows up as a compile error naming a missing kalic0re class or method.
- **The jar to install** is `polytrains-<version>+<v>.jar` for the server's version.
  `-unshaded` and `-sources` beside it are not.
- **No datagen.** Models, lang, recipes, tags and loot tables are static resources.
