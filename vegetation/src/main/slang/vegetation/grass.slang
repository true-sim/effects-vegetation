// Grass over a patch template (GrassPatches): a material type displacing every vertex of its template into its blade
// (blade.slang) in every pass, so the blades exist only as the vertex and mesh stages make them. The template holds
// per vertex only its blade's root, its place along the blade and across it, the rank detail keeps it by and its
// number; the scene's geometry hook block gives the wind (vegetation.wind) and the field (vegetation.field: the viewer
// detail falls with, the ground map, snow). Lit as thin blades: from behind, light comes through (the material's
// diffuse transmission feature).
module grass;

import materials;
__exported import vegetation.blade;

/** The hooks' indices in the shading hooks' dispatch, resolved when a shader links (hooks.properties). */
extern static const uint RUN_HOOK_VEGETATION_WIND_INDEX;
extern static const uint RUN_HOOK_VEGETATION_FIELD_INDEX;

/** The blade number a template vertex carries in its color's red, green and blue bytes. */
public uint bladeId(float4 color)
{
    uint3 bytes = uint3(round(saturate(color.rgb) * 255));
    return bytes.x | bytes.y << 8 | bytes.z << 16;
}

[MaterialType("vegetationGrass")]
public struct VegetationGrass : IMaterial
{
    public typealias Parameters = VegetationGrassParameters;

    public static Surface surface(VegetationGrassParameters p, MaterialRef m, SurfaceInput in)
    {
        Surface s = Surface.defaults(normalize(in.normal));
        uint seed = bladeHash(bladeId(in.color) * 0x9E3779B1u + 77u);
        float along = saturate(in.uv.x);
        float3 color = lerp(p.rootColor, p.tipColor, smoothstep(0.0, 1.0, along));
        if (unit(seed) < p.dryness)
        {
            color = lerp(color, p.dryColor, smoothstep(0.25, 1.0, along));
        }
        float v = p.colorVariation;
        color *= 1 + v * (2 * unit(bladeHash(seed + 1)) - 1);
        color = lerp(color, color * float3(1.25, 1.05, 0.6), v * unit(bladeHash(seed + 2))); // towards yellow
        s.albedo = saturate(color);
        if (p.detailColors > 0)
        {
            // A debug view of the template's detail: full detail green, then yellow, orange and red as segments fall
            uint segments = uint(round(saturate(in.color.a) * 255));
            float3 levels[5] = { float3(1, 0, 1), float3(1, 0.02, 0.01), float3(1, 0.35, 0.0), float3(0.9, 0.85, 0.0),
                float3(0.05, 0.8, 0.1) };
            float3 level = levels[min(segments, 4u)];
            s.albedo = lerp(s.albedo, level, p.detailColors);
            s.emissive = level * (0.6 * p.detailColors); // seen in shade too
        }
        s.roughness = p.roughness;
        s.occlusion = lerp(0.35, 1.0, along); // the sward shades its own roots
        return s;
    }

    public override static Displaced displace(VegetationGrassParameters p, MaterialRef m, DisplacePoint in)
    {
        Displaced d = Displaced.of(in);
        // The records lie in the block whether their hooks are on or not: never a null pointer (engine pitfalls.md)
        VegetationWindParameters* wind = in.hook<VegetationWindParameters>(RUN_HOOK_VEGETATION_WIND_INDEX);
        bool windOn = in.hookOn(RUN_HOOK_VEGETATION_WIND_INDEX);
        VegetationFieldParameters* field = in.hook<VegetationFieldParameters>(RUN_HOOK_VEGETATION_FIELD_INDEX);
        bool fieldOn = in.hookOn(RUN_HOOK_VEGETATION_FIELD_INDEX);
        BladeInput b;
        float3 origin = in.origin + in.originLow;
        float3 root = origin + in.toWorld(in.position);
        b.rootWorld = root.xz;
        b.rootHeight = root.y;
        float size = max(p.patchSize, 1e-3);
        b.cell = int2(floor(origin.xz / size));
        b.rootFromViewer = fieldOn ? in.worldFrom(field[0].viewer, field[0].viewerLow) : float3(0, 0, 0);
        b.id = bladeId(in.color);
        b.segments = uint(round(saturate(in.color.a) * 255));
        b.along = in.uv.x;
        b.side = in.uv.y;
        b.rank = in.uv1.x;
        BladeOutput out = bladeVertex(p, b, wind, windOn, field, fieldOn);
        d.position = in.position + in.toMesh(out.offset);
        d.normal = normalize(in.toMesh(out.normal));
        return d;
    }
};
