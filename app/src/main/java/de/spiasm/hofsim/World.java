package de.spiasm.hofsim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

/** Die Landkarte: Kacheln, Straßen, Hof, Händler, Felder und Wegsuche. Wird aus einer Zufallszahl erzeugt. */
public class World {
    public static final int GRASS = 0, ROAD = 1, FIELD = 2, FOREST = 3, WATER = 4, YARD = 5, BUILDING = 6, HOUSE = 7;

    public final int n = Defs.MAP;
    public final byte[][] tile = new byte[n][n];
    /** Feld-Index pro Kachel oder -1 */
    public final int[][] fieldAt = new int[n][n];
    public final List<Field> fields = new ArrayList<>();
    public final long seed;

    /** Rechtecke {x, y, w, h} */
    public int[] hof, house, hall, silo, landhandel, biogas;
    public final int[][] stablePlot = new int[2][];
    /** Zufahrt (Straßenkachel) {x, y} */
    public int[] hofGate, landhandelGate, biogasGate;
    public final List<int[]> villageHouses = new ArrayList<>();

    public World(long seed) {
        this.seed = seed;
        generate(new Random(seed));
    }

    // ---------------------------------------------------------------- Erzeugung

    private void generate(Random r) {
        for (int[] row : fieldAt) Arrays.fill(row, -1);
        // Hauptstraßen
        int hy = (int) (n * 0.38) + r.nextInt((int) (n * 0.24));
        int vx = (int) (n * 0.38) + r.nextInt((int) (n * 0.24));
        for (int i = 0; i < n; i++) {
            tile[i][hy] = ROAD;
            tile[vx][i] = ROAD;
        }
        // Nebenstraßen
        int off = n / 4;
        int hy2 = hy < n / 2 ? hy + off + r.nextInt(8) : hy - off - r.nextInt(8);
        boolean leftHalf = r.nextBoolean();
        for (int i = leftHalf ? 0 : vx; i <= (leftHalf ? vx : n - 1); i++) tile[i][hy2] = ROAD;
        int hy3 = hy < n / 2 ? Math.max(3, hy - off + 4 - r.nextInt(6)) : Math.min(n - 4, hy + off - 4 + r.nextInt(6));
        for (int i = leftHalf ? vx : 0; i <= (leftHalf ? n - 1 : vx); i++) tile[i][hy3] = ROAD;
        int vx2 = vx < n / 2 ? vx + off + r.nextInt(8) : vx - off - r.nextInt(8);
        boolean topHalf = r.nextBoolean();
        for (int i = topHalf ? 0 : hy; i <= (topHalf ? hy : n - 1); i++) tile[vx2][i] = ROAD;

        // Hof unterhalb/oberhalb der Hauptstraße neben der Kreuzung
        int hw = 14, hh = 10;
        int hx = vx + 3 + r.nextInt(8);
        if (hx + hw >= n - 2 || (vx2 >= hx - 1 && vx2 <= hx + hw)) hx = vx - 3 - hw - r.nextInt(5);
        boolean below = r.nextBoolean();
        int hyy = below ? hy + 1 : hy - hh;
        hof = new int[]{hx, hyy, hw, hh};
        fill(hof, YARD);
        // Gebäude im Hof (Reihe an der straßenfernen Seite)
        int far = below ? hyy + 6 : hyy + 1;
        int near = below ? hyy + 2 : hyy + 5;
        house = new int[]{hx + 1, far, 3, 3};
        hall = new int[]{hx + 5, far + (below ? 1 : 0), 5, 2 + (below ? 0 : 1)};
        silo = new int[]{hx + 11, far, 2, 2};
        fill(house, BUILDING);
        fill(hall, BUILDING);
        fill(silo, BUILDING);
        stablePlot[0] = new int[]{hx + 1, near, 5, 3};
        stablePlot[1] = new int[]{hx + 7, near, 6, 3};
        hofGate = new int[]{hx + 6, hy};

        // Händler
        landhandel = placeBuilding(r, 8, 6, hof, null);
        biogas = placeBuilding(r, 8, 8, hof, landhandel);
        landhandelGate = gateOf(landhandel);
        biogasGate = gateOf(biogas);

        // Dorf
        int cx = vx2, cy = topHalf ? hy - 10 : hy + 10;
        for (int k = 0; k < 40 && villageHouses.size() < 9; k++) {
            int x = cx - 8 + r.nextInt(16), y = cy - 6 + r.nextInt(12);
            int[] h = {x, y, 2 + r.nextInt(2), 2};
            if (areaIs(h, GRASS, 1) && touches(h, ROAD, 2)) {
                fill(h, HOUSE);
                villageHouses.add(h);
            }
        }

        // Felder
        for (int k = 0; k < 8000 && fields.size() < 16; k++) {
            int w = 14 + r.nextInt(13), h = 12 + r.nextInt(11);
            int x = 1 + r.nextInt(n - w - 2), y = 1 + r.nextInt(n - h - 2);
            int[] rect = {x, y, w, h};
            if (!areaIs(rect, GRASS, 0)) continue;
            if (!ringOk(rect)) continue;
            if (!touches(rect, ROAD, k < 4000 ? 1 : 8)) continue;
            Field f = new Field(fields.size(), x, y, w, h);
            fields.add(f);
            for (int i = x; i < x + w; i++) {
                for (int j = y; j < y + h; j++) {
                    tile[i][j] = FIELD;
                    fieldAt[i][j] = f.index;
                }
            }
        }
        // nach Entfernung zum Hof nummerieren
        final float hcx = hof[0] + hof[2] / 2f, hcy = hof[1] + hof[3] / 2f;
        List<Field> sorted = new ArrayList<>(fields);
        sorted.sort((a, b) -> Float.compare(dist2(a, hcx, hcy), dist2(b, hcx, hcy)));
        for (int i = 0; i < sorted.size(); i++) sorted.get(i).number = i + 1;

        // Wald und Weiher
        for (int k = 0; k < 10; k++) blob(r, FOREST, 3 + r.nextInt(6));
        blob(r, WATER, 3 + r.nextInt(2));
    }

