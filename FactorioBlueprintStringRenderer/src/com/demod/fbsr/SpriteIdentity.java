package com.demod.fbsr;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

import javax.imageio.ImageIO;

import com.demod.fbsr.def.SpriteDef;

// Sprite-identity helpers for the event-driven map pipeline
// (docs/specs/fbsr_event_driven_pipeline.md §A.4).
//
// Two responsibilities:
//   - identityHash(def): a stable 40-hex-char SHA-1 over the SpriteDef's
//     prototype-derived identity tuple. Every input is read from existing
//     public getters; the same prototype produces the same hash across JVM
//     runs, so the dashboard's shared sprite atlas can be keyed and grown
//     monotonically across runs.
//   - extractPng(def): PNG-encoded bytes of the SpriteDef's packed source
//     rect, with no tint / blend applied. The dashboard composes layers at
//     render time.
//
// applyRuntimeTint is intentionally NOT in the identity tuple: two
// SpriteDefs that differ only by runtime-tint flag share the same atlas
// pixels. If the dashboard ever needs to render runtime-tinted entities
// (player colour, etc.) it should carry the flag as a layer-record field
// rather than fork the sid.
public final class SpriteIdentity {

    private SpriteIdentity() {}

    public static String identityHash(SpriteDef def) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }

        update(md, def.getPath());

        Rectangle src = def.getSource();
        ByteBuffer ints = ByteBuffer.allocate(Integer.BYTES * 4);
        ints.putInt(src.x).putInt(src.y).putInt(src.width).putInt(src.height);
        md.update(ints.array());

        md.update((byte) (def.isShadow() ? 1 : 0));

        update(md, def.getBlendMode().name());

        Optional<Color> tint = def.getTint();
        ByteBuffer tintBuf = ByteBuffer.allocate(Integer.BYTES + 1);
        if (tint.isPresent()) {
            tintBuf.put((byte) 1).putInt(tint.get().getRGB());
        } else {
            tintBuf.put((byte) 0).putInt(0);
        }
        md.update(tintBuf.array());

        md.update((byte) (def.isTintAsOverlay() ? 1 : 0));

        return toHex(md.digest());
    }

    public static byte[] extractPng(SpriteDef def) throws IOException {
        BufferedImage atlas = def.requestAtlas();
        Rectangle rect = def.getAtlasRef().getRect();
        BufferedImage cropped = atlas.getSubimage(rect.x, rect.y, rect.width, rect.height);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(cropped, "PNG", baos);
        return baos.toByteArray();
    }

    private static void update(MessageDigest md, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        md.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        md.update(bytes);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
