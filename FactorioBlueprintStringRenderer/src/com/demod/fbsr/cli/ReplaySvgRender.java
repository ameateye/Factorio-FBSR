package com.demod.fbsr.cli;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.json.JSONArray;
import org.json.JSONObject;

import com.demod.dcba.CommandReporting;
import com.demod.factorio.fakelua.LuaValue;
import com.demod.factorio.prototype.RecipePrototype;
import com.demod.fbsr.AtlasRef;
import com.demod.fbsr.EntityRendererFactory;
import com.demod.fbsr.FBSR;
import com.demod.fbsr.FactorioManager;
import com.demod.fbsr.Layer;
import com.demod.fbsr.ModdingResolver;
import com.demod.fbsr.Profile;
import com.demod.fbsr.WorldMap;
import com.demod.fbsr.bs.BSBlueprint;
import com.demod.fbsr.bs.BSEntity;
import com.demod.fbsr.bs.BSMetaEntity;
import com.demod.fbsr.def.IconDef;
import com.demod.fbsr.entity.ErrorRendering;
import com.demod.fbsr.map.MapBounded;
import com.demod.fbsr.map.MapEntity;
import com.demod.fbsr.map.MapRect;
import com.demod.fbsr.map.MapRenderable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Multimaps;

// Per-entity SVG export: parses + populates the world map exactly like
// ImageRenderer, then renders each MapEntity to its own small PNG and emits
// an SVG with one <use> per entity (sprites deduped by image-bytes hash so
// 13K entities don't blow up file size).
//
// Also writes a sibling <output>.manifest.json with the same atlas + entity
// references in machine-friendly form, for the React map player.
//
// Coordinates are in tile units (1 unit = 1 tile). Each <use> carries
// data-name / data-px / data-py / data-en attributes for downstream
// inspection in the dashboard.
//
// Usage: ReplaySvgRender <input.json> <output.svg>
public class ReplaySvgRender {

