package dev.jason.rpgcore;

import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Health is on an RPG scale (class Health / mobs.yml health); damage is worked out on that scale and then
 * converted to the target's real health bar, which Minecraft caps at 1024.
 * Damage order: Attack -> Crit -> target Defense (minus your Pierce) -> target Damage Reduction.
 * Life Steal heals from the final number. Slowness and Poison are on-hit chances.
 */
public class CombatListener implements Listener {
    private final RPGCore plugin;

    public CombatListener(RPGCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof LivingEntity target)) return;
        Player attacker = attackerOf(e.getDamager());
        MobManager.MobDef attackerMob = mobAttackerOf(e.getDamager());
        PlayerManager pm = plugin.players();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Everything below is on the RPG health scale.
        double damage = attackerMob != null ? attackerMob.damage() : e.getDamage();
        double pierce = 0;
        boolean crit = false;

        if (attacker != null && pm.getClass(attacker) != null) {
            damage += pm.stat(attacker, Stat.ATTACK);
            if (rng.nextDouble() * 100 < pm.stat(attacker, Stat.CRIT_CHANCE)) {
                damage *= 1 + pm.stat(attacker, Stat.CRIT_DAMAGE) / 100.0;
                crit = true;
            }
            pierce = pm.stat(attacker, Stat.PIERCE);
        }

        double defense = defenseOf(target);
        double effective = defense * (1 - pierce / 100.0);
        damage *= 100.0 / (100.0 + effective);

        if (target instanceof Player tp && pm.getClass(tp) != null) {
            damage *= 1 - pm.stat(tp, Stat.DAMAGE_REDUCTION) / 100.0;
        }
        damage = Math.max(0, damage);

        // Convert to the target's real health bar.
        e.setDamage(damage * vanillaMax(target) / rpgMax(target));

        if (attacker == null || pm.getClass(attacker) == null) return;

        double lifeSteal = pm.stat(attacker, Stat.LIFE_STEAL);
        if (lifeSteal > 0) {
            double max = vanillaMax(attacker);
            double heal = damage * lifeSteal / 100.0 * max / rpgMax(attacker);
            attacker.setHealth(Math.min(max, attacker.getHealth() + heal));
        }
        if (rng.nextDouble() * 100 < pm.stat(attacker, Stat.SLOWNESS)) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 0));
        }
        if (rng.nextDouble() * 100 < pm.stat(attacker, Stat.POISON)) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0));
        }
        if (crit) {
            target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.2);
            attacker.playSound(attacker.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1.2f);
        }
    }

    /** Max health on the RPG scale: class Health for players, mobs.yml health for custom mobs. */
    private double rpgMax(LivingEntity e) {
        if (e instanceof Player p) return plugin.players().maxHealth(p);
        MobManager.MobDef def = plugin.mobs().of(e);
        return def != null ? def.health() : vanillaMax(e);
    }

    private double vanillaMax(LivingEntity e) {
        AttributeInstance max = e.getAttribute(Attribute.MAX_HEALTH);
        return max == null ? 20 : max.getValue();
    }

    /** The custom mob behind a hit (melee, or an arrow it shot), or null. */
    private MobManager.MobDef mobAttackerOf(Entity damager) {
        if (damager instanceof Projectile proj && proj.getShooter() instanceof LivingEntity shooter) damager = shooter;
        return damager instanceof LivingEntity le ? plugin.mobs().of(le) : null;
    }

    private Player attackerOf(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }

    private double defenseOf(LivingEntity target) {
        if (target instanceof Player p) return plugin.players().stat(p, Stat.DEFENSE);
        MobManager.MobDef def = plugin.mobs().of(target);
        return def == null ? 0 : def.defense();
    }

    /** Custom mobs give RPG XP and drop nothing. Vanilla mobs give no RPG XP. */
    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        MobManager.MobDef def = plugin.mobs().of(e.getEntity());
        if (def == null) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        Player killer = e.getEntity().getKiller();
        if (killer != null) plugin.players().addXp(killer, def.xp());
    }

    /** Keeps custom mobs' health bars current after any damage or healing. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnyDamage(EntityDamageEvent e) {
        refreshBar(e.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeal(EntityRegainHealthEvent e) {
        refreshBar(e.getEntity());
    }

    private void refreshBar(Entity entity) {
        if (!(entity instanceof LivingEntity le) || plugin.mobs().of(le) == null) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (le.isValid()) plugin.mobs().updateName(le);
        });
    }

    /** Custom mobs never burn in sunlight (fire from players, lava or fire blocks still works). */
    @EventHandler(ignoreCancelled = true)
    public void onCombust(EntityCombustEvent e) {
        if (e instanceof EntityCombustByEntityEvent || e instanceof EntityCombustByBlockEvent) return;
        if (plugin.mobs().of(e.getEntity()) != null) e.setCancelled(true);
    }

    /** Blocks vanilla hostile mobs. Plugin spawns (CUSTOM), /summon and spawn eggs still work. */
    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!plugin.getConfig().getBoolean("spawning.block-vanilla-hostiles", true)) return;
        if (!(e.getEntity() instanceof Enemy)) return;
        switch (e.getSpawnReason()) {
            case CUSTOM, COMMAND, SPAWNER_EGG -> {}
            default -> e.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.players().applyStats(e.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> plugin.players().applyStats(e.getPlayer()));
    }

    /* Item stats count while held or worn: re-apply Speed when what the player holds or wears changes. */
    @EventHandler
    public void onHeld(PlayerItemHeldEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> plugin.players().applyStats(e.getPlayer()));
    }

    @EventHandler
    public void onArmor(PlayerArmorChangeEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> plugin.players().applyStats(e.getPlayer()));
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> plugin.players().applyStats(e.getPlayer()));
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) Bukkit.getScheduler().runTask(plugin, () -> plugin.players().applyStats(p));
    }
}
