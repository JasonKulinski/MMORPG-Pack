package dev.jason.rpgexplore;

import dev.jason.rpgcore.MobManager;
import dev.jason.rpgcore.Wallet;
import dev.jason.rpgtowns.TownManager;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Level bands and wild enemies. Distance to the nearest town center decides the enemy level range
 * (every band-size blocks, levels go up by levels-per-band); the nearest town decides which enemies.
 * Also drops copper coins and ambrosia from every custom enemy.
 */
public class Wilds implements Listener {
    /** Where a player stands: the nearest town (may be null), distance to it, and the level range there. */
    record Band(TownManager.Town town, double distance, int minLevel, int maxLevel, boolean safe) {
        int index() { return minLevel; }
    }

    private final RPGExplore plugin;
    private final NamespacedKey wildKey;
    private final Map<UUID, Integer> lastBand = new HashMap<>();

    Wilds(RPGExplore plugin) {
        this.plugin = plugin;
        this.wildKey = new NamespacedKey(plugin, "wild");
    }

    Band band(Location loc) {
        TownManager.Town t = plugin.towns().towns().nearest(loc);
        Location home = t != null ? t.center : loc.getWorld().getSpawnLocation();
        double dist = Util.flatDistance(loc, home);
        int size = Math.max(1, plugin.getConfig().getInt("level-bands.band-size", 500));
        int per = Math.max(1, plugin.getConfig().getInt("level-bands.levels-per-band", 5));
        int band = (int) (dist / size);
        boolean safe = dist < plugin.getConfig().getDouble("level-bands.safe-radius", 120);
        return new Band(t, dist, band * per + 1, band * per + per, safe);
    }

    /** True for players who can meet wild things: in a wilds world, not in a town's safe circle, not spectating. */
    boolean inWilds(Player p) {
        if (p.getGameMode() == GameMode.SPECTATOR) return false;
        if (!plugin.getConfig().getStringList("wild-spawns.worlds").contains(p.getWorld().getName())) return false;
        return !band(p.getLocation()).safe();
    }

    // ------------------------------------------------------------------ spawning

    void spawnTick() {
        if (!plugin.getConfig().getBoolean("wild-spawns.enabled", true)) return;
        int max = plugin.getConfig().getInt("wild-spawns.max-per-player", 6);
        double minD = plugin.getConfig().getDouble("wild-spawns.min-distance", 18);
        double maxD = plugin.getConfig().getDouble("wild-spawns.max-distance", 36);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!inWilds(p) || plugin.dungeons().inside(p.getLocation())) continue;
            long near = p.getNearbyEntities(48, 32, 48).stream().filter(this::isWild).count();
            if (near >= max) continue;
            Location spot = Util.ring(p.getLocation(), minD, maxD);
            if (spot == null) continue;
            Band b = band(spot);
            if (b.safe()) continue;
            spawnWild(spot, b, plugin.regions().themeAt(spot));
        }
    }

    /** Picks an enemy for the band from the theme (mob id -> highest level) and spawns it. */
    LivingEntity spawnWild(Location spot, Band b, Map<String, Integer> themeOverride) {
        Map<String, Integer> theme = themeOverride != null ? themeOverride : theme(b.town());
        List<String> fits = new ArrayList<>();
        String strongest = null;
        for (Map.Entry<String, Integer> e : theme.entrySet()) {
            if (plugin.rpg().mobs().get(e.getKey()) == null) continue;
            if (e.getValue() >= b.minLevel()) fits.add(e.getKey());
            if (strongest == null || e.getValue() > theme.get(strongest)) strongest = e.getKey();
        }
        if (strongest == null) return null;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String id;
        int level;
        if (fits.isEmpty()) { // every enemy here is capped below this band: use the strongest at its cap
            id = strongest;
            level = theme.get(strongest);
        } else {
            id = fits.get(r.nextInt(fits.size()));
            level = r.nextInt(b.minLevel(), Math.min(b.maxLevel(), theme.get(id)) + 1);
        }
        MobManager.MobDef def = plugin.rpg().mobs().get(id);
        LivingEntity e = plugin.rpg().mobs().spawn(def, spot, level);
        if (e != null) e.getPersistentDataContainer().set(wildKey, PersistentDataType.BYTE, (byte) 1);
        return e;
    }

    Map<String, Integer> theme(TownManager.Town t) {
        ConfigurationSection themes = plugin.getConfig().getConfigurationSection("themes");
        ConfigurationSection s = themes == null ? null
                : (t != null && themes.isConfigurationSection(t.id) ? themes.getConfigurationSection(t.id) : themes.getConfigurationSection("default"));
        Map<String, Integer> out = new LinkedHashMap<>();
        if (s != null) for (String k : s.getKeys(false)) out.put(k, s.getInt(k, 100));
        return out;
    }

    boolean isWild(Entity e) {
        return e.getPersistentDataContainer().has(wildKey);
    }

    /** Removes wild enemies nobody is near, so they don't pile up. */
    void despawnTick() {
        double d = plugin.getConfig().getDouble("wild-spawns.despawn-distance", 96);
        for (String name : plugin.getConfig().getStringList("wild-spawns.worlds")) {
            World w = Bukkit.getWorld(name);
            if (w == null) continue;
            for (LivingEntity e : w.getLivingEntities()) {
                if (!isWild(e)) continue;
                boolean seen = w.getPlayers().stream().anyMatch(p -> p.getLocation().distanceSquared(e.getLocation()) < d * d);
                if (!seen) e.remove();
            }
        }
    }

    /** Tells players when they cross into a new level band (or back into a town's safe circle). */
    void bandTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!plugin.getConfig().getStringList("wild-spawns.worlds").contains(p.getWorld().getName())) {
                lastBand.remove(p.getUniqueId());
                continue;
            }
            Band b = band(p.getLocation());
            int key = b.safe() ? 0 : b.index();
            Integer prev = lastBand.put(p.getUniqueId(), key);
            if (prev == null || prev == key) continue;
            String where = b.town() == null ? "the wilds" : b.town().name;
            Title.Times times = Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(600));
            if (b.safe()) {
                p.showTitle(Title.title(RPGExplore.MM.deserialize(""), RPGExplore.MM.deserialize("<green>Safe near " + where), times));
            } else {
                String color = b.minLevel() <= 5 ? "<yellow>" : b.minLevel() <= 15 ? "<gold>" : "<red>";
                p.showTitle(Title.title(RPGExplore.MM.deserialize(""), RPGExplore.MM.deserialize(color + "Danger: level "
                        + b.minLevel() + "-" + b.maxLevel() + " <gray>(" + where + " wilds)"), times));
            }
        }
    }

    // ------------------------------------------------------------------ loot

    /** Runs after RPGCore cleared the vanilla drops. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent e) {
        MobManager.MobDef def = plugin.rpg().mobs().of(e.getEntity());
        if (def == null) return;
        int level = plugin.rpg().mobs().level(e.getEntity());
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int maxCoins = Math.max(1, (int) Math.round(level * plugin.getConfig().getDouble("drops.copper-per-level", 1)));
        e.getDrops().add(plugin.rpg().wallet().item(Wallet.Currency.COPPER, r.nextInt(1, maxCoins + 1)));
        double chance = plugin.getConfig().getDouble("drops.ambrosia-chance", 0.02)
                + level * plugin.getConfig().getDouble("drops.ambrosia-chance-per-level", 0.002);
        if (r.nextDouble() < chance) e.getDrops().add(plugin.rpg().wallet().item(Wallet.Currency.AMBROSIA, 1));
    }
}
