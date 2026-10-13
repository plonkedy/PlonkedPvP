package dev.plonked.pvparena;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class ArenaListener implements Listener {

    private final PvpArenaPlugin plugin;
    private final Map<UUID, String> deathArena = new HashMap<>();

    public ArenaListener(PvpArenaPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ helpers

    private FileConfiguration cfg() { return plugin.getConfig(); }
    private ArenaManager arenas() { return plugin.arenas(); }
    private CleanupManager cleanup() { return plugin.cleanup(); }

    private Arena arenaOf(Player p) {
        return p.getGameMode() == GameMode.SPECTATOR ? null : arenas().getAt(p.getLocation());
    }

    private boolean bypass(Player p) {
        return p.hasPermission("pvparena.bypass");
    }

    private void deny(Player p, String action) {
        plugin.notifier().send(p, "denied", Placeholder.unparsed("action", action));
    }

    private boolean canBreak(Location blockLoc) {
        String mode = cfg().getString("blocks.break-mode", "PLACED_ONLY").toUpperCase(Locale.ROOT);
        return switch (mode) {
            case "ANY" -> true;
            case "NONE" -> false;
            default -> cleanup().isPlaced(blockLoc);
        };
    }

    // ------------------------------------------------------------ deaths / kills

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Arena a = arenas().getAt(victim.getLocation());
        if (a == null) return;

        deathArena.put(victim.getUniqueId(), a.getName());

        if (cfg().getBoolean("arena.keep-inventory-on-death", true)) {
            e.setKeepInventory(true);
        }
        if (cfg().getBoolean("arena.clear-death-drops", true)) {
            e.getDrops().clear();
        }
        if (cfg().getBoolean("arena.keep-exp-on-death", true)) {
            e.setKeepLevel(true);
            e.setDroppedExp(0);
        }
        if (cfg().getBoolean("arena.hide-death-messages", false)) {
            e.deathMessage(null);
        }

        Player killer = victim.getKiller();
        if (killer != null && !killer.equals(victim)) {
            plugin.notifier().send(killer, "kill",
                    Placeholder.unparsed("victim", victim.getName()),
                    Placeholder.unparsed("killer", killer.getName()),
                    Placeholder.unparsed("arena", a.getName()));
            plugin.notifier().send(victim, "death",
                    Placeholder.unparsed("victim", victim.getName()),
                    Placeholder.unparsed("killer", killer.getName()),
                    Placeholder.unparsed("arena", a.getName()));
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        String name = deathArena.remove(e.getPlayer().getUniqueId());
        if (name == null || !cfg().getBoolean("arena.respawn-at-arena-spawn", true)) return;
        Arena a = arenas().getByName(name);
        if (a == null) return;
        Location spawn = a.getSpawn();
        if (spawn != null) e.setRespawnLocation(spawn);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        deathArena.remove(e.getPlayer().getUniqueId());
        plugin.tracker().handleQuit(e.getPlayer());
    }

    // ------------------------------------------------------------ player protection

    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent e) {
        if (!cfg().getBoolean("arena.no-durability-loss", true)) return;
        if (arenaOf(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!cfg().getBoolean("arena.no-hunger", true)) return;
        if (e.getFoodLevel() < p.getFoodLevel() && arenaOf(p) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!cfg().getBoolean("arena.no-fall-damage", false)) return;
        if (arenaOf(p) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (!cfg().getBoolean("arena.prevent-item-drop", true)) return;
        Player p = e.getPlayer();
        if (arenaOf(p) == null || bypass(p)) return;
        e.setCancelled(true);
        deny(p, "drop items");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!cfg().getBoolean("arena.prevent-item-pickup", true)) return;
        if (arenaOf(p) == null || bypass(p)) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (!cfg().getBoolean("arena.prevent-container-access", true)) return;
        if (arenaOf(p) == null || bypass(p)) return;
        InventoryType type = e.getInventory().getType();
        for (String s : cfg().getStringList("arena.allowed-inventories")) {
            if (s.equalsIgnoreCase(type.name())) return;
        }
        e.setCancelled(true);
        deny(p, "open that");
    }

    @EventHandler(ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (!cfg().getBoolean("arena.prevent-item-pickup", true)) return;
        if (!(e.getEntity() instanceof AbstractArrow arrow)) return;
        if (!(arrow.getShooter() instanceof Player p)) return;
        if (arenaOf(p) == null || bypass(p)) return;
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!cfg().getBoolean("arena.prevent-mob-spawning", true)) return;
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) return;
        if (arenas().getAt(e.getLocation()) != null) e.setCancelled(true);
    }

    // ------------------------------------------------------------ blocks (tracked + restored)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Arena a = arenas().getAt(e.getBlock().getLocation());
        if (a == null) return;
        Player p = e.getPlayer();
        if (bypass(p)) return;

        if (!cfg().getBoolean("blocks.allow-place", true)) {
            e.setCancelled(true);
            deny(p, "place blocks");
            return;
        }
        if (e instanceof BlockMultiPlaceEvent multi) {
            for (BlockState st : multi.getReplacedBlockStates()) {
                cleanup().record(st);
                cleanup().markPlaced(st.getLocation());
            }
        } else {
            cleanup().record(e.getBlockReplacedState());
            cleanup().markPlaced(e.getBlock().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Arena a = arenas().getAt(b.getLocation());
        if (a == null) return;
        Player p = e.getPlayer();
        if (bypass(p)) return;

        if (!canBreak(b.getLocation())) {
            e.setCancelled(true);
            deny(p, "break that block");
            return;
        }
        cleanup().recordBlock(b);
        if (!cfg().getBoolean("blocks.drop-items", false)) {
            e.setDropItems(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        Block b = e.getBlock();
        Arena a = arenas().getAt(b.getLocation());
        if (a == null) return;
        Player p = e.getPlayer();
        if (bypass(p)) return;

        if (!cfg().getBoolean("blocks.allow-liquids", true)) {
            e.setCancelled(true);
            deny(p, "place liquids");
            return;
        }
        cleanup().recordBlock(b);
        cleanup().recordBlock(b.getRelative(e.getBlockFace()));
        cleanup().markPlaced(b.getLocation());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        Block b = e.getBlock();
        Arena a = arenas().getAt(b.getLocation());
        if (a == null) return;
        Player p = e.getPlayer();
        if (bypass(p)) return;

        if (!canBreak(b.getLocation())) {
            e.setCancelled(true);
            deny(p, "take that liquid");
            return;
        }
        cleanup().recordBlock(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        cleanup().recordBlock(e.getToBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) {
        cleanup().recordBlock(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        cleanup().recordBlock(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        BlockFace d = e.getDirection();
        cleanup().recordBlock(e.getBlock());
        cleanup().recordBlock(e.getBlock().getRelative(d));
        for (Block b : e.getBlocks()) {
            cleanup().recordBlock(b);
            cleanup().recordBlock(b.getRelative(d));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        BlockFace d = e.getDirection();
        cleanup().recordBlock(e.getBlock());
        cleanup().recordBlock(e.getBlock().getRelative(d));
        for (Block b : e.getBlocks()) {
            cleanup().recordBlock(b);
            cleanup().recordBlock(b.getRelative(d.getOppositeFace()));
        }
    }

    // ------------------------------------------------------------ explosions / fire

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        handleExplosion(e.getLocation(), e.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        handleExplosion(e.getBlock().getLocation(), e.blockList());
    }

    private void handleExplosion(Location where, List<Block> blocks) {
        if (arenas().getAt(where) == null) return;
        if (!cfg().getBoolean("blocks.explosion-block-damage", false)) {
            blocks.clear();
            return;
        }
        for (Block b : blocks) cleanup().recordBlock(b);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (arenas().getAt(e.getBlock().getLocation()) == null) return;
        BlockIgniteEvent.IgniteCause c = e.getCause();
        boolean spread = c == BlockIgniteEvent.IgniteCause.SPREAD
                || c == BlockIgniteEvent.IgniteCause.LAVA
                || c == BlockIgniteEvent.IgniteCause.LIGHTNING;
        if (spread && !cfg().getBoolean("blocks.allow-fire-spread", false)) {
            e.setCancelled(true);
            return;
        }
        cleanup().recordBlock(e.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (arenas().getAt(e.getBlock().getLocation()) == null) return;
        if (!cfg().getBoolean("blocks.allow-fire-spread", false)) {
            e.setCancelled(true);
            return;
        }
        cleanup().recordBlock(e.getBlock());
    }
}
