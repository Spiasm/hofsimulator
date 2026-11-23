package de.spiasm.hofsim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

/** Die Landkarte: Kacheln, Straßen, Hof, Händler, Ställe, Dörfer, Felder und Wegsuche. Wird aus einer Zufallszahl erzeugt. */
public class World {
    public static final int GRASS = 0, ROAD = 1, FIELD = 2, FOREST = 3, WATER = 4, YARD = 5, BUILDING = 6, HOUSE = 7, WIND = 8, FORESTLOT = 9;

    // Gebäude-Arten
    public static final int G_HOUSE = 0, G_CHURCH = 1, G_FIREHOUSE = 2, G_FARMHOUSE = 3, G_BARN = 4, G_SILO = 5;

    public static class Building {
        public final int type;
        public final int[] r;

        Building(int type, int[] r) {
            this.type = type;
            this.r = r;
        }
    }

    public final int n = Defs.MAP;
    public final byte[][] tile = new byte[n][n];
    /** Feld-Index pro Kachel oder -1 */
    public final int[][] fieldAt = new int[n][n];
    public final List<Field> fields = new ArrayList<>();
    /** Waldstück-Index pro Kachel oder -1 */
    public final int[][] forestAt = new int[n][n];
    public final List<Forest> forests = new ArrayList<>();
    public final long seed;

    /** Rechtecke {x, y, w, h} */
    public int[] hof, house, hall, silo;
    /** Händler: Landhandel, Mühle, Biogas */
    public final int[][] trader = new int[Defs.BUYERS][];
    public final int[][] traderGate = new int[Defs.BUYERS][];
    /** Stallplätze (je Stallart) und ihre Zufahrt */
    public final int[][] stableLot = new int[Defs.STABLES][];
    public final int[][] stableGate = new int[Defs.STABLES][];
    public int[] hofGate;
    public int[] fireGate;
    public final List<Building> buildings = new ArrayList<>();
    /** Dorfnamen mit Mittelpunkt {x, y} */
    public final List<String> villageNames = new ArrayList<>();
    public final List<int[]> villageCenters = new ArrayList<>();
    private boolean hofBelow;

    public World(long seed) {
        this.seed = seed;
        generate(new Random(seed));
    }

    // ---------------------------------------------------------------- Erzeugung

