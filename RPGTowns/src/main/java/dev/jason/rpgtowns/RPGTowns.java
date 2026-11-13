package dev.jason.rpgtowns;

import dev.jason.rpgtowns.TownManager.Plot;
import dev.jason.rpgtowns.TownManager.PlotSize;
import dev.jason.rpgtowns.TownManager.Town;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Towns built from player designs. A town's Planner NPC lets residents design buildings in an empty
 * plot-sized space, vote on each other's designs (1-5), and build approved designs onto the town's plots.
 */
public class RPGTowns extends JavaPlugin {
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private TownManager towns;
    private DesignManager designs;
    private DesignWorld designWorld;
    private TownMenu menus;
    /** Towns whose plot outlines are being drawn, until this time. */
    private final Map<String, Long> shownUntil = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        towns = new TownManager(this);
        designs = new DesignManager(this);
        designWorld = new DesignWorld(this);
        menus = new TownMenu(this);
        towns.load();
        designs.load();
        designWorld.load();
        getServer().getPluginManager().registerEvents(designWorld, this);
        getServer().getPluginManager().registerEvents(menus, this);
        Bukkit.getScheduler().runTaskTimer(this, this::drawPlots, 20, 10);
    }

    @Override
    public void onDisable() {
        if (designWorld != null) Bukkit.getOnlinePlayers().forEach(designWorld::leave);
    }

    public TownManager towns() { return towns; }
    public DesignManager designs() { return designs; }
    public DesignWorld designWorld() { return designWorld; }
    public TownMenu menus() { return menus; }

    // ------------------------------------------------------------------ commands

    private void msg(CommandSender s, String m) {
        s.sendMessage(MM.deserialize(m));
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "help" : a[0].toLowerCase();
        if (sub.equals("leave")) {
            if (s instanceof Player p) designWorld.leave(p);
            return true;
        }
        if (!s.hasPermission("rpgtowns.admin")) {
            msg(s, "<gray>Talk to a town's Planner to join, design buildings and vote. <white>/towns leave</white> leaves the design world.");
            return true;
        }
        Town t = a.length > 1 ? towns.get(a[1]) : null;
        switch (sub) {
            case "create" -> {
                if (a.length < 3) { msg(s, "<red>/towns create <id> <name...>"); return true; }
                if (towns.get(a[1]) != null) { msg(s, "<red>That town already exists."); return true; }
                Town nt = towns.create(a[1], String.join(" ", Arrays.copyOfRange(a, 2, a.length)));
                msg(s, "<green>Created " + nt.name + ". Next: /towns npc " + nt.id + ", /towns alderman " + nt.id
                        + " <player>, /towns plot create " + nt.id + " <plot> <size>");
            }
            case "alderman" -> {
                if (t == null || a.length < 3) { msg(s, "<red>/towns alderman <town> <player>"); return true; }
                OfflinePlayer op = Bukkit.getOfflinePlayer(a[2]);
                Town old = towns.townOf(op.getUniqueId());
                if (old != null && old != t) old.residents.remove(op.getUniqueId());
                t.alderman = op.getUniqueId();
                t.residents.add(op.getUniqueId());
                towns.save();
                msg(s, "<green>" + a[2] + " is now the Alderman of " + t.name + ".");
            }
            case "npc" -> {
                if (t == null || !(s instanceof Player p)) { msg(s, "<red>/towns npc <town> (stand where it should be)"); return true; }
                menus.spawnPlanner(t, p.getLocation());
                msg(s, "<green>Placed the " + t.name + " Planner. Remove it with /kill @e[type=mannequin,distance=..2]");
            }
            case "plot" -> plotCmd(s, a);
            case "list" -> {
                if (towns.all().isEmpty()) msg(s, "<gray>No towns yet. /towns create <id> <name>");
                for (Town town : towns.all()) {
                    msg(s, "<gold>" + town.id + "<gray> - " + town.name + ", " + town.residents.size() + " residents, "
                            + town.plots.size() + " plots, threshold " + town.threshold + "/5");
                }
            }
            case "sizes" -> towns.sizes().forEach(sz -> msg(s, "<gold>" + sz.id() + "<gray> - " + sz.describe()
                    + (sz.house() ? ", house" : ", town building")));
            case "reload" -> {
                reloadConfig();
                towns.load();
                designs.load();
                msg(s, "<green>RPGTowns reloaded.");
            }
            default -> {
                msg(s, "<gold>RPGTowns");
                msg(s, "<yellow>/towns create <id> <name...> <gray>- make a town");
                msg(s, "<yellow>/towns alderman <town> <player> <gray>- set the Alderman");
                msg(s, "<yellow>/towns npc <town> <gray>- place the town's Planner where you stand");
                msg(s, "<yellow>/towns plot create <town> <plot> <size> <gray>- plot starting at your feet, going +X/+Z");
                msg(s, "<yellow>/towns plot remove <town> <plot> | show <town>");
                msg(s, "<yellow>/towns list | sizes | reload | leave");
            }
        }
        return true;
    }

    private void plotCmd(CommandSender s, String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase() : "";
        Town t = a.length > 2 ? towns.get(a[2]) : null;
        if (t == null) { msg(s, "<red>/towns plot create|remove|show <town> ..."); return; }
        switch (sub) {
            case "create" -> {
                PlotSize size = a.length > 4 ? towns.size(a[4]) : null;
                if (!(s instanceof Player p) || size == null) {
                    msg(s, "<red>/towns plot create <town> <plot> <size>   (sizes: /towns sizes)");
                    return;
                }
                if (t.plots.containsKey(a[3])) { msg(s, "<red>That plot already exists."); return; }
                Location l = p.getLocation();
                t.plots.put(a[3], new Plot(a[3], l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), size.id()));
                towns.save();
                shownUntil.put(t.id, System.currentTimeMillis() + 30_000);
                msg(s, "<green>Plot " + a[3] + " (" + size.describe() + ") created from your feet toward +X/+Z. Outline shown for 30s.");
            }
            case "remove" -> {
                if (a.length < 4 || t.plots.remove(a[3]) == null) { msg(s, "<red>Unknown plot."); return; }
                towns.save();
                msg(s, "<green>Removed plot " + a[3] + " (the blocks stay).");
            }
            case "show" -> {
                shownUntil.put(t.id, System.currentTimeMillis() + 30_000);
                msg(s, "<green>Showing " + t.name + "'s plots for 30 seconds.");
            }
            default -> msg(s, "<red>/towns plot create|remove|show <town> ...");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission("rpgtowns.admin")) return a.length == 1 ? List.of("leave") : out;
        List<String> townIds = towns.all().stream().map(t -> t.id).toList();
        if (a.length == 1) out.addAll(List.of("create", "alderman", "npc", "plot", "list", "sizes", "reload", "leave"));
        else if (a.length == 2 && List.of("alderman", "npc").contains(a[0].toLowerCase())) out.addAll(townIds);
        else if (a.length == 2 && a[0].equalsIgnoreCase("plot")) out.addAll(List.of("create", "remove", "show"));
        else if (a.length == 3 && a[0].equalsIgnoreCase("plot")) out.addAll(townIds);
        else if (a.length == 3 && a[0].equalsIgnoreCase("alderman")) return null; // player names
        else if (a.length == 4 && a[0].equalsIgnoreCase("plot") && a[1].equalsIgnoreCase("remove")) {
            Town t = towns.get(a[2]);
            if (t != null) out.addAll(t.plots.keySet());
        } else if (a.length == 5 && a[0].equalsIgnoreCase("plot") && a[1].equalsIgnoreCase("create")) {
            towns.sizes().forEach(sz -> out.add(sz.id()));
        }
        String last = a[a.length - 1].toLowerCase();
        return out.stream().filter(o -> o.toLowerCase().startsWith(last)).toList();
    }

    // ------------------------------------------------------------------ plot outlines

    private void drawPlots() {
        long now = System.currentTimeMillis();
        shownUntil.values().removeIf(t -> t < now);
        Particle.DustOptions house = new Particle.DustOptions(Color.fromRGB(0x40C040), 1.2f);
        Particle.DustOptions town = new Particle.DustOptions(Color.fromRGB(0xE0A020), 1.2f);
        for (String id : shownUntil.keySet()) {
            Town t = towns.get(id);
            if (t == null) continue;
            for (Plot plot : t.plots.values()) {
                PlotSize s = towns.size(plot.size);
                World w = Bukkit.getWorld(plot.world);
                if (s == null || w == null) continue;
                Particle.DustOptions dust = s.house() ? house : town;
                int n = s.width();
                for (int i = 0; i <= n; i++) {
                    for (double[] pt : new double[][]{{i, 0}, {i, n}, {0, i}, {n, i}}) {
                        w.spawnParticle(Particle.DUST, plot.x + pt[0], plot.y + 0.2, plot.z + pt[1], 1, dust);
                    }
                }
                for (int y = 0; y <= s.height(); y += 2) {
                    for (double[] c : new double[][]{{0, 0}, {n, 0}, {0, n}, {n, n}}) {
                        w.spawnParticle(Particle.DUST, plot.x + c[0], plot.y + y, plot.z + c[1], 1, dust);
                    }
                }
            }
        }
    }
}
