package com.cartopia.france;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Offline fixtures: no IGN API calls needed for correctness checks. */
public class FranceBuildingEnricherTest {
    private static final String BBOX =
            "\"center\":{\"lat\":48.85665,\"lng\":2.35105}," +
            "\"sizeMeters\":400," +
            "\"bbox\":{\"west\":2.349,\"east\":2.354,\"south\":48.855,\"north\":48.858},";

    private static JsonObject area(boolean alreadyMeasured) {
        String height = alreadyMeasured ? "\"height\":\"22\"," : "";
        return JsonParser.parseString("{" + BBOX +
            "\"features\":{\"elements\":[{\"type\":\"way\",\"id\":123," +
            "\"tags\":{\"building\":\"yes\"," + height + "\"name\":\"Existing OSM building\"}," +
            "\"geometry\":[" +
            "{\"lat\":48.8566,\"lon\":2.351003}," +
            "{\"lat\":48.8566,\"lon\":2.351098}," +
            "{\"lat\":48.8567,\"lon\":2.351098}," +
            "{\"lat\":48.8567,\"lon\":2.351003}," +
            "{\"lat\":48.8566,\"lon\":2.351003}" +
            "]}]}}").getAsJsonObject();
    }

    private static JsonObject ign() {
        return JsonParser.parseString("{\"type\":\"FeatureCollection\",\"numberMatched\":1," +
            "\"features\":[{\"type\":\"Feature\",\"id\":\"batiment.1\"," +
            "\"properties\":{\"hauteur\":18.0,\"altitude_minimale_sol\":64.0," +
            "\"altitude_maximale_toit\":88.0,\"nombre_d_etages\":5," +
            "\"materiaux_des_murs\":\"Brique\"}," +
            "\"geometry\":{\"type\":\"MultiPolygon\",\"coordinates\":[[[" +
            "[2.351,48.8566,85],[2.3511,48.8566,85]," +
            "[2.3511,48.8567,85],[2.351,48.8567,85],[2.351,48.8566,85]" +
            "]]]}}]}").getAsJsonObject();
    }

    @Test void measuredGeometryAndHeightAreAppliedWithoutLosingTags() {
        JsonObject area = area(false);
        FranceBuildingEnricher.Stats stats = FranceBuildingEnricher.merge(area, ign());
        assertEquals(1, stats.ignBuildings);
        assertEquals(1, stats.matchingOsmBuildings);
        assertEquals(1, stats.replacedFootprints);
        assertEquals(1, stats.measuredHeights);

        JsonObject osm = area.getAsJsonObject("features").getAsJsonArray("elements")
                .get(0).getAsJsonObject();
        JsonObject tags = osm.getAsJsonObject("tags");
        assertEquals("123", osm.get("id").getAsString());
        assertEquals("Existing OSM building", tags.get("name").getAsString());
        assertEquals("24.0", tags.get("height").getAsString());
        assertEquals(5, tags.get("building:levels").getAsInt());
        assertEquals("brick", tags.get("building:material").getAsString());
        assertEquals("IGN BD TOPO", tags.get("cartopia:source:footprint").getAsString());
        assertEquals(2.351, osm.getAsJsonArray("geometry")
                .get(0).getAsJsonObject().get("lon").getAsDouble(), 1e-8);
    }

    @Test void neverClobberExistingOsmHeight() {
        JsonObject area = area(true);
        FranceBuildingEnricher.merge(area, ign());
        JsonObject tags = area.getAsJsonObject("features").getAsJsonArray("elements")
                .get(0).getAsJsonObject().getAsJsonObject("tags");
        assertEquals("22", tags.get("height").getAsString());
        assertFalse(tags.has("cartopia:source:height"));
    }

    @Test void truncatedIgnResponseDoesNotModifyAnything() {
        JsonObject area = area(false);
        JsonObject ign = ign();
        ign.addProperty("numberMatched", 99);
        FranceBuildingEnricher.Stats stats = FranceBuildingEnricher.merge(area, ign);
        assertEquals(0, stats.matchingOsmBuildings);
        JsonObject tags = area.getAsJsonObject("features").getAsJsonArray("elements")
                .get(0).getAsJsonObject().getAsJsonObject("tags");
        assertFalse(tags.has("height"));
    }

    @Test void franceOnlyAndBoundedArea() {
        assertTrue(FranceBuildingEnricher.eligible(area(false)));
        JsonObject area = area(false);
        area.getAsJsonObject("center").addProperty("lat", 35.0);
        assertFalse(FranceBuildingEnricher.eligible(area));
        assertTrue(FranceBuildingEnricher.requestUrl(2.34,48.85,2.35,48.86)
                .contains("BDTOPO_V3%3Abatiment"));
    }
}
