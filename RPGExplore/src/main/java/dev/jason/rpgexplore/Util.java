package dev.jason.rpgexplore;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** Small shared helpers: finding ground to spawn on, giving items, and dialog menus. */
final class Util {
    private Util() {}

    record Button(String label, Consumer<Player> action) {}

    /** The surface at x/z: solid, not water, with two free blocks above. Null if not suitable. */
    static Location surface(World w, int x, int z) {
        Block top = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (!top.getType().isSolid() || top.isLiquid()) return null;
        Block feet = top.getRelative(0, 1, 0);
        if (!feet.isPassable() || feet.isLiquid() || !feet.getRelative(0, 1, 0).isPassable()) return null;
        return feet.getLocation().add(0.5, 0, 0.5);
    }

    /** A random standing spot within radius of center, within ySpan blocks up or down (works in caves too). */
    static Location near(Location center, double radius, int ySpan) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        World w = center.getWorld();
        for (int i = 0; i < 12; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = Math.sqrt(r.nextDouble()) * radius;
            int x = (int) Math.floor(center.getX() + Math.cos(a) * d), z = (int) Math.floor(center.getZ() + Math.sin(a) * d);
            for (int y = center.getBlockY() + ySpan; y >= center.getBlockY() - ySpan; y--) {
                Block feet = w.getBlockAt(x, y, z);
                if (feet.getRelative(0, -1, 0).getType().isSolid() && feet.isPassable() && !feet.isLiquid()
                        && feet.getRelative(0, 1, 0).isPassable()) {
                    return feet.getLocation().add(0.5, 0, 0.5);
                }
            }
        }
        return null;
    }

    /** A random surface spot between min and max blocks away from center. */
    static Location ring(Location center, double min, double max) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 10; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = min + r.nextDouble() * (max - min);
            Location l = surface(center.getWorld(), (int) Math.floor(center.getX() + Math.cos(a) * d),
                    (int) Math.floor(center.getZ() + Math.sin(a) * d));
            if (l != null && Math.abs(l.getY() - center.getY()) < 20) return l;
        }
        return null;
    }

    static double flatDistance(Location a, Location b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    static void give(Player p, ItemStack item) {
        if (item == null || item.getAmount() <= 0) return;
        p.getInventory().addItem(item).values().forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
    }

    /** A dialog screen with buttons (two per row) and Close. */
    static void show(Player p, String title, String body, List<Button> buttons) {
        List<ActionButton> actions = new ArrayList<>();
        for (Button b : buttons) {
            actions.add(ActionButton.create(Component.text(b.label()), null, 150, DialogAction.customClick(
                    (view, audience) -> {
                        if (audience instanceof Player pl) b.action().accept(pl);
                    },
                    ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(10)).build())));
        }
        DialogBase base = DialogBase.builder(RPGExplore.MM.deserialize("<gold>" + title))
                .canCloseWithEscape(true)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(List.of(DialogBody.plainMessage(RPGExplore.MM.deserialize(body), 300)))
                .build();
        ActionButton close = ActionButton.create(Component.text("Close"), null, 150, null);
        DialogType type = actions.isEmpty() ? DialogType.notice(close) : DialogType.multiAction(actions, close, 2);
        p.showDialog(Dialog.create(b -> b.empty().base(base).type(type)));
    }
}
