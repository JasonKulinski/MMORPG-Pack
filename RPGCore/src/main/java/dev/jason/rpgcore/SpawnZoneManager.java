package dev.jason.rpgcore;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Areas that keep custom mobs alive while players are nearby. Saved in zones.yml:
 * a center, a radius, how many mobs can be alive at once, and which mobs (with weights) it spawns.
 */
public class SpawnZoneManager {
    public static final class Zone {
        final String id, world;
        final double x, y, z;
        double radius;
        int max;
        double respawnSeconds;
        final Map<String, Integer> mobs = new LinkedHashMap<>(); // mob id -> weight
        long nextSpawn;

        Zone(String id, String world, double x, double y, double z) {
            this.id = id;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public String id() { return id; }
    }

    private final RPGCore plugin;
    private final File file;
    private final NamespacedKey zoneKey;
    private final Map<String, Zone> zones = new LinkedHashMap<>();
    private final Map<String, Long> shownUntil = new HashMap<>();

    public SpawnZoneManager(RPGCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "zones.yml");
        this.zoneKey = new NamespacedKey(plugin, "zone");
    }

    public void load() {
        zones.clear();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String id : yml.getKeys(false)) {
            ConfigurationSection s = yml.getConfigurationSection(id);
            if (s == null) continue;
            Zone z = new Zone(id, s.getString("world"), s.getDouble("x"), s.getDouble("y"), s.getDouble("z"));
            z.radius = s.getDouble("radius", 10);
            z.max = s.getInt("max", 4);
            z.respawnSeconds = s.getDouble("respawn-seconds", 8);
            ConfigurationSection m = s.getConfigurationSection("mobs");
            if (m != null) for (String mob : m.getKeys(false)) z.mobs.put(mob, Math.max(1, m.getInt(mob, 1)));
            zones.put(id, z);
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Zone z : zones.values()) {
            yml.set(z.id + ".world", z.world);
            yml.set(z.id + ".x", z.x);
            yml.set(z.id + ".y", z.y);
            yml.set(z.id + ".z", z.z);
            yml.set(z.id + ".radius", z.radius);
            yml.set(z.id + ".max", z.max);
            yml.set(z.id + ".respawn-seconds", z.respawnSeconds);
            z.mobs.forEach((mob, w) -> yml.set(z.id + ".mobs." + mob, w));
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save zones.yml: " + e.getMessage());
        }
    }

    public Zone create(String id, Location loc, double radius, int max, Map<String, Integer> mobs) {
        Zone z = new Zone(id, loc.getWorld().getName(), loc.getBlockX() + 0.5, loc.getBlockY(), loc.getBlockZ() + 0.5);
        z.radius = radius;
        z.max = max;
        z.respawnSeconds = 8;
        z.mobs.putAll(mobs);
        zones.put(id, z);
        save();
        show(id);
        return z;
    }

    public boolean remove(String id) {
        boolean removed = zones.remove(id) != null;
        if (removed) save();
        return removed;
    }

    public Zone get(String id) {
        return zones.get(id);
    }

    public Collection<Zone> all() {
        return zones.values();
    }

    /** Draws the zone's edge for 30 seconds. */
    public void show(String id) {
        shownUntil.put(id, System.currentTimeMillis() + 30_000);
    }

    /** Removes every living mob that came from this zone. Returns how many. */
    public int clear(String id) {
        Zone z = zones.get(id);
        if (z == null) return 0;
        List<LivingEntity> mobs = mobsOf(z);
        mobs.forEach(Entity::remove);
        return mobs.size();
    }

    public int alive(Zone z) {
        return mobsOf(z).size();
    }

    /** Runs once a second: each zone with a player nearby spawns one mob when it is below its max. */
    public void tick() {
        long now = System.currentTimeMillis();
        double range = plugin.getConfig().getDouble("spawning.zone-player-range", 48);
        double minDist = plugin.getConfig().getDouble("spawning.zone-min-player-distance", 8);
        for (Zone z : zones.values()) {
            if (now < z.nextSpawn || z.mobs.isEmpty()) continue;
            Location center = center(z);
            if (center == null || !center.isChunkLoaded()) continue;
            Collection<Player> near = center.getNearbyPlayers(z.radius + range);
            if (near.isEmpty() || alive(z) >= z.max) continue;
            MobManager.MobDef def = plugin.mobs().get(pick(z));
            if (def == null) continue;
            Location spot = findSpot(z, center, near, minDist);
            if (spot == null) continue;
            LivingEntity e = plugin.mobs().spawn(def, spot);
            if (e != null) e.getPersistentDataContainer().set(zoneKey, PersistentDataType.STRING, z.id);
            z.nextSpawn = now + (long) (z.respawnSeconds * 1000);
        }
    }

    /** Particle ring for zones being shown with /rpg zone show. */
    public void drawShown() {
        long now = System.currentTimeMillis();
        shownUntil.values().removeIf(t -> t < now);
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(0xE03030), 1.5f);
        for (String id : shownUntil.keySet()) {
            Zone z = zones.get(id);
            Location c = z == null ? null : center(z);
            if (c == null || !c.isChunkLoaded()) continue;
            int points = (int) Math.max(24, z.radius * 6);
            for (int i = 0; i < points; i++) {
                double a = Math.PI * 2 * i / points;
                c.getWorld().spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * z.radius, 0.2, Math.sin(a) * z.radius), 1, dust);
            }
            c.getWorld().spawnParticle(Particle.END_ROD, c.clone().add(0, 1, 0), 3, 0.2, 0.8, 0.2, 0.01);
        }
    }

    private Location center(Zone z) {
        World w = Bukkit.getWorld(z.world);
        return w == null ? null : new Location(w, z.x, z.y, z.z);
    }

    /** Mobs this zone spawned that are still alive (counted a little past the edge, since they chase players). */
    private List<LivingEntity> mobsOf(Zone z) {
        Location c = center(z);
        if (c == null) return List.of();
        double r = z.radius + 32;
        return c.getWorld().getNearbyEntities(c, r, r, r).stream()
                .filter(e -> e instanceof LivingEntity && e.isValid()
                        && z.id.equals(e.getPersistentDataContainer().get(zoneKey, PersistentDataType.STRING)))
                .map(e -> (LivingEntity) e).toList();
    }

    private String pick(Zone z) {
        int total = z.mobs.values().stream().mapToInt(Integer::intValue).sum();
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (Map.Entry<String, Integer> e : z.mobs.entrySet()) {
            roll -= e.getValue();
            if (roll < 0) return e.getKey();
        }
        return z.mobs.keySet().iterator().next();
    }

    /** A random standing spot inside the zone: solid ground, two free blocks above, not right next to a player. */
    private Location findSpot(Zone z, Location center, Collection<Player> players, double minDist) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World w = center.getWorld();
        for (int attempt = 0; attempt < 12; attempt++) {
            double a = rng.nextDouble() * Math.PI * 2, d = Math.sqrt(rng.nextDouble()) * z.radius;
            int bx = (int) Math.floor(z.x + Math.cos(a) * d), bz = (int) Math.floor(z.z + Math.sin(a) * d);
            for (int by = (int) z.y + 6; by >= (int) z.y - 6; by--) {
                Block feet = w.getBlockAt(bx, by, bz);
                if (!feet.getRelative(0, -1, 0).getType().isSolid() || !open(feet) || !open(feet.getRelative(0, 1, 0))) continue;
                Location spot = feet.getLocation().add(0.5, 0, 0.5);
                if (players.stream().anyMatch(p -> p.getWorld() == w && p.getLocation().distanceSquared(spot) < minDist * minDist)) break;
                return spot;
            }
        }
        return null;
    }

    private static boolean open(Block b) {
        return b.isPassable() && !b.isLiquid();
    }
}
