// Octahedral impostors (Brucks' octahedral impostors): a plant far away drawn as one quad turned to the viewer, its
// look from an atlas baked from the model through the engine (ImpostorBaker): the four frames of the hemi-octahedral
// grid nearest the direction it is seen from, blended bilinearly, each frame's point moved along the view to the depth
// it holds (so the frames line up and the quad shows the crown's volume), lit by the normals it holds. Its points exist
// where the frames cover them and as the fade from its mesh lets them (fade.slang), in every pass.
//
// The quad is a mesh of four corners about the center with UVs 0 to 1; displace() turns it to the viewer and hands the
// fragment the direction it is seen from (the normal) and the mesh's x axis (the tangent): plants stand upright, turned
// about y, so those give the mesh's axes in the world.
module impostor;

import materials;
import materials.metallicRoughness;
__exported import vegetation.impostorParameters;
__exported import vegetation.fade;

extern static const uint RUN_HOOK_VEGETATION_FIELD_INDEX;

/** Direction `d` (y from 0) on the unit square, hemi-octahedral: the grid's frames lie on it. */
public float2 hemiEncode(float3 d)
{
    float3 v = float3(d.x, max(d.y, 0.0), d.z);
    float s = abs(v.x) + abs(v.y) + abs(v.z);
    float2 p = v.xz / max(s, 1e-6);
    return float2(p.x + p.y, p.x - p.y) * 0.5 + 0.5;
}

/** The direction at point `uv` of the unit square: hemiEncode's inverse. */
public float3 hemiDecode(float2 uv)
{
    float a = 2 * uv.x - 1;
    float b = 2 * uv.y - 1;
    float px = (a + b) * 0.5;
    float pz = (a - b) * 0.5;
    return normalize(float3(px, 1 - abs(px) - abs(pz), pz));
}

/** The right and up of a view looking along -`f` (a frame's camera, or the quad facing `f`). */
public void viewBasis(float3 f, out float3 right, out float3 up)
{
    float3 hint = abs(f.y) > 0.999 ? float3(0, 0, -1) : float3(0, 1, 0);
    right = normalize(cross(-f, hint));
    up = cross(right, -f);
}

/** Where `rel` (from the center) falls in a frame of right `r`, up `u` over `radius`: 0 to 1, v down. */
float2 frameUv(float3 rel, float3 r, float3 u, float radius)
{
    return float2(0.5 + 0.5 * dot(rel, r) / radius, 0.5 - 0.5 * dot(rel, u) / radius);
}

/** What the frames give at a point: albedo times coverage, coverage, normal times coverage (mesh space). */
public struct ImpostorSample
{
    public float3 albedo;
    public float coverage;
    public float3 normal;
};

/** Texel `uv` (0 to 1) of frame `cell`: its color (upper half) or its normal and depth (lower half). */
float4 frameTexel(VegetationImpostorParameters p, int2 cell, float2 uv, bool lower, float2 dx, float2 dy)
{
    float n = float(p.frames);
    float2 inner = clamp(uv, 0.002, 0.998);
    float2 at = float2((cell.x + inner.x) / n, ((cell.y + inner.y) / n + (lower ? 1.0 : 0.0)) * 0.5);
    return sampleTexture(p.atlas, at, dx, dy);
}

/** Frame `cell`'s look, by `weight`, at the quad's point `rel` (from the center, mesh space) seen from `toViewer`. */
void addFrame(VegetationImpostorParameters p, int2 cell, float weight, float3 rel, float3 toViewer, float2 dx,
    float2 dy, inout ImpostorSample s)
{
    if (weight <= 0)
    {
        return;
    }
    float n = float(p.frames);
    float3 f = hemiDecode((float2(cell) + 0.5) / n);
    float3 r;
    float3 u;
    viewBasis(f, r, u);
    float2 uv = frameUv(rel, r, u, p.radius);
    if (p.parallax > 0)
    {
        // the surface the frame holds at uv lies at its depth along f: the point of the view's ray at that depth
        float depth = (frameTexel(p, cell, uv, true, dx, dy).a * 2 - 1) * p.radius;
        float t = (dot(rel, f) - depth) / max(dot(toViewer, f), 0.25);
        uv = frameUv(rel - toViewer * (t * p.parallax), r, u, p.radius);
    }
    float4 color = frameTexel(p, cell, uv, false, dx, dy);
    float4 normal = frameTexel(p, cell, uv, true, dx, dy);
    float a = weight * color.a;
    s.albedo += a * color.rgb * color.rgb; // stored as its square root
    s.coverage += a;
    s.normal += a * (normal.rgb * 2 - 1);
}

/** The mesh's axes in the world, from the tangent (x) and up; and the direction seen from, in mesh space. */
void meshAxes(SurfaceInput in, out float3 x, out float3 y, out float3 z, out float3 toViewer)
{
    x = normalize(in.tangent.xyz);
    y = float3(0, 1, 0);
    z = cross(x, y);
    float3 n = in.tangent.w < 0 ? -in.normal : in.normal; // a back face's normal turned: as displace() made it
    toViewer = normalize(float3(dot(n, x), dot(n, y), dot(n, z)));
}

