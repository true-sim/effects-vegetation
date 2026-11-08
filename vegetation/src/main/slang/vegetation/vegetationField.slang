// What grass needs to know about the world beyond its patch, as a geometry hook (a record and its bit): the viewer its
// detail falls with distance from (the main camera, the same for every view so shadows and motion vectors displace as
// the camera's image does), the ground it stands on (a map of heights and density), and the snow lying on it. The
// vegetation's sync writes it each frame; without it (off) blades stand on their patch's plane at full detail.
module vegetationField;

import hooks;

[ShadingHookParameters]
public struct VegetationFieldParameters
{
    public float3 viewer = float3(0, 0, 0);    // the viewer's world position: high part
    public float viewerOn = 0;                 // 1: detail falls with the distance from it; 0: full detail everywhere
    public float3 viewerLow = float3(0, 0, 0); // and the rest
    public float projection = 935.3;           // the viewer's pixels per unit at distance 1 (its projection scale)
    public float errorPixels = 1;              // the detail rule's allowed pixels (render.lodError)
    public float radius = 120;                 // metres: grass ends here, faded to nothing over its last quarter
    public float snowDepth = 0;                // metres of snow lying: blades flattened, and buried under it
    public float maxWiden = 4;                 // how much wider blades get at most as their number falls
    public int groundMap = -1;                 // sampled slot of the ground map (GroundMap): r, g height, b density; -1: none
    public float groundMapSize = 1;            // metres it covers, a side
    public float2 groundMapOrigin = float2(0, 0); // its corner's world x and z
    public float groundBase = 0;               // the height its texels count from (metres)
    public float groundTexel = 1;              // metres per texel
};

[ShadingHook("vegetation.field")]
public struct VegetationField : IGeometryHook
{
    public typealias Parameters = VegetationFieldParameters;
};
