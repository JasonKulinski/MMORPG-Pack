package dev.jason.rpgweapons;

import dev.jason.rpgcore.CustomItemManager.CustomItem;
import dev.jason.rpgcore.RPGCore;
import dev.jason.rpgcore.Stat;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Weapons on top of RPGCore. RPGCore does the heavy lifting:
 *  - rpg.items().register(...) makes the item, adds its texture to the resource pack, and puts its stats on it;
 *  - while a player holds it, RPGCore adds those stats to their class stats (damage, crit, life steal...);
 *  - rpg.players() gives mana (spendMana) for abilities.
 * This plugin only defines the weapons (weapons.yml) and what they do (abilities).
 */
public class RPGWeapons extends JavaPlugin implements Listener {
    record Ability(String type, double mana, double cooldown) {}

    private RPGCore rpg;
    private final Map<String, Ability> abilities = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        rpg = (RPGCore) Bukkit.getPluginManager().getPlugin("RPGCore");
        if (!new File(getDataFolder(), "weapons.yml").exists()) saveResource("weapons.yml", false);
        if (!new File(getDataFolder(), "textures/ember_blade.png").exists()) saveResource("textures/ember_blade.png", false);
        loadWeapons();
        getServer().getPluginManager().registerEvents(this, this);
    }

    private void loadWeapons() {
        abilities.clear();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "weapons.yml"));
        for (String id : yml.getKeys(false)) {
            ConfigurationSection s = yml.getConfigurationSection(id);
            Material base = s == null ? null : Material.matchMaterial(s.getString("base", "iron_sword"));
            if (base == null) {
                getLogger().warning("weapons.yml: " + id + " has an unknown base item.");
                continue;
            }
            Map<Stat, Double> stats = new LinkedHashMap<>();
            ConfigurationSection st = s.getConfigurationSection("stats");
            if (st != null) {
                for (String k : st.getKeys(false)) {
                    Stat stat = Stat.fromKey(k);
                    if (stat != null) stats.put(stat, st.getDouble(k));
                    else getLogger().warning("weapons.yml: unknown stat '" + k + "' on " + id);
                }
            }
            File texture = new File(getDataFolder(), "textures/" + s.getString("texture", id) + ".png");
            rpg.items().register(id, s.getString("name", id), base, texture, stats);
            ConfigurationSection a = s.getConfigurationSection("ability");
            if (a != null) abilities.put(id, new Ability(a.getString("type", ""), a.getDouble("mana"), a.getDouble("cooldown")));
        }
        rpg.packHost().load(); // rebuild the resource pack with our textures
        getLogger().info("Loaded " + yml.getKeys(false).size() + " weapons.");
    }

    // ------------------------------------------------------------------ abilities

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        CustomItem weapon = rpg.items().of(e.getItem());
        Ability ability = weapon == null ? null : abilities.get(weapon.id());
        if (ability == null) return;
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        if (now < cooldowns.getOrDefault(p.getUniqueId(), 0L)) return;
        if (!rpg.players().spendMana(p, ability.mana())) {
            p.sendActionBar(net.kyori.adventure.text.Component.text("Not enough mana",
                    net.kyori.adventure.text.format.NamedTextColor.BLUE));
            return;
        }
        cooldowns.put(p.getUniqueId(), now + (long) (ability.cooldown() * 1000));
        switch (ability.type()) {
            case "dash" -> dash(p);
            default -> getLogger().warning("Unknown ability type: " + ability.type());
        }
    }

    private void dash(Player p) {
        Vector dir = p.getLocation().getDirection().setY(0).normalize().multiply(1.6).setY(0.3);
        p.setVelocity(dir);
        p.getWorld().spawnParticle(Particle.FLAME, p.getLocation().add(0, 1, 0), 30, 0.3, 0.5, 0.3, 0.05);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 0.8f, 1.4f);
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length >= 2 && a[0].equalsIgnoreCase("give")) {
            CustomItem ci = rpg.items().get(a[1]);
            Player target = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : (s instanceof Player p ? p : null);
            if (ci == null || target == null) {
                s.sendMessage("Unknown weapon or player.");
                return true;
            }
            target.getInventory().addItem(rpg.items().item(ci, 1));
            s.sendMessage("Gave " + a[1] + " to " + target.getName() + ".");
            return true;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "weapons.yml"));
        s.sendMessage("Weapons: " + String.join(", ", yml.getKeys(false)));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 1) return List.of("list", "give");
        if (a.length == 2 && a[0].equalsIgnoreCase("give")) {
            return List.copyOf(YamlConfiguration.loadConfiguration(new File(getDataFolder(), "weapons.yml")).getKeys(false));
        }
        return null;
    }
}
