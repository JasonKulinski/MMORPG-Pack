package dev.jason.rpgcore;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Custom enemies from mobs.yml (plus any registered by other plugins). Each spawned mob is tagged with its id
 * and level, so we can find its stats later. Level 1 is the mobs.yml stats; each level above adds
 * mob-levels.stat-per-level (config.yml) to health, damage and defense, and xp-per-level to XP.
 */
public class MobManager {
    public record MobDef(String id, EntityType type, String name, double health, double damage, double speed,
                         double scale, double defense, double xp, ConfigurationSection equipment, String leatherColor) {}

    private final RPGCore plugin;
    private final NamespacedKey mobKey, levelKey;
    private final Map<String, MobDef> mobs = new LinkedHashMap<>();
    /** Mobs registered by other plugins (e.g. RPGExplore); kept across /rpg reload. */
    private final Map<String, MobDef> external = new LinkedHashMap<>();

    public MobManager(RPGCore plugin) {
        this.plugin = plugin;
        this.mobKey = new NamespacedKey(plugin, "mob");
        this.levelKey = new NamespacedKey(plugin, "mob_level");
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "mobs.yml");
        if (!file.exists()) plugin.saveResource("mobs.yml", false);
        mobs.clear();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String id : yml.getKeys(false)) {
            ConfigurationSection s = yml.getConfigurationSection(id);
            if (s == null) continue;
            EntityType type;
            try {
                type = EntityType.valueOf(s.getString("type", "ZOMBIE").toUpperCase());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Mob '" + id + "' has an unknown type: " + s.getString("type"));
                continue;
            }
            mobs.put(id.toLowerCase(), new MobDef(id.toLowerCase(), type, s.getString("name", id),
                    s.getDouble("health", 20), s.getDouble("damage", 3), s.getDouble("speed", 0),
                    s.getDouble("scale", 1), s.getDouble("defense", 0), s.getDouble("xp", 10),
                    s.getConfigurationSection("equipment"), s.getString("leather-color")));
        }
        mobs.putAll(external);
        plugin.getLogger().info("Loaded " + mobs.size() + " custom mobs.");
    }

    /** Adds a mob from another plugin (same fields as mobs.yml). */
    public void register(MobDef def) {
        external.put(def.id(), def);
        mobs.put(def.id(), def);
    }

    public MobDef get(String id) {
        return id == null ? null : mobs.get(id.toLowerCase());
    }

    public Collection<MobDef> all() {
        return mobs.values();
    }

    /** The stats of a spawned custom mob at its level, or null for normal entities. */
    public MobDef of(Entity e) {
        MobDef base = get(e.getPersistentDataContainer().get(mobKey, PersistentDataType.STRING));
        return base == null ? null : atLevel(base, level(e));
    }

    /** A spawned custom mob's level (1 if it has none). */
    public int level(Entity e) {
        return e.getPersistentDataContainer().getOrDefault(levelKey, PersistentDataType.INTEGER, 1);
    }

    /** The mob's stats at a level. Level 1 = mobs.yml; the name gets a "Lv N" tag above level 1. */
    public MobDef atLevel(MobDef d, int level) {
        if (level <= 1) return d;
        double s = 1 + plugin.getConfig().getDouble("mob-levels.stat-per-level", 0.15) * (level - 1);
        double x = 1 + plugin.getConfig().getDouble("mob-levels.xp-per-level", 0.25) * (level - 1);
        return new MobDef(d.id(), d.type(), "<gray>Lv " + level + "</gray> " + d.name(), d.health() * s, d.damage() * s,
                d.speed(), d.scale(), d.defense() * s, d.xp() * x, d.equipment(), d.leatherColor());
    }

    public LivingEntity spawn(MobDef def, Location loc) {
        return spawn(def, loc, 1);
    }

    public LivingEntity spawn(MobDef base, Location loc, int level) {
        MobDef def = atLevel(base, level);
        Entity raw = loc.getWorld().spawnEntity(loc, def.type());
        if (!(raw instanceof LivingEntity e)) {
            raw.remove();
            return null;
        }
        e.getPersistentDataContainer().set(mobKey, PersistentDataType.STRING, def.id());
        if (level > 1) e.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, level);
        e.setCustomNameVisible(true);
        e.setRemoveWhenFarAway(false);

        // Minecraft caps health at 1024. Bigger mobs keep 1024 and damage is scaled to match (see health()).
        setBase(e, Attribute.MAX_HEALTH, Math.min(def.health(), 1024));
        e.setHealth(Math.min(def.health(), 1024));
        updateName(e);
        setBase(e, Attribute.ATTACK_DAMAGE, Math.min(def.damage(), 2048));
        if (def.speed() > 0) setBase(e, Attribute.MOVEMENT_SPEED, def.speed());
        if (def.scale() != 1) setBase(e, Attribute.SCALE, def.scale());

        if (e instanceof Zombie z) z.setShouldBurnInDay(false);
        if (e instanceof AbstractSkeleton s) s.setShouldBurnInDay(false);
        if (e instanceof Mob m) m.setCanPickupItems(false);
        // Piglins and hoglins would turn into zombified versions in the Overworld.
        if (e instanceof org.bukkit.entity.PiglinAbstract pa) pa.setImmuneToZombification(true);
        if (e instanceof org.bukkit.entity.Hoglin h) h.setImmuneToZombification(true);
        equip(e, def);
        return e;
    }

    /** Name tag: "Ghoul ||||||||||| 21/30" — green part is health left, red part is health lost. */
    public void updateName(LivingEntity e) {
        MobDef def = of(e);
        if (def == null) return;
        double max = def.health();
        double hp = Math.max(0, health(e, def));
        int bars = 10;
        int filled = hp <= 0 ? 0 : Math.max(1, (int) Math.round(hp / max * bars));
        String bar = "<green>" + "|".repeat(filled) + "</green><red>" + "|".repeat(bars - filled) + "</red>";
        e.customName(MiniMessage.miniMessage().deserialize(def.name() + " <reset><bold>" + bar + "</bold> <white>"
                + (int) Math.ceil(hp) + "/" + (int) Math.ceil(max)));
    }

    /** Current health on the RPG scale (the mob's real health bar may be capped at 1024). */
    public double health(LivingEntity e, MobDef def) {
        AttributeInstance max = e.getAttribute(Attribute.MAX_HEALTH);
        return max == null ? e.getHealth() : e.getHealth() / max.getValue() * def.health();
    }

    private void setBase(LivingEntity e, Attribute attr, double value) {
        AttributeInstance inst = e.getAttribute(attr);
        if (inst != null) inst.setBaseValue(value);
    }

    private void equip(LivingEntity e, MobDef def) {
        EntityEquipment eq = e.getEquipment();
        if (eq == null) return;
        // Clear whatever vanilla gave it, so stand-ins look consistent.
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND}) {
            eq.setItem(slot, null);
            eq.setDropChance(slot, 0f);
        }
        if (def.equipment() == null) return;
        for (String key : def.equipment().getKeys(false)) {
            EquipmentSlot slot = switch (key.toLowerCase()) {
                case "head" -> EquipmentSlot.HEAD;
                case "chest" -> EquipmentSlot.CHEST;
                case "legs" -> EquipmentSlot.LEGS;
                case "feet" -> EquipmentSlot.FEET;
                case "hand" -> EquipmentSlot.HAND;
                case "offhand" -> EquipmentSlot.OFF_HAND;
                default -> null;
            };
            Material mat = Material.matchMaterial(def.equipment().getString(key, "").toUpperCase());
            if (slot == null || mat == null) {
                plugin.getLogger().warning("Mob '" + def.id() + "' has bad equipment entry: " + key);
                continue;
            }
            ItemStack item = new ItemStack(mat);
            if (def.leatherColor() != null && item.getItemMeta() instanceof LeatherArmorMeta meta) {
                meta.setColor(Color.fromRGB(Integer.parseInt(def.leatherColor().replace("#", ""), 16)));
                item.setItemMeta(meta);
            }
            eq.setItem(slot, item);
            eq.setDropChance(slot, 0f);
        }
    }
}
