package de.spiasm.hofsim;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Spielstand und Spiellogik: Zeit, Geld, Lager, Fahrzeuge, Aufträge, Wachstum, Ställe. */
public class Game {
    public interface Listener {
        void message(String s);
    }

    // ------------------------------------------------------------ Datentypen

    public static class Tool {
        public int id, type, attachedTo = -1;
    }

    public static final int GOTO = 0, PICKUP = 1, DROP = 2, WORK = 3, UNLOAD = 4, LOAD = 5, SELL = 6;

    public static class Step {
        public int kind, a, b, c;

        Step(int kind, int a, int b, int c) {
            this.kind = kind;
            this.a = a;
            this.b = b;
            this.c = c;
        }
    }

    public static class Vehicle {
        public int id, type, parkSlot;
        public float x, y, heading;
        public int tool = -1;
        public double load;
        public int loadProduct = -1;
        public final List<Step> steps = new ArrayList<>();
        public List<float[]> path;
        public int pathIdx;
        public String status = "steht am Hof";

        public boolean busy() {
            return !steps.isEmpty();
        }

        public String name() {
            return Defs.V_NAMES[type] + " #" + id;
        }
    }

    // ---------------------------------------------------------------- Zustand

    public World world;
    public double money;
    public double time;
    public int speedIdx = 3;
    public final double[] storage = new double[4];
    public final double[] priceFactor = {1, 1, 1, 1};
    public final boolean[] stables = new boolean[2];
    public final List<Vehicle> vehicles = new ArrayList<>();
    public final List<Tool> tools = new ArrayList<>();
    public int nextId = 1;
    public transient Listener listener;
    private final Random rnd = new Random();
    private int lastDay;
    private int lastFeedWarn;

    public static final int START_DAY = 60; // 1. März

    // ------------------------------------------------------------- Neues Spiel

    public static Game newGame(long seed) {
        Game g = new Game();
        g.world = new World(seed);
        g.money = Defs.START_MONEY;
        Random r = new Random(seed * 31 + 7);
        for (Field f : g.world.fields) {
            int k = r.nextInt(5);
            if (k == 0) {
                f.state = Field.STUBBLE;
                f.crop = Defs.WHEAT;
            } else if (k == 1) {
                f.state = Field.CULTIVATED;
            } else {
                f.state = Field.GROWING;
                f.crop = r.nextInt(4);
                f.growth = r.nextDouble() * 1.05;
                f.fertilized = r.nextBoolean();
            }
        }
        // Startfeld: das nächste am Hof
        Field start = g.fieldByNumber(1);
        if (start != null) {
            start.owned = true;
            start.state = Field.GROWING;
            start.crop = Defs.WHEAT;
            start.growth = 0.5;
            start.fertilized = false;
        }
        g.addVehicle(Defs.V_TRACTOR_S);
        g.addTool(Defs.T_GRUBBER);
        g.addTool(Defs.T_SEEDER);
        g.addTool(Defs.T_SPREADER);
        g.addTool(Defs.T_TRAILER);
        g.stables[Defs.S_CHICKEN] = true;
        g.world.buildStable(Defs.S_CHICKEN);
        g.storage[Defs.WHEAT] = 12;
        return g;
    }

    public Field fieldByNumber(int nr) {
        for (Field f : world.fields) if (f.number == nr) return f;
        return null;
    }

    private Vehicle addVehicle(int type) {
        Vehicle v = new Vehicle();
        v.id = nextId++;
        v.type = type;
        v.parkSlot = freeSlot();
        int[] p = world.parking(v.parkSlot);
        v.x = (p[0] + 0.5f) * Defs.TILE;
        v.y = (p[1] + 0.5f) * Defs.TILE;
        v.heading = -90;
        vehicles.add(v);
        return v;
    }

    private int freeSlot() {
        for (int s = 0; ; s++) {
            boolean used = false;
            for (Vehicle v : vehicles) if (v.parkSlot == s) used = true;
            if (!used) return s;
        }
    }

    private Tool addTool(int type) {
        Tool t = new Tool();
        t.id = nextId++;
        t.type = type;
        tools.add(t);
        return t;
    }

    public Tool tool(int id) {
        for (Tool t : tools) if (t.id == id) return t;
        return null;
    }

