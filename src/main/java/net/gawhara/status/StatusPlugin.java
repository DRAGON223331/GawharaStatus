package net.gawhara.status;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * GawharaStatus - بيعرض حالة السيرفر في روم ديسكورد (رسالة واحدة بتتعدل).
 * ملحوظة: البلجن جوه السيرفر، فلو السيرفر وقع فجأة (كراش) مش هيقدر يكتب "أوفلاين".
 * عشان كده الرسالة فيها "آخر تحديث" بيتحدث لوحده، ولو بقى قديم يبقى السيرفر واقف.
 */
public final class StatusPlugin extends JavaPlugin implements Listener, TabExecutor {

    enum State { ONLINE, RESTARTING, OFFLINE }

    record Payload(String url, String json) {
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GawharaStatus-IO");
        t.setDaemon(true);
        return t;
    });

    private File stateFile;
    private volatile String messageId = "";
    private volatile boolean restartRequested;
    private volatile boolean shuttingDown;
    private boolean updatePending;
    private boolean warnedNoUrl;
    private BukkitTask timer;

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        stateFile = new File(getDataFolder(), "state.yml");
        loadState();
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("serverstatus") != null) {
            getCommand("serverstatus").setExecutor(this);
            getCommand("serverstatus").setTabCompleter(this);
        }
        startTimer();
        // أول تحديث بعد ما السيرفر يخلّص تحميل
        Bukkit.getScheduler().runTaskLater(this, () -> push(State.ONLINE), 40L);
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        if (timer != null) {
            timer.cancel();
        }
        State st = (restartRequested || getConfig().getBoolean("treat-stop-as-restart", false))
                ? State.RESTARTING : State.OFFLINE;
        Payload last = buildPayload(st);
        // خلّص أي طلب شغال، وبعدين ابعت الحالة الأخيرة وانت مستني (السيرفر بيقفل)
        io.shutdown();
        try {
            io.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        send(last);
    }

    private void startTimer() {
        if (timer != null) {
            timer.cancel();
        }
        int sec = Math.max(15, getConfig().getInt("update-interval-seconds", 60));
        timer = Bukkit.getScheduler().runTaskTimer(this, () -> push(State.ONLINE), sec * 20L, sec * 20L);
    }

    private void loadState() {
        if (!stateFile.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(stateFile);
        messageId = y.getString("message-id", "");
    }

    private void saveState() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("message-id", messageId);
        try {
            getDataFolder().mkdirs();
            y.save(stateFile);
        } catch (IOException ex) {
            getLogger().warning("تعذر حفظ state.yml: " + ex.getMessage());
        }
    }

    // ---------------------------------------------------------------- events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        updateSoon();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        updateSoon();
    }

    @EventHandler(ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent e) {
        markCommand(e.getCommand());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        if (e.getPlayer().hasPermission("bukkit.command.restart")) {
            markCommand(e.getMessage());
        }
    }

    private void markCommand(String raw) {
        String c = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (c.startsWith("/")) {
            c = c.substring(1);
        }
        String label = c.split("\\s+")[0];
        int colon = label.indexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }
        if (label.equals("restart")) {
            restartRequested = true;
        }
    }

    /** تحديث بعد ثانيتين من دخول/خروج لاعب (بدون ما نزحم ديسكورد). */
    private void updateSoon() {
        if (shuttingDown || !isEnabled() || updatePending) {
            return;
        }
        updatePending = true;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            updatePending = false;
            if (!shuttingDown) {
                push(State.ONLINE);
            }
        }, 40L);
    }

    // ---------------------------------------------------------------- build + send

    private void push(State st) {
        Payload p = buildPayload(st); // على الـ main thread
        if (io.isShutdown()) {
            return;
        }
        io.execute(() -> send(p));
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> { }
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String field(String name, String value, boolean inline) {
        if (value == null || value.isBlank()) {
            value = "-";
        }
        if (value.length() > 1000) {
            value = value.substring(0, 1000) + "...";
        }
        return "{\"name\":\"" + jsonEscape(name) + "\",\"value\":\"" + jsonEscape(value)
                + "\",\"inline\":" + inline + "}";
    }

    private Payload buildPayload(State st) {
        FileConfiguration c = getConfig();
        String server = c.getString("server-name", "GawharaSMP");

        String title;
        String desc;
        String statusText;
        int color;
        if (st == State.ONLINE) {
            title = c.getString("texts.title-online", "🟢 {server} — أونلاين");
            desc = c.getString("texts.desc-online", "");
            statusText = c.getString("texts.status-online", "✅ شغال");
            color = 5763719;
        } else if (st == State.RESTARTING) {
            title = c.getString("texts.title-restarting", "🟠 {server} — بيترستر");
            desc = c.getString("texts.desc-restarting", "");
            statusText = c.getString("texts.status-restarting", "🔄 بيترستر");
            color = 15105570;
        } else {
            title = c.getString("texts.title-offline", "🔴 {server} — أوفلاين");
            desc = c.getString("texts.desc-offline", "");
            statusText = c.getString("texts.status-offline", "❌ مقفول");
            color = 15548997;
        }
        title = title.replace("{server}", server);

        boolean online = st == State.ONLINE;
        List<String> f = new ArrayList<>();
        f.add(field(c.getString("texts.f-status", "الحالة"), statusText, true));
        String players = online ? Bukkit.getOnlinePlayers().size() + " / " + Bukkit.getMaxPlayers() : "-";
        f.add(field(c.getString("texts.f-players", "اللاعبين"), "**" + players + "**", true));
        f.add(field(c.getString("texts.f-updated", "آخر تحديث"),
                "<t:" + (System.currentTimeMillis() / 1000L) + ":R>", true));
        if (online && c.getBoolean("show-tps", true)) {
            double tps = Math.min(20.0, Bukkit.getTPS()[0]);
            f.add(field("TPS", String.format(Locale.ROOT, "%.1f", tps), true));
        }
        if (online && c.getBoolean("show-version", true)) {
            f.add(field("Version", Bukkit.getMinecraftVersion(), true));
        }
        f.add(field(c.getString("texts.f-java", "Java"), "`" + c.getString("java-ip", "") + "`", false));
        f.add(field(c.getString("texts.f-bedrock", "Bedrock"),
                "`" + c.getString("bedrock-ip", "") + "` : `" + c.getInt("bedrock-port", 19132) + "`", false));
        if (online && c.getBoolean("show-player-names", false)) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                names.add(p.getName());
            }
            if (!names.isEmpty()) {
                f.add(field(c.getString("texts.f-names", "اللي جوا السيرفر"), String.join("، ", names), false));
            }
        }

        StringBuilder embed = new StringBuilder();
        embed.append("\"title\":\"").append(jsonEscape(title)).append("\",");
        if (!desc.isBlank()) {
            embed.append("\"description\":\"").append(jsonEscape(desc)).append("\",");
        }
        embed.append("\"color\":").append(color).append(',');
        embed.append("\"fields\":[").append(String.join(",", f)).append("],");
        String footer = c.getString("texts.footer", "");
        if (!footer.isBlank()) {
            embed.append("\"footer\":{\"text\":\"").append(jsonEscape(footer)).append("\"},");
        }
        embed.append("\"timestamp\":\"").append(Instant.now()).append('"');

        StringBuilder json = new StringBuilder("{");
        json.append("\"username\":\"").append(jsonEscape(c.getString("discord.username", "GawharaSMP Status")))
                .append("\",");
        String avatar = c.getString("discord.avatar-url", "");
        if (!avatar.isBlank()) {
            json.append("\"avatar_url\":\"").append(jsonEscape(avatar)).append("\",");
        }
        json.append("\"allowed_mentions\":{\"parse\":[]},");
        json.append("\"embeds\":[{").append(embed).append("}]}");

        String url = c.getString("discord.webhook-url", "").trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return new Payload(url, json.toString());
    }

    /** أول مفتاح "id" على مستوى الـ JSON الرئيسي (id الرسالة). */
    static String topLevelId(String json) {
        int depth = 0;
        int i = 0;
        int n = json.length();
        while (i < n) {
            char c = json.charAt(i);
            if (c == '"') {
                int j = i + 1;
                StringBuilder sb = new StringBuilder();
                while (j < n && json.charAt(j) != '"') {
                    if (json.charAt(j) == '\\') {
                        j++;
                    }
                    if (j < n) {
                        sb.append(json.charAt(j));
                    }
                    j++;
                }
                i = j + 1;
                if (depth == 1 && sb.toString().equals("id")) {
                    int k = i;
                    while (k < n && (json.charAt(k) == ' ' || json.charAt(k) == ':')) {
                        k++;
                    }
                    if (k < n && json.charAt(k) == '"') {
                        int e = json.indexOf('"', k + 1);
                        if (e > 0) {
                            return json.substring(k + 1, e);
                        }
                    }
                }
                continue;
            }
            if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            }
            i++;
        }
        return null;
    }

    private HttpRequest.Builder base(String uri) {
        return HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json; charset=utf-8");
    }

    private synchronized void send(Payload p) {
        if (p.url().isBlank() || !p.url().startsWith("https://")) {
            if (!warnedNoUrl) {
                warnedNoUrl = true;
                getLogger().warning("discord.webhook-url فاضي أو غلط في config.yml");
            }
            return;
        }
        try {
            if (!messageId.isEmpty()) {
                HttpRequest edit = base(p.url() + "/messages/" + messageId)
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(p.json(), StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> r = HTTP.send(edit, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() / 100 == 2) {
                    return;
                }
                if (r.statusCode() == 404) {
                    // الرسالة اتمسحت: هنبعت واحدة جديدة
                    messageId = "";
                    saveState();
                } else {
                    getLogger().warning("Discord edit فشل: HTTP " + r.statusCode() + " " + r.body());
                    return;
                }
            }
            HttpRequest post = base(p.url() + "?wait=true")
                    .POST(HttpRequest.BodyPublishers.ofString(p.json(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> r = HTTP.send(post, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 == 2) {
                String id = topLevelId(r.body());
                if (id != null) {
                    messageId = id;
                    saveState();
                }
            } else {
                getLogger().warning("Discord post فشل: HTTP " + r.statusCode() + " " + r.body());
            }
        } catch (IOException ex) {
            getLogger().warning("Discord فشل: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IllegalArgumentException ex) {
            getLogger().warning("discord.webhook-url غير صحيح: " + ex.getMessage());
        }
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("gawharastatus.admin")) {
            sender.sendMessage(Component.text("معندكش صلاحية."));
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "refresh";
        switch (sub) {
            case "reload" -> {
                reloadConfig();
                warnedNoUrl = false;
                startTimer();
                push(State.ONLINE);
                sender.sendMessage(Component.text("تم إعادة تحميل الإعدادات وتحديث الحالة."));
            }
            case "reset" -> {
                messageId = "";
                saveState();
                push(State.ONLINE);
                sender.sendMessage(Component.text("هيتبعت رسالة جديدة في الروم."));
            }
            default -> {
                push(State.ONLINE);
                sender.sendMessage(Component.text("تم تحديث الحالة."));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("refresh", "reload", "reset")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        }
        return out;
    }
}
