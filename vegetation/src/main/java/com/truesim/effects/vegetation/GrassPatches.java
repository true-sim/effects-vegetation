package com.truesim.effects.vegetation;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.assets.mesh.Cone;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.Sphere;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.render.RenderAssets;

/**
 * The grass patch template: a mesh holding no blade, only each blade's slot (grass.slang makes the blade). Every vertex
 * of a blade lies at its root on the patch's plane, with in its texture coordinates where it is along the blade (u)
 * and across it (v: -1, 1, 0 at the tip), in its second set the blade's rank (u, 0 to 1), and in its color the blade's
 * number (red, green, blue bytes) and its segments at this level (alpha, over 255). A blade of n segments is 2n + 1
 * vertices and 2n - 1 triangles.
 *
 * <p>Detail is a cluster hierarchy the template authors itself: level k (0 the finest) holds the blades of rank below
 * 4^-k, each with one segment fewer, in groups of cells halving in size towards full detail; a group's error is 0 at
 * full detail and the patch's error doubling each level after, so the engine's detail rule (a group's error projected
 * on screen at most the allowed pixels) leaves full detail at the distance the error projects to a pixel, each coarser
 * level at twice the one before. The blades a coarser level drops fade out before its distance (blade.slang), so the
 * switch is not seen. Ranks follow a 16 x 16 ordered dither with a hash below it, so each level's blades spread evenly.
 *
 * <p>The template is a generated asset ({@link #ref}): one per parameters, shared by every patch of every field.
 */
public final class GrassPatches {

    /** The template's attributes. */
    public static final VertexFormat FORMAT = VertexFormat.of(VertexAttribute.UV0, VertexAttribute.UV1,
        VertexAttribute.COLOR);
    /** Most vertices and triangles a meshlet holds: the engine's mesh stages emit 64 and 124. */
    static final int MESHLET_VERTICES = 64;
    static final int MESHLET_TRIANGLES = 124;

    /** Makes the template from its parameters; the seed is not used (the template is the same for every seed). */
    public static final Generator<GrassPatch, MeshData> GENERATOR = (patch, seed) -> build(patch);

    private GrassPatches() {
    }

    /** The template of {@code patch} as an asset. */
    @AllowAllocation("a reference made where a field is set up")
    public static AssetRef<MeshData> ref(GrassPatch patch) {
        return AssetRef.generated(RenderAssets.MESH, GENERATOR, patch, 0);
    }

    /** A blade's rank: a 16 x 16 ordered dither of its grid cell, with a hash of its number below it. */
    static float rank(int x, int z, int blade) {
        // the finest bit pair most significant: each quarter of the order holds one blade of every 2 x 2 block
        int bayer = 0;
        for (int bit = 3; bit >= 0; bit--) {
            int bx = x >> bit & 1;
            int bz = z >> bit & 1;
            bayer |= ((bx ^ bz) << 1 | bz) << (2 * (3 - bit));
        }
        float r = (float) ((bayer + unit(hash(blade * 0x9E3779B1 + 13))) / 256.0);
        return Float.float16ToFloat(Float.floatToFloat16(Math.min(r, 0.999f))); // as the template's half-float coordinates hold it

    }

