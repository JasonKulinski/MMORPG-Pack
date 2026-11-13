package dev.jason.rpgcore;

import java.util.EnumMap;
import java.util.Map;

/** A class: base stats at level 1 plus growth per level. */
public class RpgClass {
    public final String id;
    public String name;
    public String color;
    /** Short flavor text shown at the bottom of the stats book. Optional. */
    public String lore;
    public final Map<Stat, Double> base = new EnumMap<>(Stat.class);
    public final Map<Stat, Double> perLevel = new EnumMap<>(Stat.class);

    public RpgClass(String id, String name, String color) {
        this.id = id;
        this.name = name;
        this.color = color;
    }

    /** Stat value at a level, clamped to the stat's cap. */
    public double stat(Stat stat, int level) {
        double value = base.getOrDefault(stat, 0.0) + perLevel.getOrDefault(stat, 0.0) * (level - 1);
        return Math.min(value, stat.cap);
    }

    public String coloredName() {
        return "<" + color + ">" + name + "</" + color + ">";
    }
}
