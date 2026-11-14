package dev.jason.rpgexplore;

import dev.jason.rpgcore.CustomBlockManager;
import dev.jason.rpgcore.MobManager;
import dev.jason.rpgcore.RPGCore;
import dev.jason.rpgcore.Stat;
import dev.jason.rpgtowns.RPGTowns;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * The world outside towns (the "Exploring" part of the design board):
 *  - Wilds: enemies around players, stronger every 500 blocks from the nearest town, themed by that town;
 *    every custom enemy drops copper coins and sometimes ambrosia.
 *  - Traders: wandering traders who sell for copper coins (e.g. 10 copper -> small health potion).
 *  - Quests: wandering travelers with fetch quests or the lost-ring quest (return it or keep it).
 *  - Regions: special areas such as evil pixie hollows (red quartz, pixies, entry titles) and
 *    territories that towns capture and that pay into the owning town's treasury.
 *  - Dungeons: waves of custom enemies, reward at the end.
 *  - Bosses: one-of-one bosses whose drop gets weaker for every later winner.
 * Uses RPGCore for mobs, items, XP and the wallet, and RPGTowns for towns and treasuries.
 */
public class RPGExplore extends JavaPlugin {
    static final MiniMessage MM = MiniMessage.miniMessage();
    static final String[] TEXTURES = {"small_health_potion", "healing_potion", "travelers_ring", "hendors_token"};

    private RPGCore rpg;
    private RPGTowns towns;
    private YamlConfiguration data;
    private Wilds wilds;
    private Traders traders;
    private Quests quests;
    private Regions regions;
    private Dungeons dungeons;
    private Bosses bosses;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        rpg = (RPGCore) Bukkit.getPluginManager().getPlugin("RPGCore");
        towns = (RPGTowns) Bukkit.getPluginManager().getPlugin("RPGTowns");
        for (String t : TEXTURES) {
            if (!new File(getDataFolder(), "textures/" + t + ".png").exists()) saveResource("textures/" + t + ".png", false);
        }
        data = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "explore.yml"));
        registerContent();

        wilds = new Wilds(this);
        traders = new Traders(this);
        quests = new Quests(this);
        regions = new Regions(this);
        dungeons = new Dungeons(this);
        bosses = new Bosses(this);
        regions.load();
        dungeons.load();
        bosses.load();
        for (var l : new org.bukkit.event.Listener[]{wilds, traders, quests, regions, dungeons, bosses}) {
            getServer().getPluginManager().registerEvents(l, this);
        }
        Commands cmd = new Commands(this);
        var c = getCommand("explore");
        if (c != null) {
            c.setExecutor(cmd);
            c.setTabCompleter(cmd);
        }

        var s = Bukkit.getScheduler();
        s.runTaskTimer(this, wilds::spawnTick, 100, Math.max(20, getConfig().getInt("wild-spawns.interval-seconds", 8) * 20L));
        s.runTaskTimer(this, wilds::despawnTick, 400, 400);
        s.runTaskTimer(this, wilds::bandTick, 40, 40);
        s.runTaskTimer(this, traders::tick, 1200, 1200);
        s.runTaskTimer(this, quests::tick, 1200, 1200);
        s.runTaskTimer(this, quests::beaconTick, 20, 20);
        s.runTaskTimer(this, regions::tick, 20, 20);
        s.runTaskTimer(this, regions::incomeTick, 1200, 1200);
        s.runTaskTimer(this, dungeons::tick, 20, 20);
        s.runTaskTimer(this, bosses::tick, 20, 20);
    }

    @Override
    public void onDisable() {
        if (quests != null) quests.cleanup();
        if (dungeons != null) dungeons.cleanup();
        if (bosses != null) bosses.cleanup();
        if (regions != null) regions.cleanup();
    }

    /** Registers this plugin's mobs and items into RPGCore and adds the healing potion recipe. */
    private void registerContent() {
        ConfigurationSection mobs = getConfig().getConfigurationSection("mobs");
        if (mobs != null) {
            for (String id : mobs.getKeys(false)) {
                ConfigurationSection m = mobs.getConfigurationSection(id);
                if (m == null) continue;
                try {
                    rpg.mobs().register(new MobManager.MobDef(id.toLowerCase(),
                            EntityType.valueOf(m.getString("type", "ZOMBIE").toUpperCase()), m.getString("name", id),
                            m.getDouble("health", 20), m.getDouble("damage", 3), m.getDouble("speed", 0),
                            m.getDouble("scale", 1), m.getDouble("defense", 0), m.getDouble("xp", 10),
                            m.getConfigurationSection("equipment"), m.getString("leather-color")));
                } catch (IllegalArgumentException e) {
                    getLogger().warning("config.yml mobs: " + id + " has an unknown type.");
                }
            }
        }
        File tex = new File(getDataFolder(), "textures");
        rpg.items().register("small_health_potion", "<#f05a64>Small Health Potion", Material.POTION,
                new File(tex, "small_health_potion.png"), Map.of());
        rpg.items().register("healing_potion", "<#ff466e>Healing Potion", Material.POTION,
                new File(tex, "healing_potion.png"), Map.of());
        rpg.items().register("travelers_ring", "<#7fd3ff>Traveler's Ring", Material.PAPER,
                new File(tex, "travelers_ring.png"), Map.of(Stat.CRIT_CHANCE, 5.0, Stat.HEALTH, 40.0));
        rpg.items().register("hendors_token", "<gold>Hendor's Token", Material.PAPER,
                new File(tex, "hendors_token.png"), Map.of());
        rpg.packHost().load(); // add our textures to the resource pack

        // Healing potion = red quartz crystal + glass bottle
        NamespacedKey key = new NamespacedKey(this, "healing_potion");
        Bukkit.removeRecipe(key);
        CustomBlockManager.CustomBlock quartz = rpg.blocks().get("red_quartz_crystal");
        if (quartz == null) {
            getLogger().warning("No red_quartz_crystal in RPGCore's blocks.yml: the healing potion recipe is disabled.");
            return;
        }
        ShapelessRecipe r = new ShapelessRecipe(key, item("healing_potion", 1));
        r.addIngredient(rpg.blocks().recipeChoice(quartz));
        r.addIngredient(new RecipeChoice.MaterialChoice(Material.GLASS_BOTTLE));
        Bukkit.addRecipe(r);
    }

    /** An RPGCore custom item by id, or a vanilla item ("minecraft:bread"). */
    ItemStack item(String id, int amount) {
        if (id.startsWith("minecraft:")) {
            Material m = Material.matchMaterial(id);
            return m == null ? null : new ItemStack(m, amount);
        }
        var ci = rpg.items().get(id);
        return ci == null ? null : rpg.items().item(ci, amount);
    }

    public void reload() {
        reloadConfig();
        registerContent();
    }

    RPGCore rpg() { return rpg; }
    RPGTowns towns() { return towns; }
    YamlConfiguration data() { return data; }
    Wilds wilds() { return wilds; }
    Traders traders() { return traders; }
    Quests quests() { return quests; }
    Regions regions() { return regions; }
    Dungeons dungeons() { return dungeons; }
    Bosses bosses() { return bosses; }

    void saveData() {
        try {
            data.save(new File(getDataFolder(), "explore.yml"));
        } catch (IOException e) {
            getLogger().severe("Could not save explore.yml: " + e.getMessage());
        }
    }
}
