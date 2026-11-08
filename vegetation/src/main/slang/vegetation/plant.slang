// Trees, shrubs and plants in the wind: a material type shading as metallic-roughness and displacing its vertices by
// the scene's wind (vegetation.wind): the trunk leaning about its foot with the steady wind and its gusts and swaying
// slowly, branches swinging, leaves fluttering (plantParameters.slang has the weights). Every motion grows with the
// wind's speed: a calm leaves the model as it is.
module plant;

import materials;
import materials.metallicRoughness;
__exported import vegetation.plantParameters;
import vegetation.blade;

/** The wind hook's index in the shading hooks' dispatch, resolved when a shader links (hooks.properties). */
extern static const uint RUN_HOOK_VEGETATION_WIND_INDEX;

[MaterialType("vegetationPlant")]
public struct VegetationPlant : IMaterial
{
    public typealias Parameters = VegetationPlantParameters;

    public static Surface surface(VegetationPlantParameters p, MaterialRef m, SurfaceInput in)
    {
        SurfaceInput input = in;
        if (p.windColors != 0)
        {
            input.color = float4(1, 1, 1, 1); // the color carries the wind's weights, not a tint
        }
        return evaluate(p.base, input);
    }

    public override static Displaced displace(VegetationPlantParameters p, MaterialRef m, DisplacePoint in)
    {
        Displaced d = Displaced.of(in);
        if (!in.hookOn(RUN_HOOK_VEGETATION_WIND_INDEX))
        {
            return d;
        }
        VegetationWindParameters* wind = in.hook<VegetationWindParameters>(RUN_HOOK_VEGETATION_WIND_INDEX);
        float speed = wind[0].speed;
        if (!(speed > 0))
        {
            return d;
        }
        float time = wind[0].time;
        float3 position = in.position;
        float up = saturate(position.y / max(p.height, 1e-3));
        // The weights: from the vertex color and second UV set (common-procedural's trees, its ProceduralVertex: red the
        // trunk's weight, green the branch's, blue a byte of the leaf weight's 16ths of 15 (high nibble) and the phase's
        // 16ths (low nibble), alpha always 1; UV1 the main branch's pivot height and reach), or from the shape
        float trunk;
        float branch;
        float leaf;
        float phase;
        float pivot = 0;
        float reach = max(p.radius, 1e-3);
        if (p.windColors != 0)
        {
            trunk = in.color.r;
            branch = in.color.g;
            uint packed = uint(round(saturate(in.color.b) * 255));
            leaf = float(packed >> 4) / 15;
            phase = float(packed & 15u) / 16;
            pivot = in.uv1.x;
            reach = max(in.uv1.y, 1e-3);
        }
        else
        {
            trunk = up * up;
            branch = saturate(length(position.xz) / max(p.radius, 1e-3)) * up;
            leaf = p.leafWeight * up;
            int3 cell = int3(floor(position * 4));
            phase = unit(bladeHash(uint(cell.x) * 0x9E3779B1u ^ bladeHash(uint(cell.y) ^ bladeHash(uint(cell.z)))));
        }
        // The wind in the mesh's axes, and the plant's own phase
        float2 level = wind[0].direction.xz;
        level = dot(level, level) > 1e-12 ? normalize(level) : float2(1, 0);
        float3 along = normalize(in.toMesh(float3(level.x, 0, level.y)));
        float3 across = normalize(in.toMesh(float3(-level.y, 0, level.x)));
        float3 origin = in.origin + in.originLow;
        float own = TAU * unit(bladeHash(in.instance * 0x85EBCA6Bu + 5u));
        float gust = gustNoise((origin.xz - level * (time * speed * wind[0].gustSpeed)) / max(wind[0].gustScale, 1e-3));
        // The trunk: a lean about the foot by the height squared, kept to its length (the top drops as it leans)
        float lean = p.trunkBend * speed * speed * max(0.0, 1 + wind[0].gustStrength * (2 * gust - 1) * 1.5);
        lean += p.trunkSway * lean * sin(TAU * p.trunkFrequency * time + own);
        float maxLean = max(p.maxLean, 1e-3);
        lean = maxLean * tanh(lean / maxLean);
        float bend = lean * trunk;
        float y = max(position.y, 0.0);
        float3 move = along * (sin(bend) * y) + float3(0, (cos(bend) - 1) * y, 0);
        // Branches: swung about their pivot (0, pivot, 0) towards and across the wind, keeping their length
        float swing = sin(TAU * p.branchFrequency * time + own + TAU * phase);
        float angle = p.branchSway * speed * branch * swing / reach;
        float3 arm = position - float3(0, pivot, 0);
        float3 axis = normalize(cross(float3(0, 1, 0), along + 0.6 * across) + float3(1e-6, 0, 0));
        float3 turned = arm * cos(angle) + cross(axis, arm) * sin(angle) + axis * (dot(axis, arm) * (1 - cos(angle)));
        move += turned - arm;
        float flutter = sin(TAU * p.leafFrequency * time + TAU * 7.3 * phase + own);
        move += in.normal * (p.leafFlutter * speed * leaf * flutter);
        d.position = position + move;
        // The normal turned as the trunk's slope is
        float slope = lean * trunk;
        d.normal = normalize(in.normal - along * (slope * in.normal.y) + float3(0, 1, 0) * (slope * dot(in.normal, along)));
        return d;
    }
};
