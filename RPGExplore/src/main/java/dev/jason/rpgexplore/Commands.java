package dev.jason.rpgexplore;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** /explore: admin setup for areas, territories, dungeons and bosses, plus a few test helpers. */
class Commands implements CommandExecutor, TabCompleter {
    private final RPGExplore plugin;

    Commands(RPGExplore plugin) {
        this.plugin = plugin;
    }

    private void msg(CommandSender s, String m) {
        s.sendMessage(RPGExplore.MM.deserialize(m));
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "help" : a[0].toLowerCase();
        if (sub.equals("level") && s instanceof Player p) {
            Wilds.Band b = plugin.wilds().band(p.getLocation());
            msg(s, b.safe() ? "<green>Safe: near " + (b.town() == null ? "spawn" : b.town().name)
                    : "<yellow>Danger: level " + b.minLevel() + "-" + b.maxLevel() + " <gray>(" + (int) b.distance()
                    + " blocks from " + (b.town() == null ? "spawn" : b.town().name) + ")");
            return true;
        }
        if (!s.hasPermission("rpgexplore.admin")) {
            msg(s, "<gray>/explore level - how dangerous it is where you stand");
            return true;
        }
        if (!(s instanceof Player p)) {
            msg(s, "<red>Run /explore in game (it uses where you stand).");
            return true;
        }
        Location here = p.getLocation();
        switch (sub) {
            case "area" -> area(p, a, here);
            case "territory" -> territory(p, a, here);
            case "dungeon" -> dungeon(p, a, here);
            case "boss" -> boss(p, a, here);
            case "trader" -> {
                plugin.traders().spawn(here);
                msg(s, "<green>Spawned a wandering trader.");
            }
            case "quest" -> msg(s, plugin.quests().start(p) ? "<green>A traveler appeared with a quest for you."
                    : "<red>No room nearby (or you already have a quest).");
            case "wild" -> {
                Wilds.Band b = plugin.wilds().band(here);
                var e = plugin.wilds().spawnWild(here.clone().add(here.getDirection().setY(0).normalize().multiply(5)), b,
                        plugin.regions().themeAt(here));
                msg(s, e == null ? "<red>No enemy fits here." : "<green>Spawned a wild enemy for level " + b.minLevel() + "-" + b.maxLevel() + ".");
            }
            case "list" -> {
                plugin.regions().areas.values().forEach(x -> msg(s, "<light_purple>area " + x.id + "<gray> (" + x.type + ") " + x.name
                        + " r" + (int) x.radius + " at " + (int) x.x + ", " + (int) x.z));
                plugin.regions().territories.values().forEach(t -> msg(s, "<gold>territory " + t.id + "<gray> " + t.name + " - "
                        + t.perHour + " " + t.resource + "/h, owner " + t.owner));
                plugin.dungeons().dungeons.values().forEach(d -> msg(s, "<dark_red>dungeon " + d.id + "<gray> level " + d.level
                        + ", " + d.waves.size() + " waves, " + d.state));
                plugin.bosses().arenas.values().forEach(b -> msg(s, "<red>boss " + b.id + "<gray> kills " + b.kills
                        + (b.alive != null ? ", alive" : "")));
            }
            case "reload" -> {
                plugin.reload();
                msg(s, "<green>RPGExplore config reloaded.");
            }
            default -> help(s);
        }
        return true;
    }

    private void help(CommandSender s) {
        msg(s, "<gold>RPGExplore <gray>(everything uses where you stand)");
        msg(s, "<yellow>/explore area create <id> pixie <radius> <name...> <gray>| remove <id> | scatter <id> <count>");
        msg(s, "<yellow>/explore territory create <id> <copper|ambrosia|wood|stone> <per-hour> <name...> <gray>| remove <id>");
        msg(s, "<yellow>/explore dungeon create <id> <radius> <level> <gray>| remove <id>  (waves in explore.yml)");
        msg(s, "<yellow>/explore boss set <id> <gray>| spawn <id> | remove <id>  (bosses in config.yml)");
        msg(s, "<yellow>/explore trader | quest | wild | level | list | reload <gray>- test helpers");
    }

    private void area(Player p, String[] a, Location here) {
        String sub = a.length > 1 ? a[1].toLowerCase() : "";
        Regions r = plugin.regions();
        switch (sub) {
            case "create" -> {
                if (a.length < 6) { msg(p, "<red>/explore area create <id> pixie <radius> <name...>"); return; }
                String name = String.join(" ", Arrays.copyOfRange(a, 5, a.length));
                r.areas.put(a[2], new Regions.Area(a[2], a[3].toLowerCase(), here.getWorld().getName(), here.getX(), here.getZ(),
                        Double.parseDouble(a[4]), a[3].equalsIgnoreCase("pixie") ? "<light_purple>" + name : name));
                r.save();
                msg(p, "<green>Area " + a[2] + " created around you. Try /explore area scatter " + a[2] + " 20 for red quartz.");
            }
            case "remove" -> {
                if (a.length < 3 || r.areas.remove(a[2]) == null) { msg(p, "<red>Unknown area."); return; }
                r.save();
                msg(p, "<green>Removed area " + a[2] + ".");
            }
            case "scatter" -> {
                Regions.Area area = a.length > 2 ? r.areas.get(a[2]) : null;
                if (area == null) { msg(p, "<red>/explore area scatter <id> <count>"); return; }
                int n = r.scatterQuartz(area, a.length > 3 ? Integer.parseInt(a[3]) : 15);
                msg(p, "<green>Placed " + n + " red quartz crystals in " + area.id + ".");
            }
            default -> msg(p, "<red>/explore area create|remove|scatter ...");
        }
    }

