// The impostor types' parameters (impostor.slang), apart from them, as plantParameters.slang is.
module impostorParameters;

import materials;
import materials.metallicRoughness;

/**
 * A plant far away as an octahedral impostor: a quad turned to the viewer (vegetation.field's), drawing the frames of
 * its atlas (ImpostorBaker) that look from nearest the viewer's direction, blended, each moved by its depth. The
 * atlas is `frames` x `frames` views over the upper hemisphere (hemi-octahedral), `frames` cells wide and twice as
 * tall: above, sqrt(albedo) and coverage; below, the mesh-space normal (rgb, n / 2 + 1 / 2) and the depth toward the
 * view (a, -radius to radius as 0 to 1). Lengths in mesh units but the fade's, metres from the viewer.
 */
[MaterialParameters(GEOMETRY_DISPLACE | GEOMETRY_KEEP | FEATURE_DIFFUSE_TRANSMISSION)]
public struct VegetationImpostorParameters
{
    [Texture(LINEAR)] public int atlas = -1;  // the baked atlas
    public int frames = 8;                    // views per side of its grid
    public float3 center = float3(0, 0, 0);   // the sphere the frames cover: its center in mesh space
    public float radius = 1;                  // and radius
    public float fadeStart = 0;               // metres from the viewer where it starts to fade in
    public float fadeEnd = 0;                 // and whole from half way to here (0, 0: always whole)
    [Range(0, 1)] public float cutoff = 0.5;  // coverage below this is a hole
    [Range(0, 1)] public float roughness = 0.85;
    [Range(0, 1)] public float parallax = 1;  // how far the frames move by their depth (0: flat cards)
};

/** The bake's type's parameters: a model's metallic-roughness material drawn unlit as one of the atlas's parts. */
[MaterialParameters(FEATURE_UNLIT)]
public struct VegetationImpostorBakeParameters
{
    public MetallicRoughnessParameters base;
    public int mode = 0;                      // 0 sqrt(albedo), 1 the normal (n / 2 + 1 / 2), 2 the position
    public int windColors = 0;                // 1: the vertex color carries the wind's weights, not a tint
    public float3 center = float3(0, 0, 0);   // mode 2: the position as (p - center) / (2 radius) + 1 / 2
    public float radius = 1;
};