    public Vehicle vehicle(int id) {
        for (Vehicle v : vehicles) if (v.id == id) return v;
        return null;
    }

    // ----------------------------------------------------------- Zeit & Preise

    public int day() {
        return START_DAY + (int) (time / 86400);
    }

    public String dateText() {
        int d = day();
        int year = d / 360 + 1, month = (d / 30) % 12, dom = d % 30 + 1;
        int sec = (int) (time % 86400);
        return String.format(Locale.GERMANY, "%d. %s J%d  %02d:%02d", dom, Defs.MONTHS[month], year, sec / 3600, (sec / 60) % 60);
    }

    public double price(int product) {
        return Defs.BASE_PRICE[product] * priceFactor[product];
    }

    public static String euro(double v) {
        return String.format(Locale.GERMANY, "%,.0f €", v);
    }

    private void msg(String s) {
        if (listener != null) listener.message(s);
    }

    // ------------------------------------------------------------- Simulation

    public void update(double dt) {
        while (dt > 0) {
            double step = Math.min(dt, 20);
            tick(step);
            dt -= step;
        }
    }

    private void tick(double dt) {
        time += dt;
        // Wachstum
        for (Field f : world.fields) {
            if (f.state == Field.GROWING) {
                double max = f.owned ? 1.0 : 1.2;
                if (f.growth < max) f.growth = Math.min(max, f.growth + dt / (Defs.GROW_DAYS[f.crop] * 86400));
            }
        }
        // Fahrzeuge
        for (Vehicle v : new ArrayList<>(vehicles)) {
            if (v.busy()) {
                money -= (Defs.HELPER_WAGE + Defs.V_DIESEL[v.type]) * dt / 3600.0;
                updateVehicle(v, dt);
            }
        }
        int d = day();
        if (d != lastDay) {
            if (lastDay != 0) daily();
            lastDay = d;
        }
    }

    private void daily() {
        // Preise schwanken
        for (int i = 0; i < 4; i++) {
            priceFactor[i] = Math.max(0.82, Math.min(1.18, priceFactor[i] + rnd.nextGaussian() * 0.025));
        }
        // Ställe
        for (int s = 0; s < 2; s++) {
            if (!stables[s]) continue;
            double f = 1;
            String missing = null;
            for (int p = 0; p < 4; p++) {
                double need = Defs.S_FEED[s][p];
                if (need <= 0) continue;
                double avail = storage[p] / need;
                if (avail < f) {
                    f = Math.max(0, avail);
                    missing = Defs.PRODUCT_NAMES[p];
                }
            }
            for (int p = 0; p < 4; p++) storage[p] = Math.max(0, storage[p] - Defs.S_FEED[s][p] * f);
            money += Defs.S_INCOME[s] * f - Defs.S_COST[s];
            if (f < 0.999 && day() - lastFeedWarn >= 3) {
                lastFeedWarn = day();
                msg((s == 0 ? "🐔 Die Hühner" : "🐄 Die Kühe") + " haben zu wenig Futter (" + missing + ")!");
            }
        }
        // fremde Felder bewirtschaften sich selbst
        for (Field f : world.fields) {
            if (f.owned) continue;
            if (f.state == Field.GROWING && f.growth >= 1.1 + rnd.nextDouble() * 0.1) {
                f.state = Field.STUBBLE;
                f.idleDays = 0;
            } else if (f.state == Field.STUBBLE) {
                f.idleDays += 1;
                if (f.idleDays > 5) f.state = Field.CULTIVATED;
            } else if (f.state == Field.CULTIVATED) {
                f.idleDays += 1;
                if (f.idleDays > 8) {
                    f.state = Field.GROWING;
                    f.crop = rnd.nextInt(4);
                    f.growth = 0;
                    f.fertilized = rnd.nextBoolean();
                }
            }
        }
    }