    private void generate(Random r) {
        for (int[] row : fieldAt) Arrays.fill(row, -1);
        for (int[] row : forestAt) Arrays.fill(row, -1);
        int off = n / 4;
        // Hauptstraßen
        int hy = (int) (n * 0.4) + r.nextInt((int) (n * 0.2));
        int vx = (int) (n * 0.4) + r.nextInt((int) (n * 0.2));
        for (int i = 0; i < n; i++) {
            tile[i][hy] = ROAD;
            tile[vx][i] = ROAD;
        }
        // Nebenstraßen: je zwei waagrecht und senkrecht, jeweils auf einer Hälfte
        int[] hys = {hy - off - r.nextInt(8), hy + off + r.nextInt(8)};
        int[] vxs = {vx - off - r.nextInt(8), vx + off + r.nextInt(8)};
        for (int y : hys) {
            if (y < 3 || y > n - 4) continue;
            boolean left = r.nextBoolean();
            for (int i = left ? 0 : vx; i <= (left ? vx : n - 1); i++) tile[i][y] = ROAD;
        }
        for (int x : vxs) {
            if (x < 3 || x > n - 4) continue;
            boolean top = r.nextBoolean();
            for (int i = top ? 0 : hy; i <= (top ? hy : n - 1); i++) tile[x][i] = ROAD;
        }

        // Hof an der Hauptstraße neben der Kreuzung
        int hw = 14, hh = 10;
        int hx = vx + 3 + r.nextInt(8);
        if (hx + hw >= n - 2 || columnHasRoad(hx - 1, hx + hw, hy)) hx = vx - 3 - hw - r.nextInt(5);
        hofBelow = r.nextBoolean();
        int hyy = hofBelow ? hy + 1 : hy - hh;
        hof = new int[]{hx, hyy, hw, hh};
        fill(hof, YARD);
        int bTop = hofBelow ? hyy + 6 : hyy + 1;
        house = new int[]{hx + 1, bTop, 3, 3};
        hall = new int[]{hx + 5, bTop, 5, 3};
        silo = new int[]{hx + 11, bTop, 2, 2};
        fill(house, BUILDING);
        fill(hall, BUILDING);
        fill(silo, BUILDING);
        hofGate = new int[]{hx + 6, hy};

        // Händler
        int[][] avoid = {hof};
        trader[Defs.B_LANDHANDEL] = place(r, 8, 6, BUILDING, 30, avoid);
        trader[Defs.B_MILL] = place(r, 7, 7, BUILDING, 30, new int[][]{hof, trader[0]});
        trader[Defs.B_BIOGAS] = place(r, 9, 8, BUILDING, 30, new int[][]{hof, trader[0], trader[1]});
        trader[Defs.B_SAWMILL] = place(r, 10, 7, BUILDING, 30, new int[][]{hof, trader[0], trader[1], trader[2]});
        for (int i = 0; i < Defs.BUYERS; i++) traderGate[i] = gateOf(trader[i]);

        // Stallplätze: in der Nähe vom Hof
        List<int[]> taken = new ArrayList<>(Arrays.asList(hof, trader[0], trader[1], trader[2], trader[3]));
        for (int s = 0; s < Defs.STABLES; s++) {
            stableLot[s] = placeNear(r, 12, 9, hof, 12, 55, taken);
            taken.add(stableLot[s]);
            stableGate[s] = gateOf(stableLot[s]);
        }

        // Dörfer
        String[] names = {"Oberbach", "Isarmoos", "Kleinvils", "Hinterholz", "Grünwies"};
        int villages = 3;
        int[][] crossings = findCrossings();
        for (int v = 0; v < villages && v < crossings.length; v++) {
            int[] c = crossings[(v * 7 + r.nextInt(3)) % crossings.length];
            if (Math.abs(c[0] - hofGate[0]) + Math.abs(c[1] - hofGate[1]) < 25) c = crossings[(v * 5 + 3) % crossings.length];
            villageCenters.add(c);
            villageNames.add(names[(int) ((seed + v) % names.length + names.length) % names.length]);
            buildVillage(r, c[0], c[1], v == 0, taken);
        }

        // Nachbar-Bauernhöfe
        for (int k = 0; k < 4; k++) {
            int[] farm = place(r, 10, 8, YARD, 20, taken.toArray(new int[0][]));
            if (farm[0] == 2 && farm[1] == 2) continue;
            taken.add(farm);
            buildings.add(new Building(G_FARMHOUSE, sub(farm, 1, 1, 3, 3)));
            buildings.add(new Building(G_BARN, sub(farm, 5, 1, 4, 3)));
            buildings.add(new Building(G_SILO, sub(farm, 1, 5, 2, 2)));
            for (Building b : buildings.subList(buildings.size() - 3, buildings.size())) fill(b.r, BUILDING);
        }
        // Einzelhäuser an den Straßen
        for (int k = 0, made = 0; k < 400 && made < 10; k++) {
            int[] h = {1 + r.nextInt(n - 4), 1 + r.nextInt(n - 4), 2 + r.nextInt(2), 2};
            if (areaIs(h, GRASS, 1) && touches(h, ROAD, 2)) {
                fill(h, HOUSE);
                buildings.add(new Building(G_HOUSE, h));
                made++;
            }
        }

        // Waldstücke
        for (int k = 0; k < 8000 && forests.size() < 8; k++) {
            int w = 10 + r.nextInt(7), h = 9 + r.nextInt(6);
            int x = 1 + r.nextInt(n - w - 2), y = 1 + r.nextInt(n - h - 2);
            int[] rect = {x, y, w, h};
            if (!areaIs(rect, GRASS, 0) || !ringOk(rect) || !touches(rect, ROAD, 1)) continue;
            Forest fo = new Forest(forests.size(), x, y, w, h);
            forests.add(fo);
            for (int i = x; i < x + w; i++) {
                for (int j = y; j < y + h; j++) {
                    tile[i][j] = FORESTLOT;
                    forestAt[i][j] = fo.index;
                }
            }
            fo.gate = gateOf(rect);
        }
        // Felder
        for (int k = 0; k < 15000 && fields.size() < 30; k++) {
            int w = 14 + r.nextInt(14), h = 12 + r.nextInt(12);
            int x = 1 + r.nextInt(n - w - 2), y = 1 + r.nextInt(n - h - 2);
            int[] rect = {x, y, w, h};
            if (!areaIs(rect, GRASS, 0)) continue;
            if (!ringOk(rect)) continue;
            if (!touches(rect, ROAD, k < 8000 ? 1 : 8)) continue;
            Field f = new Field(fields.size(), x, y, w, h);
            fields.add(f);
            for (int i = x; i < x + w; i++) {
                for (int j = y; j < y + h; j++) {
                    tile[i][j] = FIELD;
                    fieldAt[i][j] = f.index;
                }
            }
        }
        final float hcx = hof[0] + hof[2] / 2f, hcy = hof[1] + hof[3] / 2f;
        List<Forest> fsorted = new ArrayList<>(forests);
        fsorted.sort((a, b) -> Float.compare(Math.abs(a.x - hcx) + Math.abs(a.y - hcy), Math.abs(b.x - hcx) + Math.abs(b.y - hcy)));
        for (int i = 0; i < fsorted.size(); i++) fsorted.get(i).number = i + 1;
        List<Field> sorted = new ArrayList<>(fields);
        sorted.sort((a, b) -> Float.compare(dist2(a, hcx, hcy), dist2(b, hcx, hcy)));
        for (int i = 0; i < sorted.size(); i++) sorted.get(i).number = i + 1;

        // Wald und Weiher
        for (int k = 0; k < 18; k++) blob(r, FOREST, 3 + r.nextInt(7));
        for (int k = 0; k < 2; k++) blob(r, WATER, 3 + r.nextInt(3));
    }

