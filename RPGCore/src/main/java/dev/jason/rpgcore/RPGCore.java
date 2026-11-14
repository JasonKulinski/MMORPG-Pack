package dev.jason.rpgcore;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class RPGCore extends JavaPlugin {
    private ClassManager classes;
    private PlayerManager players;
    private MobManager mobs;
    private CheckpointManager checkpoints;
    private StatsMenu statsMenu;
    private PackHost packHost;
    private SpawnZoneManager zones;
    private CustomBlockManager blocks;
    private CustomItemManager items;
    private Wallet wallet;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        classes = new ClassManager(this);
        players = new PlayerManager(this);
        mobs = new MobManager(this);
        checkpoints = new CheckpointManager(this);
        statsMenu = new StatsMenu(this);
        packHost = new PackHost(this);
        zones = new SpawnZoneManager(this);
        blocks = new CustomBlockManager(this);
        items = new CustomItemManager(this);
        wallet = new Wallet(this);
        wallet.registerItems();
        reloadAll();

        getServer().getPluginManager().registerEvents(new CombatListener(this), this);
        getServer().getPluginManager().registerEvents(checkpoints, this);
        getServer().getPluginManager().registerEvents(packHost, this);
        getServer().getPluginManager().registerEvents(blocks, this);
        getServer().getPluginManager().registerEvents(items, this);

        RpgCommand cmd = new RpgCommand(this);
        PluginCommand rpg = getCommand("rpg");
        if (rpg != null) {
            rpg.setExecutor(cmd);
            rpg.setTabCompleter(cmd);
        }
        PluginCommand walletCmd = getCommand("wallet");
        if (walletCmd != null) {
            walletCmd.setExecutor(wallet);
            walletCmd.setTabCompleter(wallet);
        }
        PluginCommand stats = getCommand("stats");
        if (stats != null) stats.setExecutor(cmd);

        // Action bar with class / level / XP, and the checkpoint particle rings.
        Bukkit.getScheduler().runTaskTimer(this, () -> Bukkit.getOnlinePlayers().forEach(p -> players.showBar(p, null)), 40, 40);
        Bukkit.getScheduler().runTaskTimer(this, () -> Bukkit.getOnlinePlayers().forEach(players::regenMana), 20, 20);
        Bukkit.getScheduler().runTaskTimer(this, checkpoints::drawMarkers, 20, 10);
        Bukkit.getScheduler().runTaskTimer(this, zones::tick, 40, 20);
        Bukkit.getScheduler().runTaskTimer(this, zones::drawShown, 20, 10);

        Bukkit.getOnlinePlayers().forEach(players::applyStats);
    }

    public void reloadAll() {
        reloadConfig();
        classes.load();
        mobs.load();
        checkpoints.load();
        zones.load();
        blocks.load();
        items.load();
        packHost.load();
        if (players != null) Bukkit.getOnlinePlayers().forEach(players::applyStats);
    }

    public ClassManager classes() { return classes; }
    public PlayerManager players() { return players; }
    public MobManager mobs() { return mobs; }
    public CheckpointManager checkpoints() { return checkpoints; }
    public StatsMenu statsMenu() { return statsMenu; }
    public PackHost packHost() { return packHost; }
    public SpawnZoneManager zones() { return zones; }
    public CustomBlockManager blocks() { return blocks; }
    public CustomItemManager items() { return items; }
    public Wallet wallet() { return wallet; }

    @Override
    public void onDisable() {
        if (packHost != null) packHost.stop();
    }
}
