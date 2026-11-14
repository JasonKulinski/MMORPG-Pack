package dev.jason.rpgcore;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Copper and Ambrosia. Each player has a balance (stored on the player), and both also exist as physical
 * items (Copper Coin, Ambrosia) that drop from mobs and quests. /wallet deposit turns the items in your
 * inventory into balance; /wallet withdraw turns balance back into items (e.g. to pay a wandering trader).
 * Neither can be mined or crafted.
 */
public class Wallet implements CommandExecutor, TabCompleter {
    public enum Currency {
        COPPER("copper", "copper_coin", "<#d9884a>Copper Coin", "<#d9884a>Copper"),
        AMBROSIA("ambrosia", "ambrosia", "<#f2d36b>Ambrosia", "<#f2d36b>Ambrosia");

        public final String key, itemId, itemName, label;

        Currency(String key, String itemId, String itemName, String label) {
            this.key = key;
            this.itemId = itemId;
            this.itemName = itemName;
            this.label = label;
        }

        public static Currency of(String s) {
            for (Currency c : values()) if (c.key.equalsIgnoreCase(s)) return c;
            return null;
        }
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final RPGCore plugin;

    public Wallet(RPGCore plugin) {
        this.plugin = plugin;
    }

    /** Registers the Copper Coin and Ambrosia items (textures in plugins/RPGCore/textures/item/). */
    public void registerItems() {
        for (Currency c : Currency.values()) {
            File tex = new File(plugin.getDataFolder(), "textures/item/" + c.itemId + ".png");
            if (!tex.exists()) plugin.saveResource("textures/item/" + c.itemId + ".png", false);
            plugin.items().register(c.itemId, c.itemName, Material.PAPER, tex, Map.of());
        }
    }

    private NamespacedKey key(Currency c) {
        return new NamespacedKey(plugin, "wallet_" + c.key);
    }

    public long balance(Player p, Currency c) {
        return p.getPersistentDataContainer().getOrDefault(key(c), PersistentDataType.LONG, 0L);
    }

    public void add(Player p, Currency c, long amount) {
        p.getPersistentDataContainer().set(key(c), PersistentDataType.LONG, Math.max(0, balance(p, c) + amount));
    }

    /** Takes from the balance if there is enough. */
    public boolean take(Player p, Currency c, long amount) {
        if (balance(p, c) < amount) return false;
        add(p, c, -amount);
        return true;
    }

    /** The physical item for a currency. */
    public ItemStack item(Currency c, int amount) {
        return plugin.items().item(plugin.items().get(c.itemId), amount);
    }

    /** Which currency an item is, or null. */
    public Currency currencyOf(ItemStack it) {
        CustomItemManager.CustomItem ci = plugin.items().of(it);
        if (ci == null) return null;
        for (Currency c : Currency.values()) if (c.itemId.equals(ci.id())) return c;
        return null;
    }

    /** Moves every coin and ambrosia item in the inventory into the balance. Returns how many items. */
    public int depositAll(Player p) {
        int moved = 0;
        ItemStack[] contents = p.getInventory().getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            Currency c = currencyOf(contents[i]);
            if (c == null) continue;
            add(p, c, contents[i].getAmount());
            moved += contents[i].getAmount();
            p.getInventory().setItem(i, null);
        }
        return moved;
    }

    /** Turns balance into items in the inventory (extra items drop at the player's feet). */
    public boolean withdraw(Player p, Currency c, int amount) {
        if (amount <= 0 || !take(p, c, amount)) return false;
        while (amount > 0) {
            int n = Math.min(64, amount);
            p.getInventory().addItem(item(c, n)).values().forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
            amount -= n;
        }
        return true;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) {
            s.sendMessage("Players only.");
            return true;
        }
        String sub = a.length > 0 ? a[0].toLowerCase() : "";
        switch (sub) {
            case "deposit" -> p.sendMessage(MM.deserialize("<green>Deposited " + depositAll(p) + " items into your wallet."));
            case "withdraw" -> {
                Currency c = a.length > 1 ? Currency.of(a[1]) : null;
                int amount = 0;
                try {
                    amount = a.length > 2 ? Integer.parseInt(a[2]) : 0;
                } catch (NumberFormatException ignored) {}
                if (c == null || amount <= 0) {
                    p.sendMessage(MM.deserialize("<red>/wallet withdraw <copper|ambrosia> <amount>"));
                } else if (!withdraw(p, c, Math.min(amount, 2304))) {
                    p.sendMessage(MM.deserialize("<red>You don't have that much."));
                } else {
                    p.sendMessage(MM.deserialize("<green>Withdrew " + amount + " " + c.label + "<green>."));
                }
            }
            case "give" -> {
                if (!s.hasPermission("rpgcore.admin") || a.length < 4) {
                    p.sendMessage(MM.deserialize("<red>/wallet give <player> <copper|ambrosia> <amount>"));
                    return true;
                }
                Player t = plugin.getServer().getPlayerExact(a[1]);
                Currency c = Currency.of(a[2]);
                if (t == null || c == null) {
                    p.sendMessage(MM.deserialize("<red>Unknown player or currency."));
                    return true;
                }
                add(t, c, Long.parseLong(a[3]));
                p.sendMessage(MM.deserialize("<green>Gave " + a[3] + " " + c.key + " to " + t.getName() + "."));
            }
            default -> {
                p.sendMessage(MM.deserialize("<gold>Wallet: " + Currency.COPPER.label + " <white>" + balance(p, Currency.COPPER)
                        + "  " + Currency.AMBROSIA.label + " <white>" + balance(p, Currency.AMBROSIA)));
                p.sendMessage(MM.deserialize("<gray>/wallet deposit <dark_gray>- coins and ambrosia in your inventory into your wallet"));
                p.sendMessage(MM.deserialize("<gray>/wallet withdraw <copper|ambrosia> <amount> <dark_gray>- take some out as items"));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 1) return List.of("deposit", "withdraw", "give");
        if (a.length == 2 && a[0].equalsIgnoreCase("withdraw")) return List.of("copper", "ambrosia");
        if (a.length == 3 && a[0].equalsIgnoreCase("give")) return List.of("copper", "ambrosia");
        return a.length == 2 && a[0].equalsIgnoreCase("give") ? null : List.of();
    }
}