    private void updateVehicle(Vehicle v, double dt) {
        int guard = 0;
        while (dt > 1e-6 && !v.steps.isEmpty() && guard++ < 30) {
            Step s = v.steps.get(0);
            switch (s.kind) {
                case GOTO: {
                    if (v.path == null) {
                        v.path = world.path((int) (v.x / Defs.TILE), (int) (v.y / Defs.TILE), s.a, s.b);
                        v.pathIdx = 0;
                        if (v.status == null || !v.status.startsWith("Bunker")) v.status = gotoText(s);
                    }
                    dt = drive(v, dt);
                    if (v.pathIdx >= v.path.size()) {
                        v.path = null;
                        v.steps.remove(0);
                    }
                    break;
                }
                case PICKUP: {
                    Tool t = null;
                    if (v.tool >= 0) {
                        Tool cur = tool(v.tool);
                        if (cur != null && cur.type == s.a) t = cur;
                    }
                    if (t == null) t = freeTool(s.a);
                    if (t == null) {
                        abort(v, "⚠ " + v.name() + ": kein freies Gerät „" + Defs.T_NAMES[s.a] + "“.");
                        return;
                    }
                    t.attachedTo = v.id;
                    v.tool = t.id;
                    v.steps.remove(0);
                    break;
                }
                case DROP: {
                    Tool t = tool(v.tool);
                    if (t != null) t.attachedTo = -1;
                    v.tool = -1;
                    v.steps.remove(0);
                    break;
                }
                case WORK:
                    dt = work(v, s, dt);
                    break;
                case UNLOAD: {
                    if (v.loadProduct >= 0 && v.load > 0) {
                        storage[v.loadProduct] += v.load;
                        msg(String.format(Locale.GERMANY, "📦 %s hat %.1f t %s eingelagert.", v.name(), v.load,
                                Defs.PRODUCT_NAMES[v.loadProduct]));
                    }
                    v.load = 0;
                    v.loadProduct = -1;
                    v.status = "lädt ab";
                    v.steps.remove(0);
                    break;
                }
                case LOAD: {
                    double amount = Math.min(storage[s.a], Defs.TRAILER_CAP);
                    if (amount < 0.1) {
                        abort(v, "⚠ Kein " + Defs.PRODUCT_NAMES[s.a] + " im Lager.");
                        return;
                    }
                    storage[s.a] -= amount;
                    v.load = amount;
                    v.loadProduct = s.a;
                    v.steps.remove(0);
                    break;
                }
                case SELL: {
                    if (v.loadProduct >= 0 && v.load > 0) {
                        double earn = v.load * price(v.loadProduct);
                        money += earn;
                        msg(String.format(Locale.GERMANY, "💶 %.1f t %s verkauft für %s", v.load,
                                Defs.PRODUCT_NAMES[v.loadProduct], euro(earn)));
                    }
                    v.load = 0;
                    v.loadProduct = -1;
                    v.steps.remove(0);
                    break;
                }
                default:
                    v.steps.remove(0);
            }
        }
        if (v.steps.isEmpty()) {
            v.path = null;
            v.status = atHof(v) ? "steht am Hof" : "wartet";
        }
    }

    private String gotoText(Step s) {
        if (s.c == 1) return "fährt zum Hof";
        if (s.c == 2) return "fährt zum Feld";
        if (s.c == 3) return "fährt zum Verkaufen";
        return "fährt";
    }

    public boolean atHof(Vehicle v) {
        int tx = (int) (v.x / Defs.TILE), ty = (int) (v.y / Defs.TILE);
        int[] h = world.hof;
        return tx >= h[0] && ty >= h[1] && tx < h[0] + h[2] && ty < h[1] + h[3];
    }

    private double drive(Vehicle v, double dt) {
        while (dt > 1e-6 && v.pathIdx < v.path.size()) {
            float[] t = v.path.get(v.pathIdx);
            float dx = t[0] - v.x, dy = t[1] - v.y;
            double d = Math.hypot(dx, dy);
            double sp = Defs.V_SPEED[v.type] * world.speedFactor(v.x, v.y);
            if (d < 1e-3) {
                v.pathIdx++;
                continue;
            }
            v.heading = (float) Math.toDegrees(Math.atan2(dy, dx));
            double move = sp * dt;
            if (move >= d) {
                v.x = t[0];
                v.y = t[1];
                dt -= d / sp;
                v.pathIdx++;
            } else {
                v.x += (float) (dx / d * move);
                v.y += (float) (dy / d * move);
                dt = 0;
            }
        }
        return dt;
    }

