package dev.plonked.pvparena;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tracks every block / water / lava change inside arenas and puts the arena
 * back to normal on a timer, with a countdown for players inside.
 */
public final class CleanupManager {

    private final PvpArenaPlugin plugin;
    /** arena key -> (block location -> ORIGINAL state of that block) */
    private final Map<String, Map<Location, BlockState>> originals = new HashMap<>();
    /** blocks placed by players (used for break-mode PLACED_ONLY) */
    private final Set<Location> placed = new HashSet<>();

    private BukkitTask task;
    private int remaining;

    public CleanupManager(PvpArenaPlugin plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() { return plugin.getConfig(); }

    private static String key(Arena a) { return a.getName().toLowerCase(Locale.ROOT); }

    private int interval() { return Math.max(5, cfg().getInt("cleanup.interval-seconds", 300)); }

    private boolean tracking() { return cfg().getBoolean("cleanup.clear-blocks", true); }

    // ------------------------------------------------------------ timer

    public void start() {
        stop();
        if (!cfg().getBoolean("cleanup.enabled", true)) return;
        remaining = interval();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void shutdown() {
        stop();
        if (cfg().getBoolean("cleanup.restore-on-disable", true)) {
            restoreAllBlocks();
        }
    }

    public void resetTimer() {
        remaining = interval();
    }

    private void tick() {
        remaining--;
        if (remaining <= 0) {
            cleanAll();
            remaining = interval();
            return;
        }
        if (cfg().getIntegerList("cleanup.countdown-times").contains(remaining)) {
            for (Arena a : plugin.arenas().all()) {
                for (Player p : plugin.tracker().playersIn(a)) {
                    plugin.notifier().send(p, "countdown",
                            Placeholder.unparsed("seconds", String.valueOf(remaining)),
                            Placeholder.unparsed("arena", a.getName()));
                }
            }
        }
    }

    // ------------------------------------------------------------ tracking

    public void markPlaced(Location blockLoc) {
        placed.add(blockLoc);
    }

    public boolean isPlaced(Location blockLoc) {
        return placed.contains(blockLoc);
    }

    /** Remember the block as it is RIGHT NOW (call before it changes). */
    public void recordBlock(Block b) {
        if (!tracking()) return;
        Arena a = plugin.arenas().getAt(b.getLocation());
        if (a == null) return;
        Map<Location, BlockState> m = originals.computeIfAbsent(key(a), k -> new LinkedHashMap<>());
        Location loc = b.getLocation();
        if (!m.containsKey(loc)) m.put(loc, b.getState());
    }

    /** Remember an already-captured state (e.g. the "replaced" state of a placement). */
    public void record(BlockState st) {
        if (!tracking()) return;
        Location loc = st.getLocation();
        Arena a = plugin.arenas().getAt(loc);
        if (a == null) return;
        Map<Location, BlockState> m = originals.computeIfAbsent(key(a), k -> new LinkedHashMap<>());
        m.putIfAbsent(loc, st);
    }

    public void renameArena(String oldName, String newName) {
        Map<Location, BlockState> m = originals.remove(oldName.toLowerCase(Locale.ROOT));
        if (m != null) originals.put(newName.toLowerCase(Locale.ROOT), m);
    }

    // ------------------------------------------------------------ cleaning

    public void cleanAll() {
        for (Arena a : plugin.arenas().all()) {
            cleanupArena(a, true);
        }
    }

    public void cleanupArena(Arena a, boolean notify) {
        boolean full = plugin.arenas().fullHeight();

        // 1) put every changed block / water / lava back
        Map<Location, BlockState> m = originals.remove(key(a));
        if (m != null) {
            for (BlockState st : m.values()) {
                try {
                    st.update(true, false);
                } catch (Exception ex) {
                    plugin.getLogger().warning("Could not restore block at " + st.getLocation() + ": " + ex.getMessage());
                }
            }
        }
        placed.removeIf(l -> a.contains(l, full));

        // 2) remove dropped items / arrows / etc. (after blocks, so popped-off drops are caught)
        if (cfg().getBoolean("cleanup.clear-entities", true)) {
            World w = a.getWorld();
            if (w != null) {
                Set<EntityType> types = entityTypes();
                if (!types.isEmpty()) {
                    for (Entity e : w.getNearbyEntities(a.boundingBox(full))) {
                        if (types.contains(e.getType())) e.remove();
                    }
                }
            }
        }

        // 3) tell people in the arena
        if (notify) {
            for (Player p : plugin.tracker().playersIn(a)) {
                plugin.notifier().send(p, "cleanup-done", Placeholder.unparsed("arena", a.getName()));
            }
        }
    }

    public void restoreAllBlocks() {
        for (Map<Location, BlockState> m : originals.values()) {
            for (BlockState st : m.values()) {
                try {
                    st.update(true, false);
                } catch (Exception ignored) {
                }
            }
        }
        originals.clear();
        placed.clear();
    }

    private Set<EntityType> entityTypes() {
        Set<EntityType> set = new HashSet<>();
        for (String s : cfg().getStringList("cleanup.remove-entities")) {
            NamespacedKey k = NamespacedKey.fromString(s.trim().toLowerCase(Locale.ROOT));
            if (k == null) continue;
            EntityType t = Registry.ENTITY_TYPE.get(k);
            if (t != null) {
                set.add(t);
            } else {
                plugin.getLogger().warning("Unknown entity type in cleanup.remove-entities: " + s);
            }
        }
        return set;
    }
}
