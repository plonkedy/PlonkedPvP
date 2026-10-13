package dev.plonked.pvparena;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class ArenaManager {

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_\\-]{1,32}");

    private final PvpArenaPlugin plugin;
    private final File file;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();

    public ArenaManager(PvpArenaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "arenas.yml");
    }

    public void load() {
        arenas.clear();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yml.getConfigurationSection("arenas");
        if (root == null) return;
        for (String name : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(name);
            if (s == null) continue;
            String world = s.getString("world");
            if (world == null) continue;
            Arena a = new Arena(name, world,
                    s.getInt("pos1.x"), s.getInt("pos1.y"), s.getInt("pos1.z"),
                    s.getInt("pos2.x"), s.getInt("pos2.y"), s.getInt("pos2.z"));
            ConfigurationSection sp = s.getConfigurationSection("spawn");
            if (sp != null) {
                a.setSpawnRaw(sp.getDouble("x"), sp.getDouble("y"), sp.getDouble("z"),
                        (float) sp.getDouble("yaw"), (float) sp.getDouble("pitch"));
            }
            arenas.put(name.toLowerCase(Locale.ROOT), a);
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Arena a : arenas.values()) {
            String p = "arenas." + a.getName();
            yml.set(p + ".world", a.getWorldName());
            yml.set(p + ".pos1.x", a.getMinX());
            yml.set(p + ".pos1.y", a.getMinY());
            yml.set(p + ".pos1.z", a.getMinZ());
            yml.set(p + ".pos2.x", a.getMaxX());
            yml.set(p + ".pos2.y", a.getMaxY());
            yml.set(p + ".pos2.z", a.getMaxZ());
            if (a.hasSpawn()) {
                double[] r = a.spawnRaw();
                yml.set(p + ".spawn.x", r[0]);
                yml.set(p + ".spawn.y", r[1]);
                yml.set(p + ".spawn.z", r[2]);
                yml.set(p + ".spawn.yaw", r[3]);
                yml.set(p + ".spawn.pitch", r[4]);
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save arenas.yml: " + ex.getMessage());
        }
    }

    public boolean fullHeight() {
        return plugin.getConfig().getBoolean("arena.full-height", true);
    }

    public Collection<Arena> all() { return arenas.values(); }

    public Arena getByName(String name) {
        return name == null ? null : arenas.get(name.toLowerCase(Locale.ROOT));
    }

    public Arena getAt(Location loc) {
        if (loc == null || arenas.isEmpty()) return null;
        boolean fh = fullHeight();
        for (Arena a : arenas.values()) {
            if (a.contains(loc, fh)) return a;
        }
        return null;
    }

    public boolean isValidName(String name) {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    public void add(Arena a) {
        arenas.put(a.getName().toLowerCase(Locale.ROOT), a);
        save();
    }

    public void remove(Arena a) {
        arenas.remove(a.getName().toLowerCase(Locale.ROOT));
        save();
    }

    public void rename(Arena a, String newName) {
        arenas.remove(a.getName().toLowerCase(Locale.ROOT));
        a.setName(newName);
        arenas.put(newName.toLowerCase(Locale.ROOT), a);
        save();
    }

    public String uniqueName() {
        String fmt = plugin.getConfig().getString("selection.default-name-format", "arena%d");
        for (int i = 1; i < 10000; i++) {
            String n;
            try {
                n = String.format(fmt, i);
            } catch (Exception ex) {
                n = "arena" + i;
            }
            if (getByName(n) == null) return n;
        }
        return "arena" + System.currentTimeMillis();
    }
}
