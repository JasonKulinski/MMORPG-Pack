package dev.jason.rpgcore;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads and saves classes.yml. */
public class ClassManager {
    private final RPGCore plugin;
    private final File file;
    private final Map<String, RpgClass> classes = new LinkedHashMap<>();

    public ClassManager(RPGCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "classes.yml");
    }

    public void load() {
        if (!file.exists()) plugin.saveResource("classes.yml", false);
        classes.clear();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String id : yml.getKeys(false)) {
            ConfigurationSection sec = yml.getConfigurationSection(id);
            if (sec == null) continue;
            RpgClass c = new RpgClass(id, sec.getString("name", id), sec.getString("color", "white"));
            c.lore = sec.getString("lore");
            readStats(sec.getConfigurationSection("base"), c.base);
            readStats(sec.getConfigurationSection("per-level"), c.perLevel);
            classes.put(id.toLowerCase(), c);
        }
        plugin.getLogger().info("Loaded " + classes.size() + " classes.");
    }

    private void readStats(ConfigurationSection sec, Map<Stat, Double> into) {
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            Stat stat = Stat.fromKey(key);
            if (stat == null) plugin.getLogger().warning("Unknown stat '" + key + "' in classes.yml");
            else into.put(stat, sec.getDouble(key));
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (RpgClass c : classes.values()) {
            yml.set(c.id + ".name", c.name);
            yml.set(c.id + ".color", c.color);
            yml.set(c.id + ".lore", c.lore);
            for (Stat s : Stat.values()) {
                yml.set(c.id + ".base." + s.key, c.base.getOrDefault(s, 0.0));
                yml.set(c.id + ".per-level." + s.key, c.perLevel.getOrDefault(s, 0.0));
            }
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save classes.yml: " + e.getMessage());
        }
    }

    public RpgClass get(String id) {
        return id == null ? null : classes.get(id.toLowerCase());
    }

    public Collection<RpgClass> all() {
        return classes.values();
    }

    public RpgClass create(String id, String name) {
        RpgClass c = new RpgClass(id.toLowerCase(), name, "white");
        c.base.put(Stat.HEALTH, 500.0);
        c.base.put(Stat.CRIT_DAMAGE, 50.0);
        classes.put(c.id, c);
        save();
        return c;
    }

    public boolean delete(String id) {
        boolean removed = classes.remove(id.toLowerCase()) != null;
        if (removed) save();
        return removed;
    }
}
