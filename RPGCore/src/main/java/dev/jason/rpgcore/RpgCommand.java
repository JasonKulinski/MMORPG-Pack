package dev.jason.rpgcore;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** /rpg ... — everything in one command. */
public class RpgCommand implements TabExecutor {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final RPGCore plugin;

    public RpgCommand(RPGCore plugin) {
        this.plugin = plugin;
    }

    private void msg(CommandSender s, String text) {
        s.sendMessage(MM.deserialize(text));
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (cmd.getName().equalsIgnoreCase("stats")) {
            String[] shifted = new String[a.length + 1];
            shifted[0] = "stats";
            System.arraycopy(a, 0, shifted, 1, a.length);
            return stats(s, shifted);
        }
        if (a.length == 0 || a[0].equalsIgnoreCase("stats")) return stats(s, a);
        if (a[0].equalsIgnoreCase("pack")) {
            if (s instanceof Player p) plugin.packHost().send(p);
            return true;
        }
        if (!s.hasPermission("rpgcore.admin")) {
            msg(s, "<red>You don't have permission.");
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "class" -> classCmd(s, a);
            case "player" -> playerCmd(s, a);
            case "mob" -> mobCmd(s, a);
            case "checkpoint" -> checkpointCmd(s, a);
            case "zone" -> zoneCmd(s, a);
            case "block" -> blockCmd(s, a);
            case "item" -> itemCmd(s, a);
            case "reload" -> {
                plugin.reloadAll();
                plugin.packHost().sendAll();
                msg(s, "<green>RPGCore reloaded.");
            }
            default -> help(s);
        }
        return true;
    }

    private void help(CommandSender s) {
        msg(s, "<gold>RPGCore commands");
        msg(s, "<yellow>/rpg stats [player]  |  /rpg pack (re-send the resource pack)");
        msg(s, "<yellow>/rpg class list | info <id> | create <id> <name> | delete <id>");
        msg(s, "<yellow>/rpg class set <id> <stat> <base> [perLevel] | color <id> <#hex> | rename <id> <name>");
        msg(s, "<yellow>/rpg player setclass <player> <class> | reset <player> | setlevel <player> <lvl> | givexp <player> <amt>");
        msg(s, "<yellow>/rpg mob list | spawn <id> [count]");
        msg(s, "<yellow>/rpg checkpoint create <id> <class> [radius] | remove <id> | list");
        msg(s, "<yellow>/rpg zone create <id> <radius> <max> <mob[:weight]>... | remove <id> | list | show <id> | clear <id>");
        msg(s, "<yellow>/rpg block list | give <id> [amount] [player]");
        msg(s, "<yellow>/rpg item list | give <id> [amount] [player]");
        msg(s, "<yellow>/rpg reload");
    }

    /** Players get the stats menu. Looking up someone else needs admin. The console gets chat output. */
    private boolean stats(CommandSender s, String[] a) {
        if (a.length > 1 && !s.hasPermission("rpgcore.admin")) {
            msg(s, "<red>You can only view your own stats.");
            return true;
        }
        Player p = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : (s instanceof Player sp ? sp : null);
        if (p == null) {
            msg(s, "<red>Player not found.");
            return true;
        }
        PlayerManager pm = plugin.players();
        if (s instanceof Player viewer && plugin.getConfig().getBoolean("gui.custom-assets", true)) {
            plugin.statsMenu().open(viewer, p);
            return true;
        }
        RpgClass c = pm.getClass(p);
        if (c == null) {
            msg(s, "<gray>" + p.getName() + " has not chosen a class yet.");
            return true;
        }
        int level = pm.getLevel(p);
        msg(s, c.coloredName() + " <gray>— " + p.getName() + ", level <white>" + level
                + "</white> (" + (int) pm.getXp(p) + "/" + (int) pm.xpToNext(level) + " XP)");
        for (Stat st : Stat.values()) {
            msg(s, "<gray>  " + st.label + ": <white>" + fmt(c.stat(st, level)));
        }
        return true;
    }

