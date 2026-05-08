package com.demod.fbsr.cli;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.json.JSONObject;

import com.demod.dcba.CommandReporting;
import com.demod.fbsr.FBSR;
import com.demod.fbsr.Profile;
import com.demod.fbsr.RenderRequest;
import com.demod.fbsr.RenderResult;
import com.demod.fbsr.bs.BSBlueprint;
import com.google.common.collect.ImmutableList;

// Replay-analyzer entry point: render a blueprint-shape JSON object directly
// to PNG. Skips the BP-string envelope (no version byte / base64 / zlib);
// hands the JSONObject straight to BSBlueprint.
//
// Usage: ReplayRender <input.json> <output.png>
//
// input.json may be either the inner blueprint object {version, entities, ...}
// or the outer wrapper {"blueprint": {...}}.
public class ReplayRender {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: ReplayRender <input.json> <output.png>");
            System.exit(1);
        }
        File input = new File(args[0]);
        File output = new File(args[1]);

        String text = Files.readString(input.toPath());
        JSONObject json = new JSONObject(text);
        if (json.has("blueprint")) {
            json = json.getJSONObject("blueprint");
        }

        Profile vanilla = Profile.vanilla();
        if (!vanilla.isReady()) {
            System.err.println("vanilla profile not ready — run 'build vanilla' first");
            System.exit(2);
        }
        List<Profile> profiles = ImmutableList.of(vanilla);
        if (!FBSR.load(profiles)) {
            System.err.println("FBSR.load failed");
            System.exit(3);
        }

        try {
            BSBlueprint blueprint = new BSBlueprint(json);

            CommandReporting reporting = new CommandReporting(null, null, null);
            RenderRequest request = new RenderRequest(blueprint, reporting);
            request.setBackground(Optional.empty());
            request.setGridLines(Optional.empty());
            request.setDontClipSprites(true);

            RenderResult result = FBSR.renderBlueprint(request);

            output.getAbsoluteFile().getParentFile().mkdirs();
            ImageIO.write(result.image, "PNG", output);
            System.out.println("wrote " + output.getAbsolutePath());
            System.out.println("dimensions: " + result.image.getWidth() + "x" + result.image.getHeight());
        } finally {
            FBSR.unload();
        }
    }
}