    private double workRate(Vehicle v, Field f, int task) {
        if (task == Defs.TASK_HARVEST) return Defs.harvestRate(v.type, f.crop);
        Tool t = tool(v.tool);
        if (t == null) return 0;
        return Defs.T_RATE[t.type] * Defs.V_POWER[v.type];
    }

    private double work(Vehicle v, Step s, double dt) {
        Field f = world.fields.get(s.a);
        if (f.busyBy != v.id) {
            abort(v, null);
            return 0;
        }
        double area = f.hectares();
        double rate = workRate(v, f, s.b);
        if (rate <= 0) {
            abort(v, "⚠ " + v.name() + " hat nicht das richtige Gerät.");
            return 0;
        }
        double dp = rate * dt / 3600.0 / area;
        boolean harvest = s.b == Defs.TASK_HARVEST;
        double yieldHa = 0;
        if (harvest) {
            yieldHa = Defs.YIELD[f.crop] * (f.fertilized ? 1.0 : 0.8);
            double cap = Defs.V_TANK[v.type];
            double room = cap - v.load;
            double dpMax = room / (yieldHa * area);
            if (dp > dpMax) dp = Math.max(0, dpMax);
            v.loadProduct = f.crop;
        }
        dp = Math.min(dp, 1 - f.progress);
        f.progress += dp;
        if (harvest) v.load += dp * yieldHa * area;
        float[] wp = f.workPos(f.progress);
        v.x = wp[0] * Defs.TILE;
        v.y = wp[1] * Defs.TILE;
        v.heading = wp[2];
        v.status = "Feld " + f.number + ": " + Defs.TASK_NAMES[s.b] + " " + Math.round(f.progress * 100) + " %";

        if (f.progress >= 1 - 1e-9) {
            int task = f.task;
            f.finishTask();
            msg("✅ Feld " + f.number + " ist fertig " + Defs.TASK_DONE[task] + ".");
            v.steps.remove(0);
            return 0;
        }
        if (harvest && v.load >= Defs.V_TANK[v.type] - 1e-6) {
            // Bunker voll: zum Hof, abladen, zurück
            int[] park = world.parking(v.parkSlot);
            int[] back = fieldTile(f);
            v.steps.add(0, new Step(GOTO, back[0], back[1], 2));
            v.steps.add(0, new Step(UNLOAD, 0, 0, 0));
            v.steps.add(0, new Step(GOTO, park[0], park[1], 1));
            v.status = "Bunker voll – fährt zum Abladen";
            msg("🚜 " + v.name() + ": Bunker voll, fährt zum Abladen.");
        }
        return 0;
    }

    private int[] fieldTile(Field f) {
        float[] wp = f.workPos(f.progress);
        return new int[]{(int) wp[0], (int) wp[1]};
    }

    private Tool freeTool(int type) {
        for (Tool t : tools) if (t.type == type && t.attachedTo == -1) return t;
        return null;
    }

    private void abort(Vehicle v, String message) {
        for (Field f : world.fields) if (f.busyBy == v.id) {
            f.busyBy = -1;
            if (f.progress <= 0) {
                f.task = -1;
                f.taskCrop = -1;
            }
        }
        v.steps.clear();
        v.path = null;
        if (message != null) msg(message);
    }

    // --------------------------------------------------------------- Aufträge

    public int toolFor(int task, int crop) {
        if (task == Defs.TASK_CULTIVATE) return Defs.T_GRUBBER;
        if (task == Defs.TASK_SOW) return crop == Defs.POTATO ? Defs.T_PLANTER : Defs.T_SEEDER;
        if (task == Defs.TASK_FERTILIZE) return Defs.T_SPREADER;
        return -1;
    }

    private boolean hasTool(Vehicle v, int type) {
        Tool cur = tool(v.tool);
        if (cur != null && cur.type == type) return true;
        return freeTool(type) != null;
    }

    /** Kosten für Saatgut/Dünger */
    public double taskCost(Field f, int task, int crop) {
        if (f.progress > 0) return 0;
        if (task == Defs.TASK_SOW) return Defs.SEED_COST[crop] * f.hectares();
        if (task == Defs.TASK_FERTILIZE) return Defs.FERTILIZER_COST * f.hectares();
        return 0;
    }

