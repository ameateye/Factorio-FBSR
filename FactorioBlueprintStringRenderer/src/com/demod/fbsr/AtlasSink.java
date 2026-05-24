package com.demod.fbsr;

// Append-only sprite atlas, keyed by stable sid (see SpriteIdentity).
// Implementations decide where bytes live; the contract is monotonic
// growth — once a sid is put, has(sid) returns true forever and put for
// the same sid is a no-op.
//
// Used by IncrementalMap.emitUpdates: for each rendered MapSprite, the
// caller computes sid = SpriteIdentity.identityHash(def). If the sink
// doesn't have it yet, the caller extracts PNG bytes via
// SpriteIdentity.extractPng(def) and puts them with the def's tile-unit
// source bounds. This way identity-based dedup happens before the
// (expensive) PNG extraction.
//
// Spec: docs/specs/fbsr_event_driven_pipeline.md §A.4.
public interface AtlasSink {

    boolean has(String sid);

    // png: raw PNG bytes of the source rect, no tint applied.
    // w, h: tile-unit footprint of the sprite (SpriteDef.sourceBounds.{width,height}).
    void put(String sid, byte[] png, double w, double h);
}
