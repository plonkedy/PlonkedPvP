package dev.plonked.pvparena;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed;

public final class PvpCommands implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS =
            List.of("create", "list", "delete", "rename", "setspawn", "clean", "reload");

    private final PvpArenaPlugin plugin;
    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();

    public PvpCommands(PvpArenaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        Notifier n = plugin.notifier();
        if (!sender.hasPermission("pvparena.admin")) {
            n.msg(sender, "no-permission");
            return true;
        }
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "pvp1" -> setPos(sender, true);
            case "pvp2" -> setPos(sender, false);
            default -> arenaCommand(sender, args);
        }
        return true;
    }

    // ------------------------------------------------------------ /pvp1 /pvp2

    private void setPos(CommandSender sender, boolean first) {
        Notifier n = plugin.notifier();
        if (!(sender instanceof Player p)) {
            n.msg(sender, "player-only");
            return;
        }
        Location loc = p.getLocation().getBlock().getLocation();
        (first ? pos1 : pos2).put(p.getUniqueId(), loc);
        n.msg(p, first ? "pos1-set" : "pos2-set",
                unparsed("x", String.valueOf(loc.getBlockX())),
                unparsed("y", String.valueOf(loc.getBlockY())),
                unparsed("z", String.valueOf(loc.getBlockZ())),
                unparsed("world", loc.getWorld().getName()));

        if (plugin.getConfig().getBoolean("selection.auto-create", true)
                && pos1.containsKey(p.getUniqueId()) && pos2.containsKey(p.getUniqueId())) {
            tryCreate(p, null);
        }
    }

    private void tryCreate(Player p, String name) {
        Notifier n = plugin.notifier();
        ArenaManager am = plugin.arenas();
        Location a = pos1.get(p.getUniqueId());
        Location b = pos2.get(p.getUniqueId());
        if (a == null || b == null) {
            n.msg(p, "need-both-points");
            return;
        }
        if (!a.getWorld().equals(b.getWorld())) {
            n.msg(p, "worlds-differ");
            return;
        }
        String arenaName = name != null ? name : am.uniqueName();
        if (!am.isValidName(arenaName)) {
            n.msg(p, "invalid-name");
            return;
        }
        if (am.getByName(arenaName) != null) {
            n.msg(p, "arena-exists", unparsed("arena", arenaName));
            return;
        }
        Arena arena = new Arena(arenaName, a.getWorld().getName(),
                a.getBlockX(), a.getBlockY(), a.getBlockZ(),
                b.getBlockX(), b.getBlockY(), b.getBlockZ());
        am.add(arena);
        pos1.remove(p.getUniqueId());
        pos2.remove(p.getUniqueId());
        n.msg(p, "arena-created", unparsed("arena", arena.getName()), unparsed("size", arena.sizeString()));
    }

    // ------------------------------------------------------------ /pvparena

    private void arenaCommand(CommandSender s, String[] args) {
        Notifier n = plugin.notifier();
        ArenaManager am = plugin.arenas();
        if (args.length == 0) {
            n.msg(s, "usage");
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (!(s instanceof Player p)) {
                    n.msg(s, "player-only");
                    return;
                }
                tryCreate(p, args.length > 1 ? args[1] : null);
            }
            case "list" -> {
                if (am.all().isEmpty()) {
                    n.msg(s, "list-empty");
                    return;
                }
                n.msg(s, "list-header", unparsed("count", String.valueOf(am.all().size())));
                for (Arena a : am.all()) {
                    n.msg(s, "list-entry",
                            unparsed("arena", a.getName()),
                            unparsed("world", a.getWorldName()),
                            unparsed("min", a.getMinX() + "," + a.getMinY() + "," + a.getMinZ()),
                            unparsed("max", a.getMaxX() + "," + a.getMaxY() + "," + a.getMaxZ()));
                }
            }
            case "delete" -> {
                if (args.length < 2) {
                    n.msg(s, "usage");
                    return;
                }
                Arena a = am.getByName(args[1]);
                if (a == null) {
                    n.msg(s, "arena-not-found", unparsed("arena", args[1]));
                    return;
                }
                plugin.cleanup().cleanupArena(a, false);
                am.remove(a);
                n.msg(s, "arena-deleted", unparsed("arena", a.getName()));
            }
            case "rename" -> {
                if (args.length < 3) {
                    n.msg(s, "usage");
                    return;
                }
                Arena a = am.getByName(args[1]);
                if (a == null) {
                    n.msg(s, "arena-not-found", unparsed("arena", args[1]));
                    return;
                }
                if (!am.isValidName(args[2])) {
                    n.msg(s, "invalid-name");
                    return;
                }
                if (am.getByName(args[2]) != null) {
                    n.msg(s, "arena-exists", unparsed("arena", args[2]));
                    return;
                }
                String old = a.getName();
                am.rename(a, args[2]);
                plugin.cleanup().renameArena(old, args[2]);
                plugin.tracker().renameArena(old, args[2]);
                n.msg(s, "arena-renamed", unparsed("old", old), unparsed("arena", args[2]));
            }
            case "setspawn" -> {
                if (!(s instanceof Player p)) {
                    n.msg(s, "player-only");
                    return;
                }
                Arena a = args.length > 1 ? am.getByName(args[1]) : am.getAt(p.getLocation());
                if (a == null) {
                    n.msg(s, args.length > 1 ? "arena-not-found" : "not-in-arena",
                            unparsed("arena", args.length > 1 ? args[1] : ""));
                    return;
                }
                a.setSpawn(p.getLocation());
                am.save();
                n.msg(s, "spawn-set", unparsed("arena", a.getName()));
            }
            case "clean" -> {
                if (args.length > 1) {
                    Arena a = am.getByName(args[1]);
                    if (a == null) {
                        n.msg(s, "arena-not-found", unparsed("arena", args[1]));
                        return;
                    }
                    plugin.cleanup().cleanupArena(a, true);
                    n.msg(s, "cleaned", unparsed("arena", a.getName()));
                } else {
                    plugin.cleanup().cleanAll();
                    plugin.cleanup().resetTimer();
                    n.msg(s, "cleaned", unparsed("arena", "all arenas"));
                }
            }
            case "reload" -> {
                plugin.reload();
                n.msg(s, "reloaded");
            }
            default -> n.msg(s, "usage");
        }
    }

    // ------------------------------------------------------------ tab complete

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("pvparena.admin") || !cmd.getName().equalsIgnoreCase("pvparena")) return out;

        if (args.length == 1) {
            for (String sub : SUBS) {
                if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(sub);
            }
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("delete") || sub.equals("rename") || sub.equals("setspawn") || sub.equals("clean")) {
                for (Arena a : plugin.arenas().all()) {
                    if (a.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                        out.add(a.getName());
                    }
                }
            }
        }
        return out;
    }
}
