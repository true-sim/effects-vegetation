// The grass type's parameters (grass.slang), apart from it: the build reads material records before it writes the
// shading hooks' dispatch, so a record's module must not import what the type's may (engine pitfalls.md).
module grassParameters;

import materials;

/**
 * A meadow's grass: the blades' look, their shape and its spread, clumps, how they bend in the wind, and what the patch
 * template they are drawn over was built with (the sync sets those from the settings). Lengths in metres.
 */
[MaterialParameters(GEOMETRY_DISPLACE | FEATURE_DIFFUSE_TRANSMISSION)]
public struct VegetationGrassParameters
{
    [Color] public float3 rootColor = float3(0.025, 0.07, 0.012); // linear, at the root
    [Color] public float3 tipColor = float3(0.16, 0.30, 0.05);    // linear, at the tip
    [Color] public float3 dryColor = float3(0.40, 0.34, 0.13);    // linear: dry blades' upper part
    [Range(0, 1)] public float dryness = 0.12;                    // share of blades with dry tips
    [Range(0, 1)] public float colorVariation = 0.25;             // how far blades' colors spread
    public float height = 0.42;                                    // a blade's length
    [Range(0, 1)] public float heightVariation = 0.35;            // spread of the length, either way
    public float width = 0.011;                                    // at the root
    public float lean = 0.3;                                       // radians: the rest lean, a blade's up to this
    public float curvature = 0.6;                                  // radians more at the tip than at the root
    public float clumpSize = 0.55;                                 // metres across a clump
    [Range(0, 1)] public float clumpPull = 0.35;                  // how far roots draw to their clump's middle
    [Range(0, 1)] public float clumpHeight = 0.3;                 // how much clumps' lengths differ
    [Range(0, 1)] public float clumpFacing = 0.45;                // how far blades face as their clump does
    [Range(0, 1)] public float density = 1;                       // share of the template's blades standing
    public float stiffness = 1;                                    // against the wind: lean over it
    public float windBend = 0.01;                                  // radians of steady lean per (m/s) squared
    public float maxLean = 1.3;                                    // radians: the most the wind lays a blade over
    public float swayFrequency = 1.3;                              // a blade's own sway, cycles a second
    [Range(0, 1)] public float roundness = 0.45;                  // normals turned across the blade: it reads round
    [Range(0, 1)] public float roughness = 0.5;
    public float patchSize = 8;                                    // the template's side: blades' identity by patch
    public float spacing = 0.07;                                   // the template's blade spacing: roots jitter by it
    public float lodError = 0.0086;                                // the template's first coarser level's error
    public float2 boundsMin = float2(0, 0);                        // the field's world x and z: blades outside go
    public float2 boundsMax = float2(0, 0);                        // (min = max: unbounded)
    public float edge = 1;                                         // metres over which blades thin out at the bounds
    [Range(0, 1)] public float detailColors = 0;                  // 1: blades colored by their detail level (debug)
};
