// The wind vegetation sways in: a geometry hook (engine-render's IGeometryHook: a record and its bit, no function) whose
// record the scene's geometry hook block carries for the vegetation material types' displace() (grass.slang,
// plant.slang). Content sets it as the scene's values (engine-scene's ShadingHookComponent "vegetation.wind"); the
// vegetation's sync adds the time each frame. Speed 0 (or the hook off) is a calm: every blade and branch at rest.
module vegetationWind;

import hooks;

[ShadingHookParameters]
public struct VegetationWindParameters
{
    public float3 direction = float3(1, 0, 0); // where it blows, in the world (its level part counts)
    public float speed = 0;                    // metres a second near the ground: steady lean grows with its square
    public float gustStrength = 0.6;           // how much gusts add and take of the lean (0: a steady wind)
    public float gustScale = 18;               // metres across a gust: the travelling noise's cell
    public float gustSpeed = 1;                // how fast gusts travel, as a share of the wind's speed
    public float turbulence = 0.25;            // each blade's own sway, as a share of the lean
    public float flutter = 0.08;               // the tips' fast flutter, radians at 10 m/s
    public float time = 0;                     // seconds: what animates it (the sync's clock)
};

[ShadingHook("vegetation.wind")]
public struct VegetationWind : IGeometryHook
{
    public typealias Parameters = VegetationWindParameters;
};
