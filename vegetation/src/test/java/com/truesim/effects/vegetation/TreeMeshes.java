package com.truesim.effects.vegetation;

import com.truesim.engine.render.testing.TestMeshes;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.meshbuild.MeshBuilder;
import java.util.Arrays;

/** Placeholder plants for tests, as a showcase's models would be: submesh 0 the bark, 1 the foliage. */
public final class TreeMeshes {

    private static final int SIDES = 10;

    private TreeMeshes() {
    }

    /** A spruce {@code height} tall: a trunk to 0.8 of it and four cones of foliage up to the top, {@code radius} wide. */
    public static MeshData spruce(double height, double radius) {
        return spruce(height, radius, false);
    }

    /** {@link #spruce(double, double)}, with common-procedural's wind attributes where {@code wind}. */
    public static MeshData spruce(double height, double radius, boolean wind) {
        float[] positions = new float[0];
        int[] trunk = new int[0];
        int[] crown = new int[0];
        // the trunk: an open cylinder
        int base = 0;
        float r = (float) (0.035 * height);
        positions = Arrays.copyOf(positions, 3 * 2 * SIDES);
        for (int i = 0; i < SIDES; i++) {
            double a = 2 * Math.PI * i / SIDES;
            set(positions, 2 * i, r * Math.cos(a), 0, r * Math.sin(a));
            set(positions, 2 * i + 1, 0.6 * r * Math.cos(a), 0.8 * height, 0.6 * r * Math.sin(a));
        }
        trunk = new int[6 * SIDES];
        for (int i = 0; i < SIDES; i++) {
            int a = 2 * i;
            int b = 2 * ((i + 1) % SIDES);
            int[] quad = {a, a + 1, b, b, a + 1, b + 1};
            System.arraycopy(quad, 0, trunk, 6 * i, 6);
        }
        base = 2 * SIDES;
        // the crown: tiers of cones, each a ring and its apex
        int tiers = 4;
        crown = new int[tiers * SIDES * 9];
        int at = 0;
        for (int t = 0; t < tiers; t++) {
            double bottom = height * (0.2 + 0.19 * t);
            double top = bottom + height * 0.38;
            double ring = radius * (1 - 0.22 * t);
            int first = positions.length / 3;
            positions = Arrays.copyOf(positions, positions.length + 3 * (2 * SIDES + 1));
            for (int i = 0; i < SIDES; i++) {
                double a = 2 * Math.PI * (i + 0.5 * t) / SIDES;
                set(positions, first + i, ring * Math.cos(a), bottom, ring * Math.sin(a));
                set(positions, first + SIDES + i, 0.15 * ring * Math.cos(a), bottom + 0.05 * height,
                    0.15 * ring * Math.sin(a));
            }
            int apex = first + 2 * SIDES;
            set(positions, apex, 0, Math.min(top, height), 0);
            for (int i = 0; i < SIDES; i++) {
                int a = first + i;
                int b = first + (i + 1) % SIDES;
                crown[at++] = a;
                crown[at++] = apex;
                crown[at++] = b;
                int ia = first + SIDES + i;
                int ib = first + SIDES + (i + 1) % SIDES;
                crown[at++] = a;
                crown[at++] = b;
                crown[at++] = ia;
                crown[at++] = b;
                crown[at++] = ib;
                crown[at++] = ia;
            }
        }
        int count = positions.length / 3;
        int[] all = new int[trunk.length + crown.length];
        System.arraycopy(trunk, 0, all, 0, trunk.length);
        System.arraycopy(crown, 0, all, trunk.length, crown.length);
        if (!wind) {
            return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL), count).positions(positions)
                .attribute(VertexAttribute.NORMAL, TestMeshes.normals(positions, all)).submesh(trunk).submesh(crown)
                .buildHierarchy();
        }
        // common-procedural's wind attributes: red the trunk's weight (h / height)^1.5, green a branch's (here the
        // crown's distance from the trunk over its radius, ^1.5), blue the leaf weight's 15ths and the phase's 16ths in
        // its nibbles, alpha 1; UV1 the pivot height (the crown's tier, here 0.2 of the height) and the reach
        float[] colors = new float[4 * count];
        float[] uv1 = new float[2 * count];
        for (int v = 0; v < count; v++) {
            double x = positions[3 * v];
            double y = positions[3 * v + 1];
            double z = positions[3 * v + 2];
            boolean foliage = v >= 2 * SIDES;
            double branch = foliage ? Math.pow(Math.min(1, Math.hypot(x, z) / radius), 1.5) : 0;
            int leaf = foliage ? 15 : 0;
            int phase = (int) Math.floor(16 * ((v * 0.618034) % 1));
            colors[4 * v] = (float) Math.pow(Math.max(0, y) / height, 1.5);
            colors[4 * v + 1] = (float) branch;
            colors[4 * v + 2] = (16 * leaf + phase) / 255f;
            colors[4 * v + 3] = 1;
            uv1[2 * v] = (float) (0.2 * height);
            uv1[2 * v + 1] = (float) radius;
        }
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL, VertexAttribute.UV1, VertexAttribute.COLOR),
            count).positions(positions).attribute(VertexAttribute.NORMAL, TestMeshes.normals(positions, all))
            .attribute(VertexAttribute.UV1, uv1).attribute(VertexAttribute.COLOR, colors).submesh(trunk)
            .submesh(crown).buildHierarchy();
    }

    private static void set(float[] positions, int vertex, double x, double y, double z) {
        positions[3 * vertex] = (float) x;
        positions[3 * vertex + 1] = (float) y;
        positions[3 * vertex + 2] = (float) z;
    }
}