    private boolean columnHasRoad(int x0, int x1, int skipRow) {
        for (int x = Math.max(0, x0); x <= Math.min(n - 1, x1); x++) {
            for (int y = 0; y < n; y++) {
                if (y != skipRow && tile[x][y] == ROAD && (y > 0 && tile[x][y - 1] == ROAD)) return true;
            }
        }
        return false;
    }

    private int[][] findCrossings() {
        List<int[]> out = new ArrayList<>();
        for (int x = 2; x < n - 2; x++) {
            for (int y = 2; y < n - 2; y++) {
                if (tile[x][y] != ROAD) continue;
                int nb = 0;
                if (tile[x - 1][y] == ROAD) nb++;
                if (tile[x + 1][y] == ROAD) nb++;
                if (tile[x][y - 1] == ROAD) nb++;
                if (tile[x][y + 1] == ROAD) nb++;
                if (nb >= 3 && Math.abs(x - hofGate[0]) + Math.abs(y - hofGate[1]) > 20) out.add(new int[]{x, y});
            }
        }
        if (out.isEmpty()) out.add(new int[]{n / 4, n / 4});
        return out.toArray(new int[0][]);
    }

    private void buildVillage(Random r, int cx, int cy, boolean main, List<int[]> taken) {
        int made = 0;
        if (main) {
            int[] church = findSpot(r, cx, cy, 4, 6, 10);
            if (church != null) {
                fill(church, HOUSE);
                buildings.add(new Building(G_CHURCH, church));
                taken.add(church);
            }
            int[] fire = findSpot(r, cx, cy, 5, 4, 10);
            if (fire != null) {
                fill(fire, HOUSE);
                buildings.add(new Building(G_FIREHOUSE, fire));
                fireGate = gateOf(fire);
                taken.add(fire);
            }
        }
        for (int k = 0; k < 300 && made < (main ? 16 : 11); k++) {
            int x = cx - 12 + r.nextInt(24), y = cy - 12 + r.nextInt(24);
            int[] h = {x, y, 2 + r.nextInt(2), 2 + r.nextInt(2)};
            if (areaIs(h, GRASS, 1) && touches(h, ROAD, 3)) {
                fill(h, HOUSE);
                buildings.add(new Building(G_HOUSE, h));
                made++;
            }
        }
        if (fireGate == null && main) fireGate = new int[]{cx, cy};
    }

