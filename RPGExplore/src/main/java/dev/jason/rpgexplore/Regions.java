package dev.jason.rpgexplore;

import dev.jason.rpgcore.CustomBlockManager;
import dev.jason.rpgtowns.TownManager;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Areas and territories, saved in explore.yml.
 *  - Areas are circles with a type. "pixie" areas (evil pixie hollows) show an entry title, spawn evil
 *    pixies instead of normal wild enemies, and are where red quartz crystals are found.
 *  - Territories are claimable resource sites. A town captures one by having its residents stand in the
 *    capture circle, uncontested, for capture-seconds. The owner gets per-hour of a resource in its treasury.
 */
public class Regions implements Listener {
    static final class Area {
        final String id, type, world, name;
        final double x, z, radius;

        Area(String id, String type, String world, double x, double z, double radius, String name) {
            this.id = id;
            this.type = type;
            this.world = world;
            this.x = x;
            this.z = z;
            this.radius = radius;
            this.name = name;
        }

        boolean contains(Location l) {
            if (!l.getWorld().getName().equals(world)) return false;
            double dx = l.getX() - x, dz = l.getZ() - z;
            return dx * dx + dz * dz <= radius * radius;
        }
    }

    static final class Territory {
        final String id, name, world, resource;
        final double x, y, z;
        final long perHour;
        String owner;          // town id
        String capturer;       // town currently capturing
        int progress;          // seconds
        double carry;          // part of a resource not paid out yet
        BossBar bar;

        Territory(String id, String name, String world, double x, double y, double z, String resource, long perHour) {
            this.id = id;
            this.name = name;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.resource = resource;
            this.perHour = perHour;
        }

