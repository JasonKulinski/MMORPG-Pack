package dev.jason.rpgtowns;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Towns, their Alderman, residents, vote threshold and plots. Saved in towns.yml. */
public class TownManager {
    public record PlotSize(String id, String label, int width, int height, boolean house) {
        public String describe() {
            return label + " (" + width + "×" + width + "×" + height + ")";
        }
    }

    public static final class Plot {
        public final String id, world, size;
        public final int x, y, z; // the corner with the lowest x, y, z
        public UUID owner;        // house plots: the resident who claimed it
        public Integer design;    // what's built on it

        Plot(String id, String world, int x, int y, int z, String size) {
            this.id = id;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.size = size;
        }

        public Location corner() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z);
        }
    }

    public static final class Town {
        public final String id;
        public String name;
        public UUID alderman;
        public int threshold = 3;
        public final Set<UUID> residents = new LinkedHashSet<>();
        public final Map<String, Plot> plots = new LinkedHashMap<>();

        Town(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public boolean isAlderman(Player p) {
            return p.getUniqueId().equals(alderman);
        }
    }

    private final RPGTowns plugin;
    private final File file;
    private final Map<String, Town> towns = new LinkedHashMap<>();
    private final Map<String, PlotSize> sizes = new LinkedHashMap<>();

    public TownManager(RPGTowns plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "towns.yml");
    }

    public void load() {
        sizes.clear();
        ConfigurationSection sz = plugin.getConfig().getConfigurationSection("sizes");
        if (sz != null) {
            for (String id : sz.getKeys(false)) {
                ConfigurationSection s = sz.getConfigurationSection(id);
                if (s != null) sizes.put(id, new PlotSize(id, s.getString("label", id), s.getInt("width"),
                        s.getInt("height"), s.getBoolean("house")));
            }
        }
        towns.clear();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String id : yml.getKeys(false)) {
            ConfigurationSection s = yml.getConfigurationSection(id);
            if (s == null) continue;
            Town t = new Town(id, s.getString("name", id));
            String al = s.getString("alderman");
            if (al != null) t.alderman = UUID.fromString(al);
            t.threshold = s.getInt("threshold", 3);
            for (String r : s.getStringList("residents")) t.residents.add(UUID.fromString(r));
            ConfigurationSection ps = s.getConfigurationSection("plots");
            if (ps != null) {
                for (String pid : ps.getKeys(false)) {
                    ConfigurationSection p = ps.getConfigurationSection(pid);
                    if (p == null) continue;
                    Plot plot = new Plot(pid, p.getString("world"), p.getInt("x"), p.getInt("y"), p.getInt("z"), p.getString("size"));
                    String owner = p.getString("owner");
                    if (owner != null) plot.owner = UUID.fromString(owner);
                    if (p.contains("design")) plot.design = p.getInt("design");
                    t.plots.put(pid, plot);
                }
            }
            towns.put(id, t);
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Town t : towns.values()) {
            String b = t.id + ".";
            yml.set(b + "name", t.name);
            yml.set(b + "alderman", t.alderman == null ? null : t.alderman.toString());
            yml.set(b + "threshold", t.threshold);
            yml.set(b + "residents", t.residents.stream().map(UUID::toString).toList());
            for (Plot p : t.plots.values()) {
                String pb = b + "plots." + p.id + ".";
                yml.set(pb + "world", p.world);
                yml.set(pb + "x", p.x);
                yml.set(pb + "y", p.y);
                yml.set(pb + "z", p.z);
                yml.set(pb + "size", p.size);
                yml.set(pb + "owner", p.owner == null ? null : p.owner.toString());
                yml.set(pb + "design", p.design);
            }
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save towns.yml: " + e.getMessage());
        }
    }

    public Town create(String id, String name) {
        Town t = new Town(id.toLowerCase(), name);
        towns.put(t.id, t);
        save();
        return t;
    }

    public Town get(String id) {
        return id == null ? null : towns.get(id.toLowerCase());
    }

    public Collection<Town> all() {
        return towns.values();
    }

    /** The town a player lives in, or null. A player lives in at most one town. */
    public Town townOf(UUID player) {
        for (Town t : towns.values()) if (t.residents.contains(player)) return t;
        return null;
    }

    public PlotSize size(String id) {
        return sizes.get(id);
    }

    public Collection<PlotSize> sizes() {
        return sizes.values();
    }

    public Plot housePlotOf(Town t, UUID player) {
        for (Plot p : t.plots.values()) if (player.equals(p.owner)) return p;
        return null;
    }
}
