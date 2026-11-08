// A grass blade from its slot in a patch template (GrassPatches: the root, its place along the blade, the rank detail
// keeps it by, its identity) and the world: everything else is made here, the same way in every pass and for every
// vertex of the blade, so nothing about a blade is stored. The grass type's displace() calls it (grass.slang), as do
// the tests' probes.
//
// A blade is a curve of fixed length: each segment's direction is the vertical tilted by an angle that grows along
// it (the rest lean and curvature, the wind's steady lean, gusts, the blade's own sway and its tip's flutter, snow
// pressing it down), and the vertices are the sum of the segments before them. However far it bends, its length stays.
module blade;

import bindless;
__exported import vegetation.grassParameters;
__exported import vegetation.vegetationWind;
__exported import vegetation.vegetationField;

public static const float TAU = 6.2831853;

/** A vertex of the template as the blade sees it: where its root is and which vertex of which blade it is. */
public struct BladeInput
{
    public float3 rootFromViewer; // the template's root, relative to the field's viewer (world axes)
    public float2 rootWorld;      // the root's world x and z
    public float rootHeight;      // the world height of the patch's plane at the root
    public int2 cell;             // the patch's cell (its origin over the patch size): blades' identity with id
    public uint id;               // the blade's number in the template
    public uint segments;         // how many segments the blade has at this vertex's detail level
    public float along;           // where the vertex is along the blade: 0 the root, 1 the tip
    public float side;            // -1 left edge, 1 right, 0 the tip
    public float rank;            // 0 to 1: detail keeps blades of rank below the share it keeps
};

/** Where the vertex goes: its move from the root as the template has it, and its unit normal, in world axes. */
public struct BladeOutput
{
    public float3 offset;
    public float3 normal;
    public float scale;           // how much of the blade stands: 0 gone (density, detail, out of the field)
};

