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

/** Custom enemies from mobs.yml. Each spawned mob is tagged with its id so we can find its stats later. */
public class MobManager {
    public record MobDef(String id, EntityType type, String name, double health, double damage, double speed,
                         double scale, double defense, double xp, ConfigurationSection equipment, String leatherColor) {}

    private final RPGCore plugin;
    private final NamespacedKey mobKey;
    private final Map<String, MobDef> mobs = new LinkedHashMap<>();

    public MobManager(RPGCore plugin) {
        this.plugin = plugin;
        this.mobKey = new NamespacedKey(plugin, "mob");
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
        plugin.getLogger().info("Loaded " + mobs.size() + " custom mobs.");
    }

    public MobDef get(String id) {
        return id == null ? null : mobs.get(id.toLowerCase());
    }

    public Collection<MobDef> all() {
        return mobs.values();
    }

    /** The definition of a spawned custom mob, or null for normal entities. */
    public MobDef of(Entity e) {
        return get(e.getPersistentDataContainer().get(mobKey, PersistentDataType.STRING));
    }

    public LivingEntity spawn(MobDef def, Location loc) {
        Entity raw = loc.getWorld().spawnEntity(loc, def.type());
        if (!(raw instanceof LivingEntity e)) {
            raw.remove();
            return null;
        }
        e.getPersistentDataContainer().set(mobKey, PersistentDataType.STRING, def.id());
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