    // Mirrors the constants CraftingMachineRendering / FurnaceRendering pass
    // to MapIcon: 1.4-tile icon with a 0.1-tile rounded background, total 1.6.
    private static final double RECIPE_ICON_SIZE_TILES   = 1.4;
    private static final double RECIPE_ICON_BORDER_TILES = 0.1;
    private static final double RECIPE_ICON_TOTAL_TILES  = RECIPE_ICON_SIZE_TILES + RECIPE_ICON_BORDER_TILES * 2;
    private static final Color  RECIPE_ICON_BG           = new Color(0, 0, 0, 180);

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: ReplaySvgRender <input.json> <output.svg>");
            System.exit(1);
        }
        File input = new File(args[0]);
        File output = new File(args[1]);
        File manifestOut = new File(output.getAbsolutePath().replaceFirst("\\.svg$", "") + ".manifest.json");

        String text = Files.readString(input.toPath());
        JSONObject json = new JSONObject(text);
        // Optional recipes list lives at the top level (not inside `blueprint`)
        // — bp-export-real puts every recipe-name a machine ever ran into
        // there so we can render a per-recipe icon sprite atlas. Read before
        // unwrapping so it survives.
        JSONArray recipesArray = json.optJSONArray("recipes");
        if (json.has("blueprint")) {
            json = json.getJSONObject("blueprint");
        }

        Profile vanilla = Profile.vanilla();
        if (!vanilla.isReady()) {
            System.err.println("vanilla profile not ready — run 'build vanilla' first");
            System.exit(2);
        }
        if (!FBSR.load(ImmutableList.of(vanilla))) {
            System.err.println("FBSR.load failed");
            System.exit(3);
        }

        try {
            BSBlueprint blueprint = new BSBlueprint(json);
            CommandReporting reporting = new CommandReporting(null, null, null);

            FactorioManager fm = FBSR.getFactorioManager();
            ModdingResolver resolver = ModdingResolver.byBlueprintBiases(fm, blueprint);

            // === parseBlueprint (mirrors ImageRenderer) ===
            List<MapEntity> mapEntities = new ArrayList<>();
            for (BSMetaEntity meta : blueprint.entities) {
                EntityRendererFactory factory = resolver.resolveFactoryEntityName(meta.name);
                BSEntity entity;
                try {
                    if (meta.isLegacy()) {
                        entity = factory.parseEntityLegacy(meta.getLegacy());
                    } else {
                        entity = factory.parseEntity(meta.getJson());
                    }
                } catch (Exception e) {
                    meta.setParseException(Optional.of(e));
                    entity = meta;
                }
                if (meta.getParseException().isPresent()) {
                    factory = new ErrorRendering();
                    reporting.addException(meta.getParseException().get(),
                            entity.name + " " + entity.entityNumber);
                }
                mapEntities.add(new MapEntity(entity, factory, resolver));
            }

            mapEntities.sort(Comparator.comparing((MapEntity r) -> r.getPosition().getYFP())
                    .thenComparing(r -> r.getPosition().getXFP()));

            // === populateMap (entity-level only — belt-readers / transit
            // logistics are private statics on FBSR; sprites should be
            // close-enough without them for the close-set we render). ===
            WorldMap map = new WorldMap();
            map.setResolver(resolver);
            // altMode is OFF: we don't want recipe icons baked into the
            // per-entity sprite. Recipes change over time, the entity sprite
            // is one image, so dynamic overlay is the only way to animate
            // recipe changes. Recipe icons are rendered separately below
            // (sid prefix "r:") and the React side overlays them at runtime.
            map.setAltMode(false);
            for (MapEntity e : mapEntities) {
                try { e.getFactory().populateWorldMap(map, e); } catch (Exception ex) {
                    reporting.addException(ex, e.fromBlueprint().name);
                }
            }
            for (MapEntity e : mapEntities) {
                try { e.getFactory().populateLogistics(map, e); } catch (Exception ex) {
                    reporting.addException(ex, e.fromBlueprint().name);
                }
            }

            // === per-entity render pass ===
            StringBuilder defs = new StringBuilder();
            StringBuilder uses = new StringBuilder();
            // Parallel manifest data (atlas + entities) for the React player
            Map<String, double[]> spriteSize = new LinkedHashMap<>();    // id → [w, h]
            Map<String, String>   spriteData = new LinkedHashMap<>();    // id → base64 PNG
            List<JSONObject>      manifestEntities = new ArrayList<>();
            Map<String, String> hashToId = new LinkedHashMap<>();
            int[] nextId = {0};

            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            int rendered = 0;
            int skipped = 0;
            long startMs = System.currentTimeMillis();

            // Granular layering: each entity emits one sprite *per FBSR Layer
            // it touches* (instead of one fused sprite for the whole entity).
            // The manifest carries the Layer ordinal on every entry; map-prep
            // sorts globally by (L, py, px) for the React DOM, matching FBSR's
            // own draw order. An inserter typically becomes 3–4 sprites
            // (OBJECT base, HIGHER_OBJECT_UNDER arm, INSERTER_INDICATORS,
            // SHADOW_BUFFER); a belt stays a single TRANSPORT_BELT sprite.
            int renderablesEmitted = 0;
            for (MapEntity entity : mapEntities) {
                ListMultimap<Layer, MapRenderable> bucket = MultimapBuilder.enumKeys(Layer.class)
                        .arrayListValues().build();
                Consumer<MapRenderable> register = r -> bucket.put(r.getLayer(), r);
                try {
                    entity.getFactory().createRenderers(register, map, entity);
                } catch (Exception ex) {
                    reporting.addException(ex, entity.fromBlueprint().name);
                    skipped++;
                    continue;
                }
                if (bucket.isEmpty()) { skipped++; continue; }

                BSEntity be = entity.fromBlueprint();
                boolean anyEmitted = false;

                // Iterate layers in enum (= draw) order; one sprite per layer.
                for (Map.Entry<Layer, List<MapRenderable>> le : Multimaps.asMap(bucket).entrySet()) {
                    Layer layer = le.getKey();
                    List<MapRenderable> layerRenderables = le.getValue();

                    List<MapRect> rects = layerRenderables.stream()
                            .filter(r -> r instanceof MapBounded)
                            .map(r -> ((MapBounded) r).getBounds())
                            .collect(Collectors.toList());
                    if (rects.isEmpty()) continue;

                    MapRect bounds = MapRect.combineAll(rects);
                    double pad = 0.05;
                    double bx = bounds.getX() - pad;
                    double by = bounds.getY() - pad;
                    double bw = bounds.getWidth() + 2 * pad;
                    double bh = bounds.getHeight() + 2 * pad;

                    int pxW = (int) Math.ceil(bw * FBSR.TILE_SIZE);
                    int pxH = (int) Math.ceil(bh * FBSR.TILE_SIZE);
                    if (pxW <= 0 || pxH <= 0) continue;

                    BufferedImage img = new BufferedImage(pxW, pxH, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = img.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                    // world → image: scale by TILE_SIZE then translate so (bx,by) maps to (0,0)
                    g.scale(FBSR.TILE_SIZE, FBSR.TILE_SIZE);
                    g.translate(-bx, -by);

                    // Shadow layer is composited at 50% alpha to match
                    // ImageRenderer's separate shadow pass. We bake that into
                    // the sprite so the React side just draws as-is.
                    Composite saved = null;
                    if (layer == Layer.SHADOW_BUFFER) {
                        saved = g.getComposite();
                        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
                    }
                    for (MapRenderable r : layerRenderables) {
                        try { r.render(g); } catch (Exception ex) {
                            reporting.addException(ex, be.name);
                        }
                    }
                    if (saved != null) g.setComposite(saved);
                    g.dispose();

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(img, "PNG", baos);
                    byte[] bytes = baos.toByteArray();
                    // dedup key = content + dimensions; layer is a position
                    // attribute on the manifest entry, not part of the sprite.
                    String key = sha1(bytes) + "|" + (int) Math.round(bw * 1000) + "x" + (int) Math.round(bh * 1000);
                    String id = hashToId.get(key);
                    if (id == null) {
                        id = "s" + (nextId[0]++);
                        hashToId.put(key, id);
                        String b64 = Base64.getEncoder().encodeToString(bytes);
                        defs.append("<symbol id=\"").append(id).append("\" overflow=\"visible\">")
                            .append("<image href=\"data:image/png;base64,").append(b64).append("\" ")
                            .append("width=\"").append(fmt(bw)).append("\" height=\"").append(fmt(bh)).append("\"")
                            .append(" preserveAspectRatio=\"none\"/></symbol>\n");
                        spriteSize.put(id, new double[]{bw, bh});
                        spriteData.put(id, b64);
                    }

                    uses.append("<use href=\"#").append(id).append("\"")
                        .append(" x=\"").append(fmt(bx)).append("\"")
                        .append(" y=\"").append(fmt(by)).append("\"")
                        .append(" data-name=\"").append(be.name).append("\"")
                        .append(" data-px=\"").append(fmt(be.position.x)).append("\"")
                        .append(" data-py=\"").append(fmt(be.position.y)).append("\"")
                        .append(" data-en=\"").append(be.entityNumber).append("\"")
                        .append(" data-l=\"").append(layer.ordinal()).append("\"/>\n");

                    JSONObject ej = new JSONObject();
                    ej.put("en", be.entityNumber);
                    ej.put("name", be.name);
                    ej.put("px", round(be.position.x, 4));
                    ej.put("py", round(be.position.y, 4));
                    ej.put("ox", round(bx, 4));
                    ej.put("oy", round(by, 4));
                    ej.put("sid", id);
                    ej.put("L", layer.ordinal());
                    manifestEntities.add(ej);

                    if (bx < minX) minX = bx;
                    if (by < minY) minY = by;
                    if (bx + bw > maxX) maxX = bx + bw;
                    if (by + bh > maxY) maxY = by + bh;
                    renderablesEmitted++;
                    anyEmitted = true;
                }

                if (anyEmitted) rendered++;
                else skipped++;
            }

            if (rendered == 0) {
                System.err.println("no entities rendered");
                System.exit(4);
            }

            // === recipe-icon sprite pass ===
            // Each unique recipe-name in the run becomes one sprite (sid =
            // "r:<name>"). Rendered at FBSR's CraftingMachineRendering /
            // FurnaceRendering parity: 1.4-tile icon with a 0.1-tile dark
            // rounded background, total 1.6×1.6 tiles. The React side
            // positions the sprite so its center sits 0.3 tiles above the
            // entity center — same offset FBSR uses internally.
            int recipeRendered = 0;
            int recipeSkipped = 0;
            if (recipesArray != null) {
                for (int i = 0; i < recipesArray.length(); i++) {
                    String recipeName = recipesArray.getString(i);
                    String sid = "r:" + recipeName;
                    if (spriteData.containsKey(sid)) continue;
                    Optional<IconDef> icon = resolveRecipeIcon(resolver, recipeName);
                    if (icon.isEmpty()) { recipeSkipped++; continue; }
                    BufferedImage iconImg = renderRecipeIconFbsrStyle(icon.get());
                    if (iconImg == null) { recipeSkipped++; continue; }
                    ByteArrayOutputStream rbaos = new ByteArrayOutputStream();
                    ImageIO.write(iconImg, "PNG", rbaos);
                    String b64 = Base64.getEncoder().encodeToString(rbaos.toByteArray());
                    spriteSize.put(sid, new double[]{RECIPE_ICON_TOTAL_TILES, RECIPE_ICON_TOTAL_TILES});
                    spriteData.put(sid, b64);
                    recipeRendered++;
                }
            }

            double vbW = maxX - minX, vbH = maxY - minY;

            output.getAbsoluteFile().getParentFile().mkdirs();
            try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(output)))) {
                pw.printf(Locale.ROOT,
                        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"%s %s %s %s\">%n",
                        fmt(minX), fmt(minY), fmt(vbW), fmt(vbH));
                pw.println("<defs>");
                pw.print(defs);
                pw.println("</defs>");
                pw.print(uses);
                pw.println("</svg>");
            }

            // === manifest.json ===
            JSONObject sprites = new JSONObject();
            for (Map.Entry<String, String> e : spriteData.entrySet()) {
                JSONObject s = new JSONObject();
                double[] wh = spriteSize.get(e.getKey());
                s.put("w", round(wh[0], 4));
                s.put("h", round(wh[1], 4));
                s.put("data", e.getValue());
                sprites.put(e.getKey(), s);
            }
            JSONObject manifest = new JSONObject();
            JSONArray vb = new JSONArray();
            vb.put(round(minX, 4)); vb.put(round(minY, 4));
            vb.put(round(vbW, 4));  vb.put(round(vbH, 4));
            manifest.put("viewBox", vb);
            manifest.put("sprites", sprites);
            manifest.put("entities", new JSONArray(manifestEntities));
            try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(manifestOut)))) {
                pw.write(manifest.toString());
            }

            long ms = System.currentTimeMillis() - startMs;
            System.out.println("wrote " + output.getAbsolutePath());
            System.out.println("wrote " + manifestOut.getAbsolutePath());
            System.out.println("entities: total=" + mapEntities.size()
                    + " rendered=" + rendered + " skipped=" + skipped);
            System.out.println("renderables emitted: " + renderablesEmitted);
            System.out.println("unique sprites: " + hashToId.size());
            System.out.println("recipe icons: rendered=" + recipeRendered + " skipped=" + recipeSkipped);
            System.out.println("viewBox: " + fmt(minX) + " " + fmt(minY) + " " + fmt(vbW) + " " + fmt(vbH));
            System.out.println("render time: " + ms + " ms");
            System.out.println("svg size: " + output.length() + " bytes");
            System.out.println("manifest size: " + manifestOut.length() + " bytes");
        } finally {
            FBSR.unload();
        }
    }

    private static double round(double v, int decimals) {
        double m = Math.pow(10, decimals);
        return Math.round(v * m) / m;
    }

    private static String fmt(double d) {
        // 4 decimals is sub-pixel at TILE_SIZE=64
        return String.format(Locale.ROOT, "%.4f", d);
    }

    // Mirrors the icon-resolution chain in CraftingMachineRendering: try the
    // recipe icon first; if missing, fall back to the icon of its primary
    // result item (or the fluid version of that name).
    private static Optional<IconDef> resolveRecipeIcon(ModdingResolver resolver, String recipeName) {
        Optional<IconDef> icon = resolver.resolveIconRecipeName(recipeName);
        if (icon.isPresent()) return icon;
        Optional<RecipePrototype> proto = resolver.resolveRecipeName(recipeName);
        if (proto.isEmpty()) return Optional.empty();
        String name;
        LuaValue results = proto.get().lua().get("results");
        if (results != LuaValue.NIL) {
            name = results.get(1).get("name").toString();
        } else {
            LuaValue result = proto.get().lua().get("result");
            if (result == LuaValue.NIL) return Optional.empty();
            name = result.toString();
        }
        icon = resolver.resolveIconItemName(name);
        if (icon.isEmpty()) icon = resolver.resolveIconFluidName(name);
        return icon;
    }

    // Renders an IconDef the way MapIcon does in FBSR: a rounded translucent
    // black background covering the full 1.6-tile sprite, then the icon
    // itself drawn at 1.4 tiles inset by the 0.1-tile border. Same recipe
    // CraftingMachineRendering uses; pixel-parity with FBSR's flat PNG output.
    private static BufferedImage renderRecipeIconFbsrStyle(IconDef def) {
        AtlasRef ref = def.getAtlasRef();
        if (!ref.isValid()) return null;
        int pxSize = (int) Math.ceil(RECIPE_ICON_TOTAL_TILES * FBSR.TILE_SIZE);
        BufferedImage img = new BufferedImage(pxSize, pxSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // Switch to tile units for the rest of the drawing — matches MapIcon's
        // own coordinate convention.
        g.scale(FBSR.TILE_SIZE, FBSR.TILE_SIZE);

        // Rounded black background spans the full sprite.
        g.setColor(RECIPE_ICON_BG);
        double cornerDiameter = RECIPE_ICON_BORDER_TILES * 2;
        g.fill(new RoundRectangle2D.Double(0, 0, RECIPE_ICON_TOTAL_TILES, RECIPE_ICON_TOTAL_TILES,
                cornerDiameter, cornerDiameter));

        // Icon offset by the border, sized 1.4 tiles. AffineTransform save/
        // restore keeps the global tile-units transform intact.
        AffineTransform pat = g.getTransform();
        g.translate(RECIPE_ICON_BORDER_TILES, RECIPE_ICON_BORDER_TILES);
        g.scale(RECIPE_ICON_SIZE_TILES, RECIPE_ICON_SIZE_TILES);
        BufferedImage atlas = def.requestAtlas();
        Rectangle src = ref.getRect();
        g.drawImage(atlas, 0, 0, 1, 1, src.x, src.y, src.x + src.width, src.y + src.height, null);
        g.setTransform(pat);

        g.dispose();
        return img;
    }

    private static String sha1(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] hash = md.digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
