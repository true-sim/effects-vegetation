// The plant type's parameters (plant.slang), apart from it, as grassParameters.slang is.
module plantParameters;

import materials;
import materials.metallicRoughness;

/**
 * A tree, a shrub or a plant swaying in the wind: metallic-roughness as its model's material has it (nested), and how
 * its parts move. Three motions, each by a weight per vertex: the trunk leaning and swaying about its foot, branches
 * swinging about their pivot, leaves fluttering along their normals. With `windColors` 1 the weights are
 * common-procedural's (its ProceduralVertex): the vertex color's red the trunk's weight, green the branch's, blue the
 * leaf weight (high nibble, 15ths) and the phase (low nibble, 16ths), the second UV set the main branch's pivot height
 * and reach; the color tints nothing (its alpha, always 1, is left to masking). With 0 they come from the
 * shape: the trunk's by height over `height`, squared; a branch's by distance from the trunk over `radius`, times
 * height; leaves `leafWeight` by height; the phase from the position; the color tints as metallic-roughness's does.
 * Lengths in mesh units; speeds are the wind's (vegetation.wind), metres a second.
 */
[MaterialParameters(GEOMETRY_DISPLACE | GEOMETRY_KEEP | FEATURE_DIFFUSE_TRANSMISSION | FEATURE_SPECULAR | FEATURE_SHEEN)]
public struct VegetationPlantParameters
{
    public MetallicRoughnessParameters base;
    public float height = 10;            // the plant's height over its origin: the trunk's weight by height
    public float radius = 3;             // the crown's radius: branches' weight by distance from the trunk
    public float trunkBend = 0.0003;     // radians of steady lean at the top per (m/s) squared
    public float maxLean = 0.3;          // radians: the most the trunk leans
    [Range(0, 2)] public float trunkSway = 0.35; // its sway, as a share of the lean
    public float trunkFrequency = 0.3;   // cycles a second
    public float branchSway = 0.008;     // mesh units a branch's tip swings per m/s at weight 1
    public float branchFrequency = 0.9;  // cycles a second
    public float leafFlutter = 0.004;    // mesh units a leaf flutters per m/s at weight 1
    public float leafFrequency = 3.5;    // cycles a second
    [Range(0, 1)] public float leafWeight = 0; // the leaves' weight where colors carry none: 1 for a leaves material
    public int windColors = 0;           // 1: the color and UV1 carry the weights, phase, pivot (common-procedural)
    public float fadeStart = 0;          // metres from the viewer where it starts to fade into its impostor
    public float fadeEnd = 0;            // and is gone (0, 0: never fades; fade.slang)
};
