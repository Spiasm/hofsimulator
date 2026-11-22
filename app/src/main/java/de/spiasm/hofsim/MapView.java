package de.spiasm.hofsim;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.List;
import java.util.Random;

/** Karte von oben: Gelände, Felder, Gebäude, Fahrzeuge. Verschieben mit einem Finger, zoomen mit zwei. */
public class MapView extends View {
    public interface TapListener {
        void onTapWorld(float mx, float my);
    }

    public Game game;
    public TapListener tapListener;
    public int selectedVehicle = -1;
    public int selectedField = -1;

    public float camX = 480, camY = 480, scale = 2f;

    private Bitmap terrain;
    private long terrainSeed = -1;
    private static final float BMP_PX_PER_M = 1.25f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();

    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;

    public static final int[] PRODUCT_COLOR = {0xFF7CB342, 0xFFE0B040, 0xFFA1887F, 0xFF9CCC65};

    public MapView(Context c) {
        super(c);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        text.setShadowLayer(4, 0, 1, 0xDD000000);
        text.setColor(0xFFFFFFFF);

        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float fx = d.getFocusX(), fy = d.getFocusY();
                float wx = screenToWorldX(fx), wy = screenToWorldY(fy);
                scale = Math.max(0.6f, Math.min(14f, scale * d.getScaleFactor()));
                camX = wx - (fx - getWidth() / 2f) / scale;
                camY = wy - (fy - getHeight() / 2f) / scale;
                clampCam();
                invalidate();
                return true;
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                camX += dx / scale;
                camY += dy / scale;
                clampCam();
                invalidate();
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (tapListener != null) tapListener.onTapWorld(screenToWorldX(e.getX()), screenToWorldY(e.getY()));
                return true;
            }
        });
    }

    public void centerOn(float mx, float my) {
        camX = mx;
        camY = my;
        clampCam();
        invalidate();
    }

    private void clampCam() {
        float size = Defs.MAP * Defs.TILE;
        camX = Math.max(0, Math.min(size, camX));
        camY = Math.max(0, Math.min(size, camY));
    }

    public float screenToWorldX(float sx) {
        return camX + (sx - getWidth() / 2f) / scale;
    }

    public float screenToWorldY(float sy) {
        return camY + (sy - getHeight() / 2f) / scale;
    }

    private float sx(float mx) {
        return getWidth() / 2f + (mx - camX) * scale;
    }

    private float sy(float my) {
        return getHeight() / 2f + (my - camY) * scale;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e);
        if (!scaler.isInProgress()) gestures.onTouchEvent(e);
        return true;
    }

    // ------------------------------------------------------------- Gelände

    private void buildTerrain() {
        World w = game.world;
        int size = (int) (w.n * Defs.TILE * BMP_PX_PER_M);
        if (terrain != null) terrain.recycle();
        terrain = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(terrain);
        float t = Defs.TILE * BMP_PX_PER_M;
        Random rnd = new Random(w.seed);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.drawColor(0xFF6FA84E);
        // Gras-Flecken
        for (int i = 0; i < 2500; i++) {
            p.setColor(rnd.nextBoolean() ? 0xFF679F47 : 0xFF77B055);
            c.drawCircle(rnd.nextFloat() * size, rnd.nextFloat() * size, 3 + rnd.nextFloat() * 10, p);
        }
        for (int x = 0; x < w.n; x++) {
            for (int y = 0; y < w.n; y++) {
                float l = x * t, tp = y * t;
                switch (w.tile[x][y]) {
                    case World.ROAD:
                        p.setColor(0xFF7E7E7E);
                        c.drawRect(l, tp, l + t, tp + t, p);
                        break;
                    case World.YARD:
                    case World.BUILDING:
                        p.setColor(0xFFB9AE96);
                        c.drawRect(l, tp, l + t, tp + t, p);
                        break;
                    case World.FIELD:
                        p.setColor(0xFF7A5634);
                        c.drawRect(l, tp, l + t, tp + t, p);
                        break;
                    case World.WATER:
                        p.setColor(0xFF3D7FB8);
                        c.drawRect(l - 1, tp - 1, l + t + 1, tp + t + 1, p);
                        break;
                    case World.FOREST:
                        p.setColor(0xFF3E6B2E);
                        c.drawRect(l, tp, l + t, tp + t, p);
                        break;
                    case World.HOUSE:
                        p.setColor(0xFFB9AE96);
                        c.drawRect(l, tp, l + t, tp + t, p);
                        break;
                    default:
                        break;
                }
            }
        }
        // Straßen: Mittellinie
        p.setColor(0xFFDDDDDD);
        for (int x = 0; x < w.n; x++) {
            for (int y = 0; y < w.n; y++) {
                if (w.tile[x][y] != World.ROAD) continue;
                boolean horiz = (x > 0 && w.tile[x - 1][y] == World.ROAD) || (x < w.n - 1 && w.tile[x + 1][y] == World.ROAD);
                boolean vert = (y > 0 && w.tile[x][y - 1] == World.ROAD) || (y < w.n - 1 && w.tile[x][y + 1] == World.ROAD);
                if (horiz && !vert) c.drawRect(x * t + t * 0.2f, y * t + t * 0.47f, x * t + t * 0.6f, y * t + t * 0.53f, p);
                if (vert && !horiz) c.drawRect(x * t + t * 0.47f, y * t + t * 0.2f, x * t + t * 0.53f, y * t + t * 0.6f, p);
            }
        }
        // Bäume
        for (int x = 0; x < w.n; x++) {
            for (int y = 0; y < w.n; y++) {
                if (w.tile[x][y] != World.FOREST) continue;
                for (int k = 0; k < 3; k++) {
                    float cx = (x + rnd.nextFloat()) * t, cy = (y + rnd.nextFloat()) * t;
                    float rad = t * (0.3f + rnd.nextFloat() * 0.3f);
                    p.setColor(0xFF2B4F20);
                    c.drawCircle(cx + 2, cy + 2, rad, p);
                    p.setColor(rnd.nextBoolean() ? 0xFF3F7A2C : 0xFF4C8A35);
                    c.drawCircle(cx, cy, rad, p);
                }
            }
        }
        // Wasser-Glanz
        p.setColor(0x334FC3F7);
        for (int x = 0; x < w.n; x++)
            for (int y = 0; y < w.n; y++)
                if (w.tile[x][y] == World.WATER && rnd.nextInt(3) == 0)
                    c.drawRect(x * t + t * 0.2f, y * t + t * 0.4f, x * t + t * 0.7f, y * t + t * 0.5f, p);
        terrainSeed = w.seed;
    }

    // ---------------------------------------------------------------- Zeichnen

    /** 0 = einfach, 1 = mittel, 2 = hoch */
    public int detail = 1;
    private long lastDraw;
    private float frameDt;

    private static class Dust {
        float x, y, age, size;
    }

    private final java.util.ArrayList<Dust> dust = new java.util.ArrayList<>();

    private static class Animal {
        float x, y, tx, ty, heading, wait;
    }

    private final java.util.ArrayList<java.util.ArrayList<Animal>> animals = new java.util.ArrayList<>();
    private final Random fxRnd = new Random();

    private float now() {
        return (android.os.SystemClock.uptimeMillis() % 10000000L) / 1000f;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (game == null) return;
        if (terrain == null || terrainSeed != game.world.seed) buildTerrain();
        long ms = android.os.SystemClock.uptimeMillis();
        frameDt = lastDraw == 0 ? 0 : Math.min(0.1f, (ms - lastDraw) / 1000f);
        lastDraw = ms;
        c.drawColor(0xFF4F7A3A);
        float w = getWidth(), h = getHeight();
        float size = Defs.MAP * Defs.TILE;
        float vl = screenToWorldX(0) - 30, vt = screenToWorldY(0) - 30, vr = screenToWorldX(w) + 30, vb = screenToWorldY(h) + 30;

        c.save();
        c.translate(w / 2f - camX * scale, h / 2f - camY * scale);
        c.scale(scale, scale);
        r.set(0, 0, size, size);
        c.drawBitmap(terrain, null, r, bmpPaint);

        World wo = game.world;
        for (Field f : wo.fields) {
            float l = f.x * Defs.TILE, t = f.y * Defs.TILE, rr = (f.x + f.w) * Defs.TILE, b = (f.y + f.h) * Defs.TILE;
            if (rr < vl || l > vr || b < vt || t > vb) continue;
            drawField(c, f, l, t, rr, b);
        }
        for (World.Building b : wo.buildings) {
            if (!visible(b.r, vl, vt, vr, vb)) continue;
            drawBuilding(c, b);
        }
        drawHof(c);
        for (int i = 0; i < 3; i++) if (visible(wo.trader[i], vl, vt, vr, vb)) drawTrader(c, wo.trader[i], i);
        for (int s = 0; s < Defs.STABLES; s++) if (visible(wo.stableLot[s], vl, vt, vr, vb)) drawStableLot(c, s);

        // Weg des ausgewählten Fahrzeugs
        Game.Vehicle sel = game.vehicle(selectedVehicle);
        if (sel != null && sel.path != null && sel.pathIdx < sel.path.size()) {
            stroke.setColor(0xCCFFEB3B);
            stroke.setStrokeWidth(2.5f / scale);
            path.reset();
            path.moveTo(sel.x, sel.y);
            List<float[]> pl = sel.path;
            for (int i = sel.pathIdx; i < pl.size(); i++) path.lineTo(pl.get(i)[0], pl.get(i)[1]);
            c.drawPath(path, stroke);
        }
        // abgestellte Geräte im Hof
        int spot = 0;
        for (Game.Tool t : game.tools) {
            if (t.attachedTo != -1) continue;
            int[] sp = wo.toolSpot(spot++);
            Defs.Model m = t.m();
            c.save();
            c.translate((sp[0] + 0.5f) * Defs.TILE, (sp[1] + 0.5f) * Defs.TILE);
            c.rotate(-90);
            float mult = Math.max(1f, 10f / ((m.len + 0.6f) * scale));
            mult = Math.min(mult, 9f / Math.max(m.wid, 1f));
            c.scale(mult, mult);
            drawTool(c, m, (m.len + 0.6f) / 2f, null);
            c.restore();
        }
        if (detail >= 1) {
            for (Game.Car car : game.cars) {
                if (car.x < vl || car.x > vr || car.y < vt || car.y > vb) continue;
                drawCar(c, car);
            }
        }
        for (Game.Vehicle v : game.vehicles) {
            if (v.x < vl || v.x > vr || v.y < vt || v.y > vb) continue;
            if (detail == 2 && v.working() && fxRnd.nextFloat() < 0.5f) spawnDust(v);
            drawVehicle(c, v, v.id == selectedVehicle);
        }
        if (detail == 2) drawDust(c);
        if (game.fireField >= 0) drawFire(c);
        if (game.fireTruck != null) drawFireTruck(c, game.fireTruck);
        for (int[] wnd : game.winds) drawWind(c, wnd);
        c.restore();

        drawLabels(c);
        if (detail == 2 || game.fireField >= 0 || game.fireTruck != null || !game.winds.isEmpty()) postInvalidateOnAnimation();
    }

    private static boolean visible(int[] rect, float vl, float vt, float vr, float vb) {
        if (rect == null) return false;
        float l = rect[0] * Defs.TILE, t = rect[1] * Defs.TILE;
        return l + rect[2] * Defs.TILE >= vl && l <= vr && t + rect[3] * Defs.TILE >= vt && t <= vb;
    }

    /** Rechtsverkehr: auf Straßen etwas nach rechts versetzt fahren */
    private void laneShift(float x, float y, float heading, float[] out) {
        out[0] = x;
        out[1] = y;
        if (!game.world.isRoad(x, y)) return;
        double a = Math.toRadians(heading);
        out[0] = x - (float) Math.sin(a) * 2.4f;
        out[1] = y + (float) Math.cos(a) * 2.4f;
    }

    private final float[] shift = new float[2];

    private void drawField(Canvas c, Field f, float l, float t, float rr, float b) {
        if (f.pv) {
            drawSolarPark(c, l, t, rr, b);
            stroke.setColor(0xFFFFEB3B);
            stroke.setStrokeWidth(Math.max(1.2f, 3f / scale));
            c.drawRect(l, t, rr, b, stroke);
            return;
        }
        drawFieldInner(c, f, l, t, rr, b);
        // abgebrannte Fläche
        float br = f.burnR;
        boolean burning = game.fireField == f.index;
        if (br > 0 || burning) {
            c.save();
            c.clipRect(l, t, rr, b);
            fill.setColor(0xFF2B2420);
            if (br > 0) c.drawCircle(f.burnX, f.burnY, br, fill);
            if (burning) c.drawCircle(game.fireX, game.fireY, game.fireR, fill);
            c.restore();
        }
    }

    private void drawFieldInner(Canvas c, Field f, float l, float t, float rr, float b) {
        drawCrop(c, l, t, rr, b, f.state, f.crop, f.growth, f.fertilized, f.mowed, f.index);
        // bearbeiteter Teil
        if (f.task != -1 && f.progress > 0) {
            int st = f.state, cr = f.crop;
            double g = f.growth;
            boolean fe = f.fertilized;
            boolean mo = f.mowed;
            switch (f.task) {
                case Defs.TASK_MOW:
                    mo = true;
                    break;
                case Defs.TASK_CULTIVATE:
                    st = Field.CULTIVATED;
                    g = 0;
                    fe = false;
                    break;
                case Defs.TASK_SOW:
                    st = Field.GROWING;
                    cr = f.taskCrop >= 0 ? f.taskCrop : cr;
                    g = 0.02;
                    fe = false;
                    break;
                case Defs.TASK_FERTILIZE:
                    fe = true;
                    break;
                case Defs.TASK_HARVEST:
                    mo = false;
                    if (cr == Defs.GRASS) {
                        g = 0.25;
                    } else {
                        st = Field.STUBBLE;
                        g = 0;
                    }
                    fe = false;
                    break;
                default:
                    break;
            }
            double lanes = f.h;
            int lane = (int) Math.floor(f.progress * lanes);
            double frac = f.progress * lanes - lane;
            float laneH = Defs.TILE;
            if (lane > 0) {
                c.save();
                c.clipRect(l, t, rr, t + lane * laneH);
                drawCrop(c, l, t, rr, b, st, cr, g, fe, mo, f.index);
                c.restore();
            }
            if (lane < f.h) {
                float width = rr - l;
                boolean right = lane % 2 == 0;
                float x0 = right ? l : (float) (rr - width * frac);
                float x1 = right ? (float) (l + width * frac) : rr;
                c.save();
                c.clipRect(x0, t + lane * laneH, x1, t + (lane + 1) * laneH);
                drawCrop(c, l, t, rr, b, st, cr, g, fe, mo, f.index);
                c.restore();
            }
        }
        // Rand
        if (f.owned) {
            stroke.setColor(0xFFFFEB3B);
            stroke.setStrokeWidth(Math.max(1.2f, 3f / scale));
        } else {
            stroke.setColor(0x66000000);
            stroke.setStrokeWidth(Math.max(0.8f, 1.5f / scale));
        }
        c.drawRect(l, t, rr, b, stroke);
        if (f.index == selectedField) {
            stroke.setColor(0xFFFFFFFF);
            stroke.setStrokeWidth(4f / scale);
            c.drawRect(l + 2 / scale, t + 2 / scale, rr - 2 / scale, b - 2 / scale, stroke);
        }
    }

    private static int mix(int a, int b, double f) {
        f = Math.max(0, Math.min(1, f));
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | ((int) (ar + (br - ar) * f) << 16) | ((int) (ag + (bg - ag) * f) << 8) | (int) (ab + (bb - ab) * f);
    }

    private static final int SOIL = 0xFF7A5634;

    /** Feld-Aussehen je nach Zustand und Frucht. */
    private void drawCrop(Canvas c, float l, float t, float rr, float b, int state, int crop, double g, boolean fert,
                          boolean mowed, int seed) {
        if (mowed && state == Field.GROWING && crop == Defs.GRASS) {
            fill.setColor(0xFF9CCC65);
            c.drawRect(l, t, rr, b, fill);
            if (detail == 0) return;
            stroke.setColor(0xFFB5B04A);
            stroke.setStrokeWidth(1.8f);
            stroke.setStrokeCap(Paint.Cap.BUTT);
            for (float y = t + 3f; y < b; y += 6f) c.drawLine(l + 1, y, rr - 1, y, stroke);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            return;
        }
        float rowSp = Math.max(2.5f, 5f / scale);
        boolean detail = scale >= 1.6f;
        int base, row = 0;
        float rowW = rowSp * 0.35f;
        int dotColor = 0;
        float dotR = 0;
        switch (state) {
            case Field.STUBBLE:
                boolean light = crop == Defs.WHEAT || crop == Defs.MAIZE;
                base = light ? 0xFFCDB677 : 0xFF8C6A45;
                row = light ? 0xFFB09650 : 0xFF6E5034;
                rowW = rowSp * 0.15f;
                break;
            case Field.CULTIVATED:
                base = 0xFF66432A;
                row = 0xFF4E321C;
                rowW = rowSp * 0.4f;
                break;
            default:
                switch (crop) {
                    case Defs.WHEAT:
                        if (g < 0.1) {
                            base = SOIL;
                            row = 0xFF9CCC65;
                            rowW = rowSp * 0.1f;
                        } else if (g < 0.45) {
                            base = mix(SOIL, 0xFF8BC34A, (g - 0.1) / 0.35);
                            row = mix(base, 0xFF689F38, 0.5);
                            rowW = rowSp * 0.25f;
                        } else if (g < 1) {
                            base = g < 0.8 ? mix(0xFF8BC34A, 0xFFB8C836, (g - 0.45) / 0.35) : mix(0xFFB8C836, 0xFFDDB13E, (g - 0.8) / 0.2);
                            row = mix(base, 0xFF000000, 0.12);
                            rowW = rowSp * 0.12f;
                        } else {
                            base = 0xFFE3B341;
                            row = 0xFFC99A2E;
                            rowW = rowSp * 0.12f;
                        }
                        break;
                    case Defs.MAIZE:
                        base = g < 1 ? mix(SOIL, 0xFF55702F, g * 0.8) : 0xFF8D7B3A;
                        row = mix(base, 0xFF000000, 0.15);
                        rowW = rowSp * 0.15f;
                        dotColor = g < 1 ? mix(0xFF7CB342, 0xFF2E7D32, g) : 0xFFB5A642;
                        dotR = (float) (0.25 + 0.5 * Math.min(1, g)) * rowSp * 0.35f;
                        break;
                    case Defs.POTATO:
                        base = SOIL;
                        row = 0xFF8E6A45;
                        rowW = rowSp * 0.55f;
                        dotColor = g < 1 ? mix(0xFF9CCC65, 0xFF388E3C, g) : 0xFF9E8D3A;
                        dotR = (float) (0.1 + 0.4 * Math.min(1, g)) * rowSp * 0.4f;
                        if (g < 0.05) dotR = 0;
                        break;
                    default: // Gras
                        if (g < 1) base = g < 0.3 ? mix(SOIL, 0xFF66BB6A, g / 0.3) : mix(0xFF66BB6A, 0xFF388E3C, (g - 0.3) / 0.7);
                        else base = 0xFF2E7D32;
                        row = mix(base, 0xFFFFFFFF, 0.12);
                        rowW = rowSp * 0.1f;
                        break;
                }
        }
        fill.setColor(base);
        c.drawRect(l, t, rr, b, fill);
        if (detail == 0) return;
        // Reihen
        stroke.setColor(row);
        stroke.setStrokeWidth(rowW);
        stroke.setStrokeCap(Paint.Cap.BUTT);
        for (float y = t + rowSp / 2; y < b; y += rowSp) c.drawLine(l + 0.5f, y, rr - 0.5f, y, stroke);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        // Pflanzen
        if (dotR > 0 && detail) {
            float dotSp = Math.max(rowSp * 0.6f, 4f / scale);
            fill.setColor(dotColor);
            for (float y = t + rowSp / 2; y < b; y += rowSp) {
                for (float x = l + dotSp / 2; x < rr; x += dotSp) c.drawCircle(x, y, dotR, fill);
            }
        } else if (dotR > 0) {
            stroke.setColor(dotColor);
            stroke.setStrokeWidth(dotR * 1.6f);
            for (float y = t + rowSp / 2; y < b; y += rowSp) c.drawLine(l + 1, y, rr - 1, y, stroke);
        }
        // reifer Weizen: Ähren-Struktur
        if (state == Field.GROWING && crop == Defs.WHEAT && g >= 1 && detail) {
            fill.setColor(0x33FFF59D);
            Random rnd = new Random(seed * 7919L);
            int n = (int) ((rr - l) * (b - t) / 40);
            for (int i = 0; i < Math.min(n, 400); i++) c.drawCircle(l + rnd.nextFloat() * (rr - l), t + rnd.nextFloat() * (b - t), 1.2f, fill);
        }
        // Dünger
        if (fert && state == Field.GROWING && g < 1 && detail) {
            fill.setColor(0xCCFFFFFF);
            Random rnd = new Random(seed * 31L + 5);
            int n = (int) ((rr - l) * (b - t) / 60);
            for (int i = 0; i < Math.min(n, 500); i++) c.drawCircle(l + rnd.nextFloat() * (rr - l), t + rnd.nextFloat() * (b - t), 0.35f, fill);
        }
    }

    private void roof(Canvas c, int[] rect, int color, boolean ridgeHorizontal) {
        float l = rect[0] * Defs.TILE + 1, t = rect[1] * Defs.TILE + 1;
        float rr = (rect[0] + rect[2]) * Defs.TILE - 1, b = (rect[1] + rect[3]) * Defs.TILE - 1;
        fill.setColor(0x55000000);
        c.drawRect(l + 1.5f, t + 1.5f, rr + 1.5f, b + 1.5f, fill);
        fill.setColor(color);
        c.drawRect(l, t, rr, b, fill);
        fill.setColor(mix(color, 0xFF000000, 0.2));
        if (ridgeHorizontal) c.drawRect(l, (t + b) / 2, rr, b, fill);
        else c.drawRect((l + rr) / 2, t, rr, b, fill);
        stroke.setColor(mix(color, 0xFF000000, 0.4));
        stroke.setStrokeWidth(0.4f);
        c.drawRect(l, t, rr, b, stroke);
    }

    private void roofPv(Canvas c, int[] rect) {
        float l = rect[0] * Defs.TILE + 2, t = rect[1] * Defs.TILE + 2;
        float rr = (rect[0] + rect[2]) * Defs.TILE - 2, b = (rect[1] + rect[3]) * Defs.TILE / 2f + t / 2f - 1;
        fill.setColor(0xFF1A237E);
        c.drawRect(l, t, rr, b, fill);
        stroke.setColor(0xFF5C6BC0);
        stroke.setStrokeWidth(0.3f);
        for (float x = l + 2; x < rr; x += 2.2f) c.drawLine(x, t, x, b, stroke);
        for (float y = t + 1.6f; y < b; y += 1.6f) c.drawLine(l, y, rr, y, stroke);
    }

    private void drawHof(Canvas c) {
        World w = game.world;
        roof(c, w.house, 0xFFB5482E, true);
        roof(c, w.hall, 0xFF8D9399, true);
        if (game.pvHouse) roofPv(c, w.house);
        if (game.pvHall) roofPv(c, w.hall);
        silo(c, (w.silo[0] + w.silo[2] / 2f) * Defs.TILE, (w.silo[1] + w.silo[3] / 2f) * Defs.TILE, 8.5f);
    }

    private void silo(Canvas c, float sx, float sy, float rad) {
        fill.setColor(0x55000000);
        c.drawCircle(sx + 1.5f, sy + 1.5f, rad, fill);
        fill.setColor(0xFFCFD8DC);
        c.drawCircle(sx, sy, rad, fill);
        fill.setColor(0xFFB0BEC5);
        c.drawCircle(sx, sy, rad * 0.35f, fill);
    }

    private void drawTrader(Canvas c, int[] rect, int type) {
        float l = rect[0] * Defs.TILE, t = rect[1] * Defs.TILE;
        float rr = (rect[0] + rect[2]) * Defs.TILE, b = (rect[1] + rect[3]) * Defs.TILE;
        fill.setColor(0xFFB9AE96);
        c.drawRect(l, t, rr, b, fill);
        if (type == Defs.B_BIOGAS) {
            float rad = (rr - l) * 0.2f;
            for (int i = 0; i < 2; i++) {
                float cx = l + (rr - l) * (0.28f + i * 0.44f), cy = t + (b - t) * 0.4f;
                fill.setColor(0x55000000);
                c.drawCircle(cx + 1.5f, cy + 1.5f, rad, fill);
                fill.setColor(0xFF558B2F);
                c.drawCircle(cx, cy, rad, fill);
                fill.setColor(0xFF7CB342);
                c.drawCircle(cx - rad * 0.25f, cy - rad * 0.25f, rad * 0.55f, fill);
            }
            fill.setColor(0xFF9E9E9E);
            c.drawRect(l + 5, b - 18, rr - 5, b - 6, fill);
        } else if (type == Defs.B_MILL) {
            int[] mill = {rect[0] + 1, rect[1] + 1, 3, rect[3] - 2};
            roof(c, mill, 0xFF8D6E63, false);
            for (int i = 0; i < 2; i++) silo(c, l + 48 + i * 0, t + 15 + i * 24, 9);
            fill.setColor(0xFFFFFFFF);
            c.drawCircle(l + 25, t + 12, 3, fill);
        } else {
            int[] hall = {rect[0] + 1, rect[1] + 3, rect[2] - 2, rect[3] - 3};
            roof(c, hall, 0xFF78909C, true);
            for (int i = 0; i < 3; i++) silo(c, l + 12 + i * 18, t + 13, 7);
        }
    }

    private void drawBuilding(Canvas c, World.Building bd) {
        int[] r0 = bd.r;
        switch (bd.type) {
            case World.G_CHURCH: {
                roof(c, r0, 0xFF8D6E63, r0[2] > r0[3]);
                float cx = (r0[0] + 0.7f) * Defs.TILE, cy = (r0[1] + 0.7f) * Defs.TILE;
                fill.setColor(0xFFECEFF1);
                c.drawRect(cx - 4, cy - 4, cx + 4, cy + 4, fill);
                stroke.setColor(0xFFFFC107);
                stroke.setStrokeWidth(1f);
                c.drawLine(cx - 3, cy, cx + 3, cy, stroke);
                c.drawLine(cx, cy - 4, cx, cy + 4, stroke);
                break;
            }
            case World.G_FIREHOUSE:
                roof(c, r0, 0xFFC62828, true);
                fill.setColor(0xFFFFFFFF);
                text.setTextSize(9);
                text.setShadowLayer(0, 0, 0, 0);
                c.drawText("112", (r0[0] + r0[2] / 2f) * Defs.TILE, (r0[1] + r0[3] / 2f) * Defs.TILE + 3, text);
                text.setShadowLayer(4, 0, 1, 0xDD000000);
                break;
            case World.G_FARMHOUSE:
                roof(c, r0, 0xFFA1452A, true);
                break;
            case World.G_BARN:
                roof(c, r0, 0xFF6D4C41, false);
                break;
            case World.G_SILO:
                silo(c, (r0[0] + r0[2] / 2f) * Defs.TILE, (r0[1] + r0[3] / 2f) * Defs.TILE, 8);
                break;
            default: {
                int hash = r0[0] * 31 + r0[1] * 17;
                int[] cols = {0xFFB5482E, 0xFF8D4F3A, 0xFF9E3B2A, 0xFF5D4037, 0xFF78909C};
                // Garten
                fill.setColor(0xFF7CB860);
                c.drawRect(r0[0] * Defs.TILE - 3, r0[1] * Defs.TILE - 3, (r0[0] + r0[2]) * Defs.TILE + 3, (r0[1] + r0[3]) * Defs.TILE + 3, fill);
                roof(c, r0, cols[Math.abs(hash) % cols.length], r0[2] >= r0[3]);
            }
        }
    }

    private static final int[] STABLE_ROOF = {0xFFA0522D, 0xFF6D7B4F, 0xFFAD8A7E, 0xFF8D8D6B};
    private static final int[] RUN_COLOR = {0xFFD7C49E, 0xFF7CB860, 0xFF9C7A5A, 0xFF8BC56A};

    private void drawStableLot(Canvas c, int s) {
        int[] lot = game.world.stableLot[s];
        float l = lot[0] * Defs.TILE, t = lot[1] * Defs.TILE;
        float rr = (lot[0] + lot[2]) * Defs.TILE, b = (lot[1] + lot[3]) * Defs.TILE;
        if (!game.stables[s]) {
            fill.setColor(0xFF8DB86E);
            c.drawRect(l, t, rr, b, fill);
            stroke.setColor(0xAAFFFFFF);
            stroke.setStrokeWidth(Math.max(1f, 2f / scale));
            stroke.setPathEffect(new android.graphics.DashPathEffect(new float[]{6, 4}, 0));
            c.drawRect(l + 2, t + 2, rr - 2, b - 2, stroke);
            stroke.setPathEffect(null);
            return;
        }
        int[] bld = {lot[0], lot[1], lot[2], 4};
        int[] run = {lot[0], lot[1] + 4, lot[2], lot[3] - 4};
        float rl = run[0] * Defs.TILE, rt = run[1] * Defs.TILE, rb = (run[1] + run[3]) * Defs.TILE;
        fill.setColor(RUN_COLOR[s]);
        c.drawRect(rl + 2, rt + 1, rr - 2, rb - 2, fill);
        stroke.setColor(0xFF6D4C41);
        stroke.setStrokeWidth(0.8f);
        c.drawRect(rl + 2, rt + 1, rr - 2, rb - 2, stroke);
        roof(c, bld, STABLE_ROOF[s], true);
        if (game.pvStable[s]) roofPv(c, bld);
        // großes Tiersymbol auf dem Dach
        text.setTextSize(26);
        c.drawText(Defs.S_ICON[s], (bld[0] + bld[2] / 2f) * Defs.TILE, (bld[1] + bld[3] / 2f) * Defs.TILE + 9, text);
        if (detail == 2) drawAnimals(c, s, rl + 5, rt + 4, rr - 5, rb - 5);
    }

    private void drawAnimals(Canvas c, int s, float l, float t, float rr, float b) {
        while (animals.size() <= s) animals.add(new java.util.ArrayList<>());
        java.util.ArrayList<Animal> list = animals.get(s);
        int count = s == Defs.S_CHICKEN ? 16 : s == Defs.S_COW ? 7 : s == Defs.S_PIG ? 9 : 10;
        while (list.size() < count) {
            Animal a = new Animal();
            a.x = a.tx = l + fxRnd.nextFloat() * (rr - l);
            a.y = a.ty = t + fxRnd.nextFloat() * (b - t);
            list.add(a);
        }
        float speed = s == Defs.S_CHICKEN ? 2.5f : 1.2f;
        for (Animal a : list) {
            if (a.wait > 0) {
                a.wait -= frameDt;
            } else {
                float dx = a.tx - a.x, dy = a.ty - a.y;
                float d = (float) Math.hypot(dx, dy);
                if (d < 0.5f) {
                    a.tx = l + fxRnd.nextFloat() * (rr - l);
                    a.ty = t + fxRnd.nextFloat() * (b - t);
                    a.wait = 1 + fxRnd.nextFloat() * 4;
                } else {
                    a.x += dx / d * speed * frameDt;
                    a.y += dy / d * speed * frameDt;
                    a.heading = (float) Math.toDegrees(Math.atan2(dy, dx));
                }
            }
            c.save();
            c.translate(a.x, a.y);
            c.rotate(a.heading);
            switch (s) {
                case Defs.S_CHICKEN:
                    fill.setColor(0xFFFFFFFF);
                    c.drawCircle(0, 0, 0.6f, fill);
                    fill.setColor(0xFFE53935);
                    c.drawCircle(0.55f, 0, 0.22f, fill);
                    break;
                case Defs.S_COW:
                    fill.setColor(0xFFFAFAFA);
                    r.set(-1.2f, -0.6f, 1.2f, 0.6f);
                    c.drawRoundRect(r, 0.4f, 0.4f, fill);
                    fill.setColor(0xFF212121);
                    c.drawCircle(-0.4f, -0.2f, 0.3f, fill);
                    c.drawCircle(0.3f, 0.25f, 0.25f, fill);
                    c.drawCircle(1.35f, 0, 0.35f, fill);
                    break;
                case Defs.S_PIG:
                    fill.setColor(0xFFF8BBD0);
                    r.set(-0.8f, -0.45f, 0.8f, 0.45f);
                    c.drawRoundRect(r, 0.4f, 0.4f, fill);
                    fill.setColor(0xFFF48FB1);
                    c.drawCircle(0.9f, 0, 0.25f, fill);
                    break;
                default:
                    fill.setColor(0xFFF5F5F5);
                    c.drawCircle(0, 0, 0.7f, fill);
                    c.drawCircle(-0.4f, 0.2f, 0.5f, fill);
                    fill.setColor(0xFF424242);
                    c.drawCircle(0.75f, 0, 0.3f, fill);
            }
            c.restore();
        }
    }

    private void drawSolarPark(Canvas c, float l, float t, float rr, float b) {
        fill.setColor(0xFF7FA35A);
        c.drawRect(l, t, rr, b, fill);
        fill.setColor(0xFF1A237E);
        for (float y = t + 2; y < b - 3; y += 6) c.drawRect(l + 3, y, rr - 3, y + 3.5f, fill);
        if (detail == 0) return;
        stroke.setColor(0xFF5C6BC0);
        stroke.setStrokeWidth(0.25f);
        for (float y = t + 2; y < b - 3; y += 6) {
            for (float x = l + 3; x < rr - 3; x += 2) c.drawLine(x, y, x, y + 3.5f, stroke);
        }
    }

    private void drawWind(Canvas c, int[] wnd) {
        float x = (wnd[0] + 0.5f) * Defs.TILE, y = (wnd[1] + 0.5f) * Defs.TILE;
        fill.setColor(0x44000000);
        c.drawCircle(x + 3, y + 3, 3.5f, fill);
        fill.setColor(0xFFECEFF1);
        c.drawCircle(x, y, 3.5f, fill);
        float angle = (float) (now() * 60 * game.weatherWind);
        c.save();
        c.translate(x, y);
        c.rotate(angle);
        stroke.setColor(0x33000000);
        stroke.setStrokeWidth(2.2f);
        for (int k = 0; k < 3; k++) {
            double a = Math.toRadians(k * 120);
            c.drawLine(3, 3, 3 + (float) Math.cos(a) * 38, 3 + (float) Math.sin(a) * 38, stroke);
        }
        stroke.setColor(0xFFFAFAFA);
        for (int k = 0; k < 3; k++) {
            double a = Math.toRadians(k * 120);
            c.drawLine(0, 0, (float) Math.cos(a) * 38, (float) Math.sin(a) * 38, stroke);
        }
        fill.setColor(0xFFB0BEC5);
        c.drawCircle(0, 0, 2.2f, fill);
        c.restore();
    }

    private void drawCar(Canvas c, Game.Car car) {
        float len = car.type == Game.CAR ? 4.5f : car.type == Game.VAN ? 5.5f : 12f;
        float wid = car.type == Game.CAR ? 1.9f : car.type == Game.VAN ? 2.1f : 2.5f;
        float mult = Math.max(1f, 8f / (len * scale));
        laneShift(car.x, car.y, car.heading, shift);
        c.save();
        c.translate(shift[0], shift[1]);
        c.rotate(car.heading);
        c.scale(mult, mult);
        float hl = len / 2, hw = wid / 2;
        if (detail == 2) {
            fill.setColor(0x44000000);
            c.drawRect(-hl + 0.6f, -hw + 0.6f, hl + 0.6f, hw + 0.6f, fill);
        }
        if (car.type == Game.TRUCK) {
            fill.setColor(0xFF1565C0);
            c.drawRect(hl - 2.4f, -hw, hl, hw, fill);
            fill.setColor(car.color);
            c.drawRect(-hl, -hw, hl - 2.8f, hw, fill);
        } else {
            fill.setColor(car.color);
            r.set(-hl, -hw, hl, hw);
            c.drawRoundRect(r, 0.5f, 0.5f, fill);
            fill.setColor(0xFF263238);
            c.drawRect(hl * 0.25f, -hw * 0.75f, hl * 0.55f, hw * 0.75f, fill);
            if (car.type == Game.BUS) {
                fill.setColor(0x99263238);
                for (float x = -hl + 1; x < hl * 0.2f; x += 1.6f) c.drawRect(x, -hw * 0.8f, x + 1, hw * 0.8f, fill);
            }
        }
        c.restore();
    }

    private void drawFireTruck(Canvas c, Game.FireTruck t) {
        float len = 8f, wid = 2.5f;
        float mult = Math.max(1f, 12f / (len * scale));
        laneShift(t.x, t.y, t.heading, shift);
        if (t.state == 1 && game.fireField >= 0) {
            // Wasserstrahl
            double a = Math.atan2(game.fireY - t.y, game.fireX - t.x);
            float ex = game.fireX - (float) Math.cos(a) * game.fireR, ey = game.fireY - (float) Math.sin(a) * game.fireR;
            stroke.setColor(0xAA4FC3F7);
            stroke.setStrokeWidth(1.6f);
            float wob = (float) Math.sin(now() * 6) * 4;
            c.drawLine(shift[0], shift[1], ex + wob, ey - wob, stroke);
            fill.setColor(0x6681D4FA);
            c.drawCircle(ex + wob, ey - wob, 5, fill);
        }
        c.save();
        c.translate(shift[0], shift[1]);
        c.rotate(t.heading);
        c.scale(mult, mult);
        float hl = len / 2, hw = wid / 2;
        fill.setColor(0xFFD32F2F);
        r.set(-hl, -hw, hl, hw);
        c.drawRoundRect(r, 0.4f, 0.4f, fill);
        fill.setColor(0xFFECEFF1);
        c.drawRect(-hl + 0.6f, -0.3f, hl - 2.2f, 0.3f, fill); // Leiter
        boolean blink = ((int) (now() * 4)) % 2 == 0;
        fill.setColor(blink ? 0xFF2979FF : 0xFF0D47A1);
        c.drawCircle(hl - 1.6f, -hw * 0.6f, 0.45f, fill);
        fill.setColor(!blink ? 0xFF2979FF : 0xFF0D47A1);
        c.drawCircle(hl - 1.6f, hw * 0.6f, 0.45f, fill);
        if (blink) {
            fill.setColor(0x332979FF);
            c.drawCircle(hl - 1.6f, 0, 3.5f, fill);
        }
        c.restore();
    }

    private void drawFire(Canvas c) {
        Field f = game.world.fields.get(game.fireField);
        float l = f.x * Defs.TILE, t = f.y * Defs.TILE, rr = (f.x + f.w) * Defs.TILE, b = (f.y + f.h) * Defs.TILE;
        float tm = now();
        float rad = game.fireR;
        c.save();
        c.clipRect(l, t, rr, b);
        int n = Math.max(8, (int) (rad * 2 * Math.PI / 3));
        for (int i = 0; i < n; i++) {
            double a = i * 2 * Math.PI / n;
            float flick = (float) (0.6 + 0.4 * Math.sin(tm * 9 + i * 1.7));
            float fx = game.fireX + (float) Math.cos(a) * rad, fy = game.fireY + (float) Math.sin(a) * rad;
            fill.setColor(0xCCFF6D00);
            c.drawCircle(fx, fy, 2.2f * flick + 0.6f, fill);
            fill.setColor(0xDDFFD600);
            c.drawCircle(fx, fy, 1.1f * flick, fill);
        }
        c.restore();
        // Rauch zieht mit dem Wind
        if (detail >= 1) {
            for (int i = 0; i < 10; i++) {
                float age = (tm * 0.35f + i / 10f) % 1f;
                double a = i * 2.3;
                float sx0 = game.fireX + (float) Math.cos(a) * rad * 0.8f, sy0 = game.fireY + (float) Math.sin(a) * rad * 0.8f;
                fill.setColor(((int) (110 * (1 - age)) << 24) | 0x555555);
                c.drawCircle(sx0 + age * 60, sy0 - age * 25, 4 + age * 16, fill);
            }
        }
    }

    private void spawnDust(Game.Vehicle v) {
        if (dust.size() > 120) return;
        Dust d = new Dust();
        double a = Math.toRadians(v.heading);
        float back = v.m().len / 2 + (game.tool(v.tool) != null ? game.tool(v.tool).m().len : 0);
        d.x = v.x - (float) Math.cos(a) * back + (fxRnd.nextFloat() - 0.5f) * 3;
        d.y = v.y - (float) Math.sin(a) * back + (fxRnd.nextFloat() - 0.5f) * 3;
        d.size = 1.5f + fxRnd.nextFloat() * 2;
        dust.add(d);
    }

    private void drawDust(Canvas c) {
        for (int i = dust.size() - 1; i >= 0; i--) {
            Dust d = dust.get(i);
            d.age += frameDt;
            if (d.age > 2.5f) {
                dust.remove(i);
                continue;
            }
            float f = d.age / 2.5f;
            fill.setColor(((int) (90 * (1 - f)) << 24) | 0x8D6E63);
            c.drawCircle(d.x + f * 4, d.y - f * 2, d.size * (1 + f * 2), fill);
        }
    }
    // ---------------------------------------------------------- Fahrzeuge

    private void drawVehicle(Canvas c, Game.Vehicle v, boolean selected) {
        Defs.Model m = v.m();
        float len = m.len, wid = m.wid;
        Game.Tool tool = game.tool(v.tool);
        float total = len + (tool != null ? tool.m().len + 0.6f : 0);
        float mult = Math.max(1f, 14f / (total * scale));
        c.save();
        laneShift(v.x, v.y, v.heading, shift);
        c.translate(shift[0], shift[1]);
        if (detail == 2) {
            c.save();
            c.rotate(v.heading);
            fill.setColor(0x44000000);
            r.set(-total * mult * 0.5f + 0.8f * mult - (tool != null ? tool.m().len * mult * 0.5f : 0), -wid * mult * 0.5f + 1.2f,
                    len * mult * 0.5f + 0.8f, wid * mult * 0.5f + 1.2f);
            c.drawRoundRect(r, 1, 1, fill);
            c.restore();
        }
        if (selected) {
            stroke.setColor(0xFFFFEB3B);
            stroke.setStrokeWidth(3f / scale);
            c.drawCircle(0, 0, total * mult * 0.7f, stroke);
        }
        c.rotate(v.heading);
        c.scale(mult, mult);
        if (v.isTractor()) {
            if (tool != null) drawTool(c, tool.m(), -len / 2, v);
            drawTractor(c, len, wid, m.color);
        } else if (m.sub == Defs.H_COMBINE) {
            drawCombine(c, len, wid, v, m.color);
        } else if (m.sub == Defs.H_CHOPPER) {
            drawChopper(c, len, wid, v, m.color);
        } else {
            drawPotatoHarvester(c, len, wid, v, m.color);
        }
        c.restore();
    }

    private void wheel(Canvas c, float x0, float x1, float y0, float y1) {
        fill.setColor(0xFF212121);
        r.set(x0, y0, x1, y1);
        c.drawRoundRect(r, 0.2f, 0.2f, fill);
    }

    private void drawTractor(Canvas c, float L, float W, int color) {
        float hl = L / 2, hw = W / 2;
        wheel(c, -hl * 0.85f, -hl * 0.15f, -hw - 0.15f, -hw + 0.55f);
        wheel(c, -hl * 0.85f, -hl * 0.15f, hw - 0.55f, hw + 0.15f);
        wheel(c, hl * 0.35f, hl * 0.85f, -hw + 0.05f, -hw + 0.45f);
        wheel(c, hl * 0.35f, hl * 0.85f, hw - 0.45f, hw - 0.05f);
        fill.setColor(color);
        r.set(-hl * 0.95f, -hw * 0.55f, hl, hw * 0.55f);
        c.drawRoundRect(r, 0.3f, 0.3f, fill);
        fill.setColor(mix(color, 0xFF000000, 0.25));
        r.set(hl * 0.1f, -hw * 0.35f, hl * 0.95f, hw * 0.35f);
        c.drawRoundRect(r, 0.25f, 0.25f, fill);
        fill.setColor(0xFFECEFF1);
        r.set(-hl * 0.75f, -hw * 0.6f, hl * 0.05f, hw * 0.6f);
        c.drawRoundRect(r, 0.25f, 0.25f, fill);
        fill.setColor(0xFF90CAF9);
        r.set(-hl * 0.05f, -hw * 0.5f, hl * 0.05f, hw * 0.5f);
        c.drawRect(r, fill);
    }

    private void drawTool(Canvas c, Defs.Model m, float back, Game.Vehicle v) {
        float hw = m.wid / 2, len = m.len;
        float b0 = back - 0.6f, b1 = b0 - len;
        fill.setColor(0xFF616161);
        c.drawRect(b0, -0.15f, back, 0.15f, fill);
        switch (m.sub) {
            case Defs.T_GRUBBER: {
                fill.setColor(0xFF9E9E9E);
                c.drawRect(b1, -hw, b0, hw, fill);
                stroke.setColor(0xFF424242);
                stroke.setStrokeWidth(0.18f);
                for (float y = -hw + 0.2f; y <= hw - 0.1f; y += 0.45f) c.drawLine(b1 - 0.2f, y, b0 - 0.2f, y, stroke);
                fill.setColor(0xFF616161);
                c.drawRect(b0 - 0.4f, -hw, b0 - 0.2f, hw, fill);
                break;
            }
            case Defs.T_SEEDER: {
                fill.setColor(0xFF1565C0);
                c.drawRect(b1, -hw, b0, hw, fill);
                fill.setColor(0xFF42A5F5);
                c.drawRect(b1 + 0.4f, -hw + 0.4f, b0 - 0.4f, hw - 0.4f, fill);
                break;
            }
            case Defs.T_PLANTER: {
                fill.setColor(0xFFE65100);
                c.drawRect(b1, -hw, b0, hw, fill);
                fill.setColor(0xFFA1887F);
                c.drawRect(b1 + 0.4f, -hw + 0.4f, b0 - 0.4f, hw - 0.4f, fill);
                break;
            }
            case Defs.T_SPREADER: {
                fill.setColor(0xFFF57C00);
                c.drawCircle((b0 + b1) / 2, 0, hw, fill);
                fill.setColor(0xFFFFFFFF);
                c.drawCircle((b0 + b1) / 2, 0, hw * 0.55f, fill);
                break;
            }
            case Defs.T_MOWER: {
                // Mähbalken quer zur Fahrtrichtung
                fill.setColor(0xFF7CB342);
                c.drawRect(b1, -hw, b0, hw, fill);
                fill.setColor(0xFF33691E);
                c.drawRect(b1 + 0.3f, -hw, b1 + 0.6f, hw, fill);
                fill.setColor(0xFFBDBDBD);
                for (float y = -hw + 0.3f; y < hw; y += 0.7f) c.drawCircle(b1 + 0.2f, y, 0.25f, fill);
                break;
            }
            case Defs.T_LOADER: {
                wheel(c, b1 + len * 0.35f - 0.5f, b1 + len * 0.35f + 0.5f, -hw - 0.2f, -hw + 0.3f);
                wheel(c, b1 + len * 0.35f - 0.5f, b1 + len * 0.35f + 0.5f, hw - 0.3f, hw + 0.2f);
                wheel(c, b1 + len * 0.35f + 0.6f, b1 + len * 0.35f + 1.6f, -hw - 0.2f, -hw + 0.3f);
                wheel(c, b1 + len * 0.35f + 0.6f, b1 + len * 0.35f + 1.6f, hw - 0.3f, hw + 0.2f);
                fill.setColor(0xFFC62828);
                c.drawRect(b1, -hw, b0, hw, fill);
                fill.setColor(0xFF8E0000);
                c.drawRect(b1 + 0.2f, -hw + 0.2f, b0 - 0.8f, hw - 0.2f, fill);
                fill.setColor(0xFF424242);
                c.drawRect(b0 - 0.8f, -hw - 0.1f, b0 - 0.2f, hw + 0.1f, fill); // Pick-up
                if (v != null && v.load > 0 && v.loadProduct >= 0) {
                    float f = (float) Math.min(1, v.load / m.cap);
                    fill.setColor(PRODUCT_COLOR[v.loadProduct]);
                    r.set(b1 + 0.2f, -(hw - 0.2f) * f, b0 - 0.8f, (hw - 0.2f) * f);
                    c.drawRoundRect(r, 0.8f, 0.8f, fill);
                }
                break;
            }
            default: { // Kipper
                int axles = m.cap >= 33 ? 3 : m.cap >= 20 ? 2 : 1;
                for (int a = 0; a < axles; a++) {
                    float ax = b1 + len * 0.3f + a * 1.1f;
                    wheel(c, ax - 0.5f, ax + 0.5f, -hw - 0.2f, -hw + 0.3f);
                    wheel(c, ax - 0.5f, ax + 0.5f, hw - 0.3f, hw + 0.2f);
                }
                fill.setColor(0xFF558B2F);
                c.drawRect(b1, -hw, b0, hw, fill);
                fill.setColor(0xFF33691E);
                c.drawRect(b1 + 0.2f, -hw + 0.2f, b0 - 0.2f, hw - 0.2f, fill);
                if (v != null && v.load > 0 && v.loadProduct >= 0) {
                    float f = (float) Math.min(1, v.load / m.cap);
                    fill.setColor(PRODUCT_COLOR[v.loadProduct]);
                    r.set(b1 + 0.2f, -(hw - 0.2f) * f, b0 - 0.2f, (hw - 0.2f) * f);
                    c.drawRoundRect(r, 0.8f, 0.8f, fill);
                }
            }
        }
    }

    private void loadFill(Canvas c, Game.Vehicle v, float x0, float x1, float hw) {
        double cap = v.m().cap;
        if (v.load <= 0 || cap <= 0 || v.loadProduct < 0) return;
        float f = (float) Math.min(1, v.load / cap);
        fill.setColor(PRODUCT_COLOR[v.loadProduct]);
        r.set(x0, -hw * f, x1, hw * f);
        c.drawRoundRect(r, 0.5f, 0.5f, fill);
    }

    private void drawCombine(Canvas c, float L, float W, Game.Vehicle v, int color) {
        float hl = L / 2, hw = W / 2;
        float header = W * 0.95f + 1.5f;
        wheel(c, hl * 0.05f, hl * 0.55f, -hw - 0.2f, -hw + 0.6f);
        wheel(c, hl * 0.05f, hl * 0.55f, hw - 0.6f, hw + 0.2f);
        fill.setColor(0xFFF9A825);
        c.drawRect(hl * 0.75f, -header, hl + 0.9f, header, fill);
        stroke.setColor(0xFFBF7F00);
        stroke.setStrokeWidth(0.15f);
        for (float y = -header + 0.2f; y <= header - 0.2f; y += 0.6f) c.drawLine(hl * 0.78f, y, hl + 0.8f, y, stroke);
        fill.setColor(color);
        r.set(-hl, -hw * 0.75f, hl * 0.78f, hw * 0.75f);
        c.drawRoundRect(r, 0.5f, 0.5f, fill);
        fill.setColor(mix(color, 0xFF000000, 0.3));
        r.set(-hl * 0.8f, -hw * 0.55f, hl * 0.15f, hw * 0.55f);
        c.drawRoundRect(r, 0.4f, 0.4f, fill);
        loadFill(c, v, -hl * 0.75f, hl * 0.1f, hw * 0.5f);
        fill.setColor(0xFFECEFF1);
        r.set(hl * 0.3f, -hw * 0.5f, hl * 0.72f, hw * 0.5f);
        c.drawRoundRect(r, 0.3f, 0.3f, fill);
        fill.setColor(0xFF90CAF9);
        c.drawRect(hl * 0.62f, -hw * 0.4f, hl * 0.72f, hw * 0.4f, fill);
        stroke.setColor(mix(color, 0xFF000000, 0.3));
        stroke.setStrokeWidth(0.35f);
        c.drawLine(-hl * 0.3f, -hw * 0.5f, -hl * 0.6f, -hw - 1.4f, stroke);
    }

    private void drawChopper(Canvas c, float L, float W, Game.Vehicle v, int color) {
        float hl = L / 2, hw = W / 2;
        float header = W * 0.7f + 0.8f;
        wheel(c, hl * 0.1f, hl * 0.6f, -hw - 0.2f, -hw + 0.6f);
        wheel(c, hl * 0.1f, hl * 0.6f, hw - 0.6f, hw + 0.2f);
        fill.setColor(0xFF424242);
        c.drawRect(hl * 0.8f, -header, hl + 0.8f, header, fill);
        stroke.setColor(0xFF9E9E9E);
        stroke.setStrokeWidth(0.15f);
        for (float y = -header + 0.2f; y <= header - 0.2f; y += 0.55f) c.drawLine(hl * 0.82f, y, hl + 0.7f, y, stroke);
        fill.setColor(color);
        r.set(-hl, -hw * 0.75f, hl * 0.82f, hw * 0.75f);
        c.drawRoundRect(r, 0.5f, 0.5f, fill);
        loadFill(c, v, -hl * 0.85f, -hl * 0.1f, hw * 0.55f);
        fill.setColor(0xFFECEFF1);
        r.set(hl * 0.3f, -hw * 0.5f, hl * 0.75f, hw * 0.5f);
        c.drawRoundRect(r, 0.3f, 0.3f, fill);
        stroke.setColor(mix(color, 0xFF000000, 0.4));
        stroke.setStrokeWidth(0.5f);
        c.drawLine(hl * 0.1f, -hw * 0.4f, -hl * 0.2f, -hw - 2.2f, stroke);
    }

    private void drawPotatoHarvester(Canvas c, float L, float W, Game.Vehicle v, int color) {
        float hl = L / 2, hw = W / 2;
        wheel(c, -hl * 0.5f, hl * 0.0f, -hw - 0.2f, -hw + 0.6f);
        wheel(c, -hl * 0.5f, hl * 0.0f, hw - 0.6f, hw + 0.2f);
        fill.setColor(0xFF5D4037);
        c.drawRect(hl * 0.75f, -hw * 0.9f, hl + 0.6f, hw * 0.9f, fill);
        fill.setColor(color);
        r.set(-hl, -hw * 0.8f, hl * 0.8f, hw * 0.8f);
        c.drawRoundRect(r, 0.5f, 0.5f, fill);
        fill.setColor(mix(color, 0xFF000000, 0.3));
        r.set(-hl * 0.85f, -hw * 0.6f, hl * 0.1f, hw * 0.6f);
        c.drawRoundRect(r, 0.3f, 0.3f, fill);
        loadFill(c, v, -hl * 0.8f, hl * 0.05f, hw * 0.55f);
        fill.setColor(0xFFECEFF1);
        r.set(hl * 0.3f, -hw * 0.5f, hl * 0.75f, hw * 0.5f);
        c.drawRoundRect(r, 0.3f, 0.3f, fill);
    }

    // -------------------------------------------------------------- Texte

    private void drawLabels(Canvas c) {
        World w = game.world;
        float ts = Math.max(26f, Math.min(42f, scale * 9f));
        text.setTextSize(ts);
        for (Field f : w.fields) {
            float cx = sx((f.x + f.w / 2f) * Defs.TILE), cy = sy((f.y + f.h / 2f) * Defs.TILE);
            if (cx < -100 || cy < -100 || cx > getWidth() + 100 || cy > getHeight() + 100) continue;
            text.setColor(f.owned ? 0xFFFFEB3B : 0xDDFFFFFF);
            c.drawText((f.owned ? "★ " : "") + f.number + (game.fireField == f.index ? " 🔥" : ""), cx, cy + ts * 0.35f, text);
        }
        text.setColor(0xFFFFFFFF);
        text.setTextSize(ts * 0.8f);
        label(c, w.hof, "🏠 Dein Hof");
        label(c, w.trader[Defs.B_LANDHANDEL], "🌾 Landhandel");
        label(c, w.trader[Defs.B_MILL], "🏭 Mühle");
        label(c, w.trader[Defs.B_BIOGAS], "⚡ Biogas");
        for (int s = 0; s < Defs.STABLES; s++) {
            if (game.stables[s]) label(c, w.stableLot[s], Defs.S_NAMES[s]);
            else label(c, w.stableLot[s], "Bauplatz " + Defs.S_ICON[s]);
        }
        if (scale < 2.5f) {
            text.setColor(0xCCFFF9C4);
            for (int i = 0; i < w.villageCenters.size(); i++) {
                int[] vc = w.villageCenters.get(i);
                c.drawText(w.villageNames.get(i), sx((vc[0] + 0.5f) * Defs.TILE), sy((vc[1] - 3) * Defs.TILE), text);
            }
            text.setColor(0xFFFFFFFF);
        }
        Game.Vehicle sel = game.vehicle(selectedVehicle);
        if (sel != null) {
            text.setColor(0xFFFFEB3B);
            c.drawText(sel.name(), sx(sel.x), sy(sel.y) - Math.max(30, 6 * scale), text);
            text.setColor(0xFFFFFFFF);
        }
    }

    private void label(Canvas c, int[] rect, String s) {
        if (rect == null) return;
        float cx = sx((rect[0] + rect[2] / 2f) * Defs.TILE), cy = sy(rect[1] * Defs.TILE) - 8;
        if (cx < -200 || cy < -50 || cx > getWidth() + 200 || cy > getHeight() + 50) return;
        c.drawText(s, cx, cy, text);
    }

    // ---------------------------------------------------------- Vorschaubilder

    private final java.util.HashMap<String, Bitmap> thumbs = new java.util.HashMap<>();

    /** Kleines Bild einer Maschine (optional mit angehängtem Gerät) für Listen */
    public Bitmap thumbnail(Defs.Model m, Defs.Model tool, int wPx, int hPx) {
        String key = m.id + "/" + (tool != null ? tool.id : -1) + "/" + wPx;
        Bitmap cached = thumbs.get(key);
        if (cached != null) return cached;
        Bitmap bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float len, wid;
        if (m.kind == Defs.K_TOOL) {
            len = m.len + 0.6f;
            wid = m.wid;
        } else {
            len = m.len + (tool != null ? tool.len + 0.6f : 0) + (m.kind == Defs.K_HARVESTER ? 2f : 0);
            wid = Math.max(m.wid, tool != null ? tool.wid : 0);
            if (m.kind == Defs.K_HARVESTER && m.sub != Defs.H_POTATO) wid = Math.max(wid, m.wid * 1.9f + 3f);
        }
        float sc = Math.min(wPx * 0.92f / len, hPx * 0.88f / Math.max(wid, 2.5f));
        c.translate(wPx / 2f, hPx / 2f);
        c.scale(sc, sc);
        int oldDetail = detail;
        detail = 1;
        if (m.kind == Defs.K_TOOL) {
            drawTool(c, m, len / 2f, null);
        } else if (m.kind == Defs.K_TRACTOR) {
            float shiftX = tool != null ? (tool.len + 0.6f) / 2f : 0;
            c.translate(shiftX, 0);
            if (tool != null) drawTool(c, tool, -m.len / 2, null);
            drawTractor(c, m.len, m.wid, m.color);
        } else {
            Game.Vehicle dummy = new Game.Vehicle();
            dummy.model = m.id;
            c.translate(-1f, 0);
            if (m.sub == Defs.H_COMBINE) drawCombine(c, m.len, m.wid, dummy, m.color);
            else if (m.sub == Defs.H_CHOPPER) drawChopper(c, m.len, m.wid, dummy, m.color);
            else drawPotatoHarvester(c, m.len, m.wid, dummy, m.color);
        }
        detail = oldDetail;
        thumbs.put(key, bmp);
        return bmp;
    }
}
