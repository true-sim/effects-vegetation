// The plant type's parameters (plant.slang), apart from it, as grassParameters.slang is.
module plantParameters;

import materials;
import materials.metallicRoughness;

/**
 * A tree, a shrub or a plant swaying in the wind: metallic-roughness as its model's material has it (nested), and how
 * its parts move. Three motions, each by a weight per vertex: the trunk leaning and swaying about its foot (by height
 * over `height`, squared), branches swinging (weight from the vertex color's red, else by distance from the trunk over
 * `radius` and height), leaves fluttering along their normals (weight from the color's green, else `leafWeight` by
 * height); the color's blue is the part's phase. With `windColors` 0 the weights come from the shape alone and the
 * vertex color tints as metallic-roughness's does; with 1 it carries the weights and tints nothing. Lengths in mesh
 * units; speeds are the wind's (vegetation.wind), metres a second.
 */
[MaterialParameters(GEOMETRY_DISPLACE | FEATURE_DIFFUSE_TRANSMISSION | FEATURE_SPECULAR | FEATURE_SHEEN)]
public struct VegetationPlantParameters
{
    public MetallicRoughnessParameters base;
    public float height = 10;            // the plant's height over its origin: the trunk's weight by height
    public float radius = 3;             // the crown's radius: branches' weight by distance from the trunk
    public float trunkBend = 0.0006;     // radians of steady lean at the top per (m/s) squared
    public float maxLean = 0.3;          // radians: the most the trunk leans
    [Range(0, 2)] public float trunkSway = 0.35; // its sway, as a share of the lean
    public float trunkFrequency = 0.3;   // cycles a second
    public float branchSway = 0.008;     // mesh units a branch swings per m/s at weight 1
    public float branchFrequency = 0.9;  // cycles a second
    public float leafFlutter = 0.004;    // mesh units a leaf flutters per m/s at weight 1
    public float leafFrequency = 3.5;    // cycles a second
    [Range(0, 1)] public float leafWeight = 0; // the leaves' weight where colors carry none: 1 for a leaves material
    public int windColors = 0;           // 1: the vertex color carries the weights (r branch, g leaf, b phase)
};
