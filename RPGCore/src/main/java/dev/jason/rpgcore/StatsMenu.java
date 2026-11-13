package dev.jason.rpgcore;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * /stats: an open spellbook shown in a dialog screen (no inventory, no slot highlights), with page buttons.
 * The book image and all text are one line of text using the resource pack fonts:
 *   rpgcore:gui   = book image + spacing characters, rpgcore:l0..l15 = text lines, rpgcore:big0 = 2x heading.
 * Coordinates are pixels from the book's top-left and must match tools/make_pack.py.
 */
public class StatsMenu {
    private static final Key GUI = Key.key("rpgcore", "gui");
    /** Left half, move back 1px (each glyph adds 1px of spacing), right half. Together they advance BOOK_W + 1. */
    private static final String BOOK = "\uE000\uF801\uE001";
    private static final int BOOK_W = 320, BOOK_H = 200;
    private static final int LEFT_X0 = 22, LEFT_X1 = 148, RIGHT_X0 = 172, RIGHT_X1 = 298;
    private static final int PAGE_NUMBER_LINE = 15;
    /** Spreads in the book. Spread 0 = profile + stats; later spreads are blank for now. */
    private static final int SPREADS = 2;

    private static final TextColor INK = TextColor.color(0x3B2A1A);
    private static final TextColor INK_LIGHT = TextColor.color(0x7A6248);
    private static final TextColor VALUE = TextColor.color(0x1A1208);
    private static final TextColor HEALTH = TextColor.color(0x9A1F1F);
    private static final TextColor MANA = TextColor.color(0x1F4F9A);
    private static final TextColor XP_FILL = TextColor.color(0x3E8A2A);
    private static final TextColor XP_EMPTY = TextColor.color(0xC8B48C);

    /** Order of the stats list on the right page. */
    private static final Stat[] STATS = {
            Stat.ATTACK, Stat.CRIT_CHANCE, Stat.CRIT_DAMAGE, Stat.PIERCE, Stat.DEFENSE,
            Stat.DAMAGE_REDUCTION, Stat.LIFE_STEAL, Stat.SPEED, Stat.SLOWNESS, Stat.POISON
    };

    private final RPGCore plugin;

    public StatsMenu(RPGCore plugin) {
        this.plugin = plugin;
    }

    public void open(Player viewer, Player target) {
        open(viewer, target, 0);
    }

