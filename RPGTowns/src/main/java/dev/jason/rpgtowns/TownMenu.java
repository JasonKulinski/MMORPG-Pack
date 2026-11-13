package dev.jason.rpgtowns;

import dev.jason.rpgtowns.DesignManager.Design;
import dev.jason.rpgtowns.TownManager.Plot;
import dev.jason.rpgtowns.TownManager.PlotSize;
import dev.jason.rpgtowns.TownManager.Town;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** The town Planner NPC and all its menus (Paper dialog screens). */
public class TownMenu implements Listener {
    private record Button(String label, Consumer<Player> action) {}

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final RPGTowns plugin;
    private final NamespacedKey npcKey;

    public TownMenu(RPGTowns plugin) {
        this.plugin = plugin;
        this.npcKey = new NamespacedKey(plugin, "town");
    }

    // ------------------------------------------------------------------ the NPC

    public Mannequin spawnPlanner(Town t, Location at) {
        return at.getWorld().spawn(at, Mannequin.class, m -> {
            m.customName(MM.deserialize("<gold>" + t.name + " Planner"));
            m.setCustomNameVisible(true);
            m.setDescription(MM.deserialize("<gray>Right-click"));
            m.setImmovable(true);
            m.setInvulnerable(true);
            m.setPersistent(true);
            m.setRemoveWhenFarAway(false);
            m.getPersistentDataContainer().set(npcKey, PersistentDataType.STRING, t.id);
        });
    }

    private Town townOfNpc(Entity e) {
        return plugin.towns().get(e.getPersistentDataContainer().get(npcKey, PersistentDataType.STRING));
    }

