package dev.jason.rpgcore;

import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Hosts plugins/RPGCore/RPGCore-pack.zip (plus the custom blocks from blocks.yml) over HTTP and sends it to players when they join.
 * Players get the normal "this server uses a resource pack" prompt.
 */
public class PackHost implements Listener {
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("rpgcore-pack".getBytes());
    private static final String PATH = "/RPGCore-pack.zip";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RPGCore plugin;
    private HttpServer server;
    private int serverPort = -1;
    private byte[] data;
    private byte[] hash;

    public PackHost(RPGCore plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("resource-pack.enabled", true);
    }

    /** Reads the pack, hashes it, and (re)starts the web server if the port changed. */
    public void load() {
        data = null;
        hash = null;
        if (!enabled()) {
            stop();
            return;
        }
        File file = new File(plugin.getDataFolder(), "RPGCore-pack.zip");
        if (!file.exists()) {
            plugin.getLogger().warning("No RPGCore-pack.zip in the plugin folder; players won't get the resource pack.");
            return;
        }
        try {
            data = withCustomBlocks(Files.readAllBytes(file.toPath()));
            hash = MessageDigest.getInstance("SHA-1").digest(data);
        } catch (Exception e) {
            plugin.getLogger().severe("Could not read RPGCore-pack.zip: " + e.getMessage());
            return;
        }
        int port = plugin.getConfig().getInt("resource-pack.port", 8163);
        if (server != null && port == serverPort) return;
        stop();
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext(PATH, exchange -> {
                byte[] body = data;
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                } else {
                    exchange.getResponseHeaders().set("Content-Type", "application/zip");
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                }
                exchange.close();
            });
            server.start();
            serverPort = port;
            plugin.getLogger().info("Hosting resource pack at " + url());
        } catch (IOException e) {
            server = null;
            plugin.getLogger().severe("Could not host the resource pack on port " + port + ": " + e.getMessage());
        }
    }

    /** Adds the custom block and item files (textures, models, note block look-up) to the base pack. */
    private byte[] withCustomBlocks(byte[] base) throws IOException {
        Map<String, byte[]> extra = new java.util.LinkedHashMap<>(plugin.blocks().packFiles());
        extra.putAll(plugin.items().packFiles());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(base));
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (e.isDirectory() || extra.containsKey(e.getName())) continue;
                zip.putNextEntry(new ZipEntry(e.getName()));
                in.transferTo(zip);
                zip.closeEntry();
            }
            for (Map.Entry<String, byte[]> e : extra.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    public void stop() {
        if (server != null) server.stop(0);
        server = null;
        serverPort = -1;
    }

    /** The address players download from. The hash on the end makes clients re-download after changes. */
    private String url() {
        return url(plugin.getConfig().getString("resource-pack.host", "auto"));
    }

    private String url(String host) {
        return "http://" + host + ":" + serverPort + PATH + "?v=" + HexFormat.of().formatHex(hash).substring(0, 12);
    }

    /** host: auto = the same address the player typed to join (LAN IP, public IP or domain). */
    private String url(Player p) {
        String host = plugin.getConfig().getString("resource-pack.host", "auto");
        if (host.equalsIgnoreCase("auto")) {
            host = p.getVirtualHost() != null ? p.getVirtualHost().getHostString() : "localhost";
        }
        return url(host);
    }

    public void send(Player p) {
        if (!enabled() || server == null || hash == null) return;
        p.setResourcePack(PACK_ID, url(p), hash,
                MM.deserialize(plugin.getConfig().getString("resource-pack.prompt", "This server uses a resource pack.")),
                plugin.getConfig().getBoolean("resource-pack.required", false));
    }

    public void sendAll() {
        Bukkit.getOnlinePlayers().forEach(this::send);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        send(e.getPlayer());
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent e) {
        if (!PACK_ID.equals(e.getID())) return;
        switch (e.getStatus()) {
            case DECLINED -> e.getPlayer().sendMessage(MM.deserialize(
                    "<gray>Resource pack declined. Menus will look plain. Type <white>/rpg pack</white> to get it."));
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD -> {
                plugin.getLogger().warning(e.getPlayer().getName() + " could not load the resource pack: " + e.getStatus());
                e.getPlayer().sendMessage(MM.deserialize("<red>The resource pack failed to load (" + e.getStatus() + ")."));
            }
            default -> {}
        }
    }
}
