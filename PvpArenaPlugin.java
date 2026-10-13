package dev.plonked.pvparena;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * PlonkedPvP - PvP arenas made with /pvp1 and /pvp2.
 * Author: Plonked
 */
public final class PvpArenaPlugin extends JavaPlugin {

    private Notifier notifier;
    private ArenaManager arenas;
    private CleanupManager cleanup;
    private ArenaTracker tracker;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        notifier = new Notifier(this);
        arenas = new ArenaManager(this);
        cleanup = new CleanupManager(this);
        tracker = new ArenaTracker(this);
        arenas.load();

        getServer().getPluginManager().registerEvents(new ArenaListener(this), this);

        PvpCommands commands = new PvpCommands(this);
        for (String name : new String[]{"pvp1", "pvp2", "pvparena"}) {
            PluginCommand pc = getCommand(name);
            if (pc != null) {
                pc.setExecutor(commands);
                pc.setTabCompleter(commands);
            }
        }

        cleanup.start();
        tracker.start();
        getLogger().info("PlonkedPvP enabled - " + arenas.all().size() + " arena(s) loaded. Author: Plonked");
    }

    @Override
    public void onDisable() {
        if (tracker != null) tracker.shutdown();
        if (cleanup != null) cleanup.shutdown();
    }

    public void reload() {
        reloadConfig();
        arenas.load();
        cleanup.start();
        tracker.start();
    }

    public Notifier notifier() { return notifier; }
    public ArenaManager arenas() { return arenas; }
    public CleanupManager cleanup() { return cleanup; }
    public ArenaTracker tracker() { return tracker; }
}
