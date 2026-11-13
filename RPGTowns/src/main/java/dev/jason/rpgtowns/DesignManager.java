package dev.jason.rpgtowns;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Jukebox;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.BlockInventoryHolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Player designs. Each design has its own plot-sized slot in the design world (the build lives there),
 * and once submitted it is saved as plugins/RPGTowns/schematics/<id>.schem for pasting into towns.
 */
public class DesignManager {
    public enum Status { DRAFT, SUBMITTED }

    public static final class Design {
        public final int id;
        public final String town, size, authorName;
        public final UUID author;
        public Status status = Status.DRAFT;
        public final Map<UUID, Integer> votes = new LinkedHashMap<>();

        Design(int id, String town, UUID author, String authorName, String size) {
            this.id = id;
            this.town = town;
            this.author = author;
            this.authorName = authorName;
            this.size = size;
        }

        public double average() {
            return votes.values().stream().mapToInt(Integer::intValue).average().orElse(0);
        }
    }

    /** Distance between design slots in the design world (bigger than the largest plot). */
    public static final int SPACING = 128;
    /** Builds start at this height; the floor is one block below. */
    public static final int BASE_Y = 64;

    private final RPGTowns plugin;
    private final File file, schemDir;
    private final Map<Integer, Design> designs = new LinkedHashMap<>();
    private int nextId = 1;

