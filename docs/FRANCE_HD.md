# Cartopia France-HD — experimental buildings pass

> **Status: built and tested offline, not yet validated visually in Minecraft.**

## What this branch adds

* **IGN BD TOPO WFS** requests for small areas in mainland France (and Corsica), using
  `BDTOPO_V3:batiment` from the official French Géoplateforme.
* **Measured building heights** when OSM has no explicit height. When both an official
  measured height and a floor count are available, the builder now uses the measured
  metres for the outside geometry rather than assuming every storey is four metres.
* **Conservative footprint refinement**: the IGN polygon replaces an existing OSM
  building outline only if the two footprints have similar surface area, very close
  centroids, and there is no multipolygon hole. Detailed OSM building parts,
  entrances and other original tags are preserved.
* **Material hints**: recognized IGN wall and roof material fields populate otherwise
  missing OSM material tags.
* **Source attribution** on enriched buildings using `cartopia:source:height` and
  `cartopia:source:footprint` tags.
* **Graceful fallback**: if the IGN service is slow/unavailable, the original OSM data
  is used. No API key or nationwide download is required.

## How to test

1. Use **Minecraft Java 1.20.1 + Forge 47.4.0** in a **new Superflat world**.
2. Open Cartopia's existing map (M).
3. Select a **200–400 m** zone in metropolitan France, for example a mixed block
   of buildings in Paris or Villejuif.
4. Start generation and wait for `Generation finished`. Generation still follows
   the original Cartopia whole-selected-zone pipeline, **not player-driven streaming**.
5. Review the Minecraft/logs/latest.log output for a line starting:
   `[Cartopia/France-HD] IGN buildings=...`
6. Compare the generated results with official maps and screenshots. The match count
   may be less than the number of buildings because ambiguous matches are skipped.

The IGN request is automatically disabled for larger areas (>1,200 m), with a
2,500-feature and 12 MiB cap to protect the public service and reduce memory usage.

## Sources / licenses

* IGN BD TOPO, French Géoplateforme WFS:
  https://data.geopf.fr/wfs/ows
* Documentation: https://cartes.gouv.fr/aide/fr/guides-utilisateur/utiliser-les-services-de-la-geoplateforme/diffusion/wfs/
* BD TOPO data terms: French Licence Ouverte 2.0 (retain IGN attribution).

Map and other OpenStreetMap-derived input remains subject to the ODbL obligations.

## What's **not** in this build

* IGN LiDAR HD surface/roof reconstruction, BD TOPO features without an OSM match,
  detailed Paris building-volume layers, or photogrammetry.
* Panoramax image analysis, automatic window/door placement from photos, or an AI
  iterative builder.
* Generation based on player movement, automatic expansion of adjacent zones, or
  regeneration over protected user builds.

The previously experimental multi-zone system is present but should still be tested
only on disposable worlds. It refuses overlapping zones rather than transparently
repairing/editing them.

## Engineering roadmap

1. Verify IGN polygon matching in several real Paris suburbs and tune thresholds.
2. Add a terrain and roof geometry provider using IGN LiDAR HD **only for small tiles**,
   with height datum consistency between zones.
3. Add Panoramax images under compatible licenses, with explicit per-facade
   observations and confidence values; do not use Google Street View as an
   unauthorized training/data extraction source.
4. Drive deterministic facade blueprints from the measured 3D geometry,
   photo observations, and the existing Cartopia block/material library.
5. Move the generation pipeline to chunk-safe queues with persistent area protection,
   safe cross-boundary features, and a strict per-tick block placement budget.
