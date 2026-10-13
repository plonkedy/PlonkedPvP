package dev.plonked.pvparena;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Detects players entering / leaving arenas, remembers what they entered
 * with, and keeps their loadout topped up while they are inside.
 */
public final class ArenaTracker {

    private static final class Session {
        String arena;
        final ItemStack[] snapshot;

        Session(String arena, ItemStack[] snapshot) {
            this.arena = arena;
            this.snapshot = snapshot;
        }
    }

    private final PvpArenaPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private BukkitTask checkTask;
    private BukkitTask replenishTask;

    public ArenaTracker(PvpArenaPlugin plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() { return plugin.getConfig(); }

    // ------------------------------------------------------------ lifecycle

    public void start() {
        stopTasks();
        long ci = Math.max(1L, cfg().getLong("general.check-interval-ticks", 5L));
        checkTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkAll, 20L, ci);
        if (cfg().getBoolean("arena.replenish-items", true)) {
            long ri = Math.max(1L, cfg().getLong("arena.replenish-interval-ticks", 10L));
            replenishTask = Bukkit.getScheduler().runTaskTimer(plugin, this::replenishAll, 20L, ri);
        }
    }

    private void stopTasks() {
        if (checkTask != null) { checkTask.cancel(); checkTask = null; }
        if (replenishTask != null) { replenishTask.cancel(); replenishTask = null; }
    }