    private static float dist2(Field f, float x, float y) {
        float dx = f.x + f.w / 2f - x, dy = f.y + f.h / 2f - y;
        return dx * dx + dy * dy;
    }

    private void blob(Random r, int type, int rad) {
        for (int tries = 0; tries < 50; tries++) {
            int cx = rad + r.nextInt(n - 2 * rad), cy = rad + r.nextInt(n - 2 * rad);
            if (tile[cx][cy] != GRASS) continue;
            for (int i = cx - rad; i <= cx + rad; i++) {
                for (int j = cy - rad; j <= cy + rad; j++) {
                    if (i < 0 || j < 0 || i >= n || j >= n) continue;
                    double d = Math.hypot(i - cx, j - cy) + r.nextDouble() * 1.5;
                    if (d <= rad && tile[i][j] == GRASS && !nearType(i, j, ROAD, 1) && !nearType(i, j, FIELD, 1)
                            && !nearType(i, j, YARD, 1)) {
                        tile[i][j] = (byte) type;
                    }
                }
            }
            return;
        }
    }

    private int[] placeBuilding(Random r, int w, int h, int[] avoid, int[] avoid2) {
        for (int k = 0; k < 4000; k++) {
            int x = 1 + r.nextInt(n - w - 2), y = 1 + r.nextInt(n - h - 2);
            int[] rect = {x, y, w, h};
            if (!areaIs(rect, GRASS, 0)) continue;
            if (!ringOk(rect)) continue;
            if (!touches(rect, ROAD, 1)) continue;
            float d = Math.abs(x - avoid[0]) + Math.abs(y - avoid[1]);
            if (d < 25) continue;
            if (avoid2 != null && Math.abs(x - avoid2[0]) + Math.abs(y - avoid2[1]) < 25) continue;
            fill(rect, BUILDING);
            return rect;
        }
        // Notlösung: irgendwo an die Hauptstraße
        int[] rect = {2, 2, w, h};
        fill(rect, BUILDING);
        return rect;
    }

