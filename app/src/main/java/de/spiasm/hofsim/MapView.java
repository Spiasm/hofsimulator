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

    public static final int[] PRODUCT_COLOR = {0xFF7CB342, 0xFFE0B040, 0xFFA1887F, 0xFF9CCC65, 0xFFD7B98E, 0xFF8D6E63, 0xFFA1887F, 0xFF6D4C41,
            0xFF3E2723, 0xFFC9A04A, 0xFFD7C48A, 0xFF9CCC65, 0xFF2E7D32};

    static int productColor(int p) {
        if (p == -2) return 0xFF5D4037; // Gülle
        if (p == -3) return 0xFF4E342E; // Mist
        if (p < 0 || p >= PRODUCT_COLOR.length) return 0xFF9E9E9E;
        return PRODUCT_COLOR[p];
    }

    public int selectedForest = -1;

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
                    case World.FORESTLOT:
                        p.setColor(0xFF4A5A32);
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
        final int local = game.currentFarm();
        for (int fp = 0; fp < game.farmCount(); fp++) {
            game.select(fp);
            drawHof(c);
            for (int s = 0; s < Defs.STABLES; s++) {
                if ((fp == local || game.stables[s]) && visible(wo.stableLot[s], vl, vt, vr, vb)) drawStableLot(c, s);
            }
        }
        game.select(local);
        for (Forest fo : wo.forests) {
            int[] fr = {fo.x, fo.y, fo.w, fo.h};
            if (visible(fr, vl, vt, vr, vb)) drawForest(c, fo);
        }
        for (int i = 0; i < Defs.BUYERS; i++) if (visible(wo.trader[i], vl, vt, vr, vb)) drawTrader(c, wo.trader[i], i);

        updateWeatherShown();
        drawSnowCover(c, vl, vt, vr, vb);
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
        for (int fp = 0; fp < game.farmCount(); fp++) {
        game.select(fp);
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
        if (detail >= 1 && fp == 0) {
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
        for (int[] wnd : game.winds) drawWind(c, wnd);
        }
        game.select(local);
        if (detail == 2) drawDust(c);
        if (game.fireField >= 0) drawFire(c);
        if (game.fireTruck != null) drawFireTruck(c, game.fireTruck);
        c.restore();

        drawWeather(c, w, h);
        for (int fp = 0; fp < game.farmCount(); fp++) {
            game.select(fp);
            drawIndicators(c);
        }
        game.select(local);
        drawLabels(c);
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
        if (f.owned || f.owner >= 0) {
            stroke.setColor(Game.FARM_COLOR[Math.max(0, f.owner) % 3]);
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
            if (this.detail == 0) return;
            stroke.setColor(0xFFB5B04A);
            stroke.setStrokeWidth(1.8f);
            stroke.setStrokeCap(Paint.Cap.BUTT);
            for (float y = t + 3f; y < b; y += 6f) c.drawLine(l + 1, y, rr - 1, y, stroke);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            return;
        }
        float rowSp = Math.max(2.5f, 5f / scale);
        boolean fine = scale >= 1.6f;
        int base, row = 0;
        float rowW = rowSp * 0.35f;
        int dotColor = 0;
        float dotR = 0;
        switch (state) {
            case Field.STUBBLE:
                boolean light = crop == Defs.WHEAT || crop == Defs.MAIZE || crop == Defs.SPELT || crop == Defs.RAPE || crop == Defs.SOY;
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
                    case Defs.RAPE:
                        if (g < 0.1) {
                            base = SOIL;
                            row = 0xFF7CB342;
                            rowW = rowSp * 0.15f;
                        } else if (g < 0.55) {
                            base = mix(SOIL, 0xFF4E8F3A, (g - 0.1) / 0.45);
                            row = mix(base, 0xFF2E5D22, 0.4);
                            rowW = rowSp * 0.35f;
                        } else if (g < 0.85) {
                            // Rapsblüte
                            double k = Math.min(1, (g - 0.55) / 0.1) * Math.min(1, (0.85 - g) / 0.08);
                            base = mix(0xFF5E9440, 0xFFF4D03F, k);
                            row = mix(base, 0xFFFFEB3B, 0.3);
                            rowW = rowSp * 0.2f;
                        } else {
                            base = g < 1 ? mix(0xFF7F8B3A, 0xFF6F5B2E, (g - 0.85) / 0.15) : 0xFF6A5530;
                            row = mix(base, 0xFF000000, 0.15);
                            rowW = rowSp * 0.12f;
                        }
                        break;
                    case Defs.SOY:
                        base = g < 1 ? mix(SOIL, 0xFF6B8E23, Math.min(1, g * 1.3)) : 0xFFA88B4F;
                        row = mix(base, 0xFF000000, 0.15);
                        rowW = rowSp * 0.2f;
                        dotColor = g < 0.85 ? mix(0xFF8BC34A, 0xFF4E7D24, g) : mix(0xFF8D8A3C, 0xFFB59B5A, Math.min(1, (g - 0.85) / 0.15));
                        dotR = (float) (0.15 + 0.45 * Math.min(1, g)) * rowSp * 0.4f;
                        if (g < 0.05) dotR = 0;
                        break;
                    case Defs.SALAD:
                        base = SOIL;
                        row = 0xFF8E6A45;
                        rowW = rowSp * 0.4f;
                        dotColor = g < 1 ? mix(0xFFC5E1A5, 0xFF7CB342, g) : 0xFF8BC34A;
                        dotR = (float) (0.15 + 0.5 * Math.min(1, g)) * rowSp * 0.42f;
                        if (g < 0.03) dotR = 0;
                        break;
                    case Defs.MELON:
                        base = mix(SOIL, 0xFF55803A, Math.min(1, g * 1.2) * 0.8);
                        row = mix(base, 0xFF2E5D22, 0.35);
                        rowW = rowSp * 0.5f;
                        if (g > 0.5) {
                            dotColor = 0xFF2E6B2E;
                            dotR = (float) Math.min(1, (g - 0.5) * 2) * rowSp * 0.38f;
                        }
                        break;
                    case Defs.SPELT:
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
                        if (crop == Defs.SPELT) {
                            // Dinkel: etwas dunkler, rötlich-braun reif
                            base = mix(base, g >= 0.8 ? 0xFFB07A3A : 0xFF4F7A2A, 0.25);
                            row = mix(row, 0xFF6D4C2A, 0.2);
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
        if (this.detail == 0) return;
        // Reihen
        stroke.setColor(row);
        stroke.setStrokeWidth(rowW);
        stroke.setStrokeCap(Paint.Cap.BUTT);
        for (float y = t + rowSp / 2; y < b; y += rowSp) c.drawLine(l + 0.5f, y, rr - 0.5f, y, stroke);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        // Pflanzen: als gerade Bahnen (einzelne Punkte wären viel zu viele zum Zeichnen)
        if (dotR > 0) {
            stroke.setColor(dotColor);
            stroke.setStrokeWidth(dotR * 1.6f);
            stroke.setStrokeCap(Paint.Cap.BUTT);
            for (float y = t + rowSp / 2; y < b; y += rowSp) c.drawLine(l + 1, y, rr - 1, y, stroke);
            if (fine && (crop == Defs.SALAD || crop == Defs.MELON)) {
                // hellere Mittellinie für etwas Struktur
                stroke.setColor(crop == Defs.SALAD ? 0x99F1F8E9 : 0xFF7CB342);
                stroke.setStrokeWidth(dotR * 0.45f);
                for (float y = t + rowSp / 2; y < b; y += rowSp) c.drawLine(l + 1, y, rr - 1, y, stroke);
            }
            stroke.setStrokeCap(Paint.Cap.ROUND);
        }
        // reifer Weizen: Ähren-Struktur
        if (state == Field.GROWING && (crop == Defs.WHEAT || crop == Defs.SPELT) && g >= 1 && fine) {
            fill.setColor(0x33FFF59D);
            Random rnd = new Random(seed * 7919L);
            int n = (int) ((rr - l) * (b - t) / 40);
            for (int i = 0; i < Math.min(n, 120); i++) c.drawCircle(l + rnd.nextFloat() * (rr - l), t + rnd.nextFloat() * (b - t), 1.2f, fill);
        }
        // Dünger
        if (fert && state == Field.GROWING && g < 1 && fine) {
            fill.setColor(0xCCFFFFFF);
            Random rnd = new Random(seed * 31L + 5);
            int n = (int) ((rr - l) * (b - t) / 60);
            for (int i = 0; i < Math.min(n, 120); i++) c.drawCircle(l + rnd.nextFloat() * (rr - l), t + rnd.nextFloat() * (b - t), 0.35f, fill);
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
        // Südseite = untere Dachhälfte (Norden ist oben)
        float l = rect[0] * Defs.TILE + 2, b = (rect[1] + rect[3]) * Defs.TILE - 2;
        float rr = (rect[0] + rect[2]) * Defs.TILE - 2, t = (rect[1] + rect[3] / 2f) * Defs.TILE + 1;
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
        } else if (type == Defs.B_SAWMILL) {
            int[] hall = {rect[0] + 1, rect[1] + 1, 5, rect[3] - 2};
            roof(c, hall, 0xFF6D4C41, false);
            for (int k = 0; k < 4; k++) drawLogPile(c, l + 66 + (k % 2) * 16, t + 14 + (k / 2) * 22, 12, 6);
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
        float angle = (float) (now() * 90 * game.windNow());
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
            Field ff = game.world.fields.get(game.fireField);
            float fl = ff.x * Defs.TILE + 2, ft = ff.y * Defs.TILE + 2, fr = (ff.x + ff.w) * Defs.TILE - 2, fb = (ff.y + ff.h) * Defs.TILE - 2;
            double d = Math.hypot(t.x - game.fireX, t.y - game.fireY);
            float ex, ey;
            if (d > game.fireR) {
                ex = game.fireX + (float) ((t.x - game.fireX) / d * game.fireR);
                ey = game.fireY + (float) ((t.y - game.fireY) / d * game.fireR);
            } else {
                ex = t.x;
                ey = t.y;
            }
            ex = Math.max(fl, Math.min(fr, ex));
            ey = Math.max(ft, Math.min(fb, ey));
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
            float flick = (float) (0.6 + 0.4 * Math.sin(tm * 9 + i * 1.7)) * game.firePower;
            if (flick < 0.05f) continue;
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
                fill.setColor(((int) (110 * (1 - age) * (0.3f + 0.7f * game.firePower)) << 24) | 0x555555);
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
    // ------------------------------------------------------------ Wald

    private void drawForest(Canvas c, Forest fo) {
        float l = fo.x * Defs.TILE, t = fo.y * Defs.TILE, rr = (fo.x + fo.w) * Defs.TILE, b = (fo.y + fo.h) * Defs.TILE;
        fill.setColor(fo.state == Forest.GROWING ? 0xFF3E5A2A : 0xFF6B5A3A);
        c.drawRect(l, t, rr, b, fill);
        boolean felling = fo.task == Defs.FT_FELL && fo.progress > 0;
        int fellLane = felling ? (int) Math.floor(fo.progress * fo.h) : -1;
        double fellFrac = felling ? fo.progress * fo.h - fellLane : 0;
        Random rnd = new Random(fo.index * 977L);
        boolean simple = detail == 0;
        for (int j = 0; j < fo.h; j++) {
            for (int i = 0; i < fo.w; i++) {
                for (int k = 0; k < (simple ? 1 : 2); k++) {
                    float x = l + (i + 0.25f + rnd.nextFloat() * 0.5f) * Defs.TILE;
                    float y = t + (j + 0.25f + rnd.nextFloat() * 0.5f) * Defs.TILE;
                    boolean felled = fo.state != Forest.GROWING;
                    if (felling) {
                        if (j < fellLane) felled = true;
                        else if (j == fellLane) {
                            float fx = (x - l) / (rr - l);
                            felled = (j % 2 == 0) ? fx < fellFrac : fx > 1 - fellFrac;
                        }
                    }
                    if (felled) {
                        // Baumstumpf
                        fill.setColor(0xFFBCA27A);
                        c.drawCircle(x, y, 0.8f, fill);
                        continue;
                    }
                    float g = (float) Math.max(0.12, Math.min(1, fo.growth));
                    float rad = Defs.TILE * 0.35f * g + 0.6f;
                    int col = fo.species == Defs.SPRUCE ? 0xFF2E5D32 : fo.species == Defs.BEECH ? 0xFF558B2F : 0xFF6B8E23;
                    if (!simple) {
                        fill.setColor(0x44000000);
                        c.drawCircle(x + rad * 0.3f, y + rad * 0.3f, rad, fill);
                    }
                    fill.setColor(col);
                    c.drawCircle(x, y, rad, fill);
                    if (!simple && fo.species == Defs.SPRUCE) {
                        fill.setColor(mix(col, 0xFF000000, 0.25));
                        c.drawCircle(x, y, rad * 0.45f, fill);
                    } else if (!simple) {
                        fill.setColor(mix(col, 0xFFFFFFFF, 0.15));
                        c.drawCircle(x - rad * 0.25f, y - rad * 0.25f, rad * 0.5f, fill);
                    }
                }
            }
        }
        // liegendes Holz im Bestand
        if (fo.logs > 0.5 && !simple) {
            int piles = (int) Math.min(30, fo.logs / 8);
            Random pr = new Random(fo.index * 31L);
            for (int k = 0; k < piles; k++) {
                float x = l + 5 + pr.nextFloat() * (rr - l - 10), y = t + 5 + pr.nextFloat() * (b - t - 10);
                drawLogPile(c, x, y, 4, 2.2f);
            }
        }
        // Polter an der Straße
        if (fo.polter > 0.5 && fo.gate != null) {
            float gx = (fo.gate[0] + 0.5f) * Defs.TILE, gy = (fo.gate[1] + 0.5f) * Defs.TILE;
            float px = Math.max(l + 4, Math.min(rr - 4, gx)), py = Math.max(t + 3, Math.min(b - 3, gy));
            float size = (float) Math.min(9, 3 + fo.polter / 15);
            drawLogPile(c, px, py, size, size * 0.6f);
        }
        boolean anyOwner = fo.owned || fo.owner >= 0;
        stroke.setColor(anyOwner ? Game.FARM_COLOR[Math.max(0, fo.owner) % 3] : 0x66000000);
        stroke.setStrokeWidth(anyOwner ? Math.max(1.2f, 3f / scale) : Math.max(0.8f, 1.5f / scale));
        c.drawRect(l, t, rr, b, stroke);
        if (fo.index == selectedForest) {
            stroke.setColor(0xFFFFFFFF);
            stroke.setStrokeWidth(4f / scale);
            c.drawRect(l + 2 / scale, t + 2 / scale, rr - 2 / scale, b - 2 / scale, stroke);
        }
    }

    private void drawLogPile(Canvas c, float x, float y, float len, float wid) {
        fill.setColor(0xFF8D6E63);
        c.drawRect(x - len / 2, y - wid / 2, x + len / 2, y + wid / 2, fill);
        fill.setColor(0xFFD7B98E);
        int n = Math.max(2, (int) (wid / 0.6f));
        for (int k = 0; k < n; k++) c.drawCircle(x + len / 2, y - wid / 2 + (k + 0.5f) * wid / n, wid / n * 0.45f, fill);
    }

    // ------------------------------------------------------------ Wetter

    private float rainShown, snowShown, cloudShown, snowCoverShown;

    private void updateWeatherShown() {
        float k = Math.min(1, frameDt * 0.5f);
        int wt = game.weatherType;
        rainShown += ((wt == 2 ? 1 : 0) - rainShown) * k;
        snowShown += ((wt == 3 ? 1 : 0) - snowShown) * k;
        cloudShown += ((wt == 0 ? 0.15f : wt == 1 ? 0.6f : 1f) - cloudShown) * k;
        snowCoverShown += ((float) game.snowCover - snowCoverShown) * k * 0.5f;
    }

    /** Schnee auf dem Boden (vor den Fahrzeugen gezeichnet, in Weltkoordinaten) */
    private void drawSnowCover(Canvas c, float vl, float vt, float vr, float vb) {
        if (snowCoverShown < 0.02f) return;
        fill.setColor(((int) (170 * snowCoverShown) << 24) | 0xF4F8FB);
        c.drawRect(vl, vt, vr, vb, fill);
    }

    /** Wolkenschatten, Regen, Schnee (in Bildschirmkoordinaten) */
    private void drawWeather(Canvas c, float w, float h) {
        float tm = now();
        // Tönung
        int tint = (int) (cloudShown * 35 + rainShown * 35);
        if (tint > 0) {
            fill.setColor((tint << 24) | 0x37474F);
            c.drawRect(0, 0, w, h, fill);
        }
        if (detail == 0) return;
        // ziehende Wolkenschatten
        int clouds = (int) (2 + cloudShown * 5);
        for (int i = 0; i < clouds; i++) {
            float span = Defs.MAP * Defs.TILE + 600;
            float wx = ((i * 397f + tm * 6f * (float) (0.5 + game.weatherWind)) % span) - 300;
            float wy = (i * 613f) % (Defs.MAP * Defs.TILE);
            float sx0 = sx(wx), sy0 = sy(wy);
            float rad = (120 + (i % 3) * 60) * scale;
            if (sx0 < -rad * 2 || sx0 > w + rad * 2 || sy0 < -rad || sy0 > h + rad) continue;
            fill.setColor(((int) (25 + cloudShown * 25) << 24));
            r.set(sx0 - rad * 1.6f, sy0 - rad * 0.8f, sx0 + rad * 1.6f, sy0 + rad * 0.8f);
            c.drawOval(r, fill);
        }
        // Regen
        if (rainShown > 0.05f) {
            int n = (int) ((detail == 2 ? 160 : 80) * rainShown);
            stroke.setColor(0x99B3E5FC);
            stroke.setStrokeWidth(2f);
            for (int i = 0; i < n; i++) {
                float px = (i * 73.3f + tm * 90) % (w + 100) - 50;
                float py = (i * 151.7f + tm * 900 + i * 37) % (h + 60) - 30;
                c.drawLine(px, py, px - 8, py + 26, stroke);
            }
        }
        // Schnee
        if (snowShown > 0.05f) {
            int n = (int) ((detail == 2 ? 140 : 70) * snowShown);
            fill.setColor(0xEEFFFFFF);
            for (int i = 0; i < n; i++) {
                float px = (i * 61.7f + (float) Math.sin(tm + i) * 20 + tm * 15) % (w + 40) - 20;
                float py = (i * 97.3f + tm * 60 + i * 13) % (h + 40) - 20;
                c.drawCircle(px, py, 2.5f + (i % 3), fill);
            }
        }
    }

    // ------------------------------------------------------- Anzeigen

    private void bar(Canvas c, float cx, float cy, float pct, String txt, int color) {
        float bw = 70, bh = 10;
        fill.setColor(0xAA000000);
        r.set(cx - bw / 2 - 2, cy - bh / 2 - 2, cx + bw / 2 + 2, cy + bh / 2 + 2);
        c.drawRoundRect(r, 4, 4, fill);
        fill.setColor(color);
        r.set(cx - bw / 2, cy - bh / 2, cx - bw / 2 + bw * Math.max(0, Math.min(1, pct)), cy + bh / 2);
        c.drawRoundRect(r, 3, 3, fill);
        text.setTextSize(24);
        text.setColor(0xFFFFFFFF);
        c.drawText(txt, cx, cy - bh - 4, text);
    }

    private static int fillColor(double pct) {
        if (pct >= 0.999) return 0xFFE53935;
        if (pct >= 0.8) return 0xFFFB8C00;
        if (pct >= 0.6) return 0xFFFDD835;
        return 0xFF66BB6A;
    }

    private static int powerColor(double pct) {
        if (pct < 0.05) return 0xFF9E9E9E;
        if (pct < 0.4) return 0xFFFDD835;
        return 0xFF66BB6A;
    }

    /** Füllstand über Fahrzeugen und Leistung von PV/Windrad */
    private void drawIndicators(Canvas c) {
        for (Game.Vehicle v : game.vehicles) {
            double cap = game.capOf(v);
            if (cap <= 0) continue;
            boolean show = v.load > 0.05 || (v.isHarvester() && v.working());
            if (!show) continue;
            float px = sx(v.x), py = sy(v.y) - Math.max(34, 7 * scale);
            if (px < -50 || py < -50 || px > getWidth() + 50 || py > getHeight() + 50) continue;
            double pct = v.load / cap;
            String icon = v.loadProduct == -2 ? "💧" : v.loadProduct == -3 ? "💩" : Defs.isWood(v.loadProduct) ? "🪵"
                    : v.loadProduct == Defs.CHIPS ? "🪵" : "🌾";
            bar(c, px, py, (float) pct, icon + " " + Math.round(pct * 100) + " %", fillColor(pct));
        }
        if (scale < 0.9f) return;
        float wnd = (float) game.windNow(), pv = (float) game.pvNow();
        for (int[] w : game.winds) {
            float px = sx((w[0] + 0.5f) * Defs.TILE), py = sy((w[1] + 0.5f) * Defs.TILE) - 45 * scale * 0.5f - 20;
            bar(c, px, py, wnd, "💨 " + Math.round(wnd * 100) + " %", powerColor(wnd));
        }
        for (Field f : game.world.fields) {
            if (!f.pv) continue;
            float px = sx((f.x + f.w / 2f) * Defs.TILE), py = sy((f.y + f.h / 2f) * Defs.TILE) + 30;
            bar(c, px, py, pv, "☀ " + Math.round(pv * 100) + " %", powerColor(pv));
        }
        if (scale < 1.5f) return;
        World w = game.world;
        if (game.pvHouse) pvBar(c, w.house, pv);
        if (game.pvHall) pvBar(c, w.hall, pv);
        for (int s = 0; s < Defs.STABLES; s++) {
            if (game.stables[s] && game.pvStable[s]) pvBar(c, new int[]{w.stableLot[s][0], w.stableLot[s][1], w.stableLot[s][2], 4}, pv);
        }
    }

    private void pvBar(Canvas c, int[] rect, float pv) {
        float px = sx((rect[0] + rect[2] / 2f) * Defs.TILE), py = sy((rect[1] + rect[3]) * Defs.TILE) + 14;
        bar(c, px, py, pv, "☀ " + Math.round(pv * 100) + " %", powerColor(pv));
    }

    // ------------------------------------------------- weitere Maschinen

    private void drawForestry(Canvas c, Game.Vehicle v, float L, float W, int color) {
        float hl = L / 2, hw = W / 2;
        Defs.Model m = v.m();
        // Räder (Bogie)
        for (float x : new float[]{-hl * 0.75f, -hl * 0.45f, hl * 0.35f, hl * 0.65f}) {
            wheel(c, x - 0.6f, x + 0.6f, -hw - 0.25f, -hw + 0.45f);
            wheel(c, x - 0.6f, x + 0.6f, hw - 0.45f, hw + 0.25f);
        }
        if (m.sub == Defs.F_LOGTRUCK) {
            fill.setColor(color);
            c.drawRect(hl - 2.6f, -hw, hl, hw, fill);
            fill.setColor(0xFF90CAF9);
            c.drawRect(hl - 0.9f, -hw * 0.8f, hl - 0.5f, hw * 0.8f, fill);
            fill.setColor(0xFF616161);
            c.drawRect(-hl, -hw * 0.9f, hl - 2.9f, hw * 0.9f, fill);
            if (v.load > 0) logLoad(c, -hl + 0.3f, hl - 3.2f, hw * 0.8f, (float) (v.load / m.cap));
            return;
        }
        fill.setColor(color);
        r.set(-hl, -hw * 0.8f, hl, hw * 0.8f);
        c.drawRoundRect(r, 0.5f, 0.5f, fill);
        fill.setColor(0xFFECEFF1);
        r.set(hl * 0.25f, -hw * 0.6f, hl * 0.75f, hw * 0.6f);
        c.drawRoundRect(r, 0.3f, 0.3f, fill);
        fill.setColor(0xFF90CAF9);
        c.drawRect(hl * 0.62f, -hw * 0.5f, hl * 0.72f, hw * 0.5f, fill);
        stroke.setColor(0xFF424242);
        stroke.setStrokeWidth(0.35f);
        if (m.sub == Defs.F_FELLER) {
            // Kran mit Aggregat vorne
            float arm = (float) Math.sin(now() * 1.5 + v.id) * 1.5f;
            c.drawLine(hl * 0.2f, 0, hl + 3f, arm, stroke);
            fill.setColor(0xFFFFD600);
            c.drawRect(hl + 2.4f, arm - 0.8f, hl + 3.6f, arm + 0.8f, fill);
        } else {
            // Rungenkorb hinten mit Holz
            fill.setColor(0xFF616161);
            c.drawRect(-hl, -hw * 0.9f, hl * 0.1f, hw * 0.9f, fill);
            if (v.load > 0) logLoad(c, -hl + 0.2f, hl * 0.05f, hw * 0.8f, (float) (v.load / m.cap));
            c.drawLine(hl * 0.15f, 0, -hl * 0.4f, hw + 1.2f, stroke);
        }
    }

    private void logLoad(Canvas c, float x0, float x1, float hw, float f) {
        f = Math.max(0.15f, Math.min(1, f));
        fill.setColor(0xFF8D6E63);
        c.drawRect(x0, -hw * f, x1, hw * f, fill);
        fill.setColor(0xFFD7B98E);
        for (float y = -hw * f + 0.3f; y < hw * f; y += 0.6f) c.drawCircle(x1, y, 0.28f, fill);
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
        } else if (v.isForestry()) {
            drawForestry(c, v, len, wid, m.color);
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
                    fill.setColor(productColor(v.loadProduct));
                    r.set(b1 + 0.2f, -(hw - 0.2f) * f, b0 - 0.8f, (hw - 0.2f) * f);
                    c.drawRoundRect(r, 0.8f, 0.8f, fill);
                }
                break;
            }
            case Defs.T_SLURRY: {
                wheel(c, b1 + len * 0.3f - 0.6f, b1 + len * 0.3f + 0.6f, -hw - 0.25f, -hw + 0.35f);
                wheel(c, b1 + len * 0.3f - 0.6f, b1 + len * 0.3f + 0.6f, hw - 0.35f, hw + 0.25f);
                fill.setColor(0xFF2E7D32);
                r.set(b1 + 0.6f, -hw + 0.1f, b0, hw - 0.1f);
                c.drawRoundRect(r, hw, hw, fill);
                fill.setColor(0xFF43A047);
                r.set(b1 + 1.0f, -hw * 0.5f, b0 - 0.4f, -hw * 0.1f);
                c.drawRoundRect(r, 0.4f, 0.4f, fill);
                // Verteiler / Schleppschlauch hinten
                fill.setColor(0xFF424242);
                float boom = m.cap >= 20 ? 7.5f : 3f;
                c.drawRect(b1 - 0.1f, -boom, b1 + 0.5f, boom, fill);
                if (v != null && v.working()) {
                    fill.setColor(0x885D4037);
                    c.drawRect(b1 - 1.6f, -boom, b1 - 0.1f, boom, fill);
                }
                break;
            }
            case Defs.T_MANURE: {
                wheel(c, b1 + len * 0.35f - 0.6f, b1 + len * 0.35f + 0.6f, -hw - 0.2f, -hw + 0.3f);
                wheel(c, b1 + len * 0.35f - 0.6f, b1 + len * 0.35f + 0.6f, hw - 0.3f, hw + 0.2f);
                fill.setColor(0xFFC62828);
                c.drawRect(b1 + 0.6f, -hw, b0, hw, fill);
                fill.setColor(0xFF8E0000);
                c.drawRect(b1 + 0.8f, -hw + 0.2f, b0 - 0.2f, hw - 0.2f, fill);
                if (v != null && v.load > 0) {
                    fill.setColor(productColor(-3));
                    float f = (float) Math.min(1, v.load / m.cap);
                    r.set(b1 + 0.8f, -(hw - 0.2f) * f, b0 - 0.2f, (hw - 0.2f) * f);
                    c.drawRoundRect(r, 0.6f, 0.6f, fill);
                }
                fill.setColor(0xFF616161);
                c.drawCircle(b1 + 0.4f, -hw * 0.5f, 0.5f, fill);
                c.drawCircle(b1 + 0.4f, hw * 0.5f, 0.5f, fill);
                break;
            }
            case Defs.T_TREEPLANT: {
                fill.setColor(0xFF795548);
                c.drawRect(b1, -hw, b0, hw, fill);
                fill.setColor(0xFF66BB6A);
                for (float y = -hw + 0.4f; y < hw; y += 0.7f) c.drawCircle(b1 + len * 0.5f, y, 0.3f, fill);
                break;
            }
            case Defs.T_CHIPPER: {
                wheel(c, b1 + len * 0.4f - 0.6f, b1 + len * 0.4f + 0.6f, -hw - 0.2f, -hw + 0.3f);
                wheel(c, b1 + len * 0.4f - 0.6f, b1 + len * 0.4f + 0.6f, hw - 0.3f, hw + 0.2f);
                fill.setColor(0xFFF57C00);
                c.drawRect(b1, -hw, b0, hw, fill);
                fill.setColor(0xFF424242);
                c.drawRect(b1 + len * 0.55f, -hw * 0.5f, b0 - 0.3f, hw * 0.5f, fill);
                stroke.setColor(0xFF616161);
                stroke.setStrokeWidth(0.4f);
                c.drawLine(b1 + len * 0.5f, 0, b1 + 0.3f, hw + 1.2f, stroke);
                break;
            }
            case Defs.T_FWDTRAILER:
            case Defs.T_LOGTRAILER: {
                int axles = m.cap >= 20 ? 3 : 2;
                for (int a = 0; a < axles; a++) {
                    float ax = b1 + len * 0.25f + a * 1.1f;
                    wheel(c, ax - 0.5f, ax + 0.5f, -hw - 0.2f, -hw + 0.3f);
                    wheel(c, ax - 0.5f, ax + 0.5f, hw - 0.3f, hw + 0.2f);
                }
                fill.setColor(0xFF616161);
                c.drawRect(b1, -hw * 0.9f, b0, hw * 0.9f, fill);
                fill.setColor(0xFF37474F);
                for (float x = b1 + 0.3f; x < b0; x += len / 4) c.drawRect(x, -hw, x + 0.25f, hw, fill);
                if (v != null && v.load > 0) logLoad(c, b1 + 0.2f, b0 - 0.2f, hw * 0.8f, (float) (v.load / m.cap));
                if (m.sub == Defs.T_FWDTRAILER) {
                    stroke.setColor(0xFF424242);
                    stroke.setStrokeWidth(0.35f);
                    c.drawLine(b0 - 0.3f, 0, b0 - len * 0.6f, hw + 1.5f, stroke);
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
                    fill.setColor(productColor(v.loadProduct));
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
        fill.setColor(productColor(v.loadProduct));
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
            text.setColor(f.owner >= 0 || f.owned ? Game.FARM_COLOR[Math.max(0, f.owner) % 3] : 0xDDFFFFFF);
            c.drawText((f.owned ? "★ " : "") + f.number + (game.fireField == f.index ? " 🔥" : ""), cx, cy + ts * 0.35f, text);
        }
        for (Forest fo : w.forests) {
            float cx = sx((fo.x + fo.w / 2f) * Defs.TILE), cy = sy((fo.y + fo.h / 2f) * Defs.TILE);
            if (cx < -100 || cy < -100 || cx > getWidth() + 100 || cy > getHeight() + 100) continue;
            text.setColor(fo.owner >= 0 || fo.owned ? Game.FARM_COLOR[Math.max(0, fo.owner) % 3] : 0xDDFFFFFF);
            c.drawText((fo.owned ? "★ " : "") + "🌲" + fo.number, cx, cy + ts * 0.35f, text);
        }
        text.setColor(0xFFFFFFFF);
        text.setTextSize(ts * 0.8f);
        int local = game.currentFarm();
        for (int fp = 0; fp < game.farmCount(); fp++) {
            game.select(fp);
            label(c, w.hof, fp == local ? (game.farmCount() == 1 && game.farms[0].name.equals("Euer Hof") ? "🏠 Euer Hof" : "🏠 Dein Hof")
                    : "🏠 Hof " + game.farms[fp].name);
            for (int s = 0; s < Defs.STABLES; s++) {
                if (game.stables[s]) label(c, w.stableLot[s], Defs.S_NAMES[s] + (fp == local ? "" : " (" + game.farms[fp].name + ")"));
                else if (fp == local) label(c, w.stableLot[s], "Bauplatz " + Defs.S_ICON[s]);
            }
        }
        game.select(local);
        label(c, w.trader[Defs.B_SAWMILL], "🪵 Sägewerk");
        label(c, w.trader[Defs.B_LANDHANDEL], "🌾 Landhandel");
        label(c, w.trader[Defs.B_MILL], "🏭 Mühle");
        label(c, w.trader[Defs.B_BIOGAS], "⚡ Biogas");
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
            len = m.len + (tool != null ? tool.len + 0.6f : 0) + (m.kind == Defs.K_HARVESTER ? 2f : m.kind == Defs.K_FORESTRY ? 4f : 0);
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
            if (m.kind == Defs.K_FORESTRY) drawForestry(c, dummy, m.len, m.wid, m.color);
            else if (m.sub == Defs.H_COMBINE) drawCombine(c, m.len, m.wid, dummy, m.color);
            else if (m.sub == Defs.H_CHOPPER) drawChopper(c, m.len, m.wid, dummy, m.color);
            else drawPotatoHarvester(c, m.len, m.wid, dummy, m.color);
        }
        detail = oldDetail;
        thumbs.put(key, bmp);
        return bmp;
    }
}
