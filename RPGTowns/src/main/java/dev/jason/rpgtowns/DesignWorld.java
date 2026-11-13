package dev.jason.rpgtowns;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The empty world where designs are built and voted on. Players are sent to the design's plot-sized slot
 * with a border around it; their real inventory, location and game mode are saved to a file and given
 * back when they leave (also if they log out or get teleported away), so creative items never get out.
 */
public class DesignWorld implements Listener {
    public enum Mode { BUILD, VOTE, VIEW }

    public record Session(int design, Mode mode) {}

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Material[] VOTE_BLOCKS = {Material.RED_CONCRETE, Material.ORANGE_CONCRETE,
            Material.YELLOW_CONCRETE, Material.LIME_CONCRETE, Material.GREEN_CONCRETE};
    private static final String[] VOTE_COLORS = {"<red>", "<gold>", "<yellow>", "<green>", "<dark_green>"};

    private final RPGTowns plugin;
    private final NamespacedKey voteKey, menuKey, leaveKey;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private World world;

    public DesignWorld(RPGTowns plugin) {
        this.plugin = plugin;
        voteKey = new NamespacedKey(plugin, "vote");
        menuKey = new NamespacedKey(plugin, "design_menu");
        leaveKey = new NamespacedKey(plugin, "leave");
    }

    /** Empty world: no terrain, no mobs, always noon. */
    public static final class VoidGenerator extends ChunkGenerator {
        @Override public boolean shouldGenerateNoise() { return false; }
        @Override public boolean shouldGenerateSurface() { return false; }
        @Override public boolean shouldGenerateCaves() { return false; }
        @Override public boolean shouldGenerateDecorations() { return false; }
        @Override public boolean shouldGenerateMobs() { return false; }
        @Override public boolean shouldGenerateStructures() { return false; }
        @Override public Location getFixedSpawnLocation(World world, Random random) {
            return new Location(world, 0, DesignManager.BASE_Y, 0);
        }
    }

    public void load() {
        world = new WorldCreator(plugin.getConfig().getString("design-world", "rpgtowns_design"))
                .generator(new VoidGenerator()).generateStructures(false).createWorld();
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setTime(6000);
        world.setStorm(false);
    }

    public World world() {
        return world;
    }

    public Session session(Player p) {
        return sessions.get(p.getUniqueId());
    }

    // ------------------------------------------------------------------ entering and leaving

    public void enter(Player p, DesignManager.Design d, Mode mode) {
        if (!sessions.containsKey(p.getUniqueId())) saveState(p); // switching designs keeps the first saved state
        sessions.put(p.getUniqueId(), new Session(d.id, mode));
        p.getInventory().clear();
        p.teleport(plugin.designs().arrival(d));
        int w = plugin.designs().size(d).width();
        WorldBorder border = Bukkit.createWorldBorder();
        Location c = plugin.designs().corner(d);
        border.setCenter(c.getX() + w / 2.0, c.getZ() + w / 2.0);
        border.setSize(w);
        border.setWarningDistance(0);
        p.setWorldBorder(border);
        TownManager.PlotSize size = plugin.designs().size(d);
        switch (mode) {
            case BUILD -> {
                p.setGameMode(GameMode.CREATIVE);
                p.getInventory().setItem(8, tagged(Material.NETHER_STAR, "<gold>Design menu", menuKey, 1));
                p.showTitle(Title.title(MM.deserialize("<gold>" + size.describe()),
                        MM.deserialize("<gray>Build inside the border, up to " + size.height() + " blocks tall")));
                p.sendMessage(MM.deserialize("<gray>Use the <gold>Design menu</gold> (nether star) to submit your design for a vote or to leave. "
                        + "Your build stays here until you come back."));
            }
            case VOTE -> {
                p.setGameMode(GameMode.ADVENTURE);
                p.setAllowFlight(true);
                for (int i = 0; i < 5; i++) {
                    p.getInventory().setItem(i, tagged(VOTE_BLOCKS[i], VOTE_COLORS[i] + "Vote " + (i + 1) + "/5", voteKey, i + 1));
                }
                p.getInventory().setItem(8, tagged(Material.BARRIER, "<red>Leave", leaveKey, 1));
                p.showTitle(Title.title(MM.deserialize("<gold>Design #" + d.id + " by " + d.authorName),
                        MM.deserialize("<gray>Look around, then right-click a concrete block to vote 1-5")));
            }
            case VIEW -> {
                p.setGameMode(GameMode.ADVENTURE);
                p.setAllowFlight(true);
                p.getInventory().setItem(8, tagged(Material.BARRIER, "<red>Leave", leaveKey, 1));
            }
        }
    }

