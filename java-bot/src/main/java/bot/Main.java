package bot;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public class Main {

    private static final String RSS_URL = "https://www.men.gov.ma/rss.xml";
    private static final String BASE_URL = "https://www.men.gov.ma";
    private static final Set<String> sentIds = Collections.synchronizedSet(new HashSet<>());
    private static final Path dataFile = Path.of("sent_ids.json");

    private static final List<Map<String, String>> PAGES = List.of(
        Map.of("name", "إعلانات إضافية", "url", BASE_URL + "/%D8%A5%D8%B9%D9%84%D8%A7%D9%86%D8%A7%D8%AA", "emoji", "\uD83D\uDCE2"),
        Map.of("name", "مباريات", "url", BASE_URL + "/%D9%85%D8%A8%D8%A7%D8%B1%D9%8A%D8%A7%D8%AA", "emoji", "\uD83C\uDFC6"),
        Map.of("name", "مذكرات", "url", BASE_URL + "/%D9%85%D8%B0%D9%83%D8%B1%D8%A7%D8%AA", "emoji", "\uD83D\uDCDD"),
        Map.of("name", "طلبات العروض", "url", BASE_URL + "/%D8%B7%D9%84%D8%A8%D8%A7%D8%AA-%D8%A7%D9%84%D8%B9%D8%B1%D9%88%D8%B6", "emoji", "\uD83D\uDCBC"),
        Map.of("name", "بلاغات", "url", BASE_URL + "/%D8%A8%D9%84%D8%A7%D8%BA%D8%A7%D8%AA", "emoji", "\uD83D\uDCCB")
    );

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private static String botToken;
    private static String chatId;

    private static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    public static void main(String[] args) {
        botToken = System.getenv("TELEGRAM_BOT_TOKEN");
        chatId = System.getenv("TELEGRAM_CHAT_ID");

        if (botToken == null || botToken.isEmpty()) {
            botToken = System.getProperty("TELEGRAM_BOT_TOKEN", "");
        }
        if (chatId == null || chatId.isEmpty()) {
            chatId = System.getProperty("TELEGRAM_CHAT_ID", "");
        }

        if (botToken.isEmpty() || chatId.isEmpty()) {
            loadEnvFile();
        }

        if (botToken.isEmpty()) {
            System.err.println("Set TELEGRAM_BOT_TOKEN env variable");
            return;
        }
        if (chatId.isEmpty()) {
            System.err.println("Set TELEGRAM_CHAT_ID env variable");
            return;
        }

        sentIds.addAll(loadSentIds());

        System.out.println("=== MEN.GOV.MA Telegram Bot (Java) ===");
        System.out.println("Monitoring RSS feed + " + PAGES.size() + " pages");
        System.out.println("Checking every 5 minutes...\n");

        checkAll();

        if (args.length > 0 && args[0].equals("--once")) {
            System.out.println("Single run mode. Exiting.");
            return;
        }

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(Main::checkAll, 5, 5, TimeUnit.MINUTES);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdownNow();
            saveSentIds();
        }));
    }

    private static void checkAll() {
        System.out.println("[" + now() + "] Checking for new items...");
        int count = 0;

        count += checkRss().size();

        for (Map<String, String> page : PAGES) {
            count += checkScrapePage(page).size();
        }

        if (count == 0) {
            System.out.println("[" + now() + "] No new items found");
        } else {
            System.out.println("[" + now() + "] Sent " + count + " new items");
        }

        saveSentIds();
    }

    private static List<String> checkRss() {
        List<String> messages = new ArrayList<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(RSS_URL))
                    .timeout(Duration.ofSeconds(30))
                    .GET().build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            SyndFeed feed = new SyndFeedInput().build(new XmlReader(response.body()));

            for (SyndEntry entry : feed.getEntries()) {
                String id = entry.getUri() != null ? entry.getUri() : entry.getLink();
                if (id == null || sentIds.contains(id)) continue;

                String title = entry.getTitle() != null ? entry.getTitle() : "بدون عنوان";
                String link = entry.getLink() != null ? entry.getLink() : "";
                String date = entry.getPublishedDate() != null ? entry.getPublishedDate().toString() : "";

                String summary = "";
                if (entry.getDescription() != null) {
                    summary = Jsoup.parse(entry.getDescription().getValue()).text();
                    if (summary.length() > 300) summary = summary.substring(0, 300) + "...";
                }

                String msg = "\uD83D\uDCF0 <b>مستجدات | وزارة التربية الوطنية</b>\n\n"
                        + "\uD83D\uDD39 <b>العنوان:</b> " + title + "\n"
                        + (date.isEmpty() ? "" : "\uD83D\uDCC5 <b>التاريخ:</b> " + date + "\n")
                        + (summary.isEmpty() ? "" : "\n\uD83D\uDCDD " + summary + "\n")
                        + (link.isEmpty() ? "" : "\n\uD83D\uDD17 <a href=\"" + link + "\">رابط الخبر</a>");

                sendTelegram(msg);
                sentIds.add(id);
                messages.add(msg);
                sleep(1000);
            }
        } catch (Exception e) {
            System.err.println("RSS check failed: " + e.getMessage());
        }
        return messages;
    }

    private static List<String> checkScrapePage(Map<String, String> pageInfo) {
        List<String> messages = new ArrayList<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(pageInfo.get("url")))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Document doc = Jsoup.parse(response.body());

            Elements rows = doc.select("div.table-row");
            if (rows.isEmpty()) {
                Element tbody = doc.selectFirst("table tbody");
                if (tbody != null) rows = tbody.select("tr");
            }
            if (rows.isEmpty()) return messages;

            for (int i = 0; i < Math.min(rows.size(), 10); i++) {
                Elements cells = rows.get(i).children();
                if (cells.size() < 3) continue;

                String date = cells.get(0).text().trim();
                String title = cells.get(1).text().trim();
                String link = "";

                Element docA = cells.get(2).selectFirst("a");
                if (docA != null) {
                    String href = docA.attr("href");
                    if (href.startsWith("/")) link = BASE_URL + href;
                    else if (href.startsWith("http")) link = href;
                }

                if (title.isEmpty()) continue;

                String id = pageInfo.get("name") + "_" + title;
                if (sentIds.contains(id)) continue;

                String msg = pageInfo.get("emoji") + " <b>" + pageInfo.get("name") + " | وزارة التربية الوطنية</b>\n\n"
                        + "\uD83D\uDD39 <b>العنوان:</b> " + title + "\n"
                        + (date.isEmpty() ? "" : "\uD83D\uDCC5 <b>التاريخ:</b> " + date + "\n")
                        + (link.isEmpty() ? "" : "\n\uD83D\uDD17 <a href=\"" + link + "\">رابط الخبر</a>");

                sendTelegram(msg);
                sentIds.add(id);
                messages.add(msg);
                sleep(1000);
            }
        } catch (Exception e) {
            System.err.println("Scrape failed for " + pageInfo.get("name") + ": " + e.getMessage());
        }
        return messages;
    }

    private static void sendTelegram(String text) {
        try {
            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
            String json = new Gson().toJson(Map.of(
                    "chat_id", chatId,
                    "text", text,
                    "parse_mode", "HTML",
                    "disable_web_page_preview", true
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                System.out.println("[" + now() + "] Message sent");
            } else {
                System.err.println("Telegram error: " + response.statusCode() + " " + response.body());
            }
        } catch (Exception e) {
            System.err.println("Failed to send: " + e.getMessage());
        }
    }

    private static Set<String> loadSentIds() {
        if (Files.exists(dataFile)) {
            try {
                String json = Files.readString(dataFile);
                return new HashSet<>(new Gson().fromJson(json, new TypeToken<List<String>>() {}.getType()));
            } catch (Exception ignored) {}
        }
        return new HashSet<>();
    }

    private static void saveSentIds() {
        try {
            Files.writeString(dataFile, new Gson().toJson(new ArrayList<>(sentIds)));
        } catch (Exception e) {
            System.err.println("Failed to save: " + e.getMessage());
        }
    }

    private static void loadEnvFile() {
        Path envFile = Path.of(".env");
        if (Files.exists(envFile)) {
            try {
                for (String line : Files.readAllLines(envFile)) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String key = line.substring(0, eq).trim();
                        String val = line.substring(eq + 1).trim();
                        if (key.equals("TELEGRAM_BOT_TOKEN") && botToken.isEmpty()) botToken = val;
                        if (key.equals("TELEGRAM_CHAT_ID") && chatId.isEmpty()) chatId = val;
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to read .env: " + e.getMessage());
            }
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
