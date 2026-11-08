package com.truesim.effects.vegetation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.MeshLevel;
import com.truesim.scene.assets.mesh.VertexAttribute;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The grass patch template: every blade's slot once at full detail, the roots covering the patch to its edges, each
 * coarser level the blades of rank below its share with one segment fewer, spread evenly, meshlets within the mesh
 * stages' limits, and the detail rule's errors doubling level by level.
 */
class GrassPatchesTest {

    private static final GrassPatch PATCH = new GrassPatch(8, 60, 4, 4, 0.01f, 1);

    @Test
    void levelsKeepTheBladesOfRankBelowTheirShareWithOneSegmentFewer() {
        try (MeshData mesh = GrassPatches.build(PATCH)) {
            assertThat(mesh.levels()).hasSize(4);
            double[] uv = new double[2];
            double[] color = new double[4];
            double[] lod = new double[10];
            Map<Integer, Integer> bladesByLevel = new HashMap<>();
            for (int m = 0; m < mesh.meshletCount(); m++) {
                assertThat(mesh.meshletVertexCount(m)).isLessThanOrEqualTo(64);
                assertThat(mesh.meshletTriangleCount(m)).isLessThanOrEqualTo(124);
                int first = mesh.meshletVertices().getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED,
                    mesh.meshletVertexOffset(m));
                mesh.unorm4(VertexAttribute.COLOR, first, color);
                int segments = (int) Math.round(color[3] * 255);
                int level = PATCH.segments() - segments;
                mesh.uv(VertexAttribute.UV1, first, uv);
                assertThat(uv[0]).as("rank below the level's share").isLessThan(GrassPatch.share(level));
                mesh.meshletLod(m, lod);
                assertThat(lod[4]).as("own error").isCloseTo(PATCH.levelError(level), within(1e-7));
                assertThat(lod[9]).as("parent error").isEqualTo(level == 3 ? Double.POSITIVE_INFINITY
                    : PATCH.levelError(level + 1), within(1e-7));
                bladesByLevel.merge(level, mesh.meshletVertexCount(m) / (2 * segments + 1), Integer::sum);
            }
            int blades = PATCH.blades();
            for (int level = 0; level < 4; level++) {
                assertThat(bladesByLevel.get(level) / (double) blades).as("level %d's share", level)
                    .isCloseTo(GrassPatch.share(level), within(0.01));
            }
        }
    }

    @Test
    void rootsCoverThePatchToItsEdgesAndEachLevelSpreadsEvenly() {
        try (MeshData mesh = GrassPatches.build(PATCH)) {
            double[] p = new double[3];
            double[] uv = new double[2];
            double minX = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            int quarter = 0;
            int[] quarterByQuadrant = new int[4];
            for (int v = 0; v < mesh.vertexCount(); v++) {
                mesh.position(v, p);
                minX = Math.min(minX, p[0]);
                maxX = Math.max(maxX, p[0]);
                assertThat(p[1]).as("on the plane").isCloseTo(0, within(1e-6));
                mesh.uv(VertexAttribute.UV1, v, uv);
                if (uv[0] < 0.25) {
                    quarter++;
                    quarterByQuadrant[(p[0] < 0 ? 0 : 1) + (p[2] < 0 ? 0 : 2)]++;
                }
            }
            double spacing = PATCH.spacing();
            assertThat(minX).as("to the west edge").isLessThan(-4 + spacing);
            assertThat(maxX).as("to the east edge").isGreaterThan(4 - spacing);
            for (int q = 0; q < 4; q++) {
                assertThat(quarterByQuadrant[q] / (double) quarter).as("quadrant %d of the quarter", q)
                    .isCloseTo(0.25, within(0.03));
            }
        }
    }

    @Test
    void theTemplateIsOneAssetPerParameters() {
        assertThat(GrassPatches.ref(PATCH)).isEqualTo(GrassPatches.ref(new GrassPatch(8, 60, 4, 4, 0.01f, 1)));
        assertThat(GrassPatches.ref(PATCH)).isNotEqualTo(GrassPatches.ref(new GrassPatch(8, 61, 4, 4, 0.01f, 1)));
        assertThat(PATCH.segments(3)).isEqualTo(1);
        assertThat(PATCH.segments(5)).isEqualTo(1);
        assertThat(GrassPatches.quarterTurns(3, -7)).isBetween(0, 3);
        for (MeshLevel level : GrassPatches.build(PATCH).levels()) {
            assertThat(level).isNotNull();
        }
    }
}
