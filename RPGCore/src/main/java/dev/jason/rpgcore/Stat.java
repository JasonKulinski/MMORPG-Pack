package dev.jason.rpgcore;

/** Every stat a class can have. Percent stats are stored as whole numbers (15 = 15%). */
public enum Stat {
    HEALTH("health", "Health", Double.MAX_VALUE, "Your max HP. 2 = one heart."),
    MANA("mana", "Mana", Double.MAX_VALUE, "Your max mana. Abilities spend it."),
    ATTACK("attack", "Attack", Double.MAX_VALUE, "Bonus damage added to every hit."),
    DEFENSE("defense", "Defense", Double.MAX_VALUE, "Reduces damage taken: def / (def + 100)."),
    DAMAGE_REDUCTION("damage_reduction", "Dmg Reduction %", 50, "Cuts damage taken after Defense."),
    PIERCE("pierce", "Pierce %", 60, "Ignores this much of the target's Defense."),
    CRIT_CHANCE("crit_chance", "Crit Chance %", 75, "Chance for a hit to crit."),
    CRIT_DAMAGE("crit_damage", "Crit Damage %", Double.MAX_VALUE, "Extra damage when you crit."),
    LIFE_STEAL("life_steal", "Life Steal %", 25, "Heals you for this much of the damage you deal."),
    SLOWNESS("slowness", "Slow Chance %", 40, "Chance on hit to slow the target for 2s."),
    POISON("poison", "Poison Chance %", 40, "Chance on hit to poison the target for 3s."),
    SPEED("speed", "Speed %", 50, "Bonus movement speed.");

    public final String key;
    public final String label;
    public final double cap;
    public final String desc;

    Stat(String key, String label, double cap, String desc) {
        this.key = key;
        this.label = label;
        this.cap = cap;
        this.desc = desc;
    }

    public static Stat fromKey(String key) {
        for (Stat s : values()) if (s.key.equalsIgnoreCase(key)) return s;
        return null;
    }
}