    private void classCmd(CommandSender s, String[] a) {
        ClassManager cm = plugin.classes();
        String sub = a.length > 1 ? a[1].toLowerCase() : "list";
        switch (sub) {
            case "list" -> msg(s, "<gold>Classes: " + cm.all().stream().map(c -> c.coloredName() + " <gray>(" + c.id + ")")
                    .collect(Collectors.joining("<gray>, ")));
            case "info" -> {
                RpgClass c = a.length > 2 ? cm.get(a[2]) : null;
                if (c == null) { msg(s, "<red>Unknown class."); return; }
                msg(s, c.coloredName() + " <gray>(" + c.id + ") — base / per level");
                for (Stat st : Stat.values()) {
                    msg(s, "<gray>  " + st.key + ": <white>" + fmt(c.base.getOrDefault(st, 0.0))
                            + " <gray>/ <white>+" + fmt(c.perLevel.getOrDefault(st, 0.0)));
                }
            }
            case "create" -> {
                if (a.length < 4) { msg(s, "<red>/rpg class create <id> <name>"); return; }
                if (cm.get(a[2]) != null) { msg(s, "<red>That class already exists."); return; }
                cm.create(a[2], String.join(" ", Arrays.copyOfRange(a, 3, a.length)));
                msg(s, "<green>Created class " + a[2] + ". Set its stats with /rpg class set.");
            }
            case "delete" -> msg(s, a.length > 2 && cm.delete(a[2]) ? "<green>Deleted." : "<red>Unknown class.");
            case "set" -> {
                if (a.length < 5) { msg(s, "<red>/rpg class set <id> <stat> <base> [perLevel]"); return; }
                RpgClass c = cm.get(a[2]);
                Stat st = Stat.fromKey(a[3]);
                if (c == null || st == null) { msg(s, "<red>Unknown class or stat."); return; }
                try {
                    c.base.put(st, Double.parseDouble(a[4]));
                    if (a.length > 5) c.perLevel.put(st, Double.parseDouble(a[5]));
                } catch (NumberFormatException e) {
                    msg(s, "<red>Numbers only.");
                    return;
                }
                cm.save();
                Bukkit.getOnlinePlayers().forEach(plugin.players()::applyStats);
                msg(s, "<green>" + c.id + " " + st.key + " = " + a[4] + (a.length > 5 ? " (+" + a[5] + "/level)" : ""));
            }
            case "color" -> {
                RpgClass c = a.length > 3 ? cm.get(a[2]) : null;
                if (c == null) { msg(s, "<red>/rpg class color <id> <#hex>"); return; }
                c.color = a[3];
                cm.save();
                msg(s, "<green>Color set: " + c.coloredName());
            }
            case "rename" -> {
                RpgClass c = a.length > 3 ? cm.get(a[2]) : null;
                if (c == null) { msg(s, "<red>/rpg class rename <id> <name>"); return; }
                c.name = String.join(" ", Arrays.copyOfRange(a, 3, a.length));
                cm.save();
                msg(s, "<green>Renamed: " + c.coloredName());
            }
            default -> help(s);
        }
    }

    private void playerCmd(CommandSender s, String[] a) {
        if (a.length < 3) { help(s); return; }
        Player p = Bukkit.getPlayerExact(a[2]);
        if (p == null) { msg(s, "<red>Player not found (must be online)."); return; }
        PlayerManager pm = plugin.players();
        switch (a[1].toLowerCase()) {
            case "setclass" -> {
                RpgClass c = a.length > 3 ? plugin.classes().get(a[3]) : null;
                if (c == null) { msg(s, "<red>Unknown class."); return; }
                pm.setClass(p, c);
                msg(s, "<green>" + p.getName() + " is now " + c.coloredName());
            }
            case "reset" -> {
                pm.reset(p);
                msg(s, "<green>" + p.getName() + " has no class again. They can walk through a checkpoint.");
            }
            case "setlevel" -> {
                try {
                    pm.setLevel(p, Integer.parseInt(a[3]));
                    msg(s, "<green>Level set.");
                } catch (Exception e) {
                    msg(s, "<red>/rpg player setlevel <player> <level>");
                }
            }
            case "givexp" -> {
                try {
                    pm.addXp(p, Double.parseDouble(a[3]));
                    msg(s, "<green>XP given.");
                } catch (Exception e) {
                    msg(s, "<red>/rpg player givexp <player> <amount>");
                }
            }
            default -> help(s);
        }
    }

