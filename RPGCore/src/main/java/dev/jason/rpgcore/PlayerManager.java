package dev.jason.rpgcore;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/** Class, level and XP live on the player (PersistentDataContainer), so no database is needed. */
public class PlayerManager {
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RPGCore plugin;
    private final NamespacedKey classKey, levelKey, xpKey, manaKey, healthMod, speedMod;

    public PlayerManager(RPGCore plugin) {
        this.plugin = plugin;
        classKey = new NamespacedKey(plugin, "class");
        levelKey = new NamespacedKey(plugin, "level");
        xpKey = new NamespacedKey(plugin, "xp");
        manaKey = new NamespacedKey(plugin, "mana");
        healthMod = new NamespacedKey(plugin, "class_health");
        speedMod = new NamespacedKey(plugin, "class_speed");
    }

    public RpgClass getClass(Player p) {
        return plugin.classes().get(p.getPersistentDataContainer().get(classKey, PersistentDataType.STRING));
    }

    public int getLevel(Player p) {
        return p.getPersistentDataContainer().getOrDefault(levelKey, PersistentDataType.INTEGER, 1);
    }

    public double getXp(Player p) {
        return p.getPersistentDataContainer().getOrDefault(xpKey, PersistentDataType.DOUBLE, 0.0);
    }

    /** Stat value for a player: their class's value plus held items (both hands) and armor, up to the stat's cap. 0 with no class. */
    public double stat(Player p, Stat stat) {
        RpgClass c = getClass(p);
        if (c == null) return 0;
        double value = c.stat(stat, getLevel(p)) + ItemStats.get(p.getInventory().getItemInMainHand(), stat)
                + ItemStats.get(p.getInventory().getItemInOffHand(), stat);
        for (org.bukkit.inventory.ItemStack armor : p.getInventory().getArmorContents()) value += ItemStats.get(armor, stat);
        return Math.min(value, stat.cap);
    }

    public int maxLevel() {
        return plugin.getConfig().getInt("leveling.max-level", 100);
    }

    /**
     * XP needed to go from this level to the next: base * level^exponent, and from late-start on the
     * curve continues from that point with the steeper late-exponent.
     */
    public double xpToNext(int level) {
        double base = plugin.getConfig().getDouble("leveling.base-xp", 100);
        double exp = plugin.getConfig().getDouble("leveling.exponent", 1.5);
        int lateStart = plugin.getConfig().getInt("leveling.late-start", 25);
        double lateExp = plugin.getConfig().getDouble("leveling.late-exponent", 2.5);
        if (level < lateStart) return Math.round(base * Math.pow(level, exp));
        return Math.round(base * Math.pow(lateStart, exp) * Math.pow((double) level / lateStart, lateExp));
    }

    /**
     * Health uses its own scale so classes can have thousands of HP (Minecraft caps real health at 1024).
     * The real heart bar always stays at 10 hearts and shows the percentage of RPG health left.
     */
    public double maxHealth(Player p) {
        return getClass(p) == null ? vanillaMax(p) : stat(p, Stat.HEALTH);
    }

    public double health(Player p) {
        return p.getHealth() / vanillaMax(p) * maxHealth(p);
    }

    private double vanillaMax(Player p) {
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        return max == null ? 20 : max.getValue();
    }

    public double maxMana(Player p) {
        return stat(p, Stat.MANA);
    }

    /** Current mana (stored on the player), never above max. Starts full. */
    public double getMana(Player p) {
        double max = maxMana(p);
        return Math.min(max, p.getPersistentDataContainer().getOrDefault(manaKey, PersistentDataType.DOUBLE, max));
    }

    public void setMana(Player p, double mana) {
        p.getPersistentDataContainer().set(manaKey, PersistentDataType.DOUBLE, Math.max(0, Math.min(maxMana(p), mana)));
    }