    /** Prüft, ob Fahrzeug v die Arbeit machen kann. null = ja, sonst Grund. */
    public String checkFieldJob(Vehicle v, Field f, int task, int crop) {
        if (!f.owned) return "Das Feld gehört dir nicht.";
        if (!f.canDo(task)) return Defs.TASK_NAMES[task] + " geht auf diesem Feld gerade nicht.";
        if (f.busyBy != -1 && f.busyBy != v.id) return "Auf dem Feld arbeitet schon jemand.";
        if (v.busy()) return v.name() + " ist gerade beschäftigt.";
        if (task == Defs.TASK_HARVEST) {
            int need = Defs.harvesterFor(f.crop);
            if (v.type != need) return "Zum Ernten von " + Defs.CROP_NAMES[f.crop] + " brauchst du einen " + Defs.V_NAMES[need] + ".";
        } else {
            if (!Defs.isTractor(v.type)) return "Dafür brauchst du einen Traktor.";
            int tt = toolFor(task, crop);
            if (!hasTool(v, tt)) return "Du hast keinen freien „" + Defs.T_NAMES[tt] + "“.";
        }
        double cost = taskCost(f, task, crop);
        if (cost > 0 && money < cost) return "Zu wenig Geld (" + euro(cost) + " nötig).";
        return null;
    }

    public String startFieldJob(Vehicle v, Field f, int task, int crop) {
        String err = checkFieldJob(v, f, task, crop);
        if (err != null) return err;
        double cost = taskCost(f, task, crop);
        if (cost > 0) {
            money -= cost;
            msg((task == Defs.TASK_SOW ? "🌱 Saatgut" : "🧪 Dünger") + " gekauft: " + euro(cost));
        }
        f.busyBy = v.id;
        f.task = task;
        if (task == Defs.TASK_SOW) f.taskCrop = crop;
        int[] park = world.parking(v.parkSlot);
        if (Defs.isTractor(v.type)) {
            int tt = toolFor(task, crop);
            Tool cur = tool(v.tool);
            if (cur == null || cur.type != tt) {
                if (cur != null) {
                    v.steps.add(new Step(GOTO, park[0], park[1], 1));
                    v.steps.add(new Step(DROP, 0, 0, 0));
                }
                v.steps.add(new Step(GOTO, park[0], park[1], 1));
                v.steps.add(new Step(PICKUP, tt, 0, 0));
            }
        }
        int[] ft = fieldTile(f);
        v.steps.add(new Step(GOTO, ft[0], ft[1], 2));
        v.steps.add(new Step(WORK, f.index, task, crop));
        v.steps.add(new Step(GOTO, park[0], park[1], 1));
        if (task == Defs.TASK_HARVEST) v.steps.add(new Step(UNLOAD, 0, 0, 0));
        if (Defs.isTractor(v.type)) v.steps.add(new Step(DROP, 0, 0, 0));
        msg("👷 Helfer übernimmt: " + v.name() + " – " + Defs.TASK_NAMES[task] + " auf Feld " + f.number);
        return null;
    }

    /** Passendes freies Fahrzeug für eine Feldarbeit suchen */
    public Vehicle findVehicleFor(Field f, int task, int crop) {
        Vehicle best = null;
        for (Vehicle v : vehicles) {
            if (checkFieldJob(v, f, task, crop) != null) continue;
            if (best == null || Defs.V_POWER[v.type] > Defs.V_POWER[best.type]) best = v;
        }
        return best;
    }