/** A well mixed hash of a 32-bit value (PCG). */
public uint bladeHash(uint v)
{
    uint state = v * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

/** A hash as a number from 0 to 1. */
public float unit(uint h)
{
    return float(h >> 8) * (1.0 / 16777216.0);
}

/** The blade's seed: its number in the template and its patch's cell. */
public uint bladeSeed(uint id, int2 cell)
{
    return bladeHash(id ^ bladeHash(uint(cell.x) * 0x9E3779B9u ^ bladeHash(uint(cell.y) + 0x632BE5ABu)));
}

/** A lattice point's hash, for the noise. */
float latticeHash(int2 c)
{
    return unit(bladeHash(uint(c.x) * 0x8DA6B343u ^ bladeHash(uint(c.y) * 0xD8163841u)));
}

/** Value noise from 0 to 1, smooth across cells of 1. */
public float valueNoise(float2 x)
{
    float2 i = floor(x);
    float2 f = x - i;
    float2 u = f * f * (3 - 2 * f);
    int2 c = int2(i);
    float a = latticeHash(c);
    float b = latticeHash(c + int2(1, 0));
    float d = latticeHash(c + int2(0, 1));
    float e = latticeHash(c + int2(1, 1));
    return lerp(lerp(a, b, u.x), lerp(d, e, u.x), u.y);
}

/** The gusts at a world point: two octaves of noise, 0 to 1. */
public float gustNoise(float2 x)
{
    return (valueNoise(x) * 2 + valueNoise(x * 2.7 + 17.3)) / 3;
}

/** The ground map's height (r, g: two halves, metres over the base) and density (b) at world x and z. */
public float4 groundMap(VegetationFieldParameters* field, float2 world)
{
    float2 uv = (world - field[0].groundMapOrigin) / field[0].groundMapSize;
    uint slot = uint(max(field[0].groundMap, 0));
    return textures[NonUniformResourceIndex(slot)].SampleLevel(samplers[LINEAR_CLAMP], uv, 0);
}

/** A tilt (its direction, along the ground, times its angle from the vertical) as a unit direction. */
float3 tilted(float2 tilt, float maxAngle)
{
    float angle = length(tilt);
    if (angle < 1e-6)
    {
        return float3(0, 1, 0);
    }
    float2 towards = tilt / angle;
    angle = min(angle, maxAngle);
    float s = sin(angle);
    return float3(towards.x * s, cos(angle), towards.y * s);
}

/** How the bend spreads along a blade: none at the root, growing to the tip (a cantilever's slope). */
float bendProfile(float s)
{
    return s * (2 - s);
}

/** The blade's direction at `s` along it, from its tilts. */
float3 bladeDirection(float s, float2 rest, float restCurve, float2 wind, float2 flutter, float2 snow, float maxAngle)
{
    float restLength = length(rest);
    float2 restFace = restLength > 1e-6 ? rest / restLength : float2(1, 0);
    float2 tilt = rest + restFace * (restCurve * s) + (wind + snow) * bendProfile(s) + flutter * (s * s);
    return tilted(tilt, maxAngle);
}

/**
 * Vertex `b` of its blade: the blade's posture from its seed, the wind (`windOn` false: calm), the field (`fieldOn`
 * false: full detail, no ground map, no snow) and the type's parameters. Both records are always valid to read (a
 * hook's record lies in the block whether it is on or not): lavapipe loads through pointers even behind a branch
 * (engine pitfalls.md).
 */
public BladeOutput bladeVertex(VegetationGrassParameters p, BladeInput b, VegetationWindParameters* wind,
    bool windOn, VegetationFieldParameters* field, bool fieldOn)
{
    BladeOutput out;
    out.offset = 0;
    out.normal = float3(0, 1, 0);
    out.scale = 0;
    uint seed = bladeSeed(b.id, b.cell);
    // Where it stands: jittered within its slot, drawn to its clump's middle
    float2 root = b.rootWorld + (float2(unit(bladeHash(seed + 1)), unit(bladeHash(seed + 2))) - 0.5) * p.spacing;
    // Clumps are the cells of a jittered grid's Voronoi diagram: the nearest of the 3 x 3 jittered points round the
    // root's cell. (Pulling roots to their grid cell's point instead left lanes along the grid's lines, bare where the
    // pull emptied the cells' edges: pitfalls.md.)
    float clumpSize = max(p.clumpSize, 1e-3);
    float2 home = floor(root / clumpSize);
    float2 clumpMiddle = root;
    uint clumpSeed = 0;
    float nearest = 1e30;
    for (int dz = -1; dz <= 1; dz++)
    {
        for (int dx = -1; dx <= 1; dx++)
        {
            int2 c = int2(home) + int2(dx, dz);
            uint h = bladeHash(uint(c.x) * 0x27D4EB2Fu ^ bladeHash(uint(c.y)));
            float2 point = (float2(c) + float2(unit(h), unit(bladeHash(h + 1)))) * clumpSize;
            float2 to = point - root;
            float d = dot(to, to);
            if (d < nearest)
            {
                nearest = d;
                clumpMiddle = point;
                clumpSeed = h;
            }
        }
    }
    root += (clumpMiddle - root) * (p.clumpPull * unit(bladeHash(seed + 3)));
    // Whether it stands: the type's density, the ground's, the field's bounds
    float keep = p.density;
    float ground = b.rootHeight;
    if (fieldOn && field[0].groundMap >= 0)
    {
        float4 map = groundMap(field, root);
        ground = field[0].groundBase + 2 * map.r + map.g;
        keep *= saturate(map.b);
    }
    if (any(p.boundsMin != p.boundsMax))
    {
        float2 inside = min(root - p.boundsMin, p.boundsMax - root);
        keep *= saturate(min(inside.x, inside.y) / max(p.edge, 1e-3));
    }
    if (unit(bladeHash(seed + 4)) >= keep)
    {
        return out;
    }
    // Detail: blades of rank above the share kept at their distance fade out before their level is left behind
    float scale = 1;
    float widen = 1;
    if (fieldOn && field[0].viewerOn > 0)
    {
        float d = max(length(b.rootFromViewer), 1e-3);
        float first = p.lodError * field[0].projection / max(field[0].errorPixels, 1e-6);
        float share = first / (2 * d);
        share *= share;
        if (share < 1)
        {
            scale = smoothstep(0.0, 1.0, (share - b.rank) / (0.25 * share));
            widen = min(sqrt(1 / share), field[0].maxWiden);
        }
        float radius = field[0].radius;
        scale *= 1 - smoothstep(0.75 * radius, radius, d);
    }
    if (scale <= 0)
    {
        return out;
    }
    // Its posture at rest
    float clumpLength = 1 + p.clumpHeight * (2 * unit(bladeHash(clumpSeed + 2)) - 1);
    float bladeLength = p.height * (1 + p.heightVariation * (2 * unit(bladeHash(seed + 5)) - 1)) * clumpLength;
    float width = p.width * (0.75 + 0.5 * unit(bladeHash(seed + 6))) * widen;
    float facing = TAU * unit(bladeHash(seed + 7));
    float clumpFace = TAU * unit(bladeHash(clumpSeed + 3));
    facing = lerp(facing, clumpFace, p.clumpFacing * 0.5); // never all one way: half way at most
    float2 face = float2(cos(facing), sin(facing));
    float2 rest = face * (p.lean * unit(bladeHash(seed + 8)));
    float restCurve = p.curvature * (0.5 + unit(bladeHash(seed + 9)));
    // The wind: a steady lean by the speed squared, gusts travelling with it, the blade's own sway and flutter
    float2 windTilt = 0;
    float2 flutterTilt = 0;
    if (windOn && wind[0].speed > 0)
    {
        float speed = wind[0].speed;
        float time = wind[0].time;
        float2 along = wind[0].direction.xz;
        along = dot(along, along) > 1e-12 ? normalize(along) : float2(1, 0);
        float2 across = float2(-along.y, along.x);
        float stiffness = max(p.stiffness, 1e-3) * (0.7 + 0.6 * unit(bladeHash(seed + 10)));
        float gustScale = max(wind[0].gustScale, 1e-3);
        float gust = gustNoise((root - along * (time * speed * wind[0].gustSpeed)) / gustScale);
        float steady = p.windBend * speed * speed / stiffness;
        steady *= max(0.0, 1 + wind[0].gustStrength * (2 * gust - 1) * 1.5);
        float phase = TAU * unit(bladeHash(seed + 11));
        float omega = TAU * p.swayFrequency * (0.8 + 0.4 * unit(bladeHash(seed + 12)));
        float sway = wind[0].turbulence * (steady + 0.02 * speed);
        float lean = steady + sway * sin(omega * time + phase);
        float maxLean = max(p.maxLean, 1e-3);
        lean = maxLean * tanh(lean / maxLean); // saturates as the blade lies down
        windTilt = along * lean + across * (0.4 * sway * sin(1.37 * omega * time + 2.1 * phase));
        float flutter = wind[0].flutter * speed * 0.1;
        flutterTilt = across * (flutter * sin(TAU * 9 * time + 3.7 * phase));
    }
    // Snow: pressed down, and partly buried under it
    float2 snowTilt = 0;
    if (fieldOn && field[0].snowDepth > 0)
    {
        float depth = field[0].snowDepth;
        snowTilt = face * (1.35 * saturate(depth / (0.6 * bladeLength)));
        bladeLength *= 1 - 0.6 * saturate(depth / bladeLength);
    }
    bladeLength *= scale;
    width *= sqrt(scale);
    // The vertex: the segments before it summed, each of the blade's length over their number
    uint n = max(b.segments, 1u);
    uint at = min(uint(round(saturate(b.along) * n)), n);
    float step = bladeLength / n;
    float maxAngle = 1.48;
    float3 position = float3(0, 0, 0);
    for (uint j = 0; j < at; j++)
    {
        position += bladeDirection((j + 0.5) / n, rest, restCurve, windTilt, flutterTilt, snowTilt, maxAngle) * step;
    }
    float s = float(at) / n;
    float3 tangent = bladeDirection(s, rest, restCurve, windTilt, flutterTilt, snowTilt, maxAngle);
    float3 across3 = float3(-face.y, 0, face.x);
    across3 = normalize(across3 - tangent * dot(across3, tangent) + 1e-6);
    float halfWidth = 0.5 * width * sqrt(max(1 - s * s * s, 0.0));
    position += across3 * (b.side * halfWidth);
    float3 normal = normalize(cross(across3, tangent));
    normal = normalize(normal + across3 * (b.side * p.roundness));
    out.offset = float3(root.x - b.rootWorld.x, ground - b.rootHeight, root.y - b.rootWorld.y) + position;
    out.normal = normal;
    out.scale = scale;
    return out;
}