    /** Spends mana if the player has enough. For abilities later. */
    public boolean spendMana(Player p, double amount) {
        double mana = getMana(p);
        if (mana < amount) return false;
        setMana(p, mana - amount);
        return true;
    }

    /** Called once a second: regenerates a percentage of max mana. */
    public void regenMana(Player p) {
        if (getClass(p) == null) return;
        double pct = plugin.getConfig().getDouble("mana.regen-percent-per-second", 2);
        setMana(p, getMana(p) + maxMana(p) * pct / 100.0);
    }

    public void setClass(Player p, RpgClass c) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        pdc.set(classKey, PersistentDataType.STRING, c.id);
        pdc.set(levelKey, PersistentDataType.INTEGER, 1);
        pdc.set(xpKey, PersistentDataType.DOUBLE, 0.0);
        applyStats(p);
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        if (max != null) p.setHealth(max.getValue());
        setMana(p, maxMana(p));
    }

    public void reset(Player p) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        pdc.remove(classKey);
        pdc.remove(levelKey);
        pdc.remove(xpKey);
        pdc.remove(manaKey);
        applyStats(p);
    }

    public void setLevel(Player p, int level) {
        p.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, Math.max(1, Math.min(level, maxLevel())));
        p.getPersistentDataContainer().set(xpKey, PersistentDataType.DOUBLE, 0.0);
        applyStats(p);
    }

    public void addXp(Player p, double amount) {
        if (getClass(p) == null || amount <= 0) return;
        int level = getLevel(p);
        double xp = getXp(p) + amount;
        boolean leveled = false;
        while (level < maxLevel() && xp >= xpToNext(level)) {
            xp -= xpToNext(level);
            level++;
            leveled = true;
        }
        if (level >= maxLevel()) xp = 0;
        p.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, level);
        p.getPersistentDataContainer().set(xpKey, PersistentDataType.DOUBLE, xp);
        if (leveled) {
            applyStats(p);
            AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
            if (max != null) p.setHealth(max.getValue());
            setMana(p, maxMana(p));
            p.sendMessage(MM.deserialize("<gold><bold>LEVEL UP!</bold></gold> <gray>You are now level <white>" + level));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        }
        showBar(p, "<green>+" + (int) amount + " XP");
    }

    /** Applies Speed as an attribute modifier. Health is scaled in combat (see maxHealth), the rest is handled there too. */
    public void applyStats(Player p) {
        setModifier(p, Attribute.MAX_HEALTH, healthMod, 0, AttributeModifier.Operation.ADD_NUMBER); // clears the old 0.2 modifier
        setModifier(p, Attribute.MOVEMENT_SPEED, speedMod, stat(p, Stat.SPEED) / 100.0,
                AttributeModifier.Operation.ADD_SCALAR);
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        if (max != null && p.getHealth() > max.getValue()) p.setHealth(max.getValue());
    }

    private void setModifier(Player p, Attribute attr, NamespacedKey key, double amount, AttributeModifier.Operation op) {
        AttributeInstance inst = p.getAttribute(attr);
        if (inst == null) return;
        inst.removeModifier(key);
        if (amount != 0) inst.addTransientModifier(new AttributeModifier(key, amount, op));
    }

    /** Action bar: class, level and XP progress. */
    public void showBar(Player p, String suffix) {
        RpgClass c = getClass(p);
        if (c == null) return;
        int level = getLevel(p);
        String xpPart = level >= maxLevel() ? "<gold>MAX" : (int) getXp(p) + "/" + (int) xpToNext(level) + " XP";
        String text = c.coloredName() + " <dark_gray>|</dark_gray> <white>Lv " + level + "</white> <dark_gray>|</dark_gray> <gray>" + xpPart
                + " <dark_gray>|</dark_gray> <aqua>" + (int) getMana(p) + "/" + (int) maxMana(p) + " Mana";
        if (suffix != null) text += " <dark_gray>|</dark_gray> " + suffix;
        p.sendActionBar(MM.deserialize(text));
    }
}