    @EventHandler
    public void onClickNpc(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Town t = townOfNpc(e.getRightClicked());
        if (t == null) return;
        e.setCancelled(true);
        main(e.getPlayer(), t);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHurtNpc(EntityDamageEvent e) {
        if (e.getEntity().getPersistentDataContainer().has(npcKey)) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ menus

    public void main(Player p, Town t) {
        Town home = plugin.towns().townOf(p.getUniqueId());
        long pending = plugin.designs().inTown(t.id).stream()
                .filter(d -> d.status == DesignManager.Status.SUBMITTED && !plugin.designs().approved(d)).count();
        String body = "<gold><bold>" + t.name + "</bold></gold>\n"
                + "<gray>Alderman: <white>" + name(t.alderman) + "\n"
                + "<gray>Residents: <white>" + t.residents.size() + "\n"
                + "<gray>Approval threshold: <white>" + t.threshold + "/5\n"
                + "<gray>Designs waiting for votes: <white>" + pending;
        List<Button> buttons = new ArrayList<>();
        if (home == null) {
            buttons.add(new Button("Join " + t.name, pl -> {
                t.residents.add(pl.getUniqueId());
                plugin.towns().save();
                pl.sendMessage(MM.deserialize("<green>Welcome to " + t.name + "!"));
                main(pl, t);
            }));
        } else if (home != t) {
            body += "\n\n<gray>You live in <white>" + home.name + "<gray>.";
        } else {
            buttons.add(new Button("Design a building", pl -> designSizes(pl, t)));
            buttons.add(new Button("Vote on designs", pl -> voteList(pl, t)));
            buttons.add(new Button("Build on a plot", pl -> plots(pl, t)));
            buttons.add(new Button("My designs", pl -> myDesigns(pl, t)));
            if (t.isAlderman(p)) buttons.add(new Button("Approval threshold", pl -> threshold(pl, t)));
        }
        show(p, t.name, body, buttons, null);
    }

    private void designSizes(Player p, Town t) {
        List<Button> buttons = new ArrayList<>();
        for (PlotSize s : plugin.towns().sizes()) {
            buttons.add(new Button(s.describe(), pl -> {
                Design d = plugin.designs().create(t, pl, s);
                plugin.designWorld().enter(pl, d, DesignWorld.Mode.BUILD);
            }));
        }
        show(p, "Design a building", "<gray>Pick a size. You'll get an empty plot of that size to build on in creative.\n"
                + "Your own inventory is kept safe and given back when you leave.\n"
                + "When it's done, submit it and the town votes on it.", buttons, pl -> main(pl, t));
    }

    private void myDesigns(Player p, Town t) {
        List<Button> buttons = new ArrayList<>();
        for (Design d : plugin.designs().inTown(t.id)) {
            if (!d.author.equals(p.getUniqueId())) continue;
            String label = "#" + d.id + " " + plugin.designs().size(d).label() + " - " + status(d);
            buttons.add(new Button(label, pl -> plugin.designWorld().enter(pl, d,
                    d.status == DesignManager.Status.DRAFT ? DesignWorld.Mode.BUILD : DesignWorld.Mode.VIEW)));
        }
        show(p, "My designs", buttons.isEmpty() ? "<gray>You haven't designed anything yet."
                : "<gray>Drafts open for building; submitted designs open to look at.", buttons, pl -> main(pl, t));
    }

    private void voteList(Player p, Town t) {
        List<Button> buttons = new ArrayList<>();
        for (Design d : plugin.designs().inTown(t.id)) {
            if (d.status != DesignManager.Status.SUBMITTED || d.author.equals(p.getUniqueId())) continue;
            Integer mine = d.votes.get(p.getUniqueId());
            String label = "#" + d.id + " " + plugin.designs().size(d).label() + " by " + d.authorName
                    + (mine == null ? "" : " (you: " + mine + ")");
            buttons.add(new Button(label, pl -> plugin.designWorld().enter(pl, d, DesignWorld.Mode.VOTE)));
        }
        show(p, "Vote on designs", buttons.isEmpty() ? "<gray>No designs to vote on right now."
                : "<gray>Pick a design to go look at it, then vote 1-5 with the colored concrete.\n"
                + "Designs averaging " + t.threshold + "/5 or more can be built.", buttons, pl -> main(pl, t));
    }

    private void plots(Player p, Town t) {
        List<Button> buttons = new ArrayList<>();
        Plot house = plugin.towns().housePlotOf(t, p.getUniqueId());
        if (house != null) {
            buttons.add(new Button("Your house: " + house.id + " (" + plugin.towns().size(house.size).label() + ")",
                    pl -> chooseDesign(pl, t, house)));
        } else {
            for (Plot plot : t.plots.values()) {
                PlotSize s = plugin.towns().size(plot.size);
                if (s == null || !s.house() || plot.owner != null) continue;
                buttons.add(new Button("Claim " + plot.id + " (" + s.describe() + ")", pl -> {
                    plot.owner = pl.getUniqueId();
                    plugin.towns().save();
                    pl.sendMessage(MM.deserialize("<green>House plot " + plot.id + " is yours."));
                    plots(pl, t);
                }));
            }
        }
        if (t.isAlderman(p)) {
            for (Plot plot : t.plots.values()) {
                PlotSize s = plugin.towns().size(plot.size);
                if (s == null || s.house()) continue;
                Design built = plot.design == null ? null : plugin.designs().get(plot.design);
                buttons.add(new Button("Town plot " + plot.id + " (" + s.label() + ")"
                        + (built == null ? "" : " - #" + built.id), pl -> chooseDesign(pl, t, plot)));
            }
        }
        show(p, "Build on a plot", buttons.isEmpty() ? "<gray>There are no plots you can build on right now."
                : "<gray>Residents claim one house plot. The Alderman builds on town plots.", buttons, pl -> main(pl, t));
    }

    private void chooseDesign(Player p, Town t, Plot plot) {
        List<Button> buttons = new ArrayList<>();
        for (Design d : plugin.designs().inTown(t.id)) {
            if (!d.size.equals(plot.size) || !plugin.designs().approved(d)) continue;
            buttons.add(new Button("#" + d.id + " by " + d.authorName + String.format(" (%.1f/5)", d.average()),
                    pl -> confirmBuild(pl, t, plot, d)));
        }
        PlotSize s = plugin.towns().size(plot.size);
        show(p, "Plot " + plot.id, buttons.isEmpty()
                ? "<gray>No approved " + s.label().toLowerCase() + " designs yet. Design one, or vote on the ones waiting."
                : "<gray>Approved " + s.label().toLowerCase() + " designs:", buttons, pl -> plots(pl, t));
    }

    private void confirmBuild(Player p, Town t, Plot plot, Design d) {
        show(p, "Build #" + d.id + "?", "<gray>This replaces everything on plot <white>" + plot.id
                + "<gray> with design <white>#" + d.id + "<gray> by " + d.authorName + ".", List.of(
                new Button("Build it", pl -> {
                    try {
                        plugin.designs().paste(d, plot);
                        plot.design = d.id;
                        plugin.towns().save();
                        pl.sendMessage(MM.deserialize("<green>Design #" + d.id + " was built on plot " + plot.id + "."));
                        pl.playSound(pl.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.2f);
                    } catch (Exception ex) {
                        pl.sendMessage(MM.deserialize("<red>Could not build: " + ex.getMessage()));
                        plugin.getLogger().warning("Paste of design " + d.id + " failed: " + ex);
                    }
                })), pl -> chooseDesign(pl, t, plot));
    }

    private void threshold(Player p, Town t) {
        List<Button> buttons = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            int value = i;
            buttons.add(new Button((i == t.threshold ? "> " : "") + i + "/5", pl -> {
                t.threshold = value;
                plugin.towns().save();
                pl.sendMessage(MM.deserialize("<green>Designs now need an average of " + value + "/5 to be approved."));
                main(pl, t);
            }));
        }
        show(p, "Approval threshold", "<gray>The average vote a design needs before it can be built.\nNow: <white>"
                + t.threshold + "/5", buttons, pl -> main(pl, t));
    }

    /** Opened from the nether star while building. */
    public void designMenu(Player p, Design d) {
        if (d == null) return;
        TownManager.PlotSize s = plugin.designs().size(d);
        show(p, "Design #" + d.id, "<gray>" + s.describe() + "\nYour build stays here if you leave, so you can come back to it "
                + "from the Planner's <white>My designs<gray>.", List.of(
                new Button("Submit for a vote", pl -> confirmSubmit(pl, d)),
                new Button("Leave (keep building later)", pl -> plugin.designWorld().leave(pl))), null);
    }

    private void confirmSubmit(Player p, Design d) {
        show(p, "Submit design #" + d.id + "?", "<gray>Once submitted you can't change it. The town votes 1-5 on it.",
                List.of(new Button("Submit", pl -> submit(pl, d))), pl -> designMenu(pl, d));
    }

    private void submit(Player p, Design d) {
        try {
            plugin.designs().saveSchematic(d);
        } catch (Exception ex) {
            p.sendMessage(MM.deserialize("<red>Could not save the design: " + ex.getMessage()));
            plugin.getLogger().warning("Saving design " + d.id + " failed: " + ex);
            return;
        }
        d.status = DesignManager.Status.SUBMITTED;
        plugin.designs().save();
        plugin.designWorld().leave(p);
        Town t = plugin.towns().get(d.town);
        p.sendMessage(MM.deserialize("<green>Design #" + d.id + " submitted. Residents can now vote on it."));
        if (t != null) notifyTown(t, "<gold>" + p.getName() + "<gray> submitted a new design (#" + d.id + ", "
                + plugin.designs().size(d).label() + "). Vote on it at the Planner!", p);
    }

    public void castVote(Player p, Design d, int vote) {
        if (d == null || d.author.equals(p.getUniqueId())) return;
        boolean wasApproved = plugin.designs().approved(d);
        d.votes.put(p.getUniqueId(), vote);
        plugin.designs().save();
        p.sendMessage(MM.deserialize("<green>You voted " + vote + "/5 on design #" + d.id
                + String.format("<gray> (average %.1f from %d votes)", d.average(), d.votes.size())));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 0.6f + vote * 0.25f);
        Town t = plugin.towns().get(d.town);
        if (!wasApproved && plugin.designs().approved(d) && t != null) {
            notifyTown(t, "<green>Design #" + d.id + " by " + d.authorName + " was approved and can now be built!", null);
        }
    }

