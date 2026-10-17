package com.cartopia.builder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class BuildHttpServer {
    private static HttpServer httpServer;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final int PORT = 4567;               // как в Node
    private static final int MAX_PACKS = getEnvInt("CARTOPIA_MAX_PACKS", 10);

    private static volatile JsonObject lastPlayerCoords = null;
    private static volatile boolean welcomeSent = false;

    public static void start() {
        if (httpServer != null) return;
        try {
            httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);

            // Раздача статики из resources/public/**
            httpServer.createContext("/", BuildHttpServer::handleStatic);

            // Совместимость: /build (как было в твоём Java-коде)
            httpServer.createContext("/build", BuildHttpServer::handleBuildWithPaths);

            // Новый «полный» сценарий как в Node: /player и /save-coords
            httpServer.createContext("/player", BuildHttpServer::handlePlayer);
            httpServer.createContext("/save-coords", BuildHttpServer::handleSaveCoords);
            // Map overlay: show each region that has been successfully generated.
            httpServer.createContext("/areas", ex -> {
                if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                    sendText(ex, 405, "Method not allowed", "text/plain");
                    return;
                }
                MinecraftServer active = ServerLifecycleHooks.getCurrentServer();
                if (active == null) {
                    sendText(ex, 503, "Server not ready", "text/plain");
                    return;
                }
                try {
                    JsonObject snapshot = com.cartopia.grow.GeoWorldRegistry.snapshot(active);
                    sendText(ex, 200, snapshot.toString(), "application/json");
                } catch (IOException err) {
                    sendText(ex, 500, "Failed to read saved areas", "text/plain");
                }
            });

            // /health — UP, когда сервер Minecraft поднят
            httpServer.createContext("/health", ex -> {
                try {
                    MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
                    String status = (s != null) ? "UP" : "STARTING";
                    sendText(ex, 200, status, "text/plain");
                } finally { ex.close(); }
            });

            httpServer.createContext("/realtime", BuildHttpServer::handleRealtime);

            httpServer.setExecutor(Executors.newCachedThreadPool());
            httpServer.start();
            System.out.println("[Cartopia] Web server started on 127.0.0.1:" + PORT);

            // Разовая подсказка в чат, когда появится игрок
            Thread t = new Thread(() -> {
                while (true) {
                    try {
                        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
                        if (s != null && !welcomeSent && !s.getPlayerList().getPlayers().isEmpty()) {
                            String[] lines = {
                                "Cartopia is ready.",
                                "Press M to open the Cartopia menu."
                            };
                            s.execute(() -> { for (String line : lines) broadcast(s, line); });
                            welcomeSent = true;
                        }
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) { return; }
                    catch (Throwable ignore) {}
                }
            }, "CartopiaWelcome");
            t.setDaemon(true);
            t.start();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
            System.out.println("[Cartopia] Web server stopped");
        }
    }

    // ------------------------------------------------------------------------------------------------
    // 1) Раздача статики из /public внутри JAR
    // ------------------------------------------------------------------------------------------------
    private static void handleStatic(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        if (!method.equalsIgnoreCase("GET") && !method.equalsIgnoreCase("HEAD")) {
            ex.sendResponseHeaders(405, -1); ex.close(); return;
        }

        String raw = ex.getRequestURI().getPath();
        if (raw == null || raw.equals("/")) raw = "/index.html";
        String resourcePath = "/public" + raw;

        try (InputStream in = BuildHttpServer.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                ex.sendResponseHeaders(404, -1);
            } else {
                byte[] bytes = in.readAllBytes();
                Headers h = ex.getResponseHeaders();
                h.add("Content-Type", guessMime(resourcePath));
                ex.sendResponseHeaders(200, bytes.length);
                if (!method.equalsIgnoreCase("HEAD")) ex.getResponseBody().write(bytes);
            }
        } finally {
            ex.close();
        }
    }

    // ------------------------------------------------------------------------------------------------
    // 2) Совместимость: /build с путями как в твоём Java-обработчике
    // ------------------------------------------------------------------------------------------------
    private static void handleBuildWithPaths(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1); return;
        }

        String json = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String coordsPath, demPath, landcoverPath = null;
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            coordsPath    = root.get("coordsPath").getAsString();
            demPath       = root.get("demPath").getAsString();
            if (root.has("landcoverPath") && !root.get("landcoverPath").isJsonNull()) {
                landcoverPath = root.get("landcoverPath").getAsString();
            }
        } catch (Exception e) {
            e.printStackTrace();
            ex.sendResponseHeaders(400, -1);
            return;
        }

        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
        if (s == null) {
            sendText(ex, 503, "Server not ready", "text/plain"); return;
        }

        String finalLandcoverPath = landcoverPath;
        s.execute(() -> {
            try {
                broadcast(s, "Generation started...");
                ServerLevel level = s.overworld();
                CartopiaPipeline.run(
                    level,
                    Path.of(coordsPath).toFile(),
                    Path.of(demPath).toFile(),
                    finalLandcoverPath != null ? Path.of(finalLandcoverPath).toFile() : null
                );
                broadcast(s, "Generation finished.");
            } catch (Exception e) {
                e.printStackTrace();
                broadcast(s, "Generation error: " + e.getMessage());
            }
        });

        sendText(ex, 200, "OK", "text/plain");
    }

    // ------------------------------------------------------------------------------------------------
    // 3) /player — как в Node: просто запоминаем координаты игрока
    // ------------------------------------------------------------------------------------------------
    private static void handlePlayer(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1); return;
        }
        String json = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            lastPlayerCoords = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception ignore) { lastPlayerCoords = null; }
        sendText(ex, 200, "OK", "text/plain");
    }

    // ------------------------------------------------------------------------------------------------
    // 4) /save-coords — полный порт из Node: создаёт пакет, качает DEM, режет OLM (через GDAL при наличии)
    //    и сразу запускает CartopiaPipeline.run(...)
    // ------------------------------------------------------------------------------------------------
    private static void handleSaveCoords(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1); return;
        }
        String json = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        JsonObject data;
        JsonObject bbox;
        try {
            data = JsonParser.parseString(json).getAsJsonObject();
            bbox = data.getAsJsonObject("bbox");
        } catch (Exception e) {
            sendText(ex, 400, "Bad JSON", "text/plain"); return;
        }

        double west = bbox.get("west").getAsDouble();
        double east = bbox.get("east").getAsDouble();
        double south = bbox.get("south").getAsDouble();
        double north = bbox.get("north").getAsDouble();

        if (Math.abs(north - south) > 20 || Math.abs(east - west) > 20) {
            sendText(ex, 400, "Область слишком большая (макс 20x20° для GMRT)", "text/plain"); return;
        }

        // создаём папку-пакет
        Path packDir = makePackDir(west, east, south, north);
        Path coordsPath = packDir.resolve("coords.json");
        Path demPath    = packDir.resolve("dem.tif");
        Path olmPath    = packDir.resolve("olm_landcover.tif");

        // данные в coords.json (+ player, + служебные поля)
        if (lastPlayerCoords != null) data.add("player", lastPlayerCoords.deepCopy());
        JsonObject lcBounds = new JsonObject();
        lcBounds.addProperty("west", west);
        lcBounds.addProperty("east", east);
        lcBounds.addProperty("south", south);
        lcBounds.addProperty("north", north);
        data.add("landcoverBounds", lcBounds);

        JsonObject paths = new JsonObject();
        paths.addProperty("dem", demPath.toString());
        paths.addProperty("landcover", olmPath.toString());
        paths.addProperty("packDir", packDir.toString());
        data.add("paths", paths);

        Files.writeString(coordsPath, GSON.toJson(data), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("💾 coords.json записан: " + coordsPath);

        // качаем DEM (с повторами)
        try {
            downloadGmrtDemWithRetry(west, east, south, north, demPath, 10, 60_000);
        } catch (Exception e) {
            e.printStackTrace();
            sendText(ex, 500, "Ошибка при скачивании DEM", "text/plain"); return;
        }

        // режем OLM COG через GDAL (если есть)
        boolean landcoverOk = false;
        try {
            if (hasGdal()) {
                clipOlmCogWithGdal(west, east, south, north, olmPath);
                landcoverOk = Files.size(olmPath) > 0;
            } else {
                System.out.println("⚠️ GDAL не найден в PATH — пропускаю клип OLM.");
            }
        } catch (Exception e) {
            System.out.println("⚠️ Не удалось вырезать OLM COG: " + e.getMessage());
        }

        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
        if (s == null) {
            sendText(ex, 503, "Server not ready", "text/plain"); return;
        }

        final boolean lcOkFinal = landcoverOk;
        s.execute(() -> {
            try {
                broadcast(s, "Старт генерации…");
                ServerLevel level = s.overworld();
                // Persist a single world-wide geographic origin: newly selected
                // coordinates must not be mapped back on top of the old area.
                com.cartopia.grow.GeoWorldRegistry.prepare(s, data);
                Files.writeString(coordsPath, GSON.toJson(data), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                CartopiaPipeline.run(
                    level,
                    coordsPath.toFile(),
                    demPath.toFile(),
                    lcOkFinal ? olmPath.toFile() : null
                );
                com.cartopia.grow.GeoWorldRegistry.complete(s, data);
                broadcast(s, "Generation finished. Package: " + packDir.getFileName());
                trimPacksDir(MAX_PACKS); // чистка старых пакетов
            } catch (Exception e) {
                e.printStackTrace();
                broadcast(s, "Generation error: " + e.getMessage());
            }
        });

        sendText(ex, 200, "OK", "text/plain");
    }

    // ------------------------------------------------------------------------------------------------
    // ВСПОМОГАТЕЛЬНОЕ
    // ------------------------------------------------------------------------------------------------
    private static Path gameDir() {
        try {
            MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
            if (s != null) return s.getServerDirectory().toPath();
        } catch (Throwable ignore) {}
        return FMLPaths.GAMEDIR.get();
    }

    private static Path packsBaseDir() {
        return gameDir().resolve("cartopia").resolve("area-packs");
    }

    private static Path makePackDir(double west, double east, double south, double north) throws IOException {
        Path base = packsBaseDir();
        Files.createDirectories(base);
        String stamp = isoStamp();
        double lat = (south + north) / 2.0;
        double lon = (west + east) / 2.0;
        String name = String.format("area_%s_lat%.5f_lon%.5f", stamp, lat, lon);
        Path dir = base.resolve(name);
        Files.createDirectories(dir);
        return dir;
    }

    private static void trimPacksDir(int max) {
        try {
            Path base = packsBaseDir();
            Files.createDirectories(base);
            List<Path> dirs = Files.list(base)
                    .filter(p -> Files.isDirectory(p) && p.getFileName().toString().startsWith("area_"))
                    .sorted((a, b) -> {
                        try {
                            long ta = Files.getLastModifiedTime(a).toMillis();
                            long tb = Files.getLastModifiedTime(b).toMillis();
                            return Long.compare(tb, ta); // новые → старые
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .collect(Collectors.toList());
            for (int i = max; i < dirs.size(); i++) {
                Path d = dirs.get(i);
                try { deleteRecursively(d); System.out.println("🧹 удалил старый пакет: " + d); }
                catch (Exception e) { System.out.println("⚠️ не смог удалить " + d + " - " + e.getMessage()); }
            }
        } catch (Exception e) {
            System.out.println("⚠️ trimPacksDir ошибка: " + e.getMessage());
        }
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        Files.walk(p)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) {} });
    }

    private static boolean hasGdal() {
        try {
            Process p = new ProcessBuilder("gdal_translate", "--version")
                    .redirectErrorStream(true).start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Вырезаем OpenLandMap COG локально через gdal_translate (если он установлен). */
    private static void clipOlmCogWithGdal(double west, double east, double south, double north, Path outFile) throws Exception {
        String src = "https://s3.openlandmap.org/arco/lc_glc.fcs30d_c_30m_s_20220101_20221231_go_epsg.4326_v20231026.tif";
        List<String> args = List.of(
                "gdal_translate",
                "-q",
                "-of","GTiff",
                "-projwin_srs","EPSG:4326",
                // порядок: WEST NORTH EAST SOUTH
                "-projwin", String.valueOf(west), String.valueOf(north), String.valueOf(east), String.valueOf(south),
                "/vsicurl/" + src,
                outFile.toString(),
                "-r","near",
                "-co","TILED=YES",
                "-co","COMPRESS=DEFLATE",
                "-co","PREDICTOR=2"
        );
        Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
        try (InputStream is = p.getInputStream()) { is.readAllBytes(); }
        int code = p.waitFor();
        if (code != 0) throw new IOException("gdal_translate exit code " + code);
    }

    /** Скачиваем DEM из GMRT c повторами и проверками. */
    private static void downloadGmrtDemWithRetry(double west, double east, double south, double north,
                                                 Path outFile, int maxRetries, long delayMs) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

        String url = String.format(Locale.US,
                "https://www.gmrt.org/services/GridServer?minlatitude=%f&maxlatitude=%f&minlongitude=%f&maxlongitude=%f&format=geotiff&layer=topo",
                south, north, west, east);
        System.out.println("⬇️ GMRT DEM URL: " + url);

        int attempt = 0;
        while (attempt < maxRetries) {
            attempt++;
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", "Mozilla/5.0 (compatible; CartopiaBot/1.0)")
                        .build();
                HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() / 100 != 2) {
                    String body = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                    Files.writeString(outFile.resolveSibling(outFile.getFileName() + ".error.html"), body, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    throw new IOException("HTTP " + resp.statusCode());
                }

                // пишем в файл
                Files.createDirectories(outFile.getParent());
                try (InputStream in = resp.body(); OutputStream os = Files.newOutputStream(outFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    in.transferTo(os);
                }

                // проверка, что это TIFF, не HTML
                byte[] head = Files.readAllBytes(outFile);
                boolean isTiff = head.length >= 4 &&
                        ((head[0] == 0x49 && head[1] == 0x49 && head[2] == 0x2A && head[3] == 0x00) ||
                         (head[0] == 0x4D && head[1] == 0x4D && head[2] == 0x00 && head[3] == 0x2A));

                String first = new String(head, 0, Math.min(100, head.length), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                if (!isTiff || first.startsWith("<") || first.contains("html")) {
                    Files.write(outFile.resolveSibling(outFile.getFileName() + ".error.html"), head);
                    throw new IOException("DEM не похож на GeoTIFF (возможно HTML)");
                }

                System.out.println("✅ DEM успешно скачан: " + outFile);
                return;
            } catch (Exception e) {
                System.out.println("❌ Ошибка DEM, попытка " + attempt + "/" + maxRetries + ": " + e.getMessage());
                Thread.sleep(delayMs);
            }
        }
        throw new IOException("Не удалось скачать DEM после " + maxRetries + " попыток");
    }

    private static void sendText(HttpExchange ex, int code, String text, String mime) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", mime);
        ex.sendResponseHeaders(code, data.length);
        ex.getResponseBody().write(data);
        ex.close();
    }

    private static void broadcast(MinecraftServer s, String msg) {
        try {
            for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                p.sendSystemMessage(Component.literal("[Cartopia] " + msg));
            }
        } catch (Throwable ignore) {}
        System.out.println("[Cartopia] " + msg);
    }

    private static String guessMime(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html; charset=utf-8";
        if (p.endsWith(".js"))   return "application/javascript; charset=utf-8";
        if (p.endsWith(".css"))  return "text/css; charset=utf-8";
        if (p.endsWith(".json")) return "application/json; charset=utf-8";
        if (p.endsWith(".png"))  return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".svg"))  return "image/svg+xml";
        return "application/octet-stream";
    }

    private static int getEnvInt(String name, int def) {
        try { return Integer.parseInt(System.getenv().getOrDefault(name, String.valueOf(def))); }
        catch (Exception e) { return def; }
    }

    private static String isoStamp() {
        return Instant.now().toString().replace("-", "").replace(":", "").replace("T", "_").replaceAll("\\..+", "");
    }

    // ---- Новый эндпоинт /realtime ----
    // GET  -> {"enabled":true|false}
    // POST -> body: {"enabled":true|false}
    private static void handleRealtime(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
        if (s == null) { sendText(ex, 503, "{\"error\":\"Server not ready\"}", "application/json"); return; }

        try {
            if ("GET".equals(method)) {
                ServerLevel level = s.overworld();

                boolean has = com.cartopia.weather.WeatherTimeController.hasInstance(level);
                boolean enabled = com.cartopia.weather.WeatherTimeController.isEnabled(level);

                // До первой генерации инстанс ещё не создан — показываем дефолт «ВКЛ»
                if (!has) {
                    enabled = true;
                    sendText(ex, 200, "{\"enabled\":true,\"pending\":true}", "application/json");
                } else {
                    sendText(ex, 200, "{\"enabled\":"+enabled+"}", "application/json");
                }
                return;
            }
            if ("POST".equals(method)) {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                boolean enabled = JsonParser.parseString(body).getAsJsonObject().get("enabled").getAsBoolean();
                s.execute(() -> {
                    for (ServerLevel lvl : s.getAllLevels()) {
                        com.cartopia.weather.WeatherTimeController.setEnabled(lvl, enabled);
                    }
                    broadcast(s, "[Cartopia] Real time/weather: " + (enabled ? "ON" : "OFF"));
                });
                sendText(ex, 200, "{\"ok\":true}", "application/json");
                return;
            }
            ex.sendResponseHeaders(405, -1);
        } finally {
            ex.close();
        }
    }


}