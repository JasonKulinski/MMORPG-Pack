package dev.jason.rpgcore;

import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom items from items.yml (tools, weapons, materials). Each one is a vanilla item underneath (so a
 * black stone pickaxe mines and wears out like a stone pickaxe) with its own name, texture, recipe and stats.
 *
 * Other plugins (e.g. RPGWeapons) can add items with register(...), then call packHost().load() so their
 * textures are added to the resource pack.
 */
public class CustomItemManager implements Listener {
    public record CustomItem(String id, String name, Material base, File texture, ConfigurationSection recipe,
                             Map<Stat, Double> stats) {}

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String[] DEFAULT_TEXTURES = {"black_stone_sword", "black_stone_pickaxe", "black_stone_axe",
            "black_stone_shovel", "black_stone_hoe"};

    private final RPGCore plugin;
    private final NamespacedKey itemKey;
    private final Map<String, CustomItem> items = new LinkedHashMap<>();
    private final List<NamespacedKey> recipes = new ArrayList<>();
    /** Items registered by other plugins; kept across /rpg reload. */
    private final Map<String, CustomItem> external = new LinkedHashMap<>();

    public CustomItemManager(RPGCore plugin) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "item");
    }

    public void load() {
        recipes.forEach(Bukkit::removeRecipe);
        recipes.clear();
        items.clear();
        File file = new File(plugin.getDataFolder(), "items.yml");
        if (!file.exists()) plugin.saveResource("items.yml", false);
        for (String tex : DEFAULT_TEXTURES) {
            if (!new File(plugin.getDataFolder(), "textures/item/" + tex + ".png").exists()) {
                plugin.saveResource("textures/item/" + tex + ".png", false);
            }
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String id : yml.getKeys(false)) {
            ConfigurationSection s = yml.getConfigurationSection(id);
            if (s == null) continue;
            Material base = Material.matchMaterial(s.getString("base", "stick"));
            if (base == null || !base.isItem()) {
                plugin.getLogger().warning("items.yml: " + id + " has an unknown base item.");
                continue;
            }
            Map<Stat, Double> stats = new LinkedHashMap<>();
            ConfigurationSection st = s.getConfigurationSection("stats");
            if (st != null) {
                for (String k : st.getKeys(false)) {
                    Stat stat = Stat.fromKey(k);
                    if (stat == null) plugin.getLogger().warning("items.yml: unknown stat '" + k + "' on " + id);
                    else stats.put(stat, st.getDouble(k));
                }
            }
            File tex = new File(plugin.getDataFolder(), "textures/item/" + s.getString("texture", id) + ".png");
            items.put(id, new CustomItem(id, s.getString("name", id), base, tex, s.getConfigurationSection("recipe"), stats));
        }
        items.putAll(external);
        // Recipes after all items are known, so recipes can use other custom items.
        for (CustomItem it : items.values()) addRecipe(it);
        Bukkit.getOnlinePlayers().forEach(p -> p.discoverRecipes(recipes));
        plugin.getLogger().info("Loaded " + items.size() + " custom items.");
    }

    /**
     * recipe:
     *   shape: ["BBB", " S ", " S "]
     *   keys: {B: "block:black_cobblestone", S: stick}
     * A key is a vanilla item, "block:<custom block>" or "item:<custom item>".
     */
    private void addRecipe(CustomItem it) {
        ConfigurationSection r = it.recipe();
        if (r == null) return;
        List<String> shape = r.getStringList("shape");
        ConfigurationSection keys = r.getConfigurationSection("keys");
        if (shape.isEmpty() || keys == null) return;
        NamespacedKey key = new NamespacedKey(plugin, "item_" + it.id());
        ShapedRecipe recipe = new ShapedRecipe(key, item(it, r.getInt("amount", 1)));
        recipe.shape(shape.toArray(new String[0]));
        for (String k : keys.getKeys(false)) {
            RecipeChoice choice = choice(keys.getString(k, ""));
            if (choice == null || k.length() != 1) {
                plugin.getLogger().warning("items.yml: bad recipe key " + k + " for " + it.id());
                return;
            }
            recipe.setIngredient(k.charAt(0), choice);
        }
        Bukkit.addRecipe(recipe);
        recipes.add(key);
    }

    private RecipeChoice choice(String value) {
        if (value.startsWith("block:")) {
            CustomBlockManager.CustomBlock b = plugin.blocks().get(value.substring(6));
            return b == null ? null : plugin.blocks().recipeChoice(b);
        }
        if (value.startsWith("item:")) {
            CustomItem ci = items.get(value.substring(5));
            return ci == null ? null : new RecipeChoice.ExactChoice(item(ci, 1));
        }
        Material m = Material.matchMaterial(value);
        return m == null ? null : new RecipeChoice.MaterialChoice(m);
    }

    /**
     * Adds an item from another plugin. texture is a 16x16 PNG anywhere on disk. Call packHost().load()
     * after registering so the texture is added to the resource pack.
     */
    public CustomItem register(String id, String name, Material base, File texture, Map<Stat, Double> stats) {
        CustomItem ci = new CustomItem(id, name, base, texture, null, new LinkedHashMap<>(stats));
        external.put(id, ci);
        items.put(id, ci);
        return ci;
    }

    /** The custom item this stack is, or null. */
    public CustomItem of(ItemStack it) {
        if (it == null || it.isEmpty() || !it.hasItemMeta()) return null;
        String id = it.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        return id == null ? null : items.get(id);
    }

    public CustomItem get(String id) {
        return items.get(id);
    }

    public Collection<CustomItem> all() {
        return items.values();
    }

    public ItemStack item(CustomItem ci, int amount) {
        ItemStack it = new ItemStack(ci.base(), amount);
        ItemMeta meta = it.getItemMeta();
        meta.itemName(MM.deserialize(ci.name()).decoration(TextDecoration.ITALIC, false));
        meta.setItemModel(new NamespacedKey("rpgcore", "item/" + ci.id()));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, ci.id());
        it.setItemMeta(meta);
        ItemStats.apply(it, ci.stats());
        return it;
    }

    /** Textures, models and item definitions for the resource pack. */
    public Map<String, byte[]> packFiles() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (CustomItem ci : items.values()) {
            File png = ci.texture();
            try {
                if (png.exists()) files.put("assets/rpgcore/textures/item/" + ci.id() + ".png", Files.readAllBytes(png.toPath()));
                else plugin.getLogger().warning("Missing texture for " + ci.id() + ": " + png);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not read " + png + ": " + e.getMessage());
            }
            // Tools and weapons are held like tools; anything else like a flat item.
            String parent = isHandheld(ci.base()) ? "minecraft:item/handheld" : "minecraft:item/generated";
            files.put("assets/rpgcore/models/item/" + ci.id() + ".json", utf8(
                    "{\"parent\": \"" + parent + "\", \"textures\": {\"layer0\": \"rpgcore:item/" + ci.id() + "\"}}\n"));
            files.put("assets/rpgcore/items/item/" + ci.id() + ".json", utf8(
                    "{\"model\": {\"type\": \"minecraft:model\", \"model\": \"rpgcore:item/" + ci.id() + "\"}}\n"));
        }
        return files;
    }

    private static boolean isHandheld(Material m) {
        String n = m.name();
        return n.endsWith("_SWORD") || n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL")
                || n.endsWith("_HOE") || n.endsWith("_SPEAR") || m == Material.STICK || m == Material.MACE;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Players see the custom recipes in their recipe book. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        e.getPlayer().discoverRecipes(recipes);
    }

    /** Older custom block items are note blocks, which count as wood: don't let them burn as furnace fuel. */
    @EventHandler(ignoreCancelled = true)
    public void onFuel(FurnaceBurnEvent e) {
        if (plugin.blocks().of(e.getFuel()) != null) {
            e.setCancelled(true);
        }
    }
}
