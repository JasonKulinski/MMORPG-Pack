package dev.jason.rpgexplore;

import dev.jason.rpgcore.Wallet;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Side quests from wandering travelers (mannequin NPCs) that appear near players in the wilds:
 *  - fetch: bring some items, get copper and XP;
 *  - ring: a ring is hidden in a chest nearby (marked by a light beam). Return it for a reward, or keep it
 *    (it gives stats while held in the off hand).
 * One quest per player at a time. Quests live in memory; travelers and chests are removed on shutdown.
 */
public class Quests implements Listener {
    static final class Quest {
        final UUID player;
        final UUID npc;
        final Map<?, ?> fetch;    // null for the ring quest
        Location chest;           // ring quest: where the ring is
        final long expires;

        Quest(UUID player, UUID npc, Map<?, ?> fetch, long expires) {
            this.player = player;
            this.npc = npc;
            this.fetch = fetch;
            this.expires = expires;
        }
    }

    private final RPGExplore plugin;
    private final NamespacedKey questKey;
    private final Map<UUID, Quest> byPlayer = new HashMap<>();

    Quests(RPGExplore plugin) {
        this.plugin = plugin;
        this.questKey = new NamespacedKey(plugin, "quest_npc");
    }

    /** Once a minute: maybe a traveler appears near each player in the wilds; old quests expire. */
    void tick() {
        long now = System.currentTimeMillis();
        for (Quest q : List.copyOf(byPlayer.values())) if (now > q.expires) end(q, "<gray>The traveler gave up waiting and moved on.");
        if (!plugin.getConfig().getBoolean("quests.enabled", true)) return;
        double chance = plugin.getConfig().getDouble("quests.chance-per-minute", 0.08);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (byPlayer.containsKey(p.getUniqueId()) || !plugin.wilds().inWilds(p)) continue;
            if (ThreadLocalRandom.current().nextDouble() < chance) start(p);
        }
    }

    /** Spawns a traveler with a quest for this player. Returns false if there was nowhere to put them. */
    boolean start(Player p) {
        Location spot = Util.ring(p.getLocation(), 5, 9);
        if (spot == null) return false;
        spot.setDirection(p.getLocation().toVector().subtract(spot.toVector()));
        boolean ring = ThreadLocalRandom.current().nextDouble() < plugin.getConfig().getDouble("quests.ring.chance", 0.4);
        List<Map<?, ?>> fetches = plugin.getConfig().getMapList("quests.fetch");
        Map<?, ?> fetch = ring || fetches.isEmpty() ? null : fetches.get(ThreadLocalRandom.current().nextInt(fetches.size()));
        Mannequin npc = spot.getWorld().spawn(spot, Mannequin.class, m -> {
            m.customName(RPGExplore.MM.deserialize("<yellow>Lost Traveler"));
            m.setCustomNameVisible(true);
            m.setDescription(RPGExplore.MM.deserialize("<gray>Right-click"));
            m.setImmovable(true);
            m.setInvulnerable(true);
            m.setPersistent(false);
            m.getPersistentDataContainer().set(questKey, PersistentDataType.STRING, p.getUniqueId().toString());
        });
        long life = plugin.getConfig().getLong("quests.lifetime-minutes", 15) * 60_000;
        byPlayer.put(p.getUniqueId(), new Quest(p.getUniqueId(), npc.getUniqueId(), fetch, System.currentTimeMillis() + life));
        p.sendMessage(RPGExplore.MM.deserialize("<yellow>A lost traveler is calling out to you nearby."));
        return true;
    }

    @EventHandler
    public void onClick(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        String owner = e.getRightClicked().getPersistentDataContainer().get(questKey, PersistentDataType.STRING);
        if (owner == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Quest q = byPlayer.get(UUID.fromString(owner));
        if (q == null) {
            e.getRightClicked().remove();
            return;
        }
        if (!p.getUniqueId().equals(q.player)) {
            Util.show(p, "Lost Traveler", "<gray>\"Sorry, I'm waiting for someone else.\"", List.of());
            return;
        }
        if (q.fetch != null) fetchMenu(p, q);
        else ringMenu(p, q);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        if (e.getEntity().getPersistentDataContainer().has(questKey)) e.setCancelled(true);
    }

    private void fetchMenu(Player p, Quest q) {
        ItemStack want = plugin.item(String.valueOf(q.fetch.get("item")), 1);
        int amount = ((Number) q.fetch.get("amount")).intValue();
        int have = count(p, want);
        int copper = ((Number) q.fetch.get("copper")).intValue();
        int xp = ((Number) q.fetch.get("xp")).intValue();
        String body = "<white>\"" + q.fetch.get("text") + "\"\n\n<gray>Bring: <white>" + amount + " "
                + pretty(want.getType()) + " <gray>(you have " + have + ")\n<gray>Reward: <#d9884a>" + copper
                + " copper<gray>, <green>" + xp + " XP";
        List<Util.Button> buttons = have >= amount ? List.of(new Util.Button("Hand them over", pl -> {
            take(pl, want, amount);
            reward(pl, copper, 0, xp);
            end(q, "<green>\"Thank you, traveler!\"");
        })) : List.of();
        Util.show(p, "Lost Traveler", body, buttons);
    }

    private void ringMenu(Player p, Quest q) {
        ItemStack ring = plugin.item("travelers_ring", 1);
        boolean hasRing = count(p, ring) > 0;
        int copper = plugin.getConfig().getInt("quests.ring.copper", 15);
        int ambrosia = plugin.getConfig().getInt("quests.ring.ambrosia", 1);
        int xp = plugin.getConfig().getInt("quests.ring.xp", 50);
        if (hasRing) {
            Util.show(p, "Lost Traveler", "<white>\"My ring! You found it! Please, give it back and I'll pay you.\"\n\n"
                    + "<gray>Reward: <#d9884a>" + copper + " copper<gray>, <#f2d36b>" + ambrosia + " ambrosia<gray>, <green>"
                    + xp + " XP\n<gray>Or keep it: it gives <white>+5% crit chance and +40 health<gray> in your off hand.", List.of(
                    new Util.Button("Return the ring", pl -> {
                        take(pl, ring, 1);
                        reward(pl, copper, ambrosia, xp);
                        end(q, "<green>\"I knew I could trust you!\"");
                    }),
                    new Util.Button("Keep it", pl -> end(q, "<red>\"Thief! You'll regret this...\""))));
        } else if (q.chest == null) {
            Util.show(p, "Lost Traveler", "<white>\"I lost my ring somewhere around here. It's in a chest I hid "
                    + "when the monsters came... can you find it?\"", List.of(new Util.Button("I'll look for it", pl -> hideRing(pl, q))));
        } else {
            Util.show(p, "Lost Traveler", "<white>\"Look for the beam of light, the chest should be there.\"", List.of());
        }
    }

    private void hideRing(Player p, Quest q) {
        List<?> dist = plugin.getConfig().getList("quests.ring.distance", List.of(20, 40));
        double min = ((Number) dist.get(0)).doubleValue(), max = ((Number) dist.get(1)).doubleValue();
        Location spot = Util.ring(p.getLocation(), min, max);
        if (spot == null) {
            p.sendMessage(RPGExplore.MM.deserialize("<gray>\"Hmm, I don't remember where... ask me again.\""));
            return;
        }
        Block b = spot.getBlock();
        b.setType(Material.CHEST);
        if (b.getState() instanceof Chest c) c.getBlockInventory().addItem(plugin.item("travelers_ring", 1));
        q.chest = b.getLocation();
        p.sendMessage(RPGExplore.MM.deserialize("<yellow>Find the chest under the beam of light."));
    }

    /** Light beam over the hidden ring chest, visible to the quest's player. */
    void beaconTick() {
        for (Quest q : byPlayer.values()) {
            Player p = Bukkit.getPlayer(q.player);
            if (q.chest == null || p == null || !p.getWorld().equals(q.chest.getWorld())) continue;
            Block b = q.chest.getBlock();
            if (b.getType() != Material.CHEST || (b.getState() instanceof Chest c && c.getBlockInventory().isEmpty())) continue;
            for (int y = 1; y < 20; y += 1) {
                p.spawnParticle(Particle.END_ROD, q.chest.clone().add(0.5, y, 0.5), 1, 0, 0, 0, 0);
            }
        }
    }

    private void reward(Player p, int copper, int ambrosia, int xp) {
        Util.give(p, plugin.rpg().wallet().item(Wallet.Currency.COPPER, copper));
        if (ambrosia > 0) Util.give(p, plugin.rpg().wallet().item(Wallet.Currency.AMBROSIA, ambrosia));
        plugin.rpg().players().addXp(p, xp);
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1f);
    }

    private void end(Quest q, String message) {
        byPlayer.remove(q.player);
        Entity npc = Bukkit.getEntity(q.npc);
        if (npc != null) {
            npc.getWorld().spawnParticle(Particle.CLOUD, npc.getLocation().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.02);
            npc.remove();
        }
        if (q.chest != null && q.chest.getBlock().getType() == Material.CHEST) {
            if (q.chest.getBlock().getState() instanceof Chest c) c.getBlockInventory().clear();
            q.chest.getBlock().setType(Material.AIR);
        }
        Player p = Bukkit.getPlayer(q.player);
        if (p != null) p.sendMessage(RPGExplore.MM.deserialize(message));
    }

    void cleanup() {
        for (Quest q : List.copyOf(byPlayer.values())) end(q, "<gray>The traveler moved on.");
    }

    private static int count(Player p, ItemStack like) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) if (it != null && it.isSimilar(like)) n += it.getAmount();
        return n;
    }

    private static void take(Player p, ItemStack like, int amount) {
        ItemStack[] contents = p.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && amount > 0; i++) {
            if (contents[i] == null || !contents[i].isSimilar(like)) continue;
            int t = Math.min(amount, contents[i].getAmount());
            contents[i].setAmount(contents[i].getAmount() - t);
            amount -= t;
        }
        p.getInventory().setStorageContents(contents);
    }

    private static String pretty(Material m) {
        String s = m.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