    private void mobCmd(CommandSender s, String[] a) {
        MobManager mm = plugin.mobs();
        String sub = a.length > 1 ? a[1].toLowerCase() : "list";
        if (sub.equals("list")) {
            for (MobManager.MobDef d : mm.all()) {
                msg(s, "<gray>" + d.id() + ": " + d.name() + " <gray>(" + d.type() + ", " + fmt(d.health())
                        + " hp, " + fmt(d.damage()) + " dmg, " + fmt(d.defense()) + " def, " + fmt(d.xp()) + " xp)");
            }
            return;
        }
        if (sub.equals("spawn")) {
            if (!(s instanceof Player p)) { msg(s, "<red>Players only."); return; }
            MobManager.MobDef def = a.length > 2 ? mm.get(a[2]) : null;
            if (def == null) { msg(s, "<red>Unknown mob. Try /rpg mob list"); return; }
            int count = 1;
            try {
                if (a.length > 3) count = Math.max(1, Math.min(20, Integer.parseInt(a[3])));
            } catch (NumberFormatException ignored) {}
            Location loc = p.getTargetBlockExact(30) != null
                    ? p.getTargetBlockExact(30).getLocation().add(0.5, 1, 0.5) : p.getLocation();
            for (int i = 0; i < count; i++) mm.spawn(def, loc);
            msg(s, "<green>Spawned " + count + " " + def.id() + ".");
            return;
        }
        help(s);
    }

    private void checkpointCmd(CommandSender s, String[] a) {
        CheckpointManager cpm = plugin.checkpoints();
        String sub = a.length > 1 ? a[1].toLowerCase() : "list";
        switch (sub) {
            case "list" -> {
                if (cpm.all().isEmpty()) msg(s, "<gray>No checkpoints yet.");
                for (CheckpointManager.Checkpoint c : cpm.all()) {
                    msg(s, "<gray>" + c.id() + " → " + c.classId() + " at " + (int) c.x() + ", " + (int) c.y() + ", "
                            + (int) c.z() + " (" + c.world() + ", radius " + fmt(c.radius()) + ")");
                }
            }
            case "create" -> {
                if (!(s instanceof Player p)) { msg(s, "<red>Players only."); return; }
                if (a.length < 4) { msg(s, "<red>/rpg checkpoint create <id> <class> [radius]"); return; }
                RpgClass c = plugin.classes().get(a[3]);
                if (c == null) { msg(s, "<red>Unknown class."); return; }
                double radius = 2;
                try {
                    if (a.length > 4) radius = Double.parseDouble(a[4]);
                } catch (NumberFormatException ignored) {}
                cpm.create(a[2], p.getLocation(), radius, c.id);
                msg(s, "<green>Checkpoint " + a[2] + " created here. Class-less players who step in become " + c.coloredName());
            }
            case "remove" -> msg(s, a.length > 2 && cpm.remove(a[2]) ? "<green>Removed." : "<red>Unknown checkpoint.");
            default -> help(s);
        }
    }

