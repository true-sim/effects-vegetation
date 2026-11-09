// Fading a plant into its impostor and back with distance, alike in every pass (GEOMETRY_KEEP): over the band from
// `start` to `end` metres from the viewer (the view's camera; shadow views share the camera's position), the mesh's
// points thin out and the impostor's fill in, each by a dither fixed to the surface (by its texture coordinates, so it
// stays as the viewer moves: no shimmer without temporal antialiasing). The two overlap: the impostor is whole from
// the band's middle, the mesh until it, so the band never shows less than one of them (no hole, no pop).
module fade;

import bindless;
__exported import vegetation.blade;

/** A dither value 0 to 1 for the surface point at texture coordinates `uv`, `uv1`: the same in every pass. */
public float fadeDither(float2 uv, float2 uv1)
{
    int4 c = int4(floor(float4(uv * 251, uv1 * 61)));
    return unit(bladeHash(uint(c.x) * 0x9E3779B1u ^ bladeHash(uint(c.y) * 0x85EBCA6Bu
        ^ bladeHash(uint(c.z) * 0xC2B2AE35u ^ bladeHash(uint(c.w))))));
}

/**
 * Whether the point `world` (camera-relative), at texture coordinates `uv`, `uv1`, of a mesh (`impostor` false) or
 * of its impostor (true) is kept, in a band from `start` to `end` metres from the viewer. Without a band (end not past start) everything is kept.
 */
public bool fadeKept(float3 world, float2 uv, float2 uv1, float start, float end, bool impostor)
{
    if (!(end > start))
    {
        return true;
    }
    float t = saturate((length(world) - start) / (end - start));
    float share = impostor ? min(1.0, 2 * t) : min(1.0, 2 * (1 - t));
    return fadeDither(uv, uv1) < share;
}