    public void shutdown() {
        stopTasks();
        for (Map.Entry<UUID, Session> en : new ArrayList<>(sessions.entrySet())) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p != null) finishSession(p, en.getValue());
        }
        sessions.clear();
    }

    // ------------------------------------------------------------ queries

    public boolean isTracked(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    public Collection<Player> playersIn(Arena a) {
        List<Player> out = new ArrayList<>();
        for (Map.Entry<UUID, Session> en : sessions.entrySet()) {
            if (en.getValue().arena.equalsIgnoreCase(a.getName())) {
                Player p = Bukkit.getPlayer(en.getKey());
                if (p != null) out.add(p);
            }
        }
        return out;
    }

    public void renameArena(String oldName, String newName) {
        for (Session s : sessions.values()) {
            if (s.arena.equalsIgnoreCase(oldName)) s.arena = newName;
        }
    }

    public void handleQuit(Player p) {
        Session s = sessions.remove(p.getUniqueId());
        if (s != null) finishSession(p, s);
    }

    // ------------------------------------------------------------ enter / leave

    private void checkAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead()) continue;
            Arena now = p.getGameMode() == GameMode.SPECTATOR ? null : plugin.arenas().getAt(p.getLocation());
            Session s = sessions.get(p.getUniqueId());

            if (s != null && (now == null || !s.arena.equalsIgnoreCase(now.getName()))) {
                sessions.remove(p.getUniqueId());
                finishSession(p, s);
                plugin.notifier().send(p, "leave", Placeholder.unparsed("arena", s.arena));
                s = null;
            }
            if (s == null && now != null) {
                enter(p, now);
            }
        }
    }

    private void enter(Player p, Arena a) {
        ItemStack[] contents = p.getInventory().getContents();
        ItemStack[] snap = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            snap[i] = contents[i] == null ? null : contents[i].clone();
        }
        sessions.put(p.getUniqueId(), new Session(a.getName(), snap));
        plugin.notifier().send(p, "enter", Placeholder.unparsed("arena", a.getName()));
    }

    private void finishSession(Player p, Session s) {
        if (cfg().getBoolean("arena.restore-inventory-on-leave", false)) {
            GameMode gm = p.getGameMode();
            if (gm != GameMode.CREATIVE && gm != GameMode.SPECTATOR) restore(p, s);
        } else if (cfg().getBoolean("arena.replenish-items", true)) {
            replenish(p, s);
        }
    }

    private void restore(Player p, Session s) {
        ItemStack[] copy = new ItemStack[s.snapshot.length];
        for (int i = 0; i < copy.length; i++) {
            copy[i] = s.snapshot[i] == null ? null : s.snapshot[i].clone();
        }
        p.getInventory().setContents(copy);
    }

    // ------------------------------------------------------------ replenishing

    private void replenishAll() {
        for (Map.Entry<UUID, Session> en : new ArrayList<>(sessions.entrySet())) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || p.isDead()) continue;
            replenish(p, en.getValue());
        }
    }

    private static boolean valid(ItemStack it) {
        return it != null && !it.getType().isAir() && it.getAmount() > 0;
    }

    /** Item "identity": same item ignoring amount, damage and loaded-crossbow state. */
    private static ItemStack key(ItemStack it) {
        ItemStack k = new ItemStack(it.getType(), 1);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            if (meta instanceof Damageable d) d.setDamage(0);
            if (meta instanceof CrossbowMeta c) c.setChargedProjectiles(null);
            k.setItemMeta(meta);
        }
        return k;
    }

    private static void count(ItemStack it, Map<ItemStack, Integer> byKey, Map<Material, Integer> byMat) {
        if (!valid(it)) return;
        byKey.merge(key(it), it.getAmount(), Integer::sum);
        byMat.merge(it.getType(), it.getAmount(), Integer::sum);
    }

    /** The empty container left behind when a consumable is used up. */
    private static Material remainderOf(Material m) {
        String n = m.name();
        if (n.endsWith("_BUCKET") && m != Material.BUCKET) return Material.BUCKET;
        if (m == Material.POTION || m == Material.HONEY_BOTTLE) return Material.GLASS_BOTTLE;
        if (m == Material.MUSHROOM_STEW || m == Material.RABBIT_STEW
                || m == Material.BEETROOT_SOUP || m == Material.SUSPICIOUS_STEW) return Material.BOWL;
        return null;
    }

    private void replenish(Player p, Session s) {
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;
        PlayerInventory inv = p.getInventory();

        // what they entered with
        Map<ItemStack, Integer> need = new HashMap<>();
        Map<ItemStack, ItemStack> proto = new HashMap<>();
        Map<Material, Integer> needMat = new HashMap<>();
        for (ItemStack it : s.snapshot) {
            if (!valid(it)) continue;
            ItemStack k = key(it);
            need.merge(k, it.getAmount(), Integer::sum);
            proto.putIfAbsent(k, it);
            needMat.merge(it.getType(), it.getAmount(), Integer::sum);
        }
        if (need.isEmpty()) return;

        // what they have right now (inventory + cursor + crafting grid)
        Map<ItemStack, Integer> have = new HashMap<>();
        Map<Material, Integer> haveMat = new HashMap<>();
        for (ItemStack it : inv.getContents()) count(it, have, haveMat);
        count(p.getItemOnCursor(), have, haveMat);
        Inventory top = p.getOpenInventory().getTopInventory();
        if (top.getType() == InventoryType.CRAFTING || top.getType() == InventoryType.WORKBENCH) {
            ItemStack[] tc = top.getContents();
            for (int i = 1; i < tc.length; i++) count(tc[i], have, haveMat);
        }

        // what is missing
        Map<ItemStack, Integer> deficit = new HashMap<>();
        for (Map.Entry<ItemStack, Integer> en : need.entrySet()) {
            int d = en.getValue() - have.getOrDefault(en.getKey(), 0);
            if (d > 0) deficit.put(en.getKey(), d);
        }
        if (deficit.isEmpty()) return;

        // used-up containers (empty bucket, glass bottle, bowl) are swapped out, not piled up
        for (Map.Entry<ItemStack, Integer> en : deficit.entrySet()) {
            Material rem = remainderOf(en.getKey().getType());
            if (rem == null) continue;
            int excess = haveMat.getOrDefault(rem, 0) - needMat.getOrDefault(rem, 0);
            int take = Math.min(en.getValue(), excess);
            if (take > 0) removePlain(inv, rem, take);
        }

        // put things back in their original slots first
        ItemStack[] snap = s.snapshot;
        for (int i = 0; i < snap.length; i++) {
            ItemStack sn = snap[i];
            if (!valid(sn)) continue;
            ItemStack k = key(sn);
            Integer d = deficit.get(k);
            if (d == null || d <= 0) continue;
            ItemStack cur = inv.getItem(i);
            if (!valid(cur)) {
                int give = Math.min(d, sn.getAmount());
                ItemStack c = sn.clone();
                c.setAmount(give);
                inv.setItem(i, c);
                deficit.put(k, d - give);
            } else if (key(cur).equals(k) && cur.getAmount() < sn.getAmount()) {
                int add = Math.min(d, sn.getAmount() - cur.getAmount());
                cur.setAmount(cur.getAmount() + add);
                inv.setItem(i, cur);
                deficit.put(k, d - add);
            }
        }

        // anything still missing goes wherever it fits
        for (Map.Entry<ItemStack, Integer> en : deficit.entrySet()) {
            int left = en.getValue();
            ItemStack pr = proto.get(en.getKey());
            if (pr == null) continue;
            while (left > 0) {
                ItemStack give = pr.clone();
                int amt = Math.min(left, Math.max(1, give.getMaxStackSize()));
                give.setAmount(amt);
                Map<Integer, ItemStack> leftover = inv.addItem(give);
                left -= amt;
                if (!leftover.isEmpty()) break; // inventory full - nothing more we can do
            }
        }
    }

    private static void removePlain(PlayerInventory inv, Material m, int count) {
        for (int i = 0; i < 36 && count > 0; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() != m || it.hasItemMeta()) continue;
            int take = Math.min(count, it.getAmount());
            if (take >= it.getAmount()) {
                inv.setItem(i, null);
            } else {
                it.setAmount(it.getAmount() - take);
                inv.setItem(i, it);
            }
            count -= take;
        }
    }
}
