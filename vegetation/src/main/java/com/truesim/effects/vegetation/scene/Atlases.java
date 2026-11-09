package com.truesim.effects.vegetation.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.render.ModelData;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.TextureData;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Impostor atlases as texture assets: a bake (on the scene's thread, through the engine) is handed over under a key of
 * its own, which the atlases' generator gives the cache once, as it loads the asset. The sync holds each atlas while
 * its scatter stands, so the cache never needs it again.
 */
final class Atlases {

    /** An atlas's key: the model baked, the grid and resolution, and which bake (each its own asset). */
    record Key(AssetRef<ModelData> model, int frames, int resolution, long bake) {
    }

    private static final Map<Key, TextureData> BAKED = new ConcurrentHashMap<>();
    private static final AtomicLong BAKES = new AtomicLong();

    /** Hands the atlas of a bake to the cache, once. */
    static final Generator<Key, TextureData> ATLASES = (key, seed) -> {
        TextureData atlas = BAKED.remove(key);
        if (atlas == null) {
            throw new IllegalStateException("No impostor baked for " + key.model() + " (bake " + key.bake() + ")");
        }
        return atlas;
    };

    private Atlases() {
    }

    /** {@code atlas}, baked from {@code model}, as an asset to acquire (once). */
    @AllowAllocation("once per bake")
    static AssetRef<TextureData> hand(AssetRef<ModelData> model, int frames, int resolution, TextureData atlas) {
        Key key = new Key(model, frames, resolution, BAKES.incrementAndGet());
        BAKED.put(key, atlas);
        return AssetRef.generated(RenderAssets.TEXTURE, ATLASES, key, 0);
    }
}
