package dev.jason.rpgcore;

import com.destroystokyo.paper.event.inventory.PrepareResultEvent;
import io.papermc.paper.event.player.PlayerPickBlockEvent;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.Keyed;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.Event;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.util.BoundingBox;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Instrument;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Note;
import org.bukkit.SoundGroup;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.NotePlayEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom blocks built on note block states. Each custom block is one (note, powered) combination; the
 * resource pack gives that combination its own texture for every instrument. Players' games change a note
 * block's instrument on their own when the block above or below changes (they still think it's a note
 * block), but never its note or powered flag, so ignoring the instrument means a custom block can never
 * flicker to the wrong texture. That gives 49 custom blocks (25 notes x 2, minus one kept for plain note
 * blocks). The plugin keeps them from changing, gives them their own item, mining speed, drops and sounds,
 * and adds them to the resource pack.
 *
 * Needs paper-global.yml: block-updates: disable-noteblock-updates: true (so the server never changes them).
 */
public class CustomBlockManager implements Listener {
    public record CustomBlock(String id, String name, String texture, BlockData like, float hardness,
                              boolean breakable, NoteBlock state) {
        /** The text WorldEdit needs to place this block, e.g. note_block[instrument=banjo,note=0,powered=false]. */
        public String worldEdit() {
            return state.getAsString().replace("minecraft:", "");
        }
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final RPGCore plugin;
    private final NamespacedKey itemKey, speedKey;
    private final Map<String, CustomBlock> blocks = new LinkedHashMap<>();
    /** Custom blocks by "note:powered". */
    private final Map<String, CustomBlock> byKey = new HashMap<>();
    /** Every (note, powered) combination custom blocks can use, in a fixed order. */
    private final List<NoteBlock> pool = new ArrayList<>();
    /** What plain note blocks are set to when placed. Its combination is never used by a custom block. */
    private static final int PLAIN_NOTE = 0;
    private static final boolean PLAIN_POWERED = true;
    private final Map<Instrument, String> instrumentNames = new LinkedHashMap<>();
    private final Map<java.util.UUID, Integer> placedTick = new HashMap<>();

    public CustomBlockManager(RPGCore plugin) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "block");
        this.speedKey = new NamespacedKey(plugin, "custom_block_speed");
        for (Instrument inst : Instrument.values()) {
            NoteBlock nb = (NoteBlock) Material.NOTE_BLOCK.createBlockData();
            try {
                nb.setInstrument(inst);
            } catch (IllegalArgumentException e) {
                continue;
            }
            String s = nb.getAsString();
            int i = s.indexOf("instrument=");
            instrumentNames.put(inst, s.substring(i + 11, s.indexOf(',', i) < 0 ? s.indexOf(']', i) : s.indexOf(',', i)));
        }
        for (boolean powered : new boolean[]{false, true}) {
            for (int note = 0; note <= 24; note++) {
                if (note == PLAIN_NOTE && powered == PLAIN_POWERED) continue;
                pool.add(state(Instrument.BASS_DRUM, note, powered));
            }
        }
    }

    private static String key(NoteBlock nb) {
        return nb.getNote().getId() + ":" + nb.isPowered();
    }

    private static NoteBlock plain() {
        return state(Instrument.PIANO, PLAIN_NOTE, PLAIN_POWERED);
    }

    private static NoteBlock state(Instrument inst, int note, boolean powered) {
        NoteBlock nb = (NoteBlock) Material.NOTE_BLOCK.createBlockData();
        nb.setInstrument(inst);
        nb.setNote(new Note(note));
        nb.setPowered(powered);
        return nb;
    }

    // ------------------------------------------------------------------ loading

    public void load() {
        blocks.clear();
        byKey.clear();
        File file = new File(plugin.getDataFolder(), "blocks.yml");
        if (!file.exists()) plugin.saveResource("blocks.yml", false);

        // Which note block state each block uses. Kept in its own file so a block keeps its look forever,
        // even if other blocks are added or removed.
        File statesFile = new File(plugin.getDataFolder(), "block-states.yml");
        YamlConfiguration states = YamlConfiguration.loadConfiguration(statesFile);
        boolean statesChanged = false;

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String id : yml.getKeys(false)) {
            ConfigurationSection s = yml.getConfigurationSection(id);
            if (s == null) continue;
            Material likeMat = Material.matchMaterial(s.getString("break-like", "stone"));
            if (likeMat == null || !likeMat.isBlock()) {
                plugin.getLogger().warning("blocks.yml: " + id + " has an unknown break-like block; using stone.");
                likeMat = Material.STONE;
            }
            NoteBlock st = null;
            String saved = states.getString(id);
            if (saved != null) {
                try {
                    BlockData d = Bukkit.createBlockData(saved);
                    if (d instanceof NoteBlock nb) st = nb;
                } catch (IllegalArgumentException ignored) {}
            }
            if (st != null) st = state(Instrument.BASS_DRUM, st.getNote().getId(), st.isPowered());
            if (st == null || byKey.containsKey(key(st)) || (st.getNote().getId() == PLAIN_NOTE && st.isPowered() == PLAIN_POWERED)) {
                st = nextFree(states);
                if (st == null) {
                    plugin.getLogger().severe("No note block states left for " + id);
                    continue;
                }
                states.set(id, st.getAsString());
                statesChanged = true;
            }
            // Built-in textures (black cobblestone, red quartz) are copied out the first time they're needed.
            String texName = "textures/block/" + s.getString("texture", id) + ".png";
            if (!new File(plugin.getDataFolder(), texName).exists() && plugin.getResource(texName) != null) {
                plugin.saveResource(texName, false);
            }
            CustomBlock b = new CustomBlock(id, s.getString("name", id), s.getString("texture", id),
                    likeMat.createBlockData(), (float) s.getDouble("hardness", likeMat.getHardness()),
                    s.getBoolean("breakable", true), st);
            blocks.put(id, b);
            byKey.put(key(st), b);
        }
        if (statesChanged) {
            try {
                states.save(statesFile);
            } catch (IOException e) {
                plugin.getLogger().severe("Could not save block-states.yml: " + e.getMessage());
            }
        }
        plugin.getLogger().info("Loaded " + blocks.size() + " custom blocks.");
    }

    private NoteBlock nextFree(YamlConfiguration states) {
        java.util.Set<String> used = new java.util.HashSet<>(byKey.keySet());
        for (String k : states.getKeys(false)) {
            try {
                if (Bukkit.createBlockData(states.getString(k, "")) instanceof NoteBlock nb) used.add(key(nb));
            } catch (IllegalArgumentException ignored) {}
        }
        for (NoteBlock nb : pool) if (!used.contains(key(nb))) return nb;
        return null;
    }

    public CustomBlock get(String id) {
        return blocks.get(id);
    }

    public Collection<CustomBlock> all() {
        return blocks.values();
    }

    /** The custom block at this spot, or null. */
    public CustomBlock at(Block b) {
        return b.getBlockData() instanceof NoteBlock nb ? byKey.get(key(nb)) : null;
    }

    /**
     * The item for a custom block. It is paper underneath, not a note block: if it were a note block,
     * players' games would draw a plain note block the moment they click and swap it a moment later.
     * With paper the game draws nothing until the server places the real block.
     */
    public ItemStack item(CustomBlock b, int amount) {
        return item(b, amount, Material.PAPER);
    }

    private ItemStack item(CustomBlock b, int amount, Material base) {
        ItemStack it = new ItemStack(base, amount);
        ItemMeta meta = it.getItemMeta();
        meta.itemName(MM.deserialize(b.name()).decoration(TextDecoration.ITALIC, false));
        meta.setItemModel(new NamespacedKey("rpgcore", "block/" + b.id()));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, b.id());
        it.setItemMeta(meta);
        return it;
    }

    /** Recipe ingredient that accepts the block item, including older note-block-based copies. */
    public RecipeChoice recipeChoice(CustomBlock b) {
        return new RecipeChoice.ExactChoice(List.of(item(b, 1), item(b, 1, Material.NOTE_BLOCK)));
    }

    /** The custom block an item places, or null. */
    public CustomBlock of(ItemStack it) {
        if (it == null || it.isEmpty() || !it.hasItemMeta()) return null;
        String id = it.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        return id == null ? null : blocks.get(id);
    }

    // ------------------------------------------------------------------ resource pack

    /** Files added to the resource pack: textures, models, item models, and the note block look-up. */
    public Map<String, byte[]> packFiles() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        StringBuilder variants = new StringBuilder("{\n  \"variants\": {\n");
        boolean first = true;
        for (Instrument inst : instrumentNames.keySet()) {
            for (int note = 0; note <= 24; note++) {
                for (boolean powered : new boolean[]{false, true}) {
                    CustomBlock b = byKey.get(note + ":" + powered);
                    String model = b == null ? "minecraft:block/note_block" : "rpgcore:block/" + b.id();
                    if (!first) variants.append(",\n");
                    first = false;
                    variants.append("    \"instrument=").append(instrumentNames.get(inst)).append(",note=").append(note)
                            .append(",powered=").append(powered).append("\": {\"model\": \"").append(model).append("\"}");
                }
            }
        }
        variants.append("\n  }\n}\n");
        files.put("assets/minecraft/blockstates/note_block.json", utf8(variants.toString()));

        File texDir = new File(plugin.getDataFolder(), "textures/block");
        for (CustomBlock b : blocks.values()) {
            File png = new File(texDir, b.texture() + ".png");
            if (!png.exists()) {
                plugin.getLogger().warning("Missing texture for " + b.id() + ": textures/block/" + b.texture() + ".png");
            } else {
                try {
                    files.put("assets/rpgcore/textures/block/" + b.texture() + ".png", Files.readAllBytes(png.toPath()));
                } catch (IOException e) {
                    plugin.getLogger().warning("Could not read " + png + ": " + e.getMessage());
                }
            }
            files.put("assets/rpgcore/models/block/" + b.id() + ".json", utf8(
                    "{\"parent\": \"minecraft:block/cube_all\", \"textures\": {\"all\": \"rpgcore:block/" + b.texture() + "\"}}\n"));
            files.put("assets/rpgcore/items/block/" + b.id() + ".json", utf8(
                    "{\"model\": {\"type\": \"minecraft:model\", \"model\": \"rpgcore:block/" + b.id() + "\"}}\n"));
        }
        return files;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ keeping note blocks still

    /** Placing: custom items become their block; plain note blocks are set to the one plain combination. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() != Material.NOTE_BLOCK) return;
        CustomBlock b = of(e.getItemInHand());
        e.getBlockPlaced().setBlockData(b != null ? b.state() : plain(), false);
        if (b != null) playSound(e.getBlockPlaced(), b.like().getSoundGroup(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAnyPlace(BlockPlaceEvent e) {
        // Placing a normal block against a custom one: the player's game didn't place it itself (it thought
        // it was clicking a note block), so it didn't play the sound either. Play it for them.
        if (!e.isCancelled() && at(e.getBlockAgainst()) != null && of(e.getItemInHand()) == null) {
            SoundGroup g = e.getBlockPlaced().getBlockData().getSoundGroup();
            e.getPlayer().playSound(e.getBlockPlaced().getLocation().add(0.5, 0.5, 0.5), g.getPlaceSound(),
                    (g.getVolume() + 1) / 2, g.getPitch() * 0.8f);
        }
    }

    /**
     * Right-clicks on blocks:
     *  - holding a custom block item: the plugin places the block itself (it's paper, so vanilla wouldn't);
     *  - clicking any note block with anything else: never retune it (that would turn it into a different
     *    block), but still let the held item be used, so blocks can be placed against it without sneaking.
     * After a click the game also sends a "use item" message; Paper turns that into a second click event on
     * whatever block is now in view (often the block just placed). Those come with block use already denied.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.getHand() == null) return;
        Player p = e.getPlayer();
        CustomBlock held = of(e.getItem());
        // One click, one block: ignore the follow-up event and the off hand after a placement this tick.
        boolean followUp = held != null && e.useInteractedBlock() == Event.Result.DENY;
        if (followUp || Integer.valueOf(Bukkit.getCurrentTick()).equals(placedTick.get(p.getUniqueId()))) {
            e.setCancelled(true);
            return;
        }
        Block clicked = e.getClickedBlock();
        boolean clickedNote = clicked.getType() == Material.NOTE_BLOCK;
        if (held != null) {
            if (!clickedNote && !p.isSneaking() && opensOnClick(clicked)) return; // chests, doors... still open
            e.setCancelled(true);
            if (place(p, e.getHand(), held, clicked, e.getBlockFace())) placedTick.put(p.getUniqueId(), Bukkit.getCurrentTick());
            return;
        }
        if (clickedNote) {
            e.setUseInteractedBlock(Event.Result.DENY);
            if (e.getItem() != null && !e.getItem().isEmpty()) e.setUseItemInHand(Event.Result.ALLOW);
        }
    }

    /** Places a custom block like a player would, with all the checks vanilla does. */
    private boolean place(Player p, EquipmentSlot hand, CustomBlock b, Block clicked, BlockFace face) {
        if (p.getGameMode() == GameMode.ADVENTURE || p.getGameMode() == GameMode.SPECTATOR) return false;
        Block target = clicked.isReplaceable() && at(clicked) == null ? clicked : clicked.getRelative(face);
        if (!target.isReplaceable() || at(target) != null) return false;
        World w = target.getWorld();
        if (target.getY() < w.getMinHeight() || target.getY() >= w.getMaxHeight()) return false;
        if (!w.getWorldBorder().isInside(target.getLocation().add(0.5, 0, 0.5))) return false;
        if (inSpawnProtection(p, target)) return false;
        boolean blocked = !w.getNearbyEntities(BoundingBox.of(target), en ->
                (en instanceof LivingEntity le && !le.isDead() && !(le instanceof Player pl && pl.getGameMode() == GameMode.SPECTATOR))
                        || en instanceof Vehicle).isEmpty();
        if (blocked) return false;

        BlockState replaced = target.getState();
        target.setBlockData(b.state(), false);
        ItemStack inHand = p.getInventory().getItem(hand);
        BlockPlaceEvent ev = new BlockPlaceEvent(target, replaced, clicked, inHand, p, true, hand);
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled() || !ev.canBuild()) {
            replaced.update(true, false);
            return false;
        }
        if (p.getGameMode() != GameMode.CREATIVE) {
            inHand.setAmount(inHand.getAmount() - 1);
            p.getInventory().setItem(hand, inHand.getAmount() > 0 ? inHand : null);
        }
        p.swingHand(hand);
        return true;
    }

    private static boolean inSpawnProtection(Player p, Block target) {
        int radius = Bukkit.getSpawnRadius();
        if (radius <= 0 || p.isOp() || !target.getWorld().equals(Bukkit.getWorlds().get(0))) return false;
        Location spawn = target.getWorld().getSpawnLocation();
        return Math.max(Math.abs(target.getX() - spawn.getBlockX()), Math.abs(target.getZ() - spawn.getBlockZ())) <= radius;
    }

    /** Blocks that do something when right-clicked, so placing against them needs sneaking (like vanilla). */
    private static boolean opensOnClick(Block b) {
        Material m = b.getType();
        if (b.getState(false) instanceof InventoryHolder) return true;
        if (Tag.DOORS.isTagged(m) || Tag.TRAPDOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m) || Tag.BUTTONS.isTagged(m)
                || Tag.BEDS.isTagged(m) || Tag.ANVIL.isTagged(m) || Tag.ALL_SIGNS.isTagged(m)
                || Tag.SHULKER_BOXES.isTagged(m) || Tag.CANDLE_CAKES.isTagged(m) || Tag.COPPER_CHESTS.isTagged(m)
                || Tag.WOODEN_SHELVES.isTagged(m)) return true;
        return switch (m) {
            case CRAFTING_TABLE, SMITHING_TABLE, STONECUTTER, LOOM, CARTOGRAPHY_TABLE, GRINDSTONE, ENCHANTING_TABLE,
                 ENDER_CHEST, LEVER, REPEATER, COMPARATOR, DAYLIGHT_DETECTOR, BELL, CAKE, BEACON, LECTERN,
                 RESPAWN_ANCHOR, JUKEBOX, CRAFTER, CHISELED_BOOKSHELF, DECORATED_POT, COMPOSTER, FLOWER_POT,
                 COMMAND_BLOCK, CHAIN_COMMAND_BLOCK, REPEATING_COMMAND_BLOCK, STRUCTURE_BLOCK, JIGSAW, VAULT -> true;
            default -> false;
        };
    }

    /** Middle-click on a custom block picks its item (instead of a plain note block). */
    @EventHandler(ignoreCancelled = true)
    public void onPick(PlayerPickBlockEvent e) {
        CustomBlock b = at(e.getBlock());
        if (b == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            if (of(inv.getItem(i)) != b) continue;
            if (i < 9) {
                inv.setHeldItemSlot(i);
            } else {
                ItemStack held = inv.getItemInMainHand();
                inv.setItemInMainHand(inv.getItem(i));
                inv.setItem(i, held);
            }
            return;
        }
        if (p.getGameMode() != GameMode.CREATIVE) return;
        int slot = inv.getHeldItemSlot();
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i) == null || inv.getItem(i).isEmpty()) { slot = i; break; }
        }
        inv.setItem(slot, item(b, 64));
        inv.setHeldItemSlot(slot);
    }

    /** Older black cobblestone items were note blocks; swap them for the new paper-based ones. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        PlayerInventory inv = e.getPlayer().getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            CustomBlock b = of(it);
            if (b != null && it.getType() == Material.NOTE_BLOCK) inv.setItem(i, item(b, it.getAmount()));
        }
    }

    // Block items are paper underneath: keep them out of vanilla recipes (books, maps...), workstations and trades.

    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        if (e.getRecipe() instanceof Keyed k && !k.getKey().getNamespace().equals("minecraft")) return; // our / other plugins' recipes
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (of(it) != null) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler
    public void onWorkstation(PrepareResultEvent e) {
        for (ItemStack it : e.getInventory().getContents()) {
            if (of(it) != null) {
                e.setResult(null);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent e) {
        if (e.getPlayer().getOpenInventory().getTopInventory() instanceof MerchantInventory mi
                && (of(mi.getItem(0)) != null || of(mi.getItem(1)) != null)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onNote(NotePlayEvent e) {
        if (at(e.getBlock()) != null) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ breaking

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        resetSpeed(e.getPlayer());
        CustomBlock b = at(e.getBlock());
        if (b == null) return;
        Player p = e.getPlayer();
        if (!b.breakable() && p.getGameMode() != GameMode.CREATIVE) {
            e.setCancelled(true);
            return;
        }
        e.setDropItems(false);
        playSound(e.getBlock(), b.like().getSoundGroup(), false);
        if (p.getGameMode() == GameMode.CREATIVE) return;
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (!b.like().requiresCorrectToolForDrops() || b.like().isPreferredTool(tool)) {
            Location at = e.getBlock().getLocation().add(0.5, 0.5, 0.5);
            at.getWorld().dropItemNaturally(at, item(b, 1));
        }
    }

    /**
     * Mining speed: a note block normally breaks like wood. While a player digs a custom block, their
     * block break speed is scaled so it breaks like its break-like block with its hardness instead.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDig(BlockDamageEvent e) {
        Player p = e.getPlayer();
        resetSpeed(p);
        CustomBlock b = at(e.getBlock());
        if (b == null || p.getGameMode() == GameMode.CREATIVE) return;
        if (!b.breakable()) return;
        ItemStack tool = e.getItemInHand();
        double vanilla = e.getBlock().getBlockData().getDestroySpeed(tool, true) / 0.8 / 30.0;
        boolean harvest = !b.like().requiresCorrectToolForDrops() || b.like().isPreferredTool(tool);
        double wanted = b.like().getDestroySpeed(tool, true) / Math.max(0.05, b.hardness()) / (harvest ? 30.0 : 100.0);
        AttributeInstance attr = p.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (attr != null && vanilla > 0) {
            attr.addTransientModifier(new AttributeModifier(speedKey, wanted / vanilla - 1,
                    AttributeModifier.Operation.ADD_SCALAR));
        }
    }

    @EventHandler
    public void onStopDigging(BlockDamageAbortEvent e) {
        resetSpeed(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        resetSpeed(e.getPlayer());
        placedTick.remove(e.getPlayer().getUniqueId());
    }

    private void resetSpeed(Player p) {
        AttributeInstance attr = p.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (attr != null) attr.removeModifier(speedKey);
    }

    /** Explosions drop the custom item instead of a note block, and leave unbreakable blocks alone. */
    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        explode(e.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        explode(e.blockList());
    }

    private void explode(List<Block> list) {
        list.removeIf(block -> {
            CustomBlock b = at(block);
            if (b == null) return false;
            if (b.breakable()) {
                block.setType(Material.AIR, false);
                Location at = block.getLocation().add(0.5, 0.5, 0.5);
                at.getWorld().dropItemNaturally(at, item(b, 1));
            }
            return true;
        });
    }

    private void playSound(Block block, SoundGroup group, boolean place) {
        block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5),
                place ? group.getPlaceSound() : group.getBreakSound(), group.getVolume(), group.getPitch() * 0.8f);
    }

    /** Shown in /rpg block list. */
    public Component describe(CustomBlock b) {
        return MM.deserialize(b.name() + " <gray>(" + b.id() + ") — WorldEdit: <white>" + b.worldEdit());
    }
}