    private int[] findSpot(Random r, int cx, int cy, int w, int h, int rad) {
        for (int k = 0; k < 300; k++) {
            int[] rect = {cx - rad + r.nextInt(2 * rad), cy - rad + r.nextInt(2 * rad), w, h};
            if (areaIs(rect, GRASS, 1) && touches(rect, ROAD, 1)) return rect;
        }
        return null;
    }

    private static int[] sub(int[] rect, int dx, int dy, int w, int h) {
        return new int[]{rect[0] + dx, rect[1] + dy, w, h};
    }

    private static float dist2(Field f, float x, float y) {
        float dx = f.x + f.w / 2f - x, dy = f.y + f.h / 2f - y;
        return dx * dx + dy * dy;
    }

    private void blob(Random r, int type, int rad) {
        for (int tries = 0; tries < 60; tries++) {
            int cx = rad + r.nextInt(n - 2 * rad), cy = rad + r.nextInt(n - 2 * rad);
            if (tile[cx][cy] != GRASS) continue;
            for (int i = cx - rad; i <= cx + rad; i++) {
                for (int j = cy - rad; j <= cy + rad; j++) {
                    if (!in(i, j)) continue;
                    double d = Math.hypot(i - cx, j - cy) + r.nextDouble() * 1.5;
                    if (d <= rad && tile[i][j] == GRASS && !nearType(i, j, ROAD, 1) && !nearType(i, j, FIELD, 1)
                            && !nearType(i, j, YARD, 1) && !nearType(i, j, BUILDING, 1) && !nearType(i, j, HOUSE, 1)) {
                        tile[i][j] = (byte) type;
                    }
                }
            }
            return;
        }
    }

    /** Rechteck an einer Straße platzieren, Abstand zu anderen Rechtecken mind. minDist */
    private int[] place(Random r, int w, int h, int type, int minDist, int[][] avoid) {
        for (int k = 0; k < 6000; k++) {
            int x = 1 + r.nextInt(n - w - 2), y = 1 + r.nextInt(n - h - 2);
            int[] rect = {x, y, w, h};
            if (!areaIs(rect, GRASS, 0) || !ringOk(rect) || !touches(rect, ROAD, 1)) continue;
            boolean ok = true;
            for (int[] a : avoid) if (a != null && Math.abs(x - a[0]) + Math.abs(y - a[1]) < minDist) ok = false;
            if (!ok) continue;
            fill(rect, type);
            return rect;
        }
        int[] rect = {2, 2, w, h};
        return rect;
    }

    /** Rechteck an einer Straße in einem Abstandsbereich zu einem Ort */
    private int[] placeNear(Random r, int w, int h, int[] near, int minD, int maxD, List<int[]> taken) {
        for (int k = 0; k < 8000; k++) {
            int x = 1 + r.nextInt(n - w - 2), y = 1 + r.nextInt(n - h - 2);
            int d = Math.abs(x - near[0]) + Math.abs(y - near[1]);
            if (d < minD || d > maxD + k / 200) continue;
            int[] rect = {x, y, w, h};
            if (!areaIs(rect, GRASS, 0) || !ringOk(rect) || !touches(rect, ROAD, 1)) continue;
            boolean ok = true;
            for (int[] a : taken) if (Math.abs(x - a[0]) + Math.abs(y - a[1]) < 10) ok = false;
            if (!ok) continue;
            fill(rect, BUILDING);
            return rect;
        }
        return place(r, w, h, BUILDING, 10, taken.toArray(new int[0][]));
    }

    private int[] gateOf(int[] b) {
        for (int i = b[0] - 1; i <= b[0] + b[2]; i++)
            for (int j = b[1] - 1; j <= b[1] + b[3]; j++)
                if (in(i, j) && tile[i][j] == ROAD) return new int[]{i, j};
        int[] p = nearestRoad(b[0], b[1]);
        return p;
    }

    private int[] nearestRoad(int x, int y) {
        for (int rad = 1; rad < 40; rad++)
            for (int i = x - rad; i <= x + rad; i++)
                for (int j = y - rad; j <= y + rad; j++)
                    if (in(i, j) && tile[i][j] == ROAD) return new int[]{i, j};
        return new int[]{x, y};
    }

