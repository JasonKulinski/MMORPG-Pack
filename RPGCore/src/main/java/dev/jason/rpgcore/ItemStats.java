package dev.jason.rpgcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Stats on items. A held weapon or worn armor adds its stats to the player's class stats.
 * Stored on the item as rpgcore:stat_<key> (a double), e.g. rpgcore:stat_attack = 40.
 * Other plugins can use set/apply, or write those keys themselves.
 */
public final class ItemStats {
    private ItemStats() {}

    public static NamespacedKey key(Stat stat) {
        return new NamespacedKey("rpgcore", "stat_" + stat.key);
    }

    public static double get(ItemStack item, Stat stat) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return 0;
        return item.getItemMeta().getPersistentDataContainer().getOrDefault(key(stat), PersistentDataType.DOUBLE, 0.0);
    }

    /** Puts the stats on the item and lists them in its tooltip ("+40 Attack"). */
    public static void apply(ItemStack item, Map<Stat, Double> stats) {
        if (stats.isEmpty()) return;
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>();
        for (Map.Entry<Stat, Double> e : stats.entrySet()) {
            Stat s = e.getKey();
            double v = e.getValue();
            meta.getPersistentDataContainer().set(key(s), PersistentDataType.DOUBLE, v);
            String num = (v == Math.floor(v) ? String.valueOf((long) Math.abs(v)) : String.valueOf(Math.abs(v)))
                    + (s.label.endsWith("%") ? "%" : "");
            lore.add(Component.text((v < 0 ? "-" : "+") + num + " " + s.label.replace(" %", ""),
                    v < 0 ? NamedTextColor.RED : NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
    }
}
