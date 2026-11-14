package dev.jason.rpgexplore;

import dev.jason.rpgcore.CustomItemManager;
import dev.jason.rpgcore.Wallet;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Wandering traders that appear near players in the wilds and sell for copper coins; and the potions. */
public class Traders implements Listener {
    private final RPGExplore plugin;
    private final NamespacedKey traderKey;

    Traders(RPGExplore plugin) {
        this.plugin = plugin;
        this.traderKey = new NamespacedKey(plugin, "trader");
    }

    /** Once a minute: maybe a trader shows up near each player out in the wilds. */
    void tick() {
        if (!plugin.getConfig().getBoolean("traders.enabled", true)) return;
        double chance = plugin.getConfig().getDouble("traders.chance-per-minute", 0.12);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!plugin.wilds().inWilds(p) || ThreadLocalRandom.current().nextDouble() >= chance) continue;
            boolean already = p.getNearbyEntities(64, 32, 64).stream().anyMatch(e -> e.getPersistentDataContainer().has(traderKey));
            if (already) continue;
            Location spot = Util.ring(p.getLocation(), 6, 12);
            if (spot == null) continue;
            spawn(spot);
            p.sendMessage(RPGExplore.MM.deserialize("<gold>A wandering trader has appeared nearby."));
        }
    }

    WanderingTrader spawn(Location at) {
        return at.getWorld().spawn(at, WanderingTrader.class, t -> {
            t.getPersistentDataContainer().set(traderKey, PersistentDataType.BYTE, (byte) 1);
            t.setDespawnDelay(plugin.getConfig().getInt("traders.lifetime-minutes", 5) * 1200);
            t.setRecipes(trades());
            t.customName(RPGExplore.MM.deserialize("<gold>Wandering Trader"));
        });
    }

    private List<MerchantRecipe> trades() {
        List<MerchantRecipe> out = new ArrayList<>();
        for (Map<?, ?> t : plugin.getConfig().getMapList("traders.trades")) {
            int cost = Math.min(64, ((Number) t.get("cost")).intValue());
            Object amountObj = t.get("amount");
            ItemStack result = plugin.item(String.valueOf(t.get("item")), amountObj == null ? 1 : ((Number) amountObj).intValue());
            if (result == null) {
                plugin.getLogger().warning("Unknown trader item: " + t.get("item"));
                continue;
            }
            MerchantRecipe r = new MerchantRecipe(result, 8);
            r.addIngredient(plugin.rpg().wallet().item(Wallet.Currency.COPPER, cost));
            r.setExperienceReward(false);
            out.add(r);
        }
        return out;
    }

    /** Health potions heal a share of the player's max health (RPG health is a percentage of the heart bar). */
    @EventHandler(ignoreCancelled = true)
    public void onDrink(PlayerItemConsumeEvent e) {
        CustomItemManager.CustomItem ci = plugin.rpg().items().of(e.getItem());
        if (ci == null || !plugin.getConfig().isDouble("potions." + ci.id())) return;
        Player p = e.getPlayer();
        double share = plugin.getConfig().getDouble("potions." + ci.id());
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        double m = max == null ? 20 : max.getValue();
        p.setHealth(Math.min(m, p.getHealth() + m * share));
        p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 2, 0), 5, 0.4, 0.3, 0.4);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.8f);
    }
}