    public String startSellJob(Vehicle v, int product) {
        if (!Defs.isTractor(v.type)) return "Zum Verkaufen brauchst du einen Traktor mit Anhänger.";
        if (v.busy()) return v.name() + " ist gerade beschäftigt.";
        if (!hasTool(v, Defs.T_TRAILER)) return "Du hast keinen freien Anhänger.";
        if (storage[product] < 0.5) return "Kein " + Defs.PRODUCT_NAMES[product] + " im Lager.";
        int[] park = world.parking(v.parkSlot);
        Tool cur = tool(v.tool);
        if (cur == null || cur.type != Defs.T_TRAILER) {
            if (cur != null) {
                v.steps.add(new Step(GOTO, park[0], park[1], 1));
                v.steps.add(new Step(DROP, 0, 0, 0));
            }
            v.steps.add(new Step(GOTO, park[0], park[1], 1));
            v.steps.add(new Step(PICKUP, Defs.T_TRAILER, 0, 0));
        } else {
            v.steps.add(new Step(GOTO, park[0], park[1], 1));
        }
        v.steps.add(new Step(LOAD, product, 0, 0));
        int[] gate = Defs.BUYER[product] == 0 ? world.landhandelGate : world.biogasGate;
        v.steps.add(new Step(GOTO, gate[0], gate[1], 3));
        v.steps.add(new Step(SELL, Defs.BUYER[product], 0, 0));
        v.steps.add(new Step(GOTO, park[0], park[1], 1));
        v.steps.add(new Step(DROP, 0, 0, 0));
        msg("👷 Helfer übernimmt: " + v.name() + " fährt " + Defs.PRODUCT_NAMES[product] + " zum "
                + (Defs.BUYER[product] == 0 ? "Landhandel" : "Biogas") + ".");
        return null;
    }

    public String startGoto(Vehicle v, int tx, int ty) {
        if (!world.passable(tx, ty)) return "Da kann man nicht hinfahren.";
        stop(v);
        v.steps.add(new Step(GOTO, tx, ty, 0));
        return null;
    }

    public void sendHome(Vehicle v) {
        stop(v);
        int[] park = world.parking(v.parkSlot);
        v.steps.add(new Step(GOTO, park[0], park[1], 1));
        if (v.tool >= 0) v.steps.add(new Step(DROP, 0, 0, 0));
        if (v.load > 0 && Defs.V_TANK[v.type] > 0) v.steps.add(new Step(UNLOAD, 0, 0, 0));
    }

    public void stop(Vehicle v) {
        abort(v, null);
        v.status = atHof(v) ? "steht am Hof" : "wartet";
    }

    // ----------------------------------------------------------------- Kaufen

    public String buyVehicle(int type) {
        if (money < Defs.V_PRICE[type]) return "Zu wenig Geld.";
        money -= Defs.V_PRICE[type];
        addVehicle(type);
        return null;
    }

    public String buyTool(int type) {
        if (money < Defs.T_PRICE[type]) return "Zu wenig Geld.";
        money -= Defs.T_PRICE[type];
        addTool(type);
        return null;
    }

    public String buyStable(int s) {
        if (stables[s]) return "Den hast du schon.";
        if (money < Defs.S_PRICE[s]) return "Zu wenig Geld.";
        money -= Defs.S_PRICE[s];
        stables[s] = true;
        world.buildStable(s);
        return null;
    }

    public String buyField(Field f) {
        if (f.owned) return "Gehört dir schon.";
        if (money < f.price()) return "Zu wenig Geld.";
        money -= f.price();
        f.owned = true;
        if (f.growth > 1) f.growth = 1;
        return null;
    }

    public String sellVehicle(Vehicle v) {
        if (v.busy()) return "Erst anhalten.";
        if (vehicles.size() <= 1) return "Dein letztes Fahrzeug behältst du lieber.";
        Tool t = tool(v.tool);
        if (t != null) t.attachedTo = -1;
        money += Defs.V_PRICE[v.type] * 0.5;
        vehicles.remove(v);
        return null;
    }

    public String sellTool(Tool t) {
        if (t.attachedTo != -1) return "Das Gerät ist gerade im Einsatz.";
        money += Defs.T_PRICE[t.type] * 0.5;
        tools.remove(t);
        return null;
    }