    private void fill(int[] rect, int type) {
        for (int i = rect[0]; i < rect[0] + rect[2]; i++)
            for (int j = rect[1]; j < rect[1] + rect[3]; j++)
                if (in(i, j)) tile[i][j] = (byte) type;
    }

    private boolean areaIs(int[] rect, int type, int margin) {
        for (int i = rect[0] - margin; i < rect[0] + rect[2] + margin; i++)
            for (int j = rect[1] - margin; j < rect[1] + rect[3] + margin; j++)
                if (!in(i, j) || tile[i][j] != type) return false;
        return true;
    }

    private boolean ringOk(int[] rect) {
        for (int i = rect[0] - 2; i < rect[0] + rect[2] + 2; i++) {
            for (int j = rect[1] - 2; j < rect[1] + rect[3] + 2; j++) {
                if (!in(i, j)) continue;
                boolean inside = i >= rect[0] && i < rect[0] + rect[2] && j >= rect[1] && j < rect[1] + rect[3];
                if (inside) continue;
                int t = tile[i][j];
                if (t != GRASS && t != ROAD) return false;
            }
        }
        return true;
    }

    private boolean touches(int[] rect, int type, int dist) {
        for (int i = rect[0] - dist; i < rect[0] + rect[2] + dist; i++)
            for (int j = rect[1] - dist; j < rect[1] + rect[3] + dist; j++)
                if (in(i, j) && tile[i][j] == type) return true;
        return false;
    }

    private boolean nearType(int x, int y, int type, int d) {
        for (int i = x - d; i <= x + d; i++)
            for (int j = y - d; j <= y + d; j++)
                if (in(i, j) && tile[i][j] == type) return true;
        return false;
    }

    public boolean in(int x, int y) {
        return x >= 0 && y >= 0 && x < n && y < n;
    }

    // ------------------------------------------------------------- Hof-Plätze

    /** Reihe im Hof: k = 0 direkt an der Straße */
    private int hofRow(int k) {
        return hofBelow ? hof[1] + k : hof[1] + hof[3] - 1 - k;
    }

    /** Abstellplatz für Gerät Nr. i (Kachel) */
    public int[] toolSpot(int i) {
        return new int[]{hof[0] + (i % 13), hofRow(i / 13 % 2)};
    }

    /** Parkplatz für Fahrzeug Nr. i (Kachel) */
    public int[] parking(int i) {
        return new int[]{hof[0] + 1 + (i % 12), hofRow(3 + (i / 12) % 2)};
    }

    /** Windrad bauen: 3x3 Wiese nötig */
    public boolean canBuildWind(int x, int y) {
        for (int i = x - 1; i <= x + 1; i++)
            for (int j = y - 1; j <= y + 1; j++)
                if (!in(i, j) || tile[i][j] != GRASS) return false;
        return true;
    }

    public void buildWind(int x, int y) {
        if (in(x, y)) tile[x][y] = WIND;
    }

    // ----------------------------------------------------------------- Wegsuche

    public boolean passable(int x, int y) {
        if (!in(x, y)) return false;
        int t = tile[x][y];
        return t == GRASS || t == ROAD || t == FIELD || t == YARD || t == FORESTLOT;
    }

    // erlaubte Felder/Waldstücke für die aktuelle Wegsuche (Start und Ziel)
    private int okFieldA = -1, okFieldB = -1, okForestA = -1, okForestB = -1;

    /** Kosten einer Kachel; < 0 = gesperrt. Maschinen bleiben auf der Straße und fahren nur aufs eigene Ziel-Feld. */
    private double cost(int x, int y) {
        int t = tile[x][y];
        if (t == ROAD || t == YARD) return 1;
        if (t == FIELD) {
            int f = fieldAt[x][y];
            return f == okFieldA || f == okFieldB ? 2 : 120;
        }
        if (t == FORESTLOT) {
            int f = forestAt[x][y];
            return f == okForestA || f == okForestB ? 3 : -1;
        }
        if (t == GRASS) return 30;
        return -1;
    }

    public boolean isRoad(float mx, float my) {
        int x = (int) (mx / Defs.TILE), y = (int) (my / Defs.TILE);
        return in(x, y) && tile[x][y] == ROAD;
    }