    private void zoneCmd(CommandSender s, String[] a) {
        SpawnZoneManager zm = plugin.zones();
        String sub = a.length > 1 ? a[1].toLowerCase() : "list";
        SpawnZoneManager.Zone z = a.length > 2 ? zm.get(a[2]) : null;
        switch (sub) {
            case "list" -> {
                if (zm.all().isEmpty()) msg(s, "<gray>No spawn zones yet.");
                for (SpawnZoneManager.Zone zone : zm.all()) {
                    msg(s, "<gray>" + zone.id + ": " + zone.mobs + " at " + (int) zone.x + ", " + (int) zone.y + ", " + (int) zone.z
                            + " (radius " + fmt(zone.radius) + ", " + zm.alive(zone) + "/" + zone.max + " alive, every "
                            + fmt(zone.respawnSeconds) + "s)");
                }
            }
            case "create" -> {
                if (!(s instanceof Player p)) { msg(s, "<red>Players only."); return; }
                if (a.length < 6) { msg(s, "<red>/rpg zone create <id> <radius> <max> <mob[:weight]> [mob[:weight]...]"); return; }
                Map<String, Integer> mobs = new LinkedHashMap<>();
                try {
                    for (int i = 5; i < a.length; i++) {
                        String[] parts = a[i].split(":");
                        if (plugin.mobs().get(parts[0]) == null) { msg(s, "<red>Unknown mob: " + parts[0]); return; }
                        mobs.put(parts[0], parts.length > 1 ? Math.max(1, Integer.parseInt(parts[1])) : 1);
                    }
                    zm.create(a[2], p.getLocation(), Double.parseDouble(a[3]), Integer.parseInt(a[4]), mobs);
                } catch (NumberFormatException e) {
                    msg(s, "<red>Radius, max and weights must be numbers.");
                    return;
                }
                msg(s, "<green>Zone " + a[2] + " created here. Its edge is shown for 30 seconds.");
            }
            case "remove" -> {
                if (z == null) { msg(s, "<red>Unknown zone."); return; }
                int n = zm.clear(z.id);
                zm.remove(z.id);
                msg(s, "<green>Removed zone " + z.id + " and its " + n + " mobs.");
            }
            case "show" -> {
                if (z == null) { msg(s, "<red>Unknown zone."); return; }
                zm.show(z.id);
                msg(s, "<green>Showing " + z.id + " for 30 seconds.");
            }
            case "clear" -> {
                if (z == null) { msg(s, "<red>Unknown zone."); return; }
                msg(s, "<green>Removed " + zm.clear(z.id) + " mobs from " + z.id + ". They will respawn over time.");
            }
            default -> help(s);
        }
    }

    private void blockCmd(CommandSender s, String[] a) {
        CustomBlockManager bm = plugin.blocks();
        String sub = a.length > 1 ? a[1].toLowerCase() : "list";
        if (sub.equals("give")) {
            CustomBlockManager.CustomBlock b = a.length > 2 ? bm.get(a[2]) : null;
            if (b == null) { msg(s, "<red>Unknown block. Try /rpg block list"); return; }
            int amount = 64;
            try {
                if (a.length > 3) amount = Math.max(1, Math.min(6400, Integer.parseInt(a[3])));
            } catch (NumberFormatException ignored) {}
            Player target = a.length > 4 ? Bukkit.getPlayerExact(a[4]) : (s instanceof Player p ? p : null);
            if (target == null) { msg(s, "<red>Player not found."); return; }
            target.getInventory().addItem(bm.item(b, amount)).values()
                    .forEach(left -> target.getWorld().dropItem(target.getLocation(), left));
            msg(s, "<green>Gave " + amount + " " + b.id() + " to " + target.getName() + ".");
            return;
        }
        if (bm.all().isEmpty()) msg(s, "<gray>No custom blocks. Add some to plugins/RPGCore/blocks.yml.");
        for (CustomBlockManager.CustomBlock b : bm.all()) s.sendMessage(bm.describe(b));
    }

