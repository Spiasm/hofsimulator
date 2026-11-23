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
        // Reihen: jedes Fahrzeug/Gerät bekommt so viel Platz, wie es lang ist (schräg gestellt)
        float rowStart = -24, rowEnd = 34, x = rowStart, z = -3;
        int row = 0;
        for (Object o : things) {
            float len;
            if (o instanceof Game.Vehicle) {
                Game.Vehicle v = (Game.Vehicle) o;
                Game.Tool t = game.tool(v.tool);
                len = (v.isTractor() ? v.m().len : Math.max(6, v.m().len)) + (t != null ? Math.min(8, t.m().len) + 0.6f : 0);
            } else {
                len = Math.min(8, ((Game.Tool) o).m().len);
            }
            float w = len * 0.94f + 2.5f;
            if (x + w > rowEnd && x > rowStart) {
                row++;
                x = rowStart + (row % 2) * 2;
                z += 9;
            }
            if (o instanceof Game.Vehicle) {
                Game.Vehicle v = (Game.Vehicle) o;
                Game.Tool t = game.tool(v.tool);
                float toolPart = t != null ? Math.min(8, t.m().len) + 0.6f : 0;
                // Fahrzeug vorne, Gerät dahinter
                addVehicle(v, x + toolPart + (len - toolPart) / 2, z);
            } else {
                addTool(((Game.Tool) o).m(), x + len / 2, z + 1, null);
            }
            x += w;
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
            if (t != null) {
                float dist = m.len / 2 + Math.min(8, t.m().len) / 2 + 0.6f;
                addTool(t.m(), x - dist * (float) Math.cos(rot), z - dist * (float) Math.sin(rot), null);
            }
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

    // --------------------------------------------------- Geräte in 3D

    private float px, pz, prot;

    /** Teil eines Geräts: la = nach vorne, lb = zur Seite (lokal) */
    private void part(float la, float lb, float y0, float sa, float sy, float sb, int color) {
        float c = (float) Math.cos(prot), s = (float) Math.sin(prot);
        boxes.add(new Box(px + la * c - lb * s, pz + la * s + lb * c, y0, sa, sy, sb, prot, color));
    }

    private void wheel(float la, float lb, float r, float w) {
        part(la, lb, 0, r * 2, r * 2, w, 0xFF212121);
        part(la, lb + (lb > 0 ? w * 0.05f : -w * 0.05f), r * 0.6f, r * 0.9f, r * 0.8f, w * 1.02f, 0xFF9E9E9E);
    }

    private void axle(float la, float track, float r, float w) {
        wheel(la, -track / 2, r, w);
        wheel(la, track / 2, r, w);
    }

    private static int toolColor(int sub) {
        switch (sub) {
            case Defs.T_GRUBBER: return 0xFFD84315;
            case Defs.T_SEEDER: return 0xFF1565C0;
            case Defs.T_PLANTER: return 0xFFEF6C00;
            case Defs.T_VEGPLANT: return 0xFF00897B;
            case Defs.T_SPREADER: return 0xFF2E7D32;
            case Defs.T_MOWER: return 0xFFC62828;
            case Defs.T_LOADER: return 0xFF2E7D32;
            case Defs.T_SLURRY: return 0xFF388E3C;
            case Defs.T_MANURE: return 0xFFB71C1C;
            case Defs.T_TREEPLANT: return 0xFF6D4C41;
            case Defs.T_FWDTRAILER:
            case Defs.T_LOGTRAILER: return 0xFF546E7A;
            case Defs.T_CHIPPER: return 0xFFF57C00;
            default: return 0xFFC62828; // Kipper
        }
    }

    private void addTool(Defs.Model m, float x, float z, Game.Vehicle v) {
        px = x;
        pz = z;
        prot = 0.35f;
        float L = Math.min(8, m.len), W = Math.min(6, m.wid);
        int col = m.color != 0 ? m.color : toolColor(m.sub);
        int steel = 0xFF616161, dark = 0xFF37474F;
        float hl = L / 2;
        // Deichsel / Dreipunkt vorne
        part(hl + 0.4f, 0, 0.5f, 1.2f, 0.18f, 0.18f, dark);
        switch (m.sub) {
            case Defs.T_GRUBBER: {
                part(hl * 0.5f, 0, 0.9f, 0.3f, 0.3f, W, col);              // vorderer Rahmen
                part(-hl * 0.1f, 0, 0.9f, 0.3f, 0.3f, W, col);             // hinterer Rahmen
                part(hl * 0.2f, 0, 1.0f, L * 0.7f, 0.25f, 0.3f, col);      // Längsträger
                int n = Math.max(4, (int) (W / 0.6f));
                for (int k = 0; k < n; k++) {
                    float b = -W / 2 + (k + 0.5f) * W / n;
                    part((k % 2 == 0 ? hl * 0.5f : -hl * 0.1f), b, 0.05f, 0.15f, 0.85f, 0.12f, steel); // Zinken
                }
                part(-hl * 0.75f, 0, 0.1f, 0.6f, 0.6f, W * 0.98f, 0xFF424242); // Packerwalze
                break;
            }
            case Defs.T_SEEDER: {
                part(0, 0, 1.0f, L * 0.5f, 1.0f, W * 0.92f, col);          // Saatkasten
                part(0, 0, 2.0f, L * 0.52f, 0.12f, W * 0.95f, mix(col, 0xFFFFFFFF, 0.3f)); // Deckel
                part(-hl * 0.6f, 0, 0.2f, 0.3f, 0.5f, W, steel);           // Säschare
                int n = Math.max(6, (int) (W / 0.4f));
                for (int k = 0; k < n; k++) part(-hl * 0.6f, -W / 2 + (k + 0.5f) * W / n, 0, 0.1f, 0.3f, 0.06f, dark);
                part(hl * 0.5f, 0, 0.3f, 0.5f, 0.5f, W, 0xFF424242);       // Walze vorne
                break;
            }
            case Defs.T_PLANTER: {
                part(hl * 0.1f, 0, 1.1f, L * 0.55f, 1.3f, W * 0.85f, col); // Kartoffelbunker
                part(hl * 0.1f, 0, 2.4f, L * 0.6f, 0.1f, W * 0.9f, mix(col, 0xFF000000, 0.2f));
                int rows = m.wid > 3 ? 4 : 2;
                for (int k = 0; k < rows; k++) {
                    float b = -W / 2 + (k + 0.5f) * W / rows;
                    part(-hl * 0.6f, b, 0, 1.4f, 0.5f, 0.45f, steel);     // Häufelkörper
                }
                axle(hl * 0.1f, W * 0.95f, 0.45f, 0.35f);
                break;
            }
            case Defs.T_VEGPLANT: {
                part(hl * 0.3f, 0, 0.8f, 0.3f, 0.3f, W, steel);            // Rahmen
                int rows = m.wid > 4 ? 8 : 4;
                for (int k = 0; k < rows; k++) {
                    float b = -W / 2 + (k + 0.5f) * W / rows;
                    part(-hl * 0.2f, b, 0.3f, 0.6f, 0.5f, 0.35f, col);     // Pflanzaggregat
                    part(0.2f, b, 1.0f, 0.8f, 0.1f, 0.45f, 0xFF66BB6A);    // Tablett mit Jungpflanzen
                    part(-hl * 0.6f, b, 0.6f, 0.35f, 0.5f, 0.3f, 0xFF455A64); // Sitz
                }
                axle(-hl * 0.3f, W * 0.9f, 0.35f, 0.25f);
                break;
            }
            case Defs.T_SPREADER: {
                float big = Math.min(W, 2.4f);
                part(0, 0, 0.6f, L * 0.45f, 0.5f, big * 0.5f, col);         // Trichter unten
                part(0, 0, 1.1f, L * 0.6f, 0.8f, big * 0.85f, col);         // Trichter oben
                part(0, 0, 1.9f, L * 0.62f, 0.08f, big * 0.88f, mix(col, 0xFFFFFFFF, 0.25f));
                part(-hl * 0.5f, -0.4f, 0.4f, 0.6f, 0.08f, 0.6f, steel);    // Streuscheiben
                part(-hl * 0.5f, 0.4f, 0.4f, 0.6f, 0.08f, 0.6f, steel);
                break;
            }
            case Defs.T_MOWER: {
                part(0, 0, 0.1f, L * 0.5f, 0.25f, W, steel);               // Mähbalken
                int segs = Math.max(2, (int) (W / 1.6f));
                for (int k = 0; k < segs; k++) {
                    float b = -W / 2 + (k + 0.5f) * W / segs;
                    part(0, b, 0.35f, L * 0.55f, 0.55f, W / segs * 0.9f, col); // Schutzhauben
                }
                part(hl * 0.4f, 0, 0.9f, 0.6f, 0.5f, 0.6f, dark);           // Getriebe
                break;
            }
            case Defs.T_LOADER: {
                axle(-hl * 0.2f, W * 0.9f, 0.55f, 0.4f);
                axle(-hl * 0.2f + 1.3f, W * 0.9f, 0.55f, 0.4f);
                part(-hl * 0.05f, 0, 1.0f, L * 0.85f, 0.25f, W * 0.95f, dark); // Boden
                part(-hl * 0.05f, -W * 0.46f, 1.25f, L * 0.85f, 2.0f, 0.1f, col); // Seitenwände
                part(-hl * 0.05f, W * 0.46f, 1.25f, L * 0.85f, 2.0f, 0.1f, col);
                part(-hl * 0.9f, 0, 1.25f, 0.1f, 2.0f, W * 0.95f, col);    // Heckklappe
                for (float a = -hl * 0.7f; a < hl * 0.7f; a += 1.2f) part(a, 0, 3.25f, 0.12f, 0.12f, W * 0.95f, steel); // Bügel oben
                part(hl * 0.85f, 0, 0.2f, 0.8f, 0.6f, W * 0.8f, steel);    // Pick-up
                break;
            }
            case Defs.T_SLURRY: {
                axle(-hl * 0.15f, W * 0.95f, 0.7f, 0.5f);
                if (m.cap >= 14) axle(-hl * 0.15f + 1.5f, W * 0.95f, 0.7f, 0.5f);
                float tw = Math.min(W, 2.4f);
                // runder Tank aus Scheiben
                part(0, 0, 0.9f, L * 0.9f, 1.8f, tw * 0.6f, col);
                part(0, 0, 1.1f, L * 0.9f, 1.4f, tw * 0.85f, mix(col, 0xFF000000, 0.08f));
                part(0, 0, 1.25f, L * 0.88f, 1.1f, tw, mix(col, 0xFF000000, 0.15f));
                part(0, 0, 2.7f, 0.8f, 0.3f, 0.8f, dark);                   // Dom
                float boom = m.cap >= 20 ? 6f : 3f;
                part(-hl * 0.95f, 0, 0.6f, 0.3f, 0.3f, boom * 2, steel);    // Verteiler
                break;
            }
            case Defs.T_MANURE: {
                axle(0, W * 0.95f, 0.65f, 0.45f);
                if (m.cap >= 12) axle(1.4f, W * 0.95f, 0.65f, 0.45f);
                part(0, 0, 1.0f, L * 0.85f, 0.2f, W * 0.95f, dark);
                part(0, -W * 0.46f, 1.2f, L * 0.85f, 1.2f, 0.1f, col);
                part(0, W * 0.46f, 1.2f, L * 0.85f, 1.2f, 0.1f, col);
                part(hl * 0.42f, 0, 1.2f, 0.1f, 1.6f, W * 0.95f, col);
                part(0, 0, 1.2f, L * 0.75f, 0.9f, W * 0.85f, 0xFF4E342E); // Mist
                part(-hl * 0.45f, -W * 0.2f, 0.8f, 0.5f, 2.2f, 0.5f, steel); // Streuwalzen
                part(-hl * 0.45f, W * 0.2f, 0.8f, 0.5f, 2.2f, 0.5f, steel);
                break;
            }
            case Defs.T_TREEPLANT: {
                part(0, 0, 0.6f, L * 0.6f, 0.4f, W * 0.8f, col);
                part(0, 0, 1.0f, L * 0.5f, 0.6f, W * 0.7f, 0xFF8D6E63);    // Kisten mit Setzlingen
                for (int k = 0; k < 6; k++) part(-hl * 0.2f + (k % 3) * 0.6f, -0.5f + (k / 3) * 1.0f, 1.6f, 0.3f, 0.5f, 0.3f, 0xFF43A047);
                axle(-hl * 0.5f, W * 0.8f, 0.4f, 0.3f);
                break;
            }
            case Defs.T_CHIPPER: {
                axle(-hl * 0.2f, W * 0.9f, 0.6f, 0.4f);
                part(0, 0, 0.9f, L * 0.8f, 1.3f, W * 0.8f, col);           // Hacker
                part(hl * 0.6f, 0, 1.0f, 1.0f, 1.0f, 1.2f, steel);          // Einzug
                part(-hl * 0.3f, 0, 2.2f, 0.4f, 1.8f, 0.4f, dark);          // Auswurfrohr
                part(-hl * 0.6f, 0, 3.8f, 1.2f, 0.35f, 0.35f, dark);
                break;
            }
            case Defs.T_FWDTRAILER:
            case Defs.T_LOGTRAILER: {
                axle(-hl * 0.3f, W * 0.95f, 0.6f, 0.4f);
                axle(-hl * 0.3f + 1.3f, W * 0.95f, 0.6f, 0.4f);
                if (m.cap >= 20) axle(-hl * 0.3f - 1.3f, W * 0.95f, 0.6f, 0.4f);
                part(0, 0, 1.0f, L * 0.9f, 0.25f, W * 0.7f, dark);          // Rahmen
                for (float a = -hl * 0.8f; a <= hl * 0.8f; a += L * 0.27f) {
                    part(a, -W * 0.42f, 1.2f, 0.15f, 1.8f, 0.15f, col);    // Rungen
                    part(a, W * 0.42f, 1.2f, 0.15f, 1.8f, 0.15f, col);
                }
                if (m.sub == Defs.T_FWDTRAILER) {
                    part(hl * 0.8f, 0, 1.2f, 0.4f, 2.6f, 0.4f, 0xFFFFA000); // Kransäule
                    part(hl * 0.3f, 0, 3.6f, 2.8f, 0.3f, 0.3f, 0xFFFFA000); // Kranarm
                }
                break;
            }
            default: { // Kipper
                int axles = m.cap >= 26 ? 3 : m.cap >= 14 ? 2 : 1;
                for (int a = 0; a < axles; a++) axle(-hl * 0.25f + (a - (axles - 1) / 2f) * 1.3f, W * 0.95f, 0.6f, 0.45f);
                part(0, 0, 0.9f, L * 0.9f, 0.25f, W * 0.95f, dark);         // Chassis
                part(0, 0, 1.15f, L * 0.88f, 0.15f, W * 0.95f, col);        // Boden
                part(0, -W * 0.46f, 1.3f, L * 0.88f, 1.2f, 0.1f, col);      // Bordwände
                part(0, W * 0.46f, 1.3f, L * 0.88f, 1.2f, 0.1f, col);
                part(hl * 0.86f, 0, 1.3f, 0.1f, 1.4f, W * 0.95f, col);
                part(-hl * 0.86f, 0, 1.3f, 0.1f, 1.2f, W * 0.95f, mix(col, 0xFF000000, 0.15f));
                break;
            }
        }
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