    /** The template of {@code patch}. */
    @AllowAllocation("building a template, once per parameters")
    public static MeshData build(GrassPatch patch) {
        int grid = patch.grid();
        int blades = grid * grid;
        float spacing = patch.spacing();
        float half = patch.size() / 2;
        float[] rootX = new float[blades];
        float[] rootZ = new float[blades];
        float[] rank = new float[blades];
        for (int z = 0; z < grid; z++) {
            for (int x = 0; x < grid; x++) {
                int b = z * grid + x;
                // jittered within the cell; the world adds more (blade.slang)
                rootX[b] = -half + (x + 0.5f + 0.7f * (unit(hash(b * 3 + 1)) - 0.5f)) * spacing;
                rootZ[b] = -half + (z + 0.5f + 0.7f * (unit(hash(b * 3 + 2)) - 0.5f)) * spacing;
                rank[b] = rank(x, z, b);
            }
        }
        int levels = patch.levels();
        // Each level's blades by group cell: the cells halve from the coarsest level (one cell) towards full detail
        int vertexCount = 0;
        int[][] levelBlades = new int[levels][];
        for (int level = 0; level < levels; level++) {
            double share = GrassPatch.share(level);
            int count = 0;
            for (int b = 0; b < blades; b++) {
                count += rank[b] < share ? 1 : 0;
            }
            int[] chosen = new int[count];
            int i = 0;
            for (int b = 0; b < blades; b++) {
                if (rank[b] < share) {
                    chosen[i++] = b;
                }
            }
            levelBlades[level] = chosen;
            vertexCount += count * (2 * patch.segments(level) + 1);
        }
        if (vertexCount == 0) {
            throw new IllegalArgumentException("A patch without blades: " + patch);
        }
        float[] positions = new float[3 * vertexCount];
        float[] uv0 = new float[2 * vertexCount];
        float[] uv1 = new float[2 * vertexCount];
        float[] colors = new float[4 * vertexCount];
        MeshData.Builder mesh = MeshData.builder(FORMAT, vertexCount);
        int vertex = 0;
        double margin = patch.height();
        for (int level = levels - 1; level >= 0; level--) { // coarsest first
            int n = patch.segments(level);
            int cells = 1 << (levels - 1 - level);
            double cell = patch.size() / cells;
            int parentCells = Math.max(1, cells / 2);
            double parentCell = patch.size() / parentCells;
            double error = patch.levelError(level);
            double parentError = level == levels - 1 ? Double.POSITIVE_INFINITY : patch.levelError(level + 1);
            int[] chosen = levelBlades[level];
            // blades by cell
            int[] cellOf = new int[chosen.length];
            int[] counts = new int[cells * cells + 1];
            for (int i = 0; i < chosen.length; i++) {
                int b = chosen[i];
                int cx = Math.clamp((int) Math.floor((rootX[b] + half) / cell), 0, cells - 1);
                int cz = Math.clamp((int) Math.floor((rootZ[b] + half) / cell), 0, cells - 1);
                cellOf[i] = cz * cells + cx;
                counts[cellOf[i] + 1]++;
            }
            for (int c = 0; c < cells * cells; c++) {
                counts[c + 1] += counts[c];
            }
            int[] order = new int[chosen.length];
            int[] fill = counts.clone();
            for (int i = 0; i < chosen.length; i++) {
                order[fill[cellOf[i]]++] = chosen[i];
            }
            int perMeshlet = Math.min(MESHLET_VERTICES / (2 * n + 1), MESHLET_TRIANGLES / (2 * n - 1));
            for (int c = 0; c < cells * cells; c++) {
                int cx = c % cells;
                int cz = c / cells;
                Sphere group = cellSphere(-half + (cx + 0.5) * cell, -half + (cz + 0.5) * cell, cell, margin);
                int px = cx * parentCells / cells;
                int pz = cz * parentCells / cells;
                Sphere parent = level == levels - 1 ? group
                    : cellSphere(-half + (px + 0.5) * parentCell, -half + (pz + 0.5) * parentCell, parentCell, margin);
                for (int first = counts[c]; first < counts[c + 1]; first += perMeshlet) {
                    int count = Math.min(perMeshlet, counts[c + 1] - first);
                    int[] vertices = new int[count * (2 * n + 1)];
                    int[] triangles = new int[3 * count * (2 * n - 1)];
                    int t = 0;
                    double minX = Double.MAX_VALUE;
                    double maxX = -Double.MAX_VALUE;
                    double minZ = Double.MAX_VALUE;
                    double maxZ = -Double.MAX_VALUE;
                    for (int k = 0; k < count; k++) {
                        int b = order[first + k];
                        int local = k * (2 * n + 1);
                        for (int v = 0; v <= 2 * n; v++) {
                            int along = v / 2;
                            boolean tip = v == 2 * n;
                            positions[3 * vertex] = rootX[b];
                            positions[3 * vertex + 2] = rootZ[b];
                            uv0[2 * vertex] = tip ? 1 : (float) along / n;
                            uv0[2 * vertex + 1] = tip ? 0 : (v % 2 == 0 ? -1 : 1);
                            uv1[2 * vertex] = rank[b];
                            colors[4 * vertex] = (b & 0xff) / 255f;
                            colors[4 * vertex + 1] = (b >> 8 & 0xff) / 255f;
                            colors[4 * vertex + 2] = (b >> 16 & 0xff) / 255f;
                            colors[4 * vertex + 3] = n / 255f;
                            vertices[local + v] = vertex++;
                        }
                        for (int s = 0; s < n - 1; s++) {
                            int l0 = local + 2 * s;
                            triangles[t++] = l0;
                            triangles[t++] = l0 + 1;
                            triangles[t++] = l0 + 2;
                            triangles[t++] = l0 + 1;
                            triangles[t++] = l0 + 3;
                            triangles[t++] = l0 + 2;
                        }
                        triangles[t++] = local + 2 * (n - 1);
                        triangles[t++] = local + 2 * (n - 1) + 1;
                        triangles[t++] = local + 2 * n;
                        minX = Math.min(minX, rootX[b]);
                        maxX = Math.max(maxX, rootX[b]);
                        minZ = Math.min(minZ, rootZ[b]);
                        maxZ = Math.max(maxZ, rootZ[b]);
                    }
                    double radius = Math.max(1e-3, 0.5 * Math.hypot(maxX - minX, maxZ - minZ));
                    mesh.meshlet(vertices, triangles, 0, new Sphere(0.5 * (minX + maxX), 0, 0.5 * (minZ + maxZ),
                        radius), Cone.NONE);
                    mesh.lod(group, error, parent, parentError);
                }
            }
            mesh.level(error);
        }
        return mesh.positions(positions).uv(VertexAttribute.UV0, uv0).uv(VertexAttribute.UV1, uv1).colors(colors)
            .build();
    }

    /** A group's sphere: round its cell's square on the plane, reaching {@code margin} up. */
    private static Sphere cellSphere(double x, double z, double side, double margin) {
        double r = Math.sqrt(0.5 * side * side + margin * margin);
        return new Sphere(x, 0, z, r);
    }

    static int hash(int v) {
        int state = v * 747796405 + 0xAC564B05;
        int word = ((state >>> ((state >>> 28) + 4)) ^ state) * 277803737;
        return (word >>> 22) ^ word;
    }

    static float unit(int h) {
        return (h >>> 8) / 16777216f;
    }
}
