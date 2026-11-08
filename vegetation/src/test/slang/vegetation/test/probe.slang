// For tests (BladeProbe): blade.slang's bladeVertex evaluated for listed vertices on the GPU, exactly as the grass
// type's displace() calls it, its results written out to be read back.
import vegetation.blade;

struct ProbePush
{
    VegetationGrassParameters* grass;
    VegetationWindParameters* wind;
    VegetationFieldParameters* field;
    float4* inputs;   // three a vertex: rootFromViewer and along; rootWorld, rootHeight, side; cell, rank, id
    float4* outputs;  // two a vertex: offset and scale; normal
    uint count;
    uint windOn;
    uint fieldOn;
    uint segments;
};

[[vk::push_constant]] ConstantBuffer<ProbePush> push;

[shader("compute")]
[numthreads(64, 1, 1)]
void probeMain(uint3 thread : SV_DispatchThreadID)
{
    uint i = thread.x;
    if (i >= push.count)
    {
        return;
    }
    float4 a = push.inputs[3 * i];
    float4 c = push.inputs[3 * i + 1];
    float4 e = push.inputs[3 * i + 2];
    BladeInput b;
    b.rootFromViewer = a.xyz;
    b.along = a.w;
    b.rootWorld = c.xy;
    b.rootHeight = c.z;
    b.side = c.w;
    b.cell = int2(e.xy);
    b.rank = e.z;
    b.id = asuint(e.w);
    b.segments = push.segments;
    BladeOutput out = bladeVertex(push.grass[0], b, push.wind, push.windOn != 0, push.field, push.fieldOn != 0);
    push.outputs[2 * i] = float4(out.offset, out.scale);
    push.outputs[2 * i + 1] = float4(out.normal, 0);
}
