package com.demod.fbsr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import com.demod.fbsr.bs.BSEntity;
import com.demod.fbsr.def.SpriteDef;
import com.demod.fbsr.map.MapEntity;
import com.demod.fbsr.map.MapPosition;
import com.demod.fbsr.map.MapRect;
import com.demod.fbsr.map.MapRenderable;
import com.demod.fbsr.map.MapSprite;

// Incremental, event-driven counterpart to WorldMap. Bundles map state
// (a WorldMap + a position-to-entity index) with two standalone public
// capabilities:
//
//   1. updateMap(events) — apply a batch of events to the map state and
//      return the entities whose render may have changed.
//   2. emitUpdates(affected, ts) — render each affected entity to a
//      renderable-timeline item (`{ ts, layers:[{L, sid, ox, oy}, …] }`),
//      keyed by entity_number.
//
// Either can be called independently (single-frame renders, tests,
// what-if previews). ReplaySidecar (com.demod.fbsr.cli) wraps them in
// the tick-batch loop that produces a full per-run timeline output.
//
// Sids are produced by SpriteIdentity and are stable across JVM runs.
// Atlas augmentation (writing PNG bytes for new sids) is the consumer's
// responsibility; this class emits sids but does not maintain an atlas.
//
// Spec: docs/specs/fbsr_event_driven_pipeline.md §B.3 + §B.4.
public final class IncrementalMap {

    public enum EventKind { REMOVE, BUILD }   // ordinal order = sort order at same tick

    public static final class Event {
        public final long tick;
        public final EventKind kind;
        public final MapEntity entity;
        public Event(long tick, EventKind kind, MapEntity entity) {
            this.tick = tick; this.kind = kind; this.entity = entity;
        }
    }

    private final ModdingResolver resolver;
    private final WorldMap map;
    private final Map<MapPosition, MapEntity> positionToEntity;

    public IncrementalMap(ModdingResolver resolver) {
        this.resolver = resolver;
        this.map = new WorldMap();
        this.map.setResolver(resolver);
        this.map.setAltMode(false);
        this.positionToEntity = new HashMap<>();
    }

    public WorldMap getMap() { return map; }

    // Helper: parse a BSEntity-shape record into an Event. Callers typically
    // build both a BUILD@tb and a paired REMOVE@tr from the same record;
    // reuse the BUILD event's MapEntity in the paired REMOVE so populate /
    // unpopulate work against the same instance.
    public Event parseEvent(JSONObject record, long tick, EventKind kind) throws Exception {
        String name = record.getString("name");
        EntityRendererFactory factory = resolver.resolveFactoryEntityName(name);
        BSEntity bse = factory.parseEntity(record);
        MapEntity me = new MapEntity(bse, factory, resolver);
        return new Event(tick, kind, me);
    }

    // === Capability 1 ===================================================
    // Apply events to map state in iteration order. For each event:
    //   - BUILD: populateWorldMap + populateLogistics + index entity at its position
    //   - REMOVE: unpopulateWorldMap + drop position from index
    // The affected-position set comes from (un)populateWorldMap's
    // Set<MapPosition> return (spec §A.1b); the affected-entity set is the
    // intersection with the current positionToEntity index. Entities just
    // removed in this batch are NOT in the affected set — their timelines
    // end naturally at their last appended snapshot.
    public Set<MapEntity> updateMap(List<Event> events) {
        Set<MapPosition> affectedPositions = new LinkedHashSet<>();
        for (Event ev : events) {
            MapEntity me = ev.entity;
            MapPosition pos = me.getPosition();
            try {
                if (ev.kind == EventKind.BUILD) {
                    affectedPositions.addAll(me.getFactory().populateWorldMap(map, me));
                    me.getFactory().populateLogistics(map, me);
                    positionToEntity.put(pos, me);
                } else {
                    affectedPositions.addAll(me.getFactory().unpopulateWorldMap(map, me));
                    // unpopulateLogistics not yet defined fork-side; logistic-grid
                    // state may drift across remove+rebuild. Not load-bearing for
                    // belt/pipe/machine renders.
                    positionToEntity.remove(pos);
                }
            } catch (Exception ex) {
                // populate/unpopulate failure on a bad entity — skip; the entity
                // will be missing from the rendered timeline.
            }
        }

        Set<MapEntity> affected = new LinkedHashSet<>();
        for (MapPosition p : affectedPositions) {
            MapEntity me = positionToEntity.get(p);
            if (me != null) affected.add(me);
        }
        return affected;
    }

    // === Capability 2 ===================================================
    // Render each affected entity via FBSR's createRenderers against the
    // current WorldMap, filter to MapSprite, and emit one layer object
    // per sprite. Each entity's result is wrapped as a renderable-timeline
    // item `{ ts, layers:[...] }` keyed by entity_number.
    //
    // Without an atlas sink, sids are emitted but no PNG bytes are
    // written anywhere — dashboards will see unresolvable sids until
    // a sink-backed run fills them in. Pass a non-null AtlasSink to grow
    // the atlas: new sids cause sink.put(sid, png, w, h), guarded by
    // sink.has(sid) so identity-based dedup precedes PNG extraction.
    public Map<Integer, JSONObject> emitUpdates(Collection<MapEntity> affected, long ts) {
        return emitUpdates(affected, ts, null);
    }

    public Map<Integer, JSONObject> emitUpdates(Collection<MapEntity> affected, long ts, AtlasSink atlas) {
        Map<Integer, JSONObject> out = new LinkedHashMap<>();
        for (MapEntity entity : affected) {
            List<MapRenderable> emitted = new ArrayList<>();
            try {
                entity.getFactory().createRenderers(emitted::add, map, entity);
            } catch (Exception ex) {
                continue;
            }
            JSONArray layers = new JSONArray();
            for (MapRenderable r : emitted) {
                if (!(r instanceof MapSprite)) continue;
                MapSprite sprite = (MapSprite) r;
                SpriteDef def = sprite.getDef();
                String sid = SpriteIdentity.identityHash(def);
                MapRect bounds = sprite.getBounds();
                if (atlas != null && !atlas.has(sid)) {
                    try {
                        byte[] png = SpriteIdentity.extractPng(def);
                        // Use sprite bounds (= def.trimmedBounds + entity position)
                        // for atlas w/h — that's the world-tile rectangle the
                        // (already-trimmed) PNG bytes from SpriteIdentity.extractPng
                        // get stretched to in MapSprite.render. Using sourceBounds
                        // here would oversize the dst rect and visually scale the
                        // sprite up (belts/drills with loose source rects: ~70%
                        // zoom).
                        atlas.put(sid, png, bounds.getWidth(), bounds.getHeight());
                    } catch (Exception ex) {
                        // skip atlas write on extraction failure; sid still
                        // emitted so a later run can backfill.
                    }
                }
                JSONObject layer = new JSONObject();
                layer.put("L", sprite.getLayer().ordinal());
                layer.put("sid", sid);
                layer.put("ox", round(bounds.getX(), 4));
                layer.put("oy", round(bounds.getY(), 4));
                layers.put(layer);
            }
            int un = entity.fromBlueprint().entityNumber;
            JSONObject item = new JSONObject();
            item.put("ts", ts);
            item.put("layers", layers);
            out.put(un, item);
        }
        return out;
    }

    private static double round(double v, int decimals) {
        double m = Math.pow(10, decimals);
        return Math.round(v * m) / m;
    }
}
