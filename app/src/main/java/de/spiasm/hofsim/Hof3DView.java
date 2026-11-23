package de.spiasm.hofsim;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Einfache 3D-Ansicht vom Hof: Gebäude hinten, davor alle eigenen Maschinen. Mit dem Finger drehen. */
public class Hof3DView extends View {

    public interface PickListener {
        void onPick(Game.Vehicle v);
    }

    private final Game game;
    public PickListener listener;
    private float yaw = -0.55f, pitch = 0.52f;
    private float lastX, downX, downY;
    private boolean dragged;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    /** ein Quader: Mitte (x,z), Boden y0, Größe sx/sy/sz, Drehung */
    private static class Box {
        float x, z, y0, sx, sy, sz, rot;
        int color;
        boolean roof;
        float depth;

        Box(float x, float z, float y0, float sx, float sy, float sz, float rot, int color) {
            this.x = x;
            this.z = z;
            this.y0 = y0;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.rot = rot;
            this.color = color;
        }
    }

    private static class Spot {
        Game.Vehicle v;
        float x, z;
        String name;
    }

    private final List<Box> boxes = new ArrayList<>();
    private final List<Spot> spots = new ArrayList<>();
    private float sc, cx, cy;

    public Hof3DView(Context c, Game game) {
        super(c);
        this.game = game;
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFFFFFFFF);
        text.setShadowLayer(4, 0, 0, 0xFF000000);
        build();
    }

    private void build() {
        boxes.clear();
        spots.clear();
        // Gebäude hinten (z negativ)
        boxes.add(withRoof(new Box(-16, -18, 0, 10, 6, 9, 0, 0xFFEFE6D2)));   // Wohnhaus
        boxes.add(withRoof(new Box(0, -20, 0, 16, 8, 12, 0, 0xFF8D6E63)));    // Maschinenhalle
        boxes.add(new Box(14, -19, 0, 5, 12, 5, 0, 0xFFB0BEC5));               // Silo
        float sx = 22;
        for (int s = 0; s < Defs.STABLES; s++) {
            if (!game.stables[s]) continue;
            boxes.add(withRoof(new Box(sx, -18, 0, 12, 5, 8, 0, 0xFFD7CCC8)));
            sx += 14;
        }
        // Maschinen in Reihen davor
        List<Object> things = new ArrayList<>();
        things.addAll(game.vehicles);
        for (Game.Tool t : game.tools) if (t.attachedTo == -1) things.add(t);
        int perRow = 6;
        for (int i = 0; i < things.size(); i++) {
            int row = i / perRow, col = i % perRow;
            float x = -20 + col * 8.5f + (row % 2) * 3;
            float z = -4 + row * 9;
            Object o = things.get(i);
            if (o instanceof Game.Vehicle) addVehicle((Game.Vehicle) o, x, z);
            else addTool(((Game.Tool) o).m(), x, z + 1, null);
        }
    }

    private Box withRoof(Box b) {
        b.roof = true;
        return b;
    }

    private void addVehicle(Game.Vehicle v, float x, float z) {
        Defs.Model m = v.m();
        float rot = 0.35f;
        int col = m.color;
        if (v.isTractor()) {
            boxes.add(new Box(x + 0.6f, z, 0.6f, 3.0f, 1.2f, 1.6f, rot, col));       // Haube
            boxes.add(new Box(x - 0.6f, z, 0.6f, 1.6f, 2.2f, 1.8f, rot, mix(col, 0xFF90CAF9, 0.35f))); // Kabine
            wheels(x, z, rot, m.len * 0.9f, 1.25f);
            Game.Tool t = game.tool(v.tool);
            if (t != null) addTool(t.m(), x - 3.5f * (float) Math.cos(rot), z - 3.5f * (float) Math.sin(rot), null);
        } else if (v.isForestry()) {
            boxes.add(new Box(x, z, 0.8f, 6.0f, 1.0f, 2.4f, rot, col));
            boxes.add(new Box(x + 1.6f, z, 1.8f, 1.6f, 1.8f, 1.8f, rot, mix(col, 0xFF90CAF9, 0.35f)));
            wheels(x, z, rot, 5.4f, 1.0f);
        } else {
            boxes.add(new Box(x, z, 0.8f, 7.0f, 2.4f, 3.0f, rot, col));             // Erntemaschine
            boxes.add(new Box(x + 2.2f, z, 3.2f, 1.8f, 1.4f, 2.2f, rot, mix(col, 0xFF90CAF9, 0.35f)));
            boxes.add(new Box(x + 4.4f, z, 0.4f, 1.0f, 0.8f, 5.5f, rot, 0xFF757575)); // Schneidwerk
            wheels(x, z, rot, 5.0f, 1.4f);
        }
        Spot sp = new Spot();
        sp.v = v;
        sp.x = x;
        sp.z = z;
        sp.name = v.name();
        spots.add(sp);
    }

    private void addTool(Defs.Model m, float x, float z, Game.Vehicle v) {
        float len = Math.min(6, m.len), wid = Math.min(4.5f, m.wid);
        int col = m.color != 0 ? m.color : 0xFF9E9E9E;
        boxes.add(new Box(x, z, 0.5f, len, 1.2f, wid, 0.35f, col));
        Spot sp = new Spot();
        sp.x = x;
        sp.z = z;
        sp.name = m.name;
        spots.add(sp);
    }

    private void wheels(float x, float z, float rot, float span, float r) {
        float c = (float) Math.cos(rot), s = (float) Math.sin(rot);
        for (float a : new float[]{-span * 0.35f, span * 0.35f}) {
            for (float b : new float[]{-1.1f, 1.1f}) {
                float wx = x + a * c - b * s, wz = z + a * s + b * c;
                boxes.add(new Box(wx, wz, 0, r * 1.4f, r * 1.4f, 0.6f, rot, 0xFF212121));
            }
        }
    }

    private static int mix(int a, int b, float f) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | ((int) (ar + (br - ar) * f) << 16) | ((int) (ag + (bg - ag) * f) << 8) | (int) (ab + (bb - ab) * f);
    }

    private static int shade(int c, float f) {
        int r = (int) Math.min(255, ((c >> 16) & 255) * f), g = (int) Math.min(255, ((c >> 8) & 255) * f), b = (int) Math.min(255, (c & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // ------------------------------------------------------------ Projektion

    private final float[] out = new float[3];

    private void project(float x, float y, float z) {
        float c = (float) Math.cos(yaw), s = (float) Math.sin(yaw);
        float rx = x * c - z * s, rz = x * s + z * c;
        float cp = (float) Math.cos(pitch), sp = (float) Math.sin(pitch);
        out[0] = cx + rx * sc;
        out[1] = cy + (rz * sp - y * cp) * sc;
        out[2] = rz * cp + y * sp; // größer = näher
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        // Himmel und Boden
        fill.setColor(0xFF87CEEB);
        c.drawRect(0, 0, w, h, fill);
        sc = Math.min(w, h * 1.6f) / 70f;
        cx = w / 2f;
        cy = h * 0.42f;
        // Wiese als großes Viereck
        groundQuad(c, -60, -40, 60, 50, 0xFF6FA84E);
        groundQuad(c, -28, -10, 34, 28, 0xFFBDB5A6); // Hofpflaster
        // Tiefe der Quader
        float cyaw = (float) Math.cos(yaw), syaw = (float) Math.sin(yaw);
        for (Box b : boxes) b.depth = (b.x * syaw + b.z * cyaw);
        List<Box> sorted = new ArrayList<>(boxes);
        Collections.sort(sorted, (a, b) -> Float.compare(a.depth, b.depth));
        for (Box b : sorted) drawBox(c, b);
        text.setTextSize(Math.max(22, sc * 1.4f));
        for (Spot sp : spots) {
            project(sp.x, 0, sp.z + 2.6f);
            if (sp.v != null) {
                text.setColor(0xFFFFFFFF);
                c.drawText(sp.name, out[0], out[1] + text.getTextSize(), text);
            }
        }
        text.setColor(0xFFFFFFFF);
        text.setTextSize(26);
        c.drawText("👆 wischen zum Drehen • Maschine antippen", w / 2f, h - 14, text);
    }

    private void groundQuad(Canvas c, float x0, float z0, float x1, float z1, int color) {
        path.reset();
        project(x0, 0, z0);
        path.moveTo(out[0], out[1]);
        project(x1, 0, z0);
        path.lineTo(out[0], out[1]);
        project(x1, 0, z1);
        path.lineTo(out[0], out[1]);
        project(x0, 0, z1);
        path.lineTo(out[0], out[1]);
        path.close();
        fill.setColor(color);
        c.drawPath(path, fill);
    }

    private final float[][] pts = new float[8][3];

    private void drawBox(Canvas c, Box b) {
        float cr = (float) Math.cos(b.rot), sr = (float) Math.sin(b.rot);
        int k = 0;
        for (int yi = 0; yi < 2; yi++) {
            for (int zi = 0; zi < 2; zi++) {
                for (int xi = 0; xi < 2; xi++) {
                    float lx = (xi - 0.5f) * b.sx, lz = (zi - 0.5f) * b.sz;
                    float wx = b.x + lx * cr - lz * sr, wz = b.z + lx * sr + lz * cr;
                    project(wx, b.y0 + yi * b.sy, wz);
                    pts[k][0] = out[0];
                    pts[k][1] = out[1];
                    pts[k][2] = out[2];
                    k++;
                }
            }
        }
        // Seitenflächen: Index = xi + 2*zi + 4*yi
        int[][] faces = {
                {0, 1, 5, 4}, // vorne z-
                {2, 3, 7, 6}, // hinten z+
                {0, 2, 6, 4}, // links x-
                {1, 3, 7, 5}, // rechts x+
        };
        float[] light = {0.75f, 0.95f, 0.65f, 0.85f};
        Integer[] order = {0, 1, 2, 3};
        final float[] fd = new float[4];
        for (int i = 0; i < 4; i++) {
            int[] f = faces[i];
            fd[i] = (pts[f[0]][2] + pts[f[1]][2] + pts[f[2]][2] + pts[f[3]][2]) / 4;
        }
        java.util.Arrays.sort(order, (a, bb) -> Float.compare(fd[a], fd[bb]));
        for (int i : order) {
            int[] f = faces[i];
            quad(c, f, shade(b.color, light[i]));
        }
        // oben
        if (b.roof) {
            // Satteldach: First in der Mitte
            float lx0 = -0.5f * b.sx, lx1 = 0.5f * b.sx;
            float yTop = b.y0 + b.sy, yRidge = yTop + b.sz * 0.35f;
            float[][] rp = new float[6][3];
            float[][] loc = {{lx0, yTop, -0.5f * b.sz}, {lx1, yTop, -0.5f * b.sz}, {lx1, yTop, 0.5f * b.sz}, {lx0, yTop, 0.5f * b.sz},
                    {lx0, yRidge, 0}, {lx1, yRidge, 0}};
            for (int i = 0; i < 6; i++) {
                float wx = b.x + loc[i][0] * cr - loc[i][2] * sr, wz = b.z + loc[i][0] * sr + loc[i][2] * cr;
                project(wx, loc[i][1], wz);
                rp[i][0] = out[0];
                rp[i][1] = out[1];
                rp[i][2] = out[2];
            }
            int roofCol = 0xFFB0413E;
            float dFront = rp[0][2] + rp[1][2], dBack = rp[2][2] + rp[3][2];
            int[][] sides = {{0, 1, 5, 4}, {3, 2, 5, 4}};
            int[] firstLast = dFront < dBack ? new int[]{0, 1} : new int[]{1, 0};
            // Giebel
            tri(c, rp[0], rp[3], rp[4], shade(b.color, 0.8f));
            tri(c, rp[1], rp[2], rp[5], shade(b.color, 0.9f));
            for (int i : firstLast) {
                int[] f = sides[i];
                path.reset();
                path.moveTo(rp[f[0]][0], rp[f[0]][1]);
                for (int j = 1; j < 4; j++) path.lineTo(rp[f[j]][0], rp[f[j]][1]);
                path.close();
                fill.setColor(shade(roofCol, i == 0 ? 0.85f : 1.0f));
                c.drawPath(path, fill);
            }
        } else {
            quad(c, new int[]{4, 5, 7, 6}, shade(b.color, 1.08f));
        }
    }

    private void tri(Canvas c, float[] a, float[] b, float[] d, int color) {
        path.reset();
        path.moveTo(a[0], a[1]);
        path.lineTo(b[0], b[1]);
        path.lineTo(d[0], d[1]);
        path.close();
        fill.setColor(color);
        c.drawPath(path, fill);
    }

    private void quad(Canvas c, int[] f, int color) {
        path.reset();
        path.moveTo(pts[f[0]][0], pts[f[0]][1]);
        for (int j = 1; j < 4; j++) path.lineTo(pts[f[j]][0], pts[f[j]][1]);
        path.close();
        fill.setColor(color);
        c.drawPath(path, fill);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = downX = e.getX();
                downY = e.getY();
                dragged = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = e.getX() - lastX;
                lastX = e.getX();
                if (Math.abs(e.getX() - downX) > 12) dragged = true;
                yaw += dx * 0.008f;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragged && listener != null) {
                    Spot best = null;
                    float bd = 60 * getResources().getDisplayMetrics().density;
                    for (Spot sp : spots) {
                        if (sp.v == null) continue;
                        project(sp.x, 1.5f, sp.z);
                        float d = (float) Math.hypot(out[0] - downX, out[1] - downY);
                        if (d < bd) {
                            bd = d;
                            best = sp;
                        }
                    }
                    if (best != null) listener.onPick(best.v);
                }
                return true;
        }
        return super.onTouchEvent(e);
    }
}
