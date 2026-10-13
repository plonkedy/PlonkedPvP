# PlonkedPvP  (author: Plonked)
Paper 1.21.11 plugin - make PvP arenas with /pvp1 and /pvp2.

## Build
Requires JDK 21 + Maven:
    mvn clean package
Jar: target/PlonkedPvP.jar  -> drop in your server's /plugins folder.

## Commands (permission: pvparena.admin, default OP)
/pvp1, /pvp2             set the two corners (your feet block), arena is created when both are set
/pvparena create [name]  create from the two points (when selection.auto-create is false)
/pvparena list | delete <name> | rename <old> <new>
/pvparena setspawn [name] respawn point for the arena
/pvparena clean [name]   clear now
/pvparena reload         reload config.yml
Permission pvparena.bypass (default: nobody) ignores build/drop/container limits.
