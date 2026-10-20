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
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
    private String peakDate = "";
    private int peakCount;
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
        peakDate = y.getString("peak-date", "");
        peakCount = y.getInt("peak-count", 0);
    }

    private synchronized void saveState() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("message-id", messageId);
        y.set("peak-date", peakDate);
        y.set("peak-count", peakCount);
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

    private static String bar(int cur, int max) {
        int len = 10;
        int filled = max > 0 ? (int) Math.round(cur * (double) len / max) : 0;
        filled = Math.max(0, Math.min(len, filled));
        if (cur > 0 && filled == 0) {
            filled = 1;
        }
        return "▰".repeat(filled) + "▱".repeat(len - filled);
    }

    private static String tpsDot(double tps) {
        if (tps >= 19.0) {
            return "🟢";
        }
        return tps >= 15.0 ? "🟡" : "🔴";
    }

    private void updatePeak(int now) {
        String today = LocalDate.now().toString();
        if (!today.equals(peakDate)) {
            peakDate = today;
            peakCount = now;
            saveStateAsync();
        } else if (now > peakCount) {
            peakCount = now;
            saveStateAsync();
        }
    }

    private void saveStateAsync() {
        if (!io.isShutdown()) {
            io.execute(this::saveState);
        }
    }

    private Payload buildPayload(State st) {
        FileConfiguration c = getConfig();
        String server = c.getString("server-name", "GawharaSMP");
        long now = System.currentTimeMillis() / 1000L;

        String title;
        String desc;
        String statusText;
        int color;
        if (st == State.ONLINE) {
            title = c.getString("texts.title-online", "🟢 {server} — أونلاين");
            desc = c.getString("texts.desc-online", "");
            statusText = c.getString("texts.status-online", "✅ شغال");
            color = 0x57F287;
        } else if (st == State.RESTARTING) {
            title = c.getString("texts.title-restarting", "🟠 {server} — بيترستر");
            desc = c.getString("texts.desc-restarting", "");
            statusText = c.getString("texts.status-restarting", "🔄 بيترستر");
            color = 0xF5A623;
        } else {
            title = c.getString("texts.title-offline", "🔴 {server} — أوفلاين");
            desc = c.getString("texts.desc-offline", "");
            statusText = c.getString("texts.status-offline", "❌ مقفول");
            color = 0xED4245;
        }
        title = title.replace("{server}", server);

        boolean online = st == State.ONLINE;
        List<String> f = new ArrayList<>();
        f.add(field(c.getString("texts.f-status", "الحالة"), "**" + statusText + "**", true));

        int count = 0;
        int max = 0;
        if (online) {
            count = Bukkit.getOnlinePlayers().size();
            max = Bukkit.getMaxPlayers();
            updatePeak(count);
            f.add(field(c.getString("texts.f-players", "اللاعبين"),
                    "`" + bar(count, max) + "`\n**" + count + "** / " + max, true));
            if (c.getBoolean("show-tps", true)) {
                double tps = Math.min(20.0, Bukkit.getTPS()[0]);
                String perf = tpsDot(tps) + " **" + String.format(Locale.ROOT, "%.1f", tps) + "** TPS";
                if (c.getBoolean("show-mspt", true)) {
                    perf += "\n" + String.format(Locale.ROOT, "%.1f", Bukkit.getAverageTickTime()) + " ms/tick";
                }
                f.add(field(c.getString("texts.f-performance", "الأداء"), perf, true));
            }
            if (c.getBoolean("show-version", true)) {
                f.add(field(c.getString("texts.f-version", "الإصدار"), Bukkit.getMinecraftVersion(), true));
            }
            if (c.getBoolean("show-uptime", true)) {
                long startSec = ManagementFactory.getRuntimeMXBean().getStartTime() / 1000L;
                f.add(field(c.getString("texts.f-uptime", "شغال من"), "<t:" + startSec + ":R>", true));
            }
            if (c.getBoolean("show-peak", true)) {
                f.add(field(c.getString("texts.f-peak", "أعلى عدد النهارده"),
                        "📈 **" + Math.max(peakCount, count) + "**", true));
            }
        } else {
            f.add(field(c.getString("texts.f-last-seen", "آخر مرة شغال"), "<t:" + now + ":R>", true));
        }

        String javaIp = c.getString("java-ip", "");
        String backup = c.getString("java-backup-address", "");
        String javaVal = "```\n" + javaIp + "\n```";
        if (!backup.isBlank()) {
            javaVal += c.getString("texts.backup-label", "↪ عنوان بديل:") + " `" + backup + "`";
        }
        f.add(field(c.getString("texts.f-java", "☕ Java"), javaVal, false));
        if (c.getBoolean("bedrock-enabled", true)) {
            String bedVal = "```\n" + c.getString("texts.bedrock-address", "العنوان") + ": "
                    + c.getString("bedrock-ip", "") + "\n" + c.getString("texts.bedrock-port", "البورت") + ": "
                    + c.getInt("bedrock-port", 19132) + "\n```";
            f.add(field(c.getString("texts.f-bedrock", "📱 Bedrock"), bedVal, false));
        }
        if (online && c.getBoolean("show-player-names", false)) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                names.add(p.getName());
            }
            if (!names.isEmpty()) {
                f.add(field(c.getString("texts.f-names", "اللي جوا السيرفر"),
                        "```\n" + String.join("، ", names) + "\n```", false));
            }
        }

        String fullDesc = (desc.isBlank() ? "" : "**" + desc + "**\n")
                + c.getString("texts.updated-prefix", "🕒 آخر تحديث:") + " <t:" + now + ":R>";

        StringBuilder embed = new StringBuilder();
        embed.append("\"title\":\"").append(jsonEscape(title)).append("\",");
        embed.append("\"description\":\"").append(jsonEscape(fullDesc)).append("\",");
        embed.append("\"color\":").append(color).append(',');
        String thumb = c.getString("discord.thumbnail-url", "").trim();
        if (thumb.isEmpty() && c.getBoolean("discord.use-server-icon", true) && !javaIp.isBlank()) {
            thumb = "https://api.mcsrvstat.us/icon/" + javaIp;
        }
        if (!thumb.isEmpty()) {
            embed.append("\"thumbnail\":{\"url\":\"").append(jsonEscape(thumb)).append("\"},");
        }
        String image = c.getString("discord.image-url", "").trim();
        if (!image.isEmpty()) {
            embed.append("\"image\":{\"url\":\"").append(jsonEscape(image)).append("\"},");
        }
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

    /** بيبعت الطلب، ولو ديسكورد رد 429 (ضغط) بيستنى المدة المطلوبة ويعيد مرة واحدة. */
    private HttpResponse<String> exec(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 429) {
            long waitMs = 1500;
            String b = r.body() == null ? "" : r.body();
            int i = b.indexOf("\"retry_after\"");
            if (i >= 0) {
                int colon = b.indexOf(':', i);
                int e = colon + 1;
                while (colon >= 0 && e < b.length() && (Character.isDigit(b.charAt(e)) || b.charAt(e) == '.'
                        || b.charAt(e) == ' ')) {
                    e++;
                }
                try {
                    waitMs = (long) (Double.parseDouble(b.substring(colon + 1, e).trim()) * 1000);
                } catch (RuntimeException ignored) {
                    // نفضل على الافتراضي
                }
            }
            Thread.sleep(Math.max(500, Math.min(5000, waitMs)));
            r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        }
        return r;
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
                HttpResponse<String> r = exec(edit);
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
            HttpResponse<String> r = exec(post);
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
