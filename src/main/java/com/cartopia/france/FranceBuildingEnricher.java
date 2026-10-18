package com.cartopia.france;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Optional France-only BD TOPO high-definition building enrichment.
 *
 * Geometry and real-world height measurements are supplied by the French IGN
 * WFS (BDTOPO_V3:batiment). Data is requested by bounding box; no nationwide
 * archive or API key is needed. The original OSM building is retained whenever
 * a match is ambiguous. The extraction is intentionally capped to prevent
 * truncation and to be gentle with the public API.
 *
 * Attribution: IGN BD TOPO, Licence Ouverte / Open Licence 2.0.
 */
public final class FranceBuildingEnricher {
    private static final String IGN_WFS = "https://data.geopf.fr/wfs/ows";
    private static final int MAX_SIDE_METERS = 1200;
    private static final int MAX_FEATURES = 2500;
    private static final int MAX_RESPONSE_BYTES = 12 * 1024 * 1024;
    private static final double MIN_MATCH_DISTANCE = 12.0;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private FranceBuildingEnricher() {}

    public static final class Stats {
        public int ignBuildings;
        public int matchingOsmBuildings;
        public int measuredHeights;
        public int replacedFootprints;
        public int materialHints;

        @Override public String toString() {
            return "IGN buildings=" + ignBuildings +
                    ", OSM matched=" + matchingOsmBuildings +
                    ", heights=" + measuredHeights +
                    ", refined footprints=" + replacedFootprints +
                    ", material hints=" + materialHints;
        }
    }