    private void territory(Player p, String[] a, Location here) {
        String sub = a.length > 1 ? a[1].toLowerCase() : "";
        Regions r = plugin.regions();
        switch (sub) {
            case "create" -> {
                if (a.length < 6) { msg(p, "<red>/explore territory create <id> <copper|ambrosia|wood|stone> <per-hour> <name...>"); return; }
                r.territories.put(a[2], new Regions.Territory(a[2], String.join(" ", Arrays.copyOfRange(a, 5, a.length)),
                        here.getWorld().getName(), here.getBlockX() + 0.5, here.getY(), here.getBlockZ() + 0.5,
                        a[3].toLowerCase(), Long.parseLong(a[4])));
                r.save();
                msg(p, "<green>Territory " + a[2] + " created. Its capture circle is centered where you stand.");
            }
            case "remove" -> {
                Regions.Territory t = a.length > 2 ? r.territories.remove(a[2]) : null;
                if (t == null) { msg(p, "<red>Unknown territory."); return; }
                if (t.bar != null) p.getServer().getOnlinePlayers().forEach(pl -> pl.hideBossBar(t.bar));
                r.save();
                msg(p, "<green>Removed territory " + a[2] + ".");
            }
            default -> msg(p, "<red>/explore territory create|remove ...");
        }
    }

    private void dungeon(Player p, String[] a, Location here) {
        String sub = a.length > 1 ? a[1].toLowerCase() : "";
        Dungeons d = plugin.dungeons();
        switch (sub) {
            case "create" -> {
                if (a.length < 5) { msg(p, "<red>/explore dungeon create <id> <radius> <level>"); return; }
                d.dungeons.put(a[2], new Dungeons.Dungeon(a[2], here.getWorld().getName(), here.getX(), here.getY(), here.getZ(),
                        Double.parseDouble(a[3]), Integer.parseInt(a[4]), new ArrayList<>(Dungeons.DEFAULT_WAVES), 25, 1, 10));
                d.save();
                msg(p, "<green>Dungeon " + a[2] + " created with 3 waves. To change waves and rewards, edit plugins/RPGExplore/explore.yml and restart.");
            }
            case "remove" -> {
                if (a.length < 3 || d.dungeons.remove(a[2]) == null) { msg(p, "<red>Unknown dungeon."); return; }
                d.save();
                msg(p, "<green>Removed dungeon " + a[2] + ".");
            }
            default -> msg(p, "<red>/explore dungeon create|remove ...");
        }
    }

    private void boss(Player p, String[] a, Location here) {
        String sub = a.length > 1 ? a[1].toLowerCase() : "";
        Bosses b = plugin.bosses();
        String id = a.length > 2 ? a[2].toLowerCase() : null;
        switch (sub) {
            case "set" -> {
                if (id == null || b.cfg(id) == null) { msg(p, "<red>/explore boss set <id>  (ids: bosses in config.yml)"); return; }
                Bosses.Arena old = b.arenas.get(id);
                Bosses.Arena arena = new Bosses.Arena(id, here.getWorld().getName(), here.getX(), here.getY(), here.getZ());
                if (old != null) arena.kills = old.kills;
                b.arenas.put(id, arena);
                b.save();
                msg(p, "<green>" + id + "'s arena is here. It spawns when a player comes within range.");
            }
            case "spawn" -> {
                Bosses.Arena arena = id == null ? null : b.arenas.get(id);
                msg(p, arena != null && b.spawn(arena, true) ? "<green>Spawned " + id + "." : "<red>Set an arena first, or it's already alive.");
            }
            case "remove" -> {
                if (id == null || b.arenas.remove(id) == null) { msg(p, "<red>Unknown boss arena."); return; }
                b.save();
                msg(p, "<green>Removed " + id + "'s arena.");
            }
            default -> msg(p, "<red>/explore boss set|spawn|remove <id>");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission("rpgexplore.admin")) return a.length == 1 ? List.of("level") : out;
        if (a.length == 1) out.addAll(List.of("area", "territory", "dungeon", "boss", "trader", "quest", "wild", "level", "list", "reload"));
        else if (a.length == 2) {
            switch (a[0].toLowerCase()) {
                case "area" -> out.addAll(List.of("create", "remove", "scatter"));
                case "territory", "dungeon" -> out.addAll(List.of("create", "remove"));
                case "boss" -> out.addAll(List.of("set", "spawn", "remove"));
            }
        } else if (a.length == 3) {
            switch (a[0].toLowerCase()) {
                case "area" -> { if (!a[1].equalsIgnoreCase("create")) out.addAll(plugin.regions().areas.keySet()); }
                case "territory" -> { if (a[1].equalsIgnoreCase("remove")) out.addAll(plugin.regions().territories.keySet()); }
                case "dungeon" -> { if (a[1].equalsIgnoreCase("remove")) out.addAll(plugin.dungeons().dungeons.keySet()); }
                case "boss" -> {
                    var bosses = plugin.getConfig().getConfigurationSection("bosses");
                    if (bosses != null) out.addAll(bosses.getKeys(false));
                }
            }
        } else if (a.length == 4 && a[0].equalsIgnoreCase("area") && a[1].equalsIgnoreCase("create")) out.add("pixie");
        else if (a.length == 4 && a[0].equalsIgnoreCase("territory") && a[1].equalsIgnoreCase("create")) {
            out.addAll(List.of("copper", "ambrosia", "wood", "stone"));
        }
        String last = a[a.length - 1].toLowerCase();
        return out.stream().filter(o -> o.toLowerCase().startsWith(last)).toList();
    }
}
