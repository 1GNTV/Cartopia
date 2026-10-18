package com.cartopia.grow;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Stable Minecraft <-> geographic origin shared by every generated area of a world.
 *
 * The original Cartopia placed each selected area's center at the current player
 * position. That makes different real-world areas overlap. This registry pins a
 * single local tangent-plane projection to the first selected area's GPS center.
 *
 * Experimental multi-area support: already generated areas are protected by
 * rejecting overlapping selections. Partial-area updates/streaming are not
 * implemented by this class.
 */
public final class GeoWorldRegistry {
    private GeoWorldRegistry() {}

    private static final double LAT_METERS = 111_132.92;
    private static final double LON_METERS = 111_319.49079327358;
    private static final int MARGIN = 16;

    private static Path registry(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("cartopia-georef.json");
    }

    private static JsonObject load(MinecraftServer server) throws IOException {
        Path path = registry(server);
        return Files.exists(path)
                ? JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject()
                : null;
    }

    private static void save(MinecraftServer server, JsonObject data) throws IOException {
        Path file = registry(server);
        Files.createDirectories(file.getParent());
        Path pending = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(pending, new GsonBuilder().setPrettyPrinting().create().toJson(data),
                StandardCharsets.UTF_8);
        try {
            Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static JsonObject initial(JsonObject coords) {
        JsonObject center = coords.getAsJsonObject("center");
        JsonObject player = coords.has("player") && coords.get("player").isJsonObject()
                ? coords.getAsJsonObject("player") : new JsonObject();
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        root.addProperty("latitude", center.get("lat").getAsDouble());
        root.addProperty("longitude", center.get("lng").getAsDouble());
        root.addProperty("originX", player.has("x") ? Math.round(player.get("x").getAsDouble()) : 0L);
        root.addProperty("originZ", player.has("z") ? Math.round(player.get("z").getAsDouble()) : 0L);
        root.add("areas", new JsonArray());
        return root;
    }

    /**
     * Returns the actual area projection and prevents loading a selection over a
     * previously generated one. Called on the Minecraft server thread.
     */
    public static synchronized JsonObject prepare(MinecraftServer server, JsonObject coords)
            throws IOException {
        JsonObject root = load(server);
        boolean first = root == null;
        if (first) root = initial(coords);

        JsonObject center = coords.getAsJsonObject("center");
        double lat = center.get("lat").getAsDouble();
        double lng = center.get("lng").getAsDouble();
        double baseLat = root.get("latitude").getAsDouble();
        double baseLng = root.get("longitude").getAsDouble();

        double eastScale = LON_METERS * Math.cos(Math.toRadians(baseLat));
        if (Math.abs(eastScale) < 1) throw new IOException("The poles are not supported yet");
        long x = root.get("originX").getAsLong() + Math.round((lng - baseLng) * eastScale);
        long z = root.get("originZ").getAsLong() - Math.round((lat - baseLat) * LAT_METERS);

        if (Math.abs(x) > 29_000_000 || Math.abs(z) > 29_000_000) {
            throw new IOException("Area falls outside Minecraft world borders");
        }
        int side = coords.get("sizeMeters").getAsInt();
        if (side <= 0 || side > 5000) throw new IOException("Area size must be within 1..5000 metres");
        long minX = x - side / 2L;
        long maxX = x + (side + 1L) / 2L;
        long minZ = z - side / 2L;
        long maxZ = z + (side + 1L) / 2L;
        if (root.has("areas")) {
            for (var el : root.getAsJsonArray("areas")) {
                JsonObject a = el.getAsJsonObject();
                boolean collides = minX <= a.get("maxX").getAsLong() + MARGIN &&
                        maxX >= a.get("minX").getAsLong() - MARGIN &&
                        minZ <= a.get("maxZ").getAsLong() + MARGIN &&
                        maxZ >= a.get("minZ").getAsLong() - MARGIN;
                if (collides) {
                    throw new IOException("Area overlaps a previously generated zone (16-block safety margin). " +
                            "Choose an adjacent, non-overlapping area. No blocks were changed.");
                }
            }
        }
        JsonObject player = coords.has("player") && coords.get("player").isJsonObject()
                ? coords.getAsJsonObject("player") : new JsonObject();
        player.addProperty("x", x);
        player.addProperty("z", z);
        coords.add("player", player);
        if (first) save(server, root);
        return coords;
    }

    /** A read-only snapshot used by the web map to display generated zones. */
    public static synchronized JsonObject snapshot(MinecraftServer server) throws IOException {
        JsonObject root = load(server);
        if (root == null) {
            root = new JsonObject();
            root.add("areas", new JsonArray());
        }
        return root.deepCopy();
    }

    /**
     * Once a generation has fully succeeded, register the covered area to
     * prevent accidental later overwrites. Does not mark failed generations.
     */
    public static synchronized void complete(MinecraftServer server, JsonObject coords)
            throws IOException {
        JsonObject root = load(server);
        if (root == null) throw new IOException("Missing Cartopia geographic reference");
        int side = coords.get("sizeMeters").getAsInt();
        JsonObject player = coords.getAsJsonObject("player");
        long x = player.get("x").getAsLong(), z = player.get("z").getAsLong();
        JsonObject area = new JsonObject();
        area.addProperty("centerX", x);
        area.addProperty("centerZ", z);
        area.addProperty("minX", x - side / 2L);
        area.addProperty("maxX", x + (side + 1L) / 2L);
        area.addProperty("minZ", z - side / 2L);
        area.addProperty("maxZ", z + (side + 1L) / 2L);
        area.add("center", coords.getAsJsonObject("center").deepCopy());
        root.getAsJsonArray("areas").add(area);
        save(server, root);
    }

    /**
     * World-global elevation datum: keeping the same baseline across multiple
     * selections avoids a vertical step at each area's edge.
     */
    public static synchronized double elevationDatum(MinecraftServer server, double initial)
            throws IOException {
        JsonObject root = load(server);
        if (root == null) return initial; // legacy single-area generation
        if (!root.has("elevationDatum")) {
            root.addProperty("elevationDatum", initial);
            save(server, root);
        }
        return root.get("elevationDatum").getAsDouble();
    }
}