    /** Never fail the rest of the world's generation if IGN is unavailable. */
    public static void tryEnrich(JsonObject coords) {
        try {
            if (!eligible(coords)) return;
            JsonObject bbox = coords.getAsJsonObject("bbox");
            String url = requestUrl(
                    bbox.get("west").getAsDouble(), bbox.get("south").getAsDouble(),
                    bbox.get("east").getAsDouble(), bbox.get("north").getAsDouble());
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Cartopia-Forge/1.20.1 (IGN open data enrichment)")
                    .timeout(Duration.ofSeconds(20))
                    .GET().build();
            HttpResponse<InputStream> reply = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = reply.body()) {
                if (reply.statusCode() != 200) throw new IOException("HTTP " + reply.statusCode());
                byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) throw new IOException("IGN response too large");
                JsonObject fc = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                Stats stats = merge(coords, fc);
                System.out.println("[Cartopia/France-HD] " + stats);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[Cartopia/France-HD] Interrupted, keeping OpenStreetMap geometries");
        } catch (Exception e) {
            System.err.println("[Cartopia/France-HD] IGN unavailable: " + e.getMessage() +
                    "; falling back to unmodified OSM buildings.");
        }
    }

    static boolean eligible(JsonObject coords) {
        if (coords == null || !coords.has("center") || !coords.has("bbox") ||
                !coords.has("sizeMeters") || !coords.has("features")) return false;
        JsonObject center = coords.getAsJsonObject("center");
        double lat = center.get("lat").getAsDouble(), lon = center.get("lng").getAsDouble();
        int size = coords.get("sizeMeters").getAsInt();
        // Mainland France + Corsica. Overseas territories require their own bounds.
        return lat >= 41.2 && lat <= 51.3 && lon >= -5.3 && lon <= 9.8 &&
                size >= 10 && size <= MAX_SIDE_METERS;
    }

    static String requestUrl(double west, double south, double east, double north) {
        String bbox = String.format(Locale.ROOT, "%.8f,%.8f,%.8f,%.8f,EPSG:4326",
                west, south, east, north);
        return IGN_WFS +
                "?SERVICE=WFS&VERSION=2.0.0&REQUEST=GetFeature" +
                "&TYPENAMES=BDTOPO_V3%3Abatiment" +
                "&SRSNAME=EPSG%3A4326&OUTPUTFORMAT=application%2Fjson" +
                "&COUNT=" + MAX_FEATURES +
                "&BBOX=" + URLEncoder.encode(bbox, StandardCharsets.UTF_8);
    }

    static final class Shape {
        final List<double[]> points = new ArrayList<>();
        final JsonObject properties;
        boolean hasHoles;
        double lat, lon, area;

        Shape(JsonObject properties) { this.properties = properties; }
    }

    /** Pure merge function: testable using local GeoJSON fixtures, no network. */
    public static Stats merge(JsonObject coords, JsonObject geoJson) {
        Stats stats = new Stats();
        if (coords == null || geoJson == null || !geoJson.has("features") ||
                !geoJson.get("features").isJsonArray()) return stats;
        JsonArray features = geoJson.getAsJsonArray("features");
        // Refuse partial WFS result sets: missing buildings must not be mistaken
        // for authoritative absences.
        if (features.size() >= MAX_FEATURES ||
                (geoJson.has("numberMatched") && !geoJson.get("numberMatched").isJsonNull()
                        && geoJson.get("numberMatched").isJsonPrimitive()
                        && isTruncated(geoJson.get("numberMatched").getAsString(), features.size()))) {
            System.err.println("[Cartopia/France-HD] IGN feature result truncated; keeping OSM.");
            return stats;
        }

        final List<Shape> shapes = new ArrayList<>();
        for (JsonElement fe : features) {
            if (!fe.isJsonObject()) continue;
            JsonObject item = fe.getAsJsonObject();
            JsonObject geom = object(item, "geometry");
            JsonObject prop = object(item, "properties");
            if (geom == null || prop == null) continue;
            Shape shape = readShape(geom, prop);
            if (shape != null) shapes.add(shape);
        }
        stats.ignBuildings = shapes.size();

        JsonObject featureObject = object(coords, "features");
        if (featureObject == null || !featureObject.has("elements") ||
                !featureObject.get("elements").isJsonArray()) return stats;

        // Every OSM way retains its attributes, detailed parts, entrances and
        // relationships. Only confidently matched footprints get replaced.
        Set<Shape> used = new HashSet<>();
        for (JsonElement el : featureObject.getAsJsonArray("elements")) {
            if (!el.isJsonObject()) continue;
            JsonObject osm = el.getAsJsonObject();
            if (!"way".equals(string(osm, "type"))) continue;
            JsonObject tags = object(osm, "tags");
            if (tags == null || !tags.has("building") || tags.has("building:part")) continue;
            JsonArray outline = array(osm, "geometry");
            if (outline == null || outline.size() < 4) continue;
            Shape osmShape = fromOsm(outline);
            if (osmShape == null || osmShape.area < 4) continue;

            Shape match = null;
            double score = Double.POSITIVE_INFINITY;
            for (Shape candidate : shapes) {
                if (used.contains(candidate)) continue;
                double distance = distanceMeters(osmShape.lat, osmShape.lon,
                        candidate.lat, candidate.lon);
                if (distance > MIN_MATCH_DISTANCE) continue;
                double ratio = osmShape.area / Math.max(1, candidate.area);
                // Never map one OSM building to an entire city block.
                if (ratio < 0.55 || ratio > 1.8) continue;
                boolean inside = inPolygon(osmShape.lat, osmShape.lon, candidate.points);
                if (!inside && distance > 5) continue;
                double relativeAreaError = Math.abs(Math.log(ratio));
                double candidateScore = distance + 10 * relativeAreaError;
                if (candidateScore < score) {
                    match = candidate;
                    score = candidateScore;
                }
            }
            if (match == null) continue;
            used.add(match);
            stats.matchingOsmBuildings++;
            double ratio = osmShape.area / Math.max(1, match.area);

            // Safe alignment: no multipolygon holes, good footprint similarity.
            // Changes preserve the same OSM way ID and tags.
            if (!match.hasHoles && ratio > 0.72 && ratio < 1.38 &&
                    distanceMeters(osmShape.lat, osmShape.lon, match.lat, match.lon) < 8) {
                JsonArray refined = new JsonArray();
                for (double[] point : match.points) {
                    JsonObject coord = new JsonObject();
                    coord.addProperty("lat", point[0]);
                    coord.addProperty("lon", point[1]);
                    refined.add(coord);
                }
                osm.add("geometry", refined);
                tags.addProperty("cartopia:source:footprint", "IGN BD TOPO");
                stats.replacedFootprints++;
            }

            Double measured = measuredHeight(match.properties);
            if (measured != null) {
                // IGN is based on actual surveys and is preferable to a default.
                // Preserve explicit OSM height data when there is already a
                // potentially more detailed measurement from a mapper.
                if (!tags.has("height") && !tags.has("building:height")) {
                    tags.addProperty("height", String.format(Locale.ROOT, "%.1f", measured));
                    tags.addProperty("cartopia:source:height", "IGN BD TOPO");
                    stats.measuredHeights++;
                }
            }
            if (!tags.has("building:levels") &&
                    validNumber(match.properties, "nombre_d_etages", 1, 100) != null) {
                tags.addProperty("building:levels",
                        validNumber(match.properties, "nombre_d_etages", 1, 100).intValue());
            }
            stats.materialHints += addMaterials(tags, match.properties);
        }
        return stats;
    }

    private static boolean isTruncated(String count, int returned) {
        try { return Integer.parseInt(count) > returned; }
        catch (Exception ignored) { return false; } // e.g. "unknown"
    }

    private static JsonObject object(JsonObject root, String key) {
        return root != null && root.has(key) && root.get(key).isJsonObject()
                ? root.getAsJsonObject(key) : null;
    }
    private static JsonArray array(JsonObject root, String key) {
        return root != null && root.has(key) && root.get(key).isJsonArray()
                ? root.getAsJsonArray(key) : null;
    }
    private static String string(JsonObject root, String key) {
        return root != null && root.has(key) && root.get(key).isJsonPrimitive()
                ? root.get(key).getAsString() : null;
    }

    private static Shape readShape(JsonObject geom, JsonObject prop) {
        String type = string(geom, "type");
        JsonArray coords = array(geom, "coordinates");
        if (coords == null || type == null) return null;
        JsonArray polygons;
        if ("Polygon".equals(type)) {
            polygons = new JsonArray();
            polygons.add(coords);
        } else if ("MultiPolygon".equals(type)) {
            polygons = coords;
        } else return null;
        if (polygons.size() != 1 || !polygons.get(0).isJsonArray()) return null;
        JsonArray rings = polygons.get(0).getAsJsonArray();
        if (rings.isEmpty() || !rings.get(0).isJsonArray()) return null;
        JsonArray exterior = rings.get(0).getAsJsonArray();
        if (exterior.size() < 4 || exterior.size() > 2048) return null;
        Shape shape = new Shape(prop);
        shape.hasHoles = rings.size() > 1;
        for (JsonElement pair : exterior) {
            if (!pair.isJsonArray() || pair.getAsJsonArray().size() < 2) return null;
            JsonArray coordinates = pair.getAsJsonArray();
            shape.points.add(new double[]{coordinates.get(1).getAsDouble(),
                    coordinates.get(0).getAsDouble()});
        }
        return finishShape(shape);
    }

    private static Shape fromOsm(JsonArray points) {
        Shape shape = new Shape(new JsonObject());
        for (JsonElement p : points) {
            if (!p.isJsonObject()) return null;
            JsonObject o = p.getAsJsonObject();
            if (!o.has("lat") || !o.has("lon")) return null;
            shape.points.add(new double[]{o.get("lat").getAsDouble(), o.get("lon").getAsDouble()});
        }
        return finishShape(shape);
    }

    /** Area-weighted centroid of a small building using a local metre projection. */
    private static Shape finishShape(Shape shape) {
        if (shape.points.size() < 3) return null;
        final double lat0 = shape.points.get(0)[0], lon0 = shape.points.get(0)[1];
        final double mx = 111_320 * Math.cos(Math.toRadians(lat0)), my = 111_132.92;
        double sumCross = 0, sumX = 0, sumY = 0;
        for (int i = 0; i < shape.points.size(); i++) {
            double[] a = shape.points.get(i), b = shape.points.get((i + 1) % shape.points.size());
            double ax = (a[1] - lon0) * mx, ay = (a[0] - lat0) * my;
            double bx = (b[1] - lon0) * mx, by = (b[0] - lat0) * my;
            double cross = ax * by - bx * ay;
            sumCross += cross;
            sumX += (ax + bx) * cross;
            sumY += (ay + by) * cross;
        }
        shape.area = Math.abs(sumCross * 0.5);
        if (shape.area < 1 || Math.abs(sumCross) < 0.01) return null;
        shape.lon = lon0 + (sumX / (3 * sumCross)) / mx;
        shape.lat = lat0 + (sumY / (3 * sumCross)) / my;
        return shape;
    }

    private static boolean inPolygon(double lat, double lon, List<double[]> poly) {
        boolean inside = false;
        for (int i = 0, j = poly.size() - 1; i < poly.size(); j = i++) {
            double[] a = poly.get(i), b = poly.get(j);
            if ((a[0] > lat) != (b[0] > lat) &&
                    lon < (b[1] - a[1]) * (lat - a[0]) / (b[0] - a[0]) + a[1])
                inside = !inside;
        }
        return inside;
    }

    private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dy = (lat1 - lat2) * 111_132.92;
        double dx = (lon1 - lon2) * 111_320 * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        return Math.hypot(dx, dy);
    }

    private static Double validNumber(JsonObject root, String key, double low, double high) {
        try {
            if (root == null || !root.has(key) || root.get(key).isJsonNull()) return null;
            double v = root.get(key).getAsDouble();
            return Double.isFinite(v) && v >= low && v <= high ? v : null;
        } catch (Exception ignored) { return null; }
    }

    private static Double measuredHeight(JsonObject props) {
        Double roofMax = validNumber(props, "altitude_maximale_toit", -500, 10000);
        Double ground = validNumber(props, "altitude_minimale_sol", -500, 10000);
        if (roofMax != null && ground != null) {
            double height = roofMax - ground;
            if (height >= 2 && height <= 300) return height;
        }
        return validNumber(props, "hauteur", 2, 300);
    }

    private static int addMaterials(JsonObject tags, JsonObject properties) {
        int added = 0;
        if (!tags.has("building:material")) {
            String material = material(string(properties, "materiaux_des_murs"));
            if (material != null) {
                tags.addProperty("building:material", material);
                added++;
            }
        }
        if (!tags.has("roof:material")) {
            String material = material(string(properties, "materiaux_de_la_toiture"));
            if (material != null) {
                tags.addProperty("roof:material", material);
                added++;
            }
        }
        return added;
    }
    private static String material(String raw) {
        if (raw == null) return null;
        String s = raw.toLowerCase(Locale.ROOT);
        if (s.contains("brique")) return "brick";
        if (s.contains("ardoise")) return "slate";
        if (s.contains("tuile")) return "tile";
        if (s.contains("pierre")) return "stone";
        if (s.contains("bois")) return "wood";
        if (s.contains("béton") || s.contains("beton")) return "concrete";
        if (s.contains("métal") || s.contains("metal")) return "metal";
        return null;
    }
}