        Location center() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z);
        }
    }

    private final RPGExplore plugin;
    final Map<String, Area> areas = new LinkedHashMap<>();
    final Map<String, Territory> territories = new LinkedHashMap<>();
    private final Map<UUID, String> lastArea = new HashMap<>();
    private final Map<UUID, Set<String>> barsShown = new HashMap<>();

    Regions(RPGExplore plugin) {
        this.plugin = plugin;
    }

    void load() {
        ConfigurationSection a = plugin.data().getConfigurationSection("areas");
        if (a != null) for (String id : a.getKeys(false)) {
            ConfigurationSection s = a.getConfigurationSection(id);
            areas.put(id, new Area(id, s.getString("type"), s.getString("world"), s.getDouble("x"), s.getDouble("z"),
                    s.getDouble("radius"), s.getString("name", id)));
        }
        ConfigurationSection t = plugin.data().getConfigurationSection("territories");
        if (t != null) for (String id : t.getKeys(false)) {
            ConfigurationSection s = t.getConfigurationSection(id);
            Territory tr = new Territory(id, s.getString("name", id), s.getString("world"), s.getDouble("x"), s.getDouble("y"),
                    s.getDouble("z"), s.getString("resource", "copper"), s.getLong("per-hour", 30));
            tr.owner = s.getString("owner");
            territories.put(id, tr);
        }
    }

    void save() {
        plugin.data().set("areas", null);
        plugin.data().set("territories", null);
        for (Area a : areas.values()) {
            String b = "areas." + a.id + ".";
            plugin.data().set(b + "type", a.type);
            plugin.data().set(b + "name", a.name);
            plugin.data().set(b + "world", a.world);
            plugin.data().set(b + "x", a.x);
            plugin.data().set(b + "z", a.z);
            plugin.data().set(b + "radius", a.radius);
        }
        for (Territory t : territories.values()) {
            String b = "territories." + t.id + ".";
            plugin.data().set(b + "name", t.name);
            plugin.data().set(b + "world", t.world);
            plugin.data().set(b + "x", t.x);
            plugin.data().set(b + "y", t.y);
            plugin.data().set(b + "z", t.z);
            plugin.data().set(b + "resource", t.resource);
            plugin.data().set(b + "per-hour", t.perHour);
            plugin.data().set(b + "owner", t.owner);
        }
        plugin.saveData();
    }

    Area areaAt(Location l) {
        for (Area a : areas.values()) if (a.contains(l)) return a;
        return null;
    }

    /** Pixie areas spawn evil pixies instead of the nearest town's enemies. Null = use the normal theme. */
    Map<String, Integer> themeAt(Location l) {
        Area a = areaAt(l);
        return a != null && a.type.equals("pixie") ? Map.of("evil_pixie", 100) : null;
    }

    /** Puts red quartz crystals on the ground at random spots in a pixie area. Returns how many. */
    int scatterQuartz(Area a, int count) {
        CustomBlockManager.CustomBlock quartz = plugin.rpg().blocks().get("red_quartz_crystal");
        World w = Bukkit.getWorld(a.world);
        if (quartz == null || w == null) return 0;
        int placed = 0;
        for (int i = 0; i < count * 4 && placed < count; i++) {
            Location l = Util.near(new Location(w, a.x, w.getHighestBlockYAt((int) a.x, (int) a.z) + 1, a.z), a.radius, 40);
            if (l == null) continue;
            l.getBlock().setBlockData(quartz.state(), false);
            placed++;
        }
        return placed;
    }

    void tick() {
        Title.Times times = Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(2), Duration.ofMillis(800));
        for (Player p : Bukkit.getOnlinePlayers()) {
            Area a = areaAt(p.getLocation());
            String now = a == null ? null : a.id;
            String before = lastArea.put(p.getUniqueId(), now);
            if (now != null && !now.equals(before)) {
                p.showTitle(Title.title(RPGExplore.MM.deserialize(a.name), RPGExplore.MM.deserialize(
                        a.type.equals("pixie") ? "<light_purple>Evil pixies lurk here. Red quartz grows in the dark." : ""), times));
            } else if (now == null && before != null) {
                Area old = areas.get(before);
                if (old != null) p.sendActionBar(RPGExplore.MM.deserialize("<gray>You left " + old.name));
            }
        }
        territoryTick();
    }

    // ------------------------------------------------------------------ territories

    private void territoryTick() {
        int need = plugin.getConfig().getInt("territories.capture-seconds", 60);
        double r = plugin.getConfig().getDouble("territories.capture-radius", 6);
        TownManager tm = plugin.towns().towns();
        for (Territory t : territories.values()) {
            Location c = t.center();
            if (c == null) continue;
            Set<String> present = new HashSet<>();
            for (Player p : c.getNearbyPlayers(r)) {
                TownManager.Town home = tm.townOf(p.getUniqueId());
                if (home != null && !p.isDead()) present.add(home.id);
            }
            String status;
            if (present.size() == 1) {
                String town = present.iterator().next();
                if (town.equals(t.owner)) {
                    t.progress = 0;
                    t.capturer = null;
                    status = "held by " + townName(t.owner);
                } else {
                    if (!town.equals(t.capturer)) {
                        t.capturer = town;
                        t.progress = 0;
                    }
                    t.progress++;
                    status = townName(town) + " capturing " + t.progress + "/" + need;
                    if (t.progress >= need) {
                        t.owner = town;
                        t.progress = 0;
                        t.capturer = null;
                        save();
                        Bukkit.broadcast(RPGExplore.MM.deserialize("<gold>" + townName(town) + "<yellow> captured " + t.name + "!"));
                        status = "held by " + townName(town);
                    }
                }
            } else if (present.size() > 1) {
                status = "<red>contested";
            } else {
                t.progress = Math.max(0, t.progress - 1);
                status = t.owner == null ? "unclaimed" : "held by " + townName(t.owner);
            }
            showBar(t, c, status, need);
            drawCircle(c, r, t.owner == null ? Color.WHITE : Color.fromRGB(0xF0B030));
        }
    }

    private void showBar(Territory t, Location c, String status, int need) {
        if (t.bar == null) t.bar = BossBar.bossBar(RPGExplore.MM.deserialize(""), 0, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        t.bar.name(RPGExplore.MM.deserialize("<gold>" + t.name + " <gray>(" + t.perHour + " " + t.resource + "/hour) <white>- " + status));
        t.bar.progress(t.capturer == null ? (t.owner == null ? 0 : 1) : Math.min(1f, t.progress / (float) need));
        Collection<Player> near = c.getNearbyPlayers(32);
        for (Player p : Bukkit.getOnlinePlayers()) {
            Set<String> shown = barsShown.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
            if (near.contains(p)) {
                if (shown.add(t.id)) p.showBossBar(t.bar);
            } else if (shown.remove(t.id)) {
                p.hideBossBar(t.bar);
            }
        }
    }

    private void drawCircle(Location c, double r, Color color) {
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.3f);
        for (int i = 0; i < 32; i++) {
            double a = Math.PI * 2 * i / 32;
            c.getWorld().spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * r, 0.3, Math.sin(a) * r), 1, dust);
        }
    }

    /** Once a minute: each owned territory pays a minute's share of its hourly income into the owner's treasury. */
    void incomeTick() {
        TownManager tm = plugin.towns().towns();
        for (Territory t : territories.values()) {
            TownManager.Town owner = tm.get(t.owner);
            if (owner == null) continue;
            t.carry += t.perHour / 60.0;
            long whole = (long) t.carry;
            if (whole > 0) {
                t.carry -= whole;
                tm.addToTreasury(owner, t.resource, whole);
            }
        }
    }

    private String townName(String id) {
        TownManager.Town t = plugin.towns().towns().get(id);
        return t == null ? String.valueOf(id) : t.name;
    }

    void cleanup() {
        for (Territory t : territories.values()) {
            if (t.bar != null) Bukkit.getOnlinePlayers().forEach(p -> p.hideBossBar(t.bar));
        }
    }
}
