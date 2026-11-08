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
        // The weights: from the vertex color, or from the shape
        float branch;
        float leaf;
        float phase;
        if (p.windColors != 0)
        {
            branch = in.color.r;
            leaf = in.color.g;
            phase = in.color.b;
        }
        else
        {
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
        float bend = lean * up * up;
        float y = max(position.y, 0.0);
        float3 move = along * (sin(bend) * y) + float3(0, (cos(bend) - 1) * y, 0);
        // Branches and leaves
        float swing = sin(TAU * p.branchFrequency * time + own + TAU * phase);
        move += (along + 0.6 * across) * (p.branchSway * speed * branch * swing);
        float flutter = sin(TAU * p.leafFrequency * time + TAU * 7.3 * phase + own);
        move += in.normal * (p.leafFlutter * speed * leaf * flutter);
        d.position = position + move;
        // The normal turned as the trunk's slope is
        float slope = 2 * lean * up / max(p.height, 1e-3) * y;
        d.normal = normalize(in.normal - along * (slope * in.normal.y) + float3(0, 1, 0) * (slope * dot(in.normal, along)));
        return d;
    }
};
