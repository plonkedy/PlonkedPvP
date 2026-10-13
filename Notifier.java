package dev.plonked.pvparena;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.SoundCategory;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Sends all config-driven messages / titles / action bars / sounds. */
public final class Notifier {

    private final PvpArenaPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public Notifier(PvpArenaPlugin plugin) {
        this.plugin = plugin;
    }

    public Component parse(String text, TagResolver... resolvers) {
        return mm.deserialize(text == null ? "" : text, resolvers);
    }

    private Component prefix() {
        return parse(plugin.getConfig().getString("general.prefix", ""));
    }

    /** Command/staff message from the "messages" section. */
    public void msg(CommandSender to, String key, TagResolver... resolvers) {
        String raw = plugin.getConfig().getString("messages." + key);
        if (raw == null || raw.isBlank()) return;
        to.sendMessage(prefix().append(parse(raw, resolvers)));
    }

    /** Notification from the "notifications" section (chat / action bar / title + sound). */
    public void send(Player p, String key, TagResolver... resolvers) {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("notifications." + key);
        if (s == null || !s.getBoolean("enabled", true)) return;

        String message = s.getString("message", "");
        for (String mode : displays(s)) {
            switch (mode) {
                case "CHAT" -> {
                    if (!message.isBlank()) p.sendMessage(prefix().append(parse(message, resolvers)));
                }
                case "ACTIONBAR" -> {
                    if (!message.isBlank()) p.sendActionBar(parse(message, resolvers));
                }
                case "TITLE" -> {
                    Title.Times times = Title.Times.times(
                            ticks(s.getInt("fade-in", 5)),
                            ticks(s.getInt("stay", 40)),
                            ticks(s.getInt("fade-out", 10)));
                    p.showTitle(Title.title(
                            parse(s.getString("title", ""), resolvers),
                            parse(s.getString("subtitle", ""), resolvers),
                            times));
                }
                default -> { }
            }
        }

        ConfigurationSection snd = s.getConfigurationSection("sound");
        if (snd != null && snd.getBoolean("enabled", false)) {
            p.playSound(p.getLocation(),
                    snd.getString("name", "entity.experience_orb.pickup"),
                    SoundCategory.MASTER,
                    (float) snd.getDouble("volume", 1.0),
                    (float) snd.getDouble("pitch", 1.0));
        }
    }

    private static Duration ticks(int t) {
        return Duration.ofMillis(Math.max(0, t) * 50L);
    }

    private static List<String> displays(ConfigurationSection s) {
        List<String> out = new ArrayList<>();
        if (s.isList("display")) {
            for (String x : s.getStringList("display")) out.add(x.toUpperCase(Locale.ROOT));
        } else {
            out.add(s.getString("display", "CHAT").toUpperCase(Locale.ROOT));
        }
        return out;
    }
}