/** The impostor at the point of `in`, and the mesh's axes in the world. */
public ImpostorSample impostorSample(VegetationImpostorParameters p, SurfaceInput in, out float3 x, out float3 y,
    out float3 z)
{
    float3 toViewer;
    meshAxes(in, x, y, z, toViewer);
    float3 r;
    float3 u;
    viewBasis(toViewer, r, u);
    float2 q = float2(in.uv.x * 2 - 1, 1 - in.uv.y * 2);
    float3 rel = (q.x * r + q.y * u) * p.radius;
    float n = float(p.frames);
    float2 g = hemiEncode(toViewer) * n - 0.5;
    int2 c = int2(floor(g));
    float2 w = g - float2(c);
    float2 dx = in.dx / n * 0.5;
    float2 dy = in.dy / n * 0.5;
    ImpostorSample s;
    s.albedo = 0;
    s.coverage = 0;
    s.normal = 0;
    int2 last = int2(p.frames - 1);
    addFrame(p, clamp(c, int2(0), last), (1 - w.x) * (1 - w.y), rel, toViewer, dx, dy, s);
    addFrame(p, clamp(c + int2(1, 0), int2(0), last), w.x * (1 - w.y), rel, toViewer, dx, dy, s);
    addFrame(p, clamp(c + int2(0, 1), int2(0), last), (1 - w.x) * w.y, rel, toViewer, dx, dy, s);
    addFrame(p, clamp(c + int2(1, 1), int2(0), last), w.x * w.y, rel, toViewer, dx, dy, s);
    return s;
}

[MaterialType("vegetationImpostor")]
public struct VegetationImpostor : IMaterial
{
    public typealias Parameters = VegetationImpostorParameters;

    public static Surface surface(VegetationImpostorParameters p, MaterialRef m, SurfaceInput in)
    {
        float3 x;
        float3 y;
        float3 z;
        ImpostorSample s = impostorSample(p, in, x, y, z);
        float c = max(s.coverage, 1e-4);
        float3 nm = dot(s.normal, s.normal) > 1e-8 ? normalize(s.normal) : float3(0, 1, 0);
        Surface surface = Surface.defaults(normalize(nm.x * x + nm.y * y + nm.z * z));
        surface.albedo = s.albedo / c;
        surface.roughness = p.roughness;
        surface.metallic = 0;
        return surface;
    }

    /** Covered by the frames, and as far into the fade as its distance is. */
    public override static bool keep(VegetationImpostorParameters p, MaterialRef m, SurfaceInput in)
    {
        if (!fadeKept(in.world, in.uv, in.uv1, p.fadeStart, p.fadeEnd, true))
        {
            return false;
        }
        float3 x;
        float3 y;
        float3 z;
        return impostorSample(p, in, x, y, z).coverage >= p.cutoff;
    }

    /** The quad turned to the viewer: the normal the direction it is seen from, the tangent the mesh's x axis. */
    public override static Displaced displace(VegetationImpostorParameters p, MaterialRef m, DisplacePoint in)
    {
        Displaced d = Displaced.of(in);
        float3 toViewer = float3(0, 0, 1);
        if (in.hookOn(RUN_HOOK_VEGETATION_FIELD_INDEX))
        {
            VegetationFieldParameters* field = in.hook<VegetationFieldParameters>(RUN_HOOK_VEGETATION_FIELD_INDEX);
            if (field[0].viewerOn > 0)
            {
                // the center from the viewer, in world axes: the vertex's, less its offset from the center
                float3 centerFromViewer = in.worldFrom(field[0].viewer, field[0].viewerLow)
                    - in.toWorld(in.position - p.center);
                float3 local = in.toMesh(-centerFromViewer);
                if (dot(local, local) > 1e-12)
                {
                    toViewer = normalize(local);
                }
            }
        }
        float3 r;
        float3 u;
        viewBasis(toViewer, r, u);
        float2 q = float2(in.uv.x * 2 - 1, 1 - in.uv.y * 2);
        d.position = p.center + (q.x * r + q.y * u) * p.radius;
        d.normal = toViewer;
        d.tangent = float4(1, 0, 0, 1);
        return d;
    }
};

[MaterialType("vegetationImpostorBake")]
public struct VegetationImpostorBake : IMaterial
{
    public typealias Parameters = VegetationImpostorBakeParameters;

    public static Surface surface(VegetationImpostorBakeParameters p, MaterialRef m, SurfaceInput in)
    {
        SurfaceInput input = in;
        if (p.windColors != 0)
        {
            input.color = float4(1, 1, 1, 1);
        }
        Surface s = evaluate(p.base, input);
        if (p.mode == 0)
        {
            s.albedo = sqrt(saturate(s.albedo));
        }
        else if (p.mode == 1)
        {
            s.albedo = normalize(s.normal) * 0.5 + 0.5;
        }
        else
        {
            s.albedo = saturate((in.world - p.center) / (2 * p.radius) + 0.5);
        }
        s.emissive = 0;
        return s;
    }
};
