package com.demod.fbsr.cli;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import com.demod.fbsr.FBSR;
import com.demod.fbsr.FactorioManager;
import com.demod.fbsr.FileAtlasSink;
import com.demod.fbsr.IncrementalMap;
import com.demod.fbsr.ModdingResolver;
import com.demod.fbsr.Profile;
import com.demod.fbsr.map.MapEntity;
import com.google.common.collect.ImmutableList;

// CLI driver for the event-driven map renderer (Component B of
// docs/specs/fbsr_event_driven_pipeline.md).
//
// Reads a BSEntity-shape record stream + per-record tb/tr, builds events,
// sorts by tick, and iterates per-tick batches through IncrementalMap's two
// public capabilities:
//   1. replayMap.updateMap(batch)               → affected entities
//   2. replayMap.emitUpdates(affected, tick)    → renderableTimeline items
//
// Diffs each emitted item's `layers` against the previous snapshot for
// that `un` and appends to that `un`'s renderableTimeline only when the
// layer array changes. Writes the per-run output JSON per §B.6.
//
// Atlas augmentation goes through FileAtlasSink: the CLI opens the
// atlas file before the per-tick loop, passes it into
// IncrementalMap.emitUpdates for monotonic growth, and writes back on
// close. Sids in the output are real and stable (via SpriteIdentity),
// and the dashboard's atlas lookup resolves them once a run has been
// processed against the atlas file.
//
// Usage: ReplaySidecar <run-input.json> <run-output.json> <atlas-path>
public class ReplaySidecar {

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: ReplaySidecar <input.json> <output.json> <atlas-path>");
            System.exit(1);
        }
        File input = new File(args[0]);
        File output = new File(args[1]);
        File atlasPath = new File(args[2]);

        JSONObject in = new JSONObject(Files.readString(input.toPath()));
        String runName = in.optString("runName", input.getName());
        JSONArray records = in.optJSONArray("entities");
        if (records == null) records = new JSONArray();

        Profile vanilla = Profile.vanilla();
        if (!vanilla.isReady()) {
            System.err.println("vanilla profile not ready — run 'build vanilla' first");
            System.exit(2);
        }
        if (!FBSR.load(ImmutableList.of(vanilla))) {
            System.err.println("FBSR.load failed");
            System.exit(3);
        }

        long startMs = System.currentTimeMillis();
        try {
            // ===== Vanilla-only resolver ====================================
            // The replay input carries no modding info, so the sidecar
            // commits to the vanilla profile up front. Modded sources need
            // a real BP through a different CLI path.
            FactorioManager fm = FBSR.getFactorioManager();
            ModdingResolver resolver = ModdingResolver.byProfileOrder(
                    fm, ImmutableList.of(vanilla), true);

            IncrementalMap replayMap = new IncrementalMap(resolver);
            FileAtlasSink atlas = new FileAtlasSink(atlasPath);
            int atlasStartCount = atlas.totalCount();

            // ===== events ======================================================
            // One BUILD at tb and (if set) one REMOVE at tr per record. The
            // paired REMOVE reuses the BUILD's MapEntity so populate/unpopulate
            // work against the same instance.
            List<IncrementalMap.Event> events = new ArrayList<>(records.length() * 2);
            int parseSkipped = 0;
            for (int i = 0; i < records.length(); i++) {
                JSONObject rec = records.getJSONObject(i);
                long tb = rec.getLong("tb");
                IncrementalMap.Event buildEv;
                try {
                    buildEv = replayMap.parseEvent(rec, tb, IncrementalMap.EventKind.BUILD);
                } catch (Exception ex) {
                    parseSkipped++;
                    continue;
                }
                events.add(buildEv);
                if (rec.has("tr")) {
                    long tr = rec.getLong("tr");
                    events.add(new IncrementalMap.Event(tr, IncrementalMap.EventKind.REMOVE, buildEv.entity));
                }
            }

            // Spec §B.2 ordering: tick asc; REMOVE before BUILD at the same tick
            // (rotate-in-place re-establishes the cell with the new state);
            // entity_number tiebreak for byte-stable output.
            events.sort(Comparator
                    .comparingLong((IncrementalMap.Event e) -> e.tick)
                    .thenComparingInt(e -> e.kind.ordinal())
                    .thenComparingInt(e -> e.entity.fromBlueprint().entityNumber));

            // ===== driver loop =================================================
            Map<Integer, JSONArray> timelines = new LinkedHashMap<>();
            Map<Integer, JSONArray> prevLayers = new LinkedHashMap<>();
            int snapshotCount = 0;
            int idx = 0;
            while (idx < events.size()) {
                long tick = events.get(idx).tick;
                int j = idx;
                while (j < events.size() && events.get(j).tick == tick) j++;
                List<IncrementalMap.Event> batch = events.subList(idx, j);

                Set<MapEntity> affected = replayMap.updateMap(batch);
                Map<Integer, JSONObject> updates = replayMap.emitUpdates(affected, tick, atlas);

                for (Map.Entry<Integer, JSONObject> e : updates.entrySet()) {
                    int un = e.getKey();
                    JSONObject item = e.getValue();
                    JSONArray currLayers = item.getJSONArray("layers");
                    JSONArray prev = prevLayers.get(un);
                    if (prev == null || !prev.similar(currLayers)) {
                        timelines.computeIfAbsent(un, k -> new JSONArray()).put(item);
                        prevLayers.put(un, currLayers);
                        snapshotCount++;
                    }
                }

                idx = j;
            }

            // ===== output ======================================================
            JSONObject outJson = new JSONObject();
            outJson.put("runName", runName);
            JSONArray outEntities = new JSONArray();
            for (Map.Entry<Integer, JSONArray> e : timelines.entrySet()) {
                JSONObject row = new JSONObject();
                row.put("un", e.getKey());
                row.put("renderableTimeline", e.getValue());
                outEntities.put(row);
            }
            outJson.put("entities", outEntities);

            output.getAbsoluteFile().getParentFile().mkdirs();
            try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(output)))) {
                pw.write(outJson.toString());
            }

            atlas.close();

            long ms = System.currentTimeMillis() - startMs;
            System.out.println("wrote " + output.getAbsolutePath());
            System.out.println("records: " + records.length() + " parseSkipped: " + parseSkipped);
            System.out.println("events: " + events.size());
            System.out.println("entities-with-timeline: " + timelines.size());
            System.out.println("snapshots: " + snapshotCount);
            System.out.println("atlas: " + atlas.totalCount() + " entries (added " + atlas.addedCount()
                    + " from start " + atlasStartCount + ") → " + atlasPath.getAbsolutePath());
            System.out.println("run time: " + ms + " ms");
        } finally {
            FBSR.unload();
        }
    }
}
