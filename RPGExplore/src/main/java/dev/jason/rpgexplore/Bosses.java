package dev.jason.rpgexplore;

import dev.jason.rpgcore.MobManager;
import dev.jason.rpgcore.Wallet;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-of-one bosses. Each boss (config.yml "bosses") has an arena (set with /explore boss set <id>). It spawns
 * when a player comes near and its respawn timer is done, shows a boss bar, and slams nearby players away.
 * The player who did the most damage gets the drop; the first winner ever gets tier 0 ("Legendary"), each later
 * winner one tier lower. The drop is tagged so another plugin can turn it into the real reward:
 *   rpgexplore:boss = boss id, rpgexplore:tier = 0.., rpgexplore:edition = 1 for the first winner, 2, 3...
 */
public class Bosses implements Listener {
    static final class Arena {
        final String id, world;
        final double x, y, z;
        int kills;
        long nextSpawn;
        UUID alive;
        long lastSlam;
        BossBar bar;
        final Map<UUID, Double> damage = new HashMap<>();

        Arena(String id, String world, double x, double y, double z) {
            this.id = id;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        Location center() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z);
        }
    }

    private final RPGExplore plugin;
    private final NamespacedKey bossKey, tierKey, editionKey;
    final Map<String, Arena> arenas = new LinkedHashMap<>();

    Bosses(RPGExplore plugin) {
        this.plugin = plugin;
        this.bossKey = new NamespacedKey(plugin, "boss");
        this.tierKey = new NamespacedKey(plugin, "tier");
        this.editionKey = new NamespacedKey(plugin, "edition");
    }

    void load() {
        ConfigurationSection all = plugin.data().getConfigurationSection("bosses");
        if (all == null) return;
        for (String id : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(id);
            Arena a = new Arena(id, s.getString("world"), s.getDouble("x"), s.getDouble("y"), s.getDouble("z"));
            a.kills = s.getInt("kills");
            a.nextSpawn = s.getLong("next-spawn");
            arenas.put(id, a);
        }
    }

    void save() {
        plugin.data().set("bosses", null);
        for (Arena a : arenas.values()) {
            String b = "bosses." + a.id + ".";
            plugin.data().set(b + "world", a.world);
            plugin.data().set(b + "x", a.x);
            plugin.data().set(b + "y", a.y);
            plugin.data().set(b + "z", a.z);
            plugin.data().set(b + "kills", a.kills);
            plugin.data().set(b + "next-spawn", a.nextSpawn);
        }
        plugin.saveData();
    }

    ConfigurationSection cfg(String id) {
        return plugin.getConfig().getConfigurationSection("bosses." + id);
    }

    void tick() {
        long now = System.currentTimeMillis();
        for (Arena a : arenas.values()) {
            ConfigurationSection c = cfg(a.id);
            Location center = a.center();
            if (c == null || center == null || !center.isChunkLoaded()) continue;
            LivingEntity boss = a.alive == null ? null : (Bukkit.getEntity(a.alive) instanceof LivingEntity le ? le : null);
            if (boss == null || boss.isDead()) {
                a.alive = null;
                hideBar(a);
                double trigger = c.getDouble("trigger-distance", 32);
                if (now >= a.nextSpawn && !center.getNearbyPlayers(trigger).isEmpty()) spawn(a, false);
                continue;
            }
            if (boss.getLocation().distanceSquared(center) > 40 * 40) boss.teleport(center); // stay in the arena
            updateBar(a, boss);
            long every = c.getLong("slam-every-seconds", 8) * 1000;
            if (now - a.lastSlam >= every) {
                a.lastSlam = now;
                slam(boss, c.getDouble("slam-radius", 6));
            }
        }
    }

    /** Spawns the boss now. force = ignore the respawn timer (admin command). */
    boolean spawn(Arena a, boolean force) {
        ConfigurationSection c = cfg(a.id);
        Location center = a.center();
        if (c == null || center == null) return false;
        if (a.alive != null && Bukkit.getEntity(a.alive) != null) return false;
        MobManager.MobDef def = plugin.rpg().mobs().get(c.getString("mob", a.id));
        if (def == null) return false;
        LivingEntity boss = plugin.rpg().mobs().spawn(def, center, c.getInt("level", 10));
        if (boss == null) return false;
        boss.getPersistentDataContainer().set(bossKey, PersistentDataType.STRING, a.id);
        a.alive = boss.getUniqueId();
        a.damage.clear();
        a.lastSlam = System.currentTimeMillis();
        center.getWorld().playSound(center, Sound.ENTITY_WITHER_SPAWN, 1f, 0.8f);
        for (Player p : center.getNearbyPlayers(48)) {
            p.sendMessage(RPGExplore.MM.deserialize(def.name() + " <red>has awoken!"));
        }
        return true;
    }

    /** The boss smashes the ground: players close by are thrown back and hurt. */
    private void slam(LivingEntity boss, double radius) {
        Location l = boss.getLocation();
        l.getWorld().spawnParticle(Particle.EXPLOSION, l, 6, radius / 2, 0.2, radius / 2);
        l.getWorld().playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 0.7f);
        for (Player p : l.getNearbyPlayers(radius)) {
            Vector away = p.getLocation().toVector().subtract(l.toVector()).setY(0);
            if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
            p.setVelocity(away.normalize().multiply(1.4).setY(0.6));
            p.damage(1, boss); // goes through RPGCore's combat: the boss's damage stat
        }
    }

    private void updateBar(Arena a, LivingEntity boss) {
        MobManager.MobDef def = plugin.rpg().mobs().of(boss);
        if (def == null) return;
        if (a.bar == null) a.bar = BossBar.bossBar(Component.empty(), 1, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
        double hp = plugin.rpg().mobs().health(boss, def);
        a.bar.name(RPGExplore.MM.deserialize(def.name() + " <white>" + (int) Math.ceil(hp) + "/" + (int) def.health()));
        a.bar.progress((float) Math.max(0, Math.min(1, hp / def.health())));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().equals(boss.getWorld()) && p.getLocation().distanceSquared(boss.getLocation()) < 48 * 48) p.showBossBar(a.bar);
            else p.hideBossBar(a.bar);
        }
    }

    private void hideBar(Arena a) {
        if (a.bar != null) Bukkit.getOnlinePlayers().forEach(p -> p.hideBossBar(a.bar));
    }

    private Arena arenaOf(Entity e) {
        String id = e.getPersistentDataContainer().get(bossKey, PersistentDataType.STRING);
        return id == null ? null : arenas.get(id);
    }

    /** Remembers who did how much damage, to pick the winner. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        Arena a = arenaOf(e.getEntity());
        if (a == null) return;
        Entity d = e.getDamager();
        if (d instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) d = shooter;
        if (d instanceof Player p) a.damage.merge(p.getUniqueId(), e.getFinalDamage(), Double::sum);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent e) {
        Arena a = arenaOf(e.getEntity());
        if (a == null) return;
        ConfigurationSection c = cfg(a.id);
        a.alive = null;
        hideBar(a);
        a.nextSpawn = System.currentTimeMillis() + (c == null ? 30 : c.getLong("respawn-minutes", 30)) * 60_000;
        UUID winnerId = a.damage.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        Player winner = winnerId == null ? null : Bukkit.getPlayer(winnerId);
        if (c != null) {
            for (UUID id : a.damage.keySet()) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                Util.give(p, plugin.rpg().wallet().item(Wallet.Currency.COPPER, c.getInt("copper", 60)));
                Util.give(p, plugin.rpg().wallet().item(Wallet.Currency.AMBROSIA, c.getInt("ambrosia", 3)));
            }
            if (winner != null) {
                ItemStack drop = drop(a, c);
                if (drop != null) {
                    Util.give(winner, drop);
                    Bukkit.broadcast(RPGExplore.MM.deserialize("<gold>" + winner.getName() + "<yellow> defeated the boss and claimed ")
                            .append(drop.effectiveName()).append(RPGExplore.MM.deserialize("<yellow>!")));
                }
            }
        }
        a.kills++;
        save();
    }

    /** The one-of-one drop: tier gets weaker with every winner after the first. */
    private ItemStack drop(Arena a, ConfigurationSection c) {
        ItemStack it = plugin.item(c.getString("drop", ""), 1);
        if (it == null) return null;
        List<String> tiers = c.getStringList("tiers");
        int tier = Math.min(a.kills, Math.max(0, tiers.size() - 1));
        ItemMeta meta = it.getItemMeta();
        String tierName = tiers.isEmpty() ? "" : tiers.get(tier);
        meta.itemName(RPGExplore.MM.deserialize(tierName + " ").append(meta.itemName()).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Edition #" + (a.kills + 1), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Placeholder reward: the RPGWeapons team swaps this", NamedTextColor.DARK_GRAY),
                Component.text("for the real item (tier and edition are on the item).", NamedTextColor.DARK_GRAY)));
        meta.getPersistentDataContainer().set(bossKey, PersistentDataType.STRING, a.id);
        meta.getPersistentDataContainer().set(tierKey, PersistentDataType.INTEGER, tier);
        meta.getPersistentDataContainer().set(editionKey, PersistentDataType.INTEGER, a.kills + 1);
        it.setItemMeta(meta);
        return it;
    }

    void cleanup() {
        for (Arena a : arenas.values()) {
            hideBar(a);
            if (a.alive != null) {
                Entity e = Bukkit.getEntity(a.alive);
                if (e != null) e.remove(); // respawns when players come back
            }
        }
    }
}