    private void notifyTown(Town t, String message, Player except) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online != except && t.residents.contains(online.getUniqueId())) online.sendMessage(MM.deserialize(message));
        }
    }

    // ------------------------------------------------------------------ helpers

    private String status(Design d) {
        if (d.status == DesignManager.Status.DRAFT) return "draft";
        return (plugin.designs().approved(d) ? "approved" : "voting") + String.format(" %.1f/5 (%d)", d.average(), d.votes.size());
    }

    private static String name(java.util.UUID id) {
        if (id == null) return "none";
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        return op.getName() == null ? "?" : op.getName();
    }

    /** A dialog screen with buttons (two per row), an optional Back button, and Close. */
    private void show(Player p, String title, String body, List<Button> buttons, Consumer<Player> back) {
        List<ActionButton> actions = new ArrayList<>();
        for (Button b : buttons) actions.add(button(b.label(), b.action()));
        if (back != null) actions.add(button("< Back", back));
        DialogBase base = DialogBase.builder(MM.deserialize("<gold>" + title))
                .canCloseWithEscape(true)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(List.of(DialogBody.plainMessage(MM.deserialize(body), 300)))
                .build();
        ActionButton close = ActionButton.create(Component.text("Close"), null, 150, null);
        DialogType type = actions.isEmpty() ? DialogType.notice(close) : DialogType.multiAction(actions, close, 2);
        p.showDialog(Dialog.create(b -> b.empty().base(base).type(type)));
    }

    private ActionButton button(String label, Consumer<Player> action) {
        return ActionButton.create(Component.text(label), null, 150, DialogAction.customClick(
                (view, audience) -> {
                    if (audience instanceof Player pl) action.accept(pl);
                },
                ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(10)).build()));
    }
}