    private int[] gateOf(int[] b) {
        int bestX = b[0], bestY = b[1];
        for (int i = b[0] - 1; i <= b[0] + b[2]; i++) {
            for (int j = b[1] - 1; j <= b[1] + b[3]; j++) {
                if (in(i, j) && tile[i][j] == ROAD) return new int[]{i, j};
            }
        }
        return new int[]{bestX, bestY};
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

    /** Ring um ein Rechteck: nur Gras oder Straße erlaubt (Abstand zu anderen Feldern/Gebäuden) */
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

    /** Stall bauen: Kacheln blockieren */
    public void buildStable(int s) {
        fill(stablePlot[s], BUILDING);
    }

    /** Abstellplatz im Hof für Gerät Nr. i (Kachel) */
    public int[] toolSpot(int i) {
        boolean below = hof[1] > hofGate[1];
        int row = below ? hof[1] + 1 : hof[1] + 8;
        if (i >= 13) row += below ? -1 : 1;
        return new int[]{hof[0] + (i % 13), row};
    }

    /** Parkplatz im Hof für Fahrzeug Nr. i (Kachel) */
    public int[] parking(int i) {
        boolean below = hof[1] > hofGate[1];
        int row = below ? hof[1] + 5 : hof[1] + 4;
        int col = hof[0] + 1 + (i % 12);
        if (i >= 12) row += below ? -4 : 4;
        return new int[]{col, row};
    }

    // ----------------------------------------------------------------- Wegsuche

    public boolean passable(int x, int y) {
        if (!in(x, y)) return false;
        int t = tile[x][y];
        return t == GRASS || t == ROAD || t == FIELD || t == YARD;
    }

    private double cost(int x, int y) {
        int t = tile[x][y];
        if (t == ROAD || t == YARD) return 1;
        if (t == FIELD) return 2.5;
        return 3;
    }

    /** Geländefaktor fürs Tempo */
    public double speedFactor(float mx, float my) {
        int x = (int) (mx / Defs.TILE), y = (int) (my / Defs.TILE);
        if (!in(x, y)) return 0.5;
        int t = tile[x][y];
        if (t == ROAD) return 1;
        if (t == YARD) return 0.5;
        return 0.55;
    }

    /** Kürzester Weg von Kachel a nach b, Ergebnis Liste von Kachelmitten in Metern {x, y}. */
    public List<float[]> path(int ax, int ay, int bx, int by) {
        List<float[]> out = new ArrayList<>();
        if (!passable(ax, ay)) {
            int[] s = nearestPassable(ax, ay);
            ax = s[0];
            ay = s[1];
        }
        if (!passable(bx, by)) {
            int[] s = nearestPassable(bx, by);
            bx = s[0];
            by = s[1];
        }
        int total = n * n;
        double[] g = new double[total];
        Arrays.fill(g, Double.MAX_VALUE);
        int[] prev = new int[total];
        Arrays.fill(prev, -1);
        boolean[] closed = new boolean[total];
        final int start = ax * n + ay, goal = bx * n + by;
        final int gx = bx, gy = by;
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
                if (!passable(nx, ny)) continue;
                boolean diag = d[0] != 0 && d[1] != 0;
                if (diag && (!passable(cx + d[0], cy) || !passable(cx, cy + d[1]))) continue;
                int ni = nx * n + ny;
                if (closed[ni]) continue;
                double c = g[cur] + cost(nx, ny) * (diag ? 1.414 : 1);
                if (c < g[ni]) {
                    g[ni] = c;
                    prev[ni] = cur;
                    double hEst = Math.hypot(nx - gx, ny - gy);
                    open.add(new double[]{c + hEst, ni});
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

    public int[] nearestPassable(int x, int y) {
        for (int rad = 1; rad < 20; rad++) {
            for (int i = x - rad; i <= x + rad; i++) {
                for (int j = y - rad; j <= y + rad; j++) {
                    if (passable(i, j)) return new int[]{i, j};
                }
            }
        }
        return new int[]{hofGate[0], hofGate[1]};
    }
}