    /** Sends the player back where they came from with their own inventory. */
    public void leave(Player p) {
        if (sessions.containsKey(p.getUniqueId())) restore(p, true);
    }

    private File stateFile(UUID id) {
        return new File(plugin.getDataFolder(), "players/" + id + ".yml");
    }

    private void saveState(Player p) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("location", p.getLocation());
        y.set("gamemode", p.getGameMode().name());
        y.set("inventory", Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(p.getInventory().getContents())));
        y.set("allow-flight", p.getAllowFlight());
        try {
            File f = stateFile(p.getUniqueId());
            f.getParentFile().mkdirs();
            y.save(f);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save " + p.getName() + "'s inventory: " + e.getMessage());
        }
    }

    /** Gives back inventory and game mode; with teleport, also sends them back. Removes the session. */
    private void restore(Player p, boolean teleport) {
        sessions.remove(p.getUniqueId());
        p.setWorldBorder(null);
        File f = stateFile(p.getUniqueId());
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        p.getInventory().setContents(ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(y.getString("inventory", ""))));
        p.setGameMode(GameMode.valueOf(y.getString("gamemode", "SURVIVAL")));
        p.setAllowFlight(y.getBoolean("allow-flight") || p.getGameMode() == GameMode.CREATIVE);
        if (teleport) {
            Location back = y.getLocation("location");
            if (back != null && back.getWorld() != null && !back.getWorld().equals(world)) p.teleport(back);
            else p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
        }
        f.delete();
    }

    /** Logged out in the design world: put them back when they join. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (stateFile(p.getUniqueId()).exists()) {
            Bukkit.getScheduler().runTask(plugin, () -> restore(p, true));
        }
    }

    /** Left the design world some other way (/tp, /spawn...): give their inventory back right away. */
    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (e.getFrom().equals(world) && sessions.containsKey(p.getUniqueId())) restore(p, false);
    }

    // ------------------------------------------------------------------ items: design menu, votes, leave

    private ItemStack tagged(Material m, String name, NamespacedKey key, int value) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.itemName(MM.deserialize(name).decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, value);
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() == Action.PHYSICAL || e.getItem() == null) return;
        Player p = e.getPlayer();
        Session s = sessions.get(p.getUniqueId());
        if (s == null || !e.getItem().hasItemMeta()) return;
        var pdc = e.getItem().getItemMeta().getPersistentDataContainer();
        if (pdc.has(leaveKey)) {
            e.setCancelled(true);
            leave(p);
        } else if (pdc.has(menuKey)) {
            e.setCancelled(true);
            plugin.menus().designMenu(p, plugin.designs().get(s.design()));
        } else if (pdc.has(voteKey) && s.mode() == Mode.VOTE) {
            e.setCancelled(true);
            plugin.menus().castVote(p, plugin.designs().get(s.design()), pdc.get(voteKey, PersistentDataType.INTEGER));
        }
    }

    // ------------------------------------------------------------------ keeping each design in its space

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e.getBlock().getWorld().equals(world) && !canEdit(e.getPlayer(), e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (e.getBlock().getWorld().equals(world) && !canEdit(e.getPlayer(), e.getBlock())) e.setCancelled(true);
    }

    private boolean canEdit(Player p, org.bukkit.block.Block b) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null || s.mode() != Mode.BUILD) return false;
        DesignManager.Design d = plugin.designs().get(s.design());
        return d != null && d.status == DesignManager.Status.DRAFT && plugin.designs().inside(d, b);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (e.getBlock().getWorld().equals(world) && plugin.designs().at(e.getToBlock()) == null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (e.getBlock().getWorld().equals(world)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (e.getLocation().getWorld().equals(world)) e.blockList().clear();
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (e.getBlock().getWorld().equals(world)) e.blockList().clear();
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (e.getPlayer().getWorld().equals(world)) e.setCancelled(true);
    }

    /** No damage in the design world; falling into the void puts you back on the floor. */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !p.getWorld().equals(world)) return;
        e.setCancelled(true);
        Session s = sessions.get(p.getUniqueId());
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID && s != null) {
            p.teleport(plugin.designs().arrival(plugin.designs().get(s.design())));
        }
    }
}
