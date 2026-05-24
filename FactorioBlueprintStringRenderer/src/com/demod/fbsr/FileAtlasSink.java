package com.demod.fbsr;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.FileWriter;
import java.nio.file.Files;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONObject;

// File-backed AtlasSink. Loads <atlas-path> on construction (or starts
// empty if the file doesn't exist yet), holds entries in memory, and
// writes back on close() — but only if any put() actually added a new
// entry. Existing entries are never rewritten (spec §Sprite atlas:
// the atlas grows monotonically across runs).
//
// File schema (matches the existing game-data/map-sprites.json):
//   { "<sid>": { "data": "<base64-png>", "w": <tiles>, "h": <tiles> } }
//
// Hex-string sids from SpriteIdentity sit alongside the dashboard's
// "r:<recipe>" / "f:<item>" overlay sids in the same file. The sidecar
// only adds; it never touches keys it didn't write.
//
// Spec: docs/specs/fbsr_event_driven_pipeline.md §A.4.
public final class FileAtlasSink implements AtlasSink, Closeable {

    private final File file;
    private final Map<String, JSONObject> entries;
    private int added;

    public FileAtlasSink(File file) throws IOException {
        this.file = file;
        this.entries = new LinkedHashMap<>();
        if (file.isFile()) {
            JSONObject json = new JSONObject(Files.readString(file.toPath()));
            for (String sid : json.keySet()) {
                entries.put(sid, json.getJSONObject(sid));
            }
        }
    }

    @Override
    public boolean has(String sid) {
        return entries.containsKey(sid);
    }

    @Override
    public void put(String sid, byte[] png, double w, double h) {
        if (entries.containsKey(sid)) return;
        JSONObject entry = new JSONObject();
        entry.put("data", Base64.getEncoder().encodeToString(png));
        entry.put("w", round(w, 4));
        entry.put("h", round(h, 4));
        entries.put(sid, entry);
        added++;
    }

    public int addedCount() { return added; }
    public int totalCount() { return entries.size(); }

    @Override
    public void close() throws IOException {
        if (added == 0) return;
        JSONObject out = new JSONObject();
        for (Map.Entry<String, JSONObject> e : entries.entrySet()) {
            out.put(e.getKey(), e.getValue());
        }
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null) parent.mkdirs();
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(file)))) {
            pw.write(out.toString());
        }
    }

    private static double round(double v, int decimals) {
        double m = Math.pow(10, decimals);
        return Math.round(v * m) / m;
    }
}
