package com.truesim.effects.vegetation;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Vec3;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.meshbuild.MeshBuilder;

/**
 * The octahedral impostors' geometry, as impostor.slang has it: the hemi-octahedral grid of frame directions, a frame's
 * (or the quad's) right and up, and the quad the impostor type turns to the viewer.
 */
public final class Impostors {

    private Impostors() {
    }

    /** The direction (unit, y from 0) at point ({@code u}, {@code v}) of the unit square. */
    public static Vec3 decode(double u, double v) {
        double a = 2 * u - 1;
        double b = 2 * v - 1;
        double px = (a + b) * 0.5;
        double pz = (a - b) * 0.5;
        double py = 1 - Math.abs(px) - Math.abs(pz);
        double l = Math.sqrt(px * px + py * py + pz * pz);
        return new Vec3(px / l, py / l, pz / l);
    }

    /** Where direction {@code d} (y below 0 taken as 0) falls on the unit square: u, v. */
    public static double[] encode(Vec3 d) {
        double y = Math.max(0, d.y());
        double s = Math.abs(d.x()) + y + Math.abs(d.z());
        double px = d.x() / Math.max(s, 1e-6);
        double pz = d.z() / Math.max(s, 1e-6);
        return new double[] {(px + pz) * 0.5 + 0.5, (px - pz) * 0.5 + 0.5};
    }

    /** The direction toward frame ({@code i}, {@code j})'s camera, of a grid of {@code frames} a side. */
    public static Vec3 direction(int i, int j, int frames) {
        return decode((i + 0.5) / frames, (j + 0.5) / frames);
    }

    /** The right (0 to 2) and up (3 to 5) of a view looking along -{@code f}. */
    public static double[] basis(Vec3 f) {
        double hx = 0;
        double hy = 1;
        double hz = 0;
        if (Math.abs(f.y()) > 0.999) {
            hy = 0;
            hz = -1;
        }
        // right = (-f) x hint, up = right x (-f)
        double bx = -f.x();
        double by = -f.y();
        double bz = -f.z();
        double rx = by * hz - bz * hy;
        double ry = bz * hx - bx * hz;
        double rz = bx * hy - by * hx;
        double rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
        rx /= rl;
        ry /= rl;
        rz /= rl;
        double ux = ry * bz - rz * by;
        double uy = rz * bx - rx * bz;
        double uz = rx * by - ry * bx;
        return new double[] {rx, ry, rz, ux, uy, uz};
    }

    /**
     * The quad an impostor of the sphere ({@code cx}, {@code cy}, {@code cz}) of {@code radius} draws: four corners about
     * the center in the plane facing +z, UVs 0 to 1 (v down), normals +z; its type turns it to the viewer, at most
     * {@link #displacement} from where it rests.
     */
    @AllowAllocation("made once per impostor")
    public static MeshData quad(float cx, float cy, float cz, float radius) {
        float[] positions = {cx - radius, cy + radius, cz, cx + radius, cy + radius, cz, cx + radius, cy - radius, cz,
            cx - radius, cy - radius, cz};
        float[] uvs = {0, 0, 1, 0, 1, 1, 0, 1};
        float[] normals = {0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1};
        float[] tangents = {1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1};
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL, VertexAttribute.TANGENT, VertexAttribute.UV0),
            4).positions(positions).attribute(VertexAttribute.NORMAL, normals)
            .attribute(VertexAttribute.TANGENT, tangents).attribute(VertexAttribute.UV0, uvs)
            .submesh(new int[] {0, 2, 1, 0, 3, 2}).buildFlat();
    }

    /** How far the impostor type moves a corner of the quad of {@code radius} at most: turned about the center. */
    public static float displacement(float radius) {
        return (float) (2 * Math.sqrt(2) * radius * 1.01);
    }
}