    public DesignManager(RPGTowns plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "designs.yml");
        this.schemDir = new File(plugin.getDataFolder(), "schematics");
    }

    public void load() {
        designs.clear();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        nextId = yml.getInt("next-id", 1);
        ConfigurationSection all = yml.getConfigurationSection("designs");
        if (all == null) return;
        for (String key : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(key);
            if (s == null) continue;
            Design d = new Design(Integer.parseInt(key), s.getString("town"), UUID.fromString(s.getString("author")),
                    s.getString("author-name", "?"), s.getString("size"));
            d.status = Status.valueOf(s.getString("status", "DRAFT"));
            ConfigurationSection v = s.getConfigurationSection("votes");
            if (v != null) for (String u : v.getKeys(false)) d.votes.put(UUID.fromString(u), v.getInt(u));
            designs.put(d.id, d);
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("next-id", nextId);
        for (Design d : designs.values()) {
            String b = "designs." + d.id + ".";
            yml.set(b + "town", d.town);
            yml.set(b + "author", d.author.toString());
            yml.set(b + "author-name", d.authorName);
            yml.set(b + "size", d.size);
            yml.set(b + "status", d.status.name());
            d.votes.forEach((u, v) -> yml.set(b + "votes." + u, v));
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save designs.yml: " + e.getMessage());
        }
    }

    public Design create(TownManager.Town town, Player author, TownManager.PlotSize size) {
        Design d = new Design(nextId++, town.id, author.getUniqueId(), author.getName(), size.id());
        designs.put(d.id, d);
        save();
        // Grass floor exactly the size of the plot, one block below where building starts.
        World w = plugin.designWorld().world();
        Location c = corner(d);
        for (int x = 0; x < size.width(); x++) {
            for (int z = 0; z < size.width(); z++) {
                w.getBlockAt(c.getBlockX() + x, BASE_Y - 1, c.getBlockZ() + z).setType(Material.GRASS_BLOCK, false);
            }
        }
        return d;
    }

    public Design get(int id) {
        return designs.get(id);
    }

    public Collection<Design> all() {
        return designs.values();
    }

    public List<Design> inTown(String town) {
        List<Design> out = new ArrayList<>();
        for (Design d : designs.values()) if (d.town.equals(town)) out.add(d);
        return out;
    }

    public TownManager.PlotSize size(Design d) {
        return plugin.towns().size(d.size);
    }

    /** Submitted, with enough votes, and an average at or above the town's threshold. */
    public boolean approved(Design d) {
        TownManager.Town t = plugin.towns().get(d.town);
        int minVotes = plugin.getConfig().getInt("voting.min-votes", 1);
        return d.status == Status.SUBMITTED && t != null && d.votes.size() >= Math.max(1, minVotes)
                && d.average() >= t.threshold;
    }

    // ------------------------------------------------------------------ the design's space

    /** Lowest corner of the design's build space. */
    public Location corner(Design d) {
        return new Location(plugin.designWorld().world(), d.id * SPACING, BASE_Y, 0);
    }

    /** Where players stand when they arrive: the middle of the floor. */
    public Location arrival(Design d) {
        int w = size(d).width();
        Location c = corner(d).add(w / 2.0, 0, w / 2.0);
        c.setYaw(-135); // looking across the plot
        return c;
    }

    public boolean inside(Design d, Block b) {
        TownManager.PlotSize s = size(d);
        Location c = corner(d);
        return b.getWorld().equals(c.getWorld())
                && b.getX() >= c.getBlockX() && b.getX() < c.getBlockX() + s.width()
                && b.getZ() >= c.getBlockZ() && b.getZ() < c.getBlockZ() + s.width()
                && b.getY() >= BASE_Y && b.getY() < BASE_Y + s.height();
    }

    /** The design whose slot this block is in (by position), or null. */
    public Design at(Block b) {
        Design d = designs.get(Math.floorDiv(b.getX(), SPACING));
        return d != null && inside(d, b) ? d : null;
    }

    // ------------------------------------------------------------------ saving and pasting

    /** Saves the build as a schematic. Containers are emptied first so no creative items get into towns. */
    public void saveSchematic(Design d) throws IOException, WorldEditException {
        TownManager.PlotSize s = size(d);
        Location c = corner(d);
        World w = c.getWorld();
        for (int x = 0; x < s.width(); x++) {
            for (int y = 0; y < s.height(); y++) {
                for (int z = 0; z < s.width(); z++) {
                    BlockState st = w.getBlockAt(c.getBlockX() + x, BASE_Y + y, c.getBlockZ() + z).getState(false);
                    if (st instanceof BlockInventoryHolder h) h.getInventory().clear();
                    if (st instanceof Jukebox j) j.setRecord(null);
                }
            }
        }
        CuboidRegion region = region(w, c.getBlockX(), BASE_Y, c.getBlockZ(), s);
        BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
        clipboard.setOrigin(region.getMinimumPoint());
        try (EditSession es = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(w))) {
            ForwardExtentCopy copy = new ForwardExtentCopy(es, region, clipboard, region.getMinimumPoint());
            copy.setCopyingEntities(false);
            Operations.complete(copy);
        }
        schemDir.mkdirs();
        try (ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC
                .getWriter(new FileOutputStream(new File(schemDir, d.id + ".schem")))) {
            writer.write(clipboard);
        }
    }

    /** Clears the plot and pastes the design into it. */
    public void paste(Design d, TownManager.Plot plot) throws IOException, WorldEditException {
        File f = new File(schemDir, d.id + ".schem");
        Clipboard clipboard;
        try (ClipboardReader reader = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getReader(new FileInputStream(f))) {
            clipboard = reader.read();
        }
        TownManager.PlotSize s = plugin.towns().size(plot.size);
        World w = plot.corner().getWorld();
        try (EditSession es = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(w))) {
            es.setBlocks(region(w, plot.x, plot.y, plot.z, s), BlockTypes.AIR.getDefaultState());
            Operations.complete(new ClipboardHolder(clipboard).createPaste(es)
                    .to(BlockVector3.at(plot.x, plot.y, plot.z)).ignoreAirBlocks(false).build());
        }
    }

    private static CuboidRegion region(World w, int x, int y, int z, TownManager.PlotSize s) {
        return new CuboidRegion(BukkitAdapter.adapt(w), BlockVector3.at(x, y, z),
                BlockVector3.at(x + s.width() - 1, y + s.height() - 1, z + s.width() - 1));
    }
}