    // ------------------------------------------------------------- Speichern

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("v", 1);
        o.put("seed", world.seed);
        o.put("money", money);
        o.put("time", time);
        o.put("speed", speedIdx);
        o.put("nextId", nextId);
        o.put("lastDay", lastDay);
        JSONArray st = new JSONArray(), pf = new JSONArray();
        for (int i = 0; i < 4; i++) {
            st.put(storage[i]);
            pf.put(priceFactor[i]);
        }
        o.put("storage", st);
        o.put("prices", pf);
        o.put("stables", new JSONArray().put(stables[0]).put(stables[1]));
        JSONArray fa = new JSONArray();
        for (Field f : world.fields) {
            JSONObject j = new JSONObject();
            j.put("o", f.owned);
            j.put("s", f.state);
            j.put("c", f.crop);
            j.put("g", f.growth);
            j.put("f", f.fertilized);
            j.put("i", f.idleDays);
            j.put("t", f.task);
            j.put("tc", f.taskCrop);
            j.put("p", f.progress);
            j.put("b", f.busyBy);
            fa.put(j);
        }
        o.put("fields", fa);
        JSONArray va = new JSONArray();
        for (Vehicle v : vehicles) {
            JSONObject j = new JSONObject();
            j.put("id", v.id);
            j.put("type", v.type);
            j.put("slot", v.parkSlot);
            j.put("x", v.x);
            j.put("y", v.y);
            j.put("h", v.heading);
            j.put("tool", v.tool);
            j.put("load", v.load);
            j.put("lp", v.loadProduct);
            JSONArray sa = new JSONArray();
            for (Step s : v.steps) sa.put(new JSONArray().put(s.kind).put(s.a).put(s.b).put(s.c));
            j.put("steps", sa);
            va.put(j);
        }
        o.put("vehicles", va);
        JSONArray ta = new JSONArray();
        for (Tool t : tools) ta.put(new JSONArray().put(t.id).put(t.type).put(t.attachedTo));
        o.put("tools", ta);
        return o;
    }

    public static Game fromJson(JSONObject o) throws JSONException {
        Game g = new Game();
        g.world = new World(o.getLong("seed"));
        g.money = o.getDouble("money");
        g.time = o.getDouble("time");
        g.speedIdx = o.optInt("speed", 3);
        g.nextId = o.getInt("nextId");
        g.lastDay = o.optInt("lastDay", 0);
        JSONArray st = o.getJSONArray("storage"), pf = o.getJSONArray("prices");
        for (int i = 0; i < 4; i++) {
            g.storage[i] = st.getDouble(i);
            g.priceFactor[i] = pf.getDouble(i);
        }
        JSONArray sb = o.getJSONArray("stables");
        for (int s = 0; s < 2; s++) {
            g.stables[s] = sb.getBoolean(s);
            if (g.stables[s]) g.world.buildStable(s);
        }
        JSONArray fa = o.getJSONArray("fields");
        for (int i = 0; i < fa.length() && i < g.world.fields.size(); i++) {
            JSONObject j = fa.getJSONObject(i);
            Field f = g.world.fields.get(i);
            f.owned = j.getBoolean("o");
            f.state = j.getInt("s");
            f.crop = j.getInt("c");
            f.growth = j.getDouble("g");
            f.fertilized = j.getBoolean("f");
            f.idleDays = j.optDouble("i", 0);
            f.task = j.getInt("t");
            f.taskCrop = j.getInt("tc");
            f.progress = j.getDouble("p");
            f.busyBy = j.getInt("b");
        }
        JSONArray va = o.getJSONArray("vehicles");
        for (int i = 0; i < va.length(); i++) {
            JSONObject j = va.getJSONObject(i);
            Vehicle v = new Vehicle();
            v.id = j.getInt("id");
            v.type = j.getInt("type");
            v.parkSlot = j.getInt("slot");
            v.x = (float) j.getDouble("x");
            v.y = (float) j.getDouble("y");
            v.heading = (float) j.getDouble("h");
            v.tool = j.getInt("tool");
            v.load = j.getDouble("load");
            v.loadProduct = j.getInt("lp");
            JSONArray sa = j.getJSONArray("steps");
            for (int k = 0; k < sa.length(); k++) {
                JSONArray s = sa.getJSONArray(k);
                v.steps.add(new Step(s.getInt(0), s.getInt(1), s.getInt(2), s.getInt(3)));
            }
            g.vehicles.add(v);
        }
        JSONArray ta = o.getJSONArray("tools");
        for (int i = 0; i < ta.length(); i++) {
            JSONArray a = ta.getJSONArray(i);
            Tool t = new Tool();
            t.id = a.getInt(0);
            t.type = a.getInt(1);
            t.attachedTo = a.getInt(2);
            g.tools.add(t);
        }
        return g;
    }
}