    private void itemCmd(CommandSender s, String[] a) {
        CustomItemManager im = plugin.items();
        String sub = a.length > 1 ? a[1].toLowerCase() : "list";
        if (sub.equals("give")) {
            CustomItemManager.CustomItem ci = a.length > 2 ? im.get(a[2]) : null;
            if (ci == null) { msg(s, "<red>Unknown item. Try /rpg item list"); return; }
            int amount = 1;
            try {
                if (a.length > 3) amount = Math.max(1, Math.min(640, Integer.parseInt(a[3])));
            } catch (NumberFormatException ignored) {}
            Player target = a.length > 4 ? Bukkit.getPlayerExact(a[4]) : (s instanceof Player p ? p : null);
            if (target == null) { msg(s, "<red>Player not found."); return; }
            target.getInventory().addItem(im.item(ci, amount)).values()
                    .forEach(left -> target.getWorld().dropItem(target.getLocation(), left));
            msg(s, "<green>Gave " + amount + " " + ci.id() + " to " + target.getName() + ".");
            return;
        }
        if (im.all().isEmpty()) msg(s, "<gray>No custom items. Add some to plugins/RPGCore/items.yml.");
        for (CustomItemManager.CustomItem ci : im.all()) {
            msg(s, ci.name() + " <gray>(" + ci.id() + ", works like " + ci.base().name().toLowerCase() + ")");
        }
    }

    private static String fmt(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.format("%.2f", d);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        List<String> classIds = plugin.classes().all().stream().map(c -> c.id).toList();
        List<String> players = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        if (a.length == 1) out.addAll(List.of("stats", "pack", "class", "player", "mob", "checkpoint", "zone", "block", "item", "reload"));
        else if (a.length == 2) {
            switch (a[0].toLowerCase()) {
                case "class" -> out.addAll(List.of("list", "info", "create", "delete", "set", "color", "rename"));
                case "player" -> out.addAll(List.of("setclass", "reset", "setlevel", "givexp"));
                case "mob" -> out.addAll(List.of("list", "spawn"));
                case "checkpoint" -> out.addAll(List.of("create", "remove", "list"));
                case "zone" -> out.addAll(List.of("create", "remove", "list", "show", "clear"));
                case "block" -> out.addAll(List.of("list", "give"));
                case "item" -> out.addAll(List.of("list", "give"));
                case "stats" -> out.addAll(players);
            }
        } else if (a.length == 3) {
            switch (a[0].toLowerCase()) {
                case "class" -> out.addAll(classIds);
                case "player" -> out.addAll(players);
                case "mob" -> out.addAll(plugin.mobs().all().stream().map(MobManager.MobDef::id).toList());
                case "checkpoint" -> out.addAll(plugin.checkpoints().all().stream().map(CheckpointManager.Checkpoint::id).toList());
                case "zone" -> out.addAll(plugin.zones().all().stream().map(SpawnZoneManager.Zone::id).toList());
                case "block" -> out.addAll(plugin.blocks().all().stream().map(CustomBlockManager.CustomBlock::id).toList());
                case "item" -> out.addAll(plugin.items().all().stream().map(CustomItemManager.CustomItem::id).toList());
            }
        } else if (a.length >= 6 && a[0].equalsIgnoreCase("zone") && a[1].equalsIgnoreCase("create")) {
            out.addAll(plugin.mobs().all().stream().map(MobManager.MobDef::id).toList());
        } else if (a.length == 4) {
            if (a[0].equalsIgnoreCase("class") && a[1].equalsIgnoreCase("set")) {
                out.addAll(Stream.of(Stat.values()).map(st -> st.key).toList());
            } else if ((a[0].equalsIgnoreCase("player") && a[1].equalsIgnoreCase("setclass"))
                    || (a[0].equalsIgnoreCase("checkpoint") && a[1].equalsIgnoreCase("create"))) {
                out.addAll(classIds);
            }
        }
        String last = a[a.length - 1].toLowerCase();
        return out.stream().filter(x -> x.toLowerCase().startsWith(last)).toList();
    }
}