    /** Geländefaktor fürs Tempo */
    public double speedFactor(float mx, float my) {
        int x = (int) (mx / Defs.TILE), y = (int) (my / Defs.TILE);
        if (!in(x, y)) return 0.5;
        int t = tile[x][y];
        if (t == ROAD) return 1;
        if (t == YARD) return 0.5;
        if (t == FORESTLOT) return 0.35;
        return 0.55;
    }

    public List<float[]> path(int ax, int ay, int bx, int by) {
        return path(ax, ay, bx, by, false);
    }

    /** Kürzester Weg von Kachel a nach b (nur Straße, wenn roadsOnly). Liste von Kachelmitten in Metern. */
    public List<float[]> path(int ax, int ay, int bx, int by, boolean roadsOnly) {
        List<float[]> out = new ArrayList<>();
        if (!ok(ax, ay, roadsOnly)) {
            int[] s = roadsOnly ? nearestRoad(ax, ay) : nearestPassable(ax, ay);
            ax = s[0];
            ay = s[1];
        }
        if (!ok(bx, by, roadsOnly)) {
            int[] s = roadsOnly ? nearestRoad(bx, by) : nearestPassable(bx, by);
            bx = s[0];
            by = s[1];
        }
        okFieldA = fieldAt[ax][ay];
        okFieldB = fieldAt[bx][by];
        okForestA = forestAt[ax][ay];
        okForestB = forestAt[bx][by];
        int total = n * n;
        double[] g = new double[total];
        Arrays.fill(g, Double.MAX_VALUE);
        int[] prev = new int[total];
        Arrays.fill(prev, -1);
        boolean[] closed = new boolean[total];
        final int start = ax * n + ay, goal = bx * n + by;
        PriorityQueue<double[]> open = new PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
        g[start] = 0;
        open.add(new double[]{0, start});
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        while (!open.isEmpty()) {
            int cur = (int) open.poll()[1];
            if (closed[cur]) continue;
            closed[cur] = true;
            if (cur == goal) break;
            int cx = cur / n, cy = cur % n;
            for (int[] d : dirs) {
                int nx = cx + d[0], ny = cy + d[1];
                if (!ok(nx, ny, roadsOnly)) continue;
                double tc = cost(nx, ny);
                if (tc < 0) continue;
                boolean diag = d[0] != 0 && d[1] != 0;
                if (diag && (roadsOnly || !ok(cx + d[0], cy, false) || !ok(cx, cy + d[1], false)
                        || cost(cx + d[0], cy) < 0 || cost(cx, cy + d[1]) < 0)) continue;
                int ni = nx * n + ny;
                if (closed[ni]) continue;
                double c = g[cur] + tc * (diag ? 1.414 : 1);
                if (c < g[ni]) {
                    g[ni] = c;
                    prev[ni] = cur;
                    open.add(new double[]{c + Math.hypot(nx - bx, ny - by), ni});
                }
            }
        }
        if (prev[goal] == -1 && goal != start) {
            out.add(new float[]{(bx + 0.5f) * Defs.TILE, (by + 0.5f) * Defs.TILE});
            return out;
        }
        List<Integer> rev = new ArrayList<>();
        for (int c = goal; c != -1 && c != start; c = prev[c]) rev.add(c);
        for (int i = rev.size() - 1; i >= 0; i--) {
            int c = rev.get(i);
            out.add(new float[]{(c / n + 0.5f) * Defs.TILE, (c % n + 0.5f) * Defs.TILE});
        }
        return out;
    }

    private boolean ok(int x, int y, boolean roadsOnly) {
        if (roadsOnly) return in(x, y) && tile[x][y] == ROAD;
        return passable(x, y);
    }

    public int[] nearestPassable(int x, int y) {
        for (int rad = 1; rad < 25; rad++)
            for (int i = x - rad; i <= x + rad; i++)
                for (int j = y - rad; j <= y + rad; j++)
                    if (passable(i, j)) return new int[]{i, j};
        return new int[]{hofGate[0], hofGate[1]};
    }

    /** Zufällige Straßenkachel */
    public int[] randomRoad(Random r) {
        for (int k = 0; k < 2000; k++) {
            int x = r.nextInt(n), y = r.nextInt(n);
            if (tile[x][y] == ROAD) return new int[]{x, y};
        }
        return hofGate;
    }
}