    private void open(Player viewer, Player target, int spread) {
        Component book = render(target, spread).shadowColor(ShadowColor.none());
        List<ActionButton> buttons = new ArrayList<>();
        if (spread > 0) buttons.add(pageButton("<  Previous", viewer, target, spread - 1));
        if (spread < SPREADS - 1) buttons.add(pageButton("Next  >", viewer, target, spread + 1));
        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.empty())
                        .canCloseWithEscape(true)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        // a little wider than the book so the client never wraps the layout line
                        .body(List.of(DialogBody.plainMessage(book, BOOK_W + 16)))
                        .build())
                .type(DialogType.multiAction(buttons,
                        ActionButton.create(Component.text("Close"), null, 100, null), 2)));
        viewer.showDialog(dialog);
    }

    private ActionButton pageButton(String label, Player viewer, Player target, int spread) {
        DialogAction action = DialogAction.customClick(
                (view, audience) -> {
                    if (viewer.isOnline() && target.isOnline()) open(viewer, target, spread);
                },
                ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(10)).build());
        return ActionButton.create(Component.text(label), null, 100, action);
    }

    // ------------------------------------------------------------------ page content

    private Component render(Player target, int spread) {
        Page page = new Page();
        page.raw(Component.text(BOOK, TextColor.color(0xFFFFFF)).font(GUI), BOOK_W + 1);

        page.centered(PAGE_NUMBER_LINE, "- " + (spread * 2 + 1) + " -", LEFT_X0, LEFT_X1, INK_LIGHT);
        page.centered(PAGE_NUMBER_LINE, "- " + (spread * 2 + 2) + " -", RIGHT_X0, RIGHT_X1, INK_LIGHT);
        if (spread == 0) profileAndStats(page, target);
        return page.finish();
    }

    private void profileAndStats(Page page, Player target) {
        PlayerManager pm = plugin.players();
        RpgClass c = pm.getClass(target);
        if (c == null) {
            page.bigCentered("Stats", RIGHT_X0, RIGHT_X1, INK);
            page.centered(4, "No path chosen yet.", LEFT_X0, LEFT_X1, INK);
            page.centered(6, "Walk through a class", LEFT_X0, LEFT_X1, INK_LIGHT);
            page.centered(7, "marker to begin.", LEFT_X0, LEFT_X1, INK_LIGHT);
            return;
        }
        int level = pm.getLevel(target);
        boolean maxed = level >= pm.maxLevel();

        // Left page: who you are
        page.bigCentered(c.name, LEFT_X0, LEFT_X1, darken(c));
        page.centered(2, target.getName(), LEFT_X0, LEFT_X1, INK_LIGHT);
        // line 3: flourish (art)
        page.row(4, "Level", String.valueOf(level), LEFT_X0, LEFT_X1, INK, VALUE);
        page.row(5, "Health", (int) Math.ceil(pm.health(target)) + " / " + (int) pm.maxHealth(target),
                LEFT_X0, LEFT_X1, INK, HEALTH);
        page.row(6, "Mana", (int) pm.getMana(target) + " / " + (int) pm.maxMana(target), LEFT_X0, LEFT_X1, INK, MANA);
        double xp = pm.getXp(target), need = pm.xpToNext(level);
        page.row(7, "Experience", maxed ? "MAX" : (int) xp + " / " + (int) need, LEFT_X0, LEFT_X1, INK, VALUE);
        page.bar(8, maxed ? 1 : xp / need, LEFT_X0, LEFT_X1);
        // line 9: flourish (art)
        List<String> lore = Page.wrap(c.lore == null ? "" : c.lore, LEFT_X1 - LEFT_X0);
        for (int i = 0; i < Math.min(3, lore.size()); i++) page.centered(10 + i, lore.get(i), LEFT_X0, LEFT_X1, INK_LIGHT);

        // Right page: stats
        page.bigCentered("Stats", RIGHT_X0, RIGHT_X1, INK);
        // line 2: flourish (art)
        for (int i = 0; i < STATS.length; i++) {
            Stat s = STATS[i];
            String value = fmt(c.stat(s, level)) + (s.label.endsWith("%") ? "%" : "");
            page.row(3 + i, s.label.replace(" %", ""), value, RIGHT_X0, RIGHT_X1, INK, VALUE);
        }
    }

    // ------------------------------------------------------------------ text placement

    /** Builds one line of text where every piece is moved to an exact x with spacing characters. */
    private static final class Page {
        private final List<Component> parts = new ArrayList<>();
        private int pen = 0; // x position relative to the book's left edge

        void shiftTo(int x) {
            int d = x - pen;
            if (d != 0) parts.add(Component.text(space(d)).font(GUI));
            pen = x;
        }

        void raw(Component c, int advance) {
            parts.add(c);
            pen += advance;
        }

        void text(int line, int x, String s, TextColor color) {
            shiftTo(x);
            raw(Component.text(s, color).font(Key.key("rpgcore", "l" + line)), width(s));
        }

        void centered(int line, String s, int x0, int x1, TextColor color) {
            text(line, x0 + (x1 - x0 - width(s)) / 2, s, color);
        }

        void bigCentered(String s, int x0, int x1, TextColor color) {
            int w = bigWidth(s);
            shiftTo(x0 + (x1 - x0 - w) / 2);
            raw(Component.text(s, color).font(Key.key("rpgcore", "big0")), w);
        }

        /** "Label ....... value" with the value right-aligned. */
        void row(int line, String label, String value, int x0, int x1, TextColor labelColor, TextColor valueColor) {
            text(line, x0, label, labelColor);
            int vx = x1 - width(value);
            int gap = vx - pen - 3;
            if (gap >= 4) text(line, pen + 2, ".".repeat(gap / 2), INK_LIGHT);
            text(line, vx, value, valueColor);
        }

        /** Ink progress bar made of '|' characters (2px each). */
        void bar(int line, double progress, int x0, int x1) {
            int count = (x1 - x0) / 2;
            int filled = (int) Math.round(Math.max(0, Math.min(1, progress)) * count);
            text(line, x0, "|".repeat(filled), XP_FILL);
            text(line, pen, "|".repeat(count - filled), XP_EMPTY);
        }

        /**
         * Ends at the book's right edge so the dialog centers the book, then adds empty lines
         * so the dialog reserves the book's full height.
         */
        Component finish() {
            shiftTo(BOOK_W);
            var b = Component.text();
            parts.forEach(b::append);
            b.append(Component.text("\n".repeat((BOOK_H + 8) / 9)));
            return b.build();
        }

        static List<String> wrap(String text, int maxWidth) {
            List<String> lines = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            for (String word : text.split(" ")) {
                if (word.isEmpty()) continue;
                String next = cur.isEmpty() ? word : cur + " " + word;
                if (width(next) > maxWidth && !cur.isEmpty()) {
                    lines.add(cur.toString());
                    cur = new StringBuilder(word);
                } else {
                    cur = new StringBuilder(next);
                }
            }
            if (!cur.isEmpty()) lines.add(cur.toString());
            return lines;
        }
    }

    /** Pixel advance of a character in Minecraft's default font (glyph width + 1). */
    private static int charWidth(char ch) {
        return switch (ch) {
            case ' ' -> 4;
            case '!', '\'', ',', '.', ':', ';', 'i', '|' -> 2;
            case '`', 'l' -> 3;
            case '"', '(', ')', '*', 'I', '[', ']', 't', '{', '}' -> 4;
            case '<', '>', 'f', 'k' -> 5;
            case '@', '~' -> 7;
            default -> 6;
        };
    }

    static int width(String s) {
        int w = 0;
        for (char ch : s.toCharArray()) w += charWidth(ch);
        return w;
    }

    /** Width at 2x size: each glyph is twice as wide, plus 1px spacing. */
    static int bigWidth(String s) {
        int w = 0;
        for (char ch : s.toCharArray()) w += ch == ' ' ? 7 : (charWidth(ch) - 1) * 2 + 1;
        return w;
    }

    /** Spacing characters from the pack font: - move left 1..128 px, - move right. */
    static String space(int px) {
        StringBuilder sb = new StringBuilder();
        int base = px < 0 ? 0xF801 : 0xF821;
        int n = Math.abs(px);
        for (int bit = 7; bit >= 0; bit--) {
            int size = 1 << bit;
            while (n >= size) {
                sb.append((char) (base + bit));
                n -= size;
            }
        }
        return sb.toString();
    }

    /** Class color, darkened to read like ink on parchment. */
    private static TextColor darken(RpgClass c) {
        TextColor tc = c.color.startsWith("#") ? TextColor.fromHexString(c.color) : null;
        if (tc == null) return INK;
        return TextColor.color((int) (tc.red() * 0.55), (int) (tc.green() * 0.55), (int) (tc.blue() * 0.55));
    }

    private static String fmt(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.format("%.1f", d);
    }
}
