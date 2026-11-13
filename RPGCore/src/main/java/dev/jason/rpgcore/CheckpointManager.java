package dev.jason.rpgcore;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Markers that assign a class the first time a class-less player walks through them. */
public class CheckpointManager implements Listener {
    public record Checkpoint(String id, String world, double x, double y, double z, double radius, String classId) {
        boolean contains(Location l) {
            if (l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
            double dx = l.getX() - x, dz = l.getZ() - z, dy = l.getY() - y;
            return dx * dx + dz * dz <= radius * radius && dy > -1 && dy < 3;
        }
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final RPGCore plugin;
    private final File file;
    private final Map<String, Checkpoint> checkpoints = new LinkedHashMap<>();
    private final Map<UUID, Long> lastNotice = new HashMap<>();

    public CheckpointManager(RPGCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "checkpoints.yml");
    }

    public void load() {
        checkpoints.clear();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String id : yml.getKeys(false)) {
            ConfigurationSection s = yml.getConfigurationSection(id);
            if (s == null) continue;
            checkpoints.put(id, new Checkpoint(id, s.getString("world"), s.getDouble("x"), s.getDouble("y"),
                    s.getDouble("z"), s.getDouble("radius", 2), s.getString("class")));
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Checkpoint c : checkpoints.values()) {
            yml.set(c.id() + ".world", c.world());
            yml.set(c.id() + ".x", c.x());
            yml.set(c.id() + ".y", c.y());
            yml.set(c.id() + ".z", c.z());
            yml.set(c.id() + ".radius", c.radius());
            yml.set(c.id() + ".class", c.classId());
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save checkpoints.yml: " + e.getMessage());
        }
    }

    public void create(String id, Location loc, double radius, String classId) {
        checkpoints.put(id, new Checkpoint(id, loc.getWorld().getName(), loc.getBlockX() + 0.5, loc.getBlockY(),
                loc.getBlockZ() + 0.5, radius, classId));
        save();
    }

    public boolean remove(String id) {
        boolean removed = checkpoints.remove(id) != null;
        if (removed) save();
        return removed;
    }

    public Collection<Checkpoint> all() {
        return checkpoints.values();
    }

    /** Ring of colored particles so players can see each marker. */
    public void drawMarkers() {
        for (Checkpoint c : checkpoints.values()) {
            World w = Bukkit.getWorld(c.world());
            RpgClass rc = plugin.classes().get(c.classId());
            if (w == null || rc == null) continue;
            Location center = new Location(w, c.x(), c.y() + 0.1, c.z());
            if (!center.isChunkLoaded()) continue;
            Particle.DustOptions dust = new Particle.DustOptions(parseColor(rc.color), 1.2f);
            for (int i = 0; i < 24; i++) {
                double a = Math.PI * 2 * i / 24;
                w.spawnParticle(Particle.DUST, center.clone().add(Math.cos(a) * c.radius(), 0, Math.sin(a) * c.radius()), 1, dust);
            }
            w.spawnParticle(Particle.END_ROD, center.clone().add(0, 1, 0), 2, 0.2, 0.6, 0.2, 0.01);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!e.hasChangedBlock()) return;
        Player p = e.getPlayer();
        for (Checkpoint c : checkpoints.values()) {
            if (!c.contains(e.getTo())) continue;
            RpgClass target = plugin.classes().get(c.classId());
            if (target == null) return;
            RpgClass current = plugin.players().getClass(p);
            if (current == null) {
                plugin.players().setClass(p, target);
                p.showTitle(Title.title(MM.deserialize(target.coloredName()),
                        MM.deserialize("<gray>You have chosen your path")));
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 60, 0.5, 1, 0.5, 0.3);
            } else if (current != target && noticeReady(p)) {
                p.sendActionBar(MM.deserialize("<gray>You already follow the path of " + current.coloredName()));
            }
            return;
        }
    }

    private boolean noticeReady(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastNotice.get(p.getUniqueId());
        if (last != null && now - last < 3000) return false;
        lastNotice.put(p.getUniqueId(), now);
        return true;
    }

    static Color parseColor(String color) {
        if (color != null && color.startsWith("#") && color.length() == 7) {
            return Color.fromRGB(Integer.parseInt(color.substring(1), 16));
        }
        return Color.WHITE;
    }
}
