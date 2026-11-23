package de.spiasm.hofsim;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Spielstand und Spiellogik: Zeit, Geld, Lager, Fahrzeuge, Aufträge, Wachstum, Ställe, Energie, Verkehr, Brände. */
public class Game {
    public interface Listener {
        void message(String s);
    }

    // ------------------------------------------------------------ Datentypen

    public static class Tool {
        public int id, model, attachedTo = -1;
        /** für einen Auftrag reserviert (Fahrzeug-ID) */
        public int reservedBy = -1;

        public Defs.Model m() {
            return Defs.model(model);
        }
    }

    public static final int GOTO = 0, PICKUP = 1, DROP = 2, WORK = 3, UNLOAD = 4, LOAD = 5, SELL = 6, REPEAT_SELL = 7,
            WAIT_OVERLOAD = 8, OVERLOAD = 9, LOAD_FEED = 10, DELIVER = 11, REPEAT_FEED = 12, FOLLOW = 13, LOAD_MANURE = 14,
            FWORK = 15, POLTER = 16, LOAD_POLTER = 17, REPEAT_WOOD = 18;

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
        public int id, model, parkSlot;
        public float x, y, heading;
        public int tool = -1;
        public double load;
        public int loadProduct = -1;
        public double timer;
        /** Erntemaschine: welcher Traktor gerade zum Überladen kommt */
        public int overloader = -1;
        /** geliehen von Hof Nr. (-1 = eigenes Fahrzeug) */
        public int lentFrom = -1;
        public final List<Step> steps = new ArrayList<>();
        public List<float[]> path;
        public int pathIdx;
        public String status = "steht am Hof";

        public boolean busy() {
            return !steps.isEmpty();
        }

        public String name() {
            return m().name + " #" + id;
        }

        public Defs.Model m() {
            return Defs.model(model);
        }

        public boolean isTractor() {
            return m().kind == Defs.K_TRACTOR;
        }

        public boolean isHarvester() {
            return m().kind == Defs.K_HARVESTER;
        }

        public boolean isForestry() {
            return m().kind == Defs.K_FORESTRY;
        }

        /** arbeitet gerade auf dem Feld? */
        public boolean working() {
            return !steps.isEmpty() && (steps.get(0).kind == WORK || steps.get(0).kind == FWORK);
        }
    }

    /** Fahrzeug im allgemeinen Verkehr (wird nicht gespeichert) */
    public static class Car {
        public float x, y, heading;
        public int type, color;
        public double speed;
        public List<float[]> path;
        public int pathIdx;
    }

    public static final int CAR = 0, VAN = 1, TRUCK = 2, BUS = 3;

    /** Feuerwehr */
    public static class FireTruck {
        public float x, y, heading;
        public int state; // 0 Anfahrt, 1 löschen, 2 Rückfahrt
        public double timer;
        public List<float[]> path;
        public int pathIdx;
    }

    // ---------------------------------------------------------------- Zustand

    /** Alles, was einem Hof (Spieler) gehört. Die Felder unten (money, storage, vehicles …) zeigen auf den gewählten Hof. */
    public static class Farm {
        public double money;
        public final double[] storage = new double[Defs.PRODUCTS];
        public final boolean[] stables = new boolean[Defs.STABLES];
        public final double[][] stableFeed = new double[Defs.STABLES][4];
        public final double[] stableManure = new double[Defs.STABLES];
        public double energyToday, lastEnergy;
        public final int[] lastManureWarn = new int[Defs.STABLES], lastFeedWarn = new int[Defs.STABLES];
        public boolean pvHouse, pvHall;
        public final boolean[] pvStable = new boolean[Defs.STABLES];
        public final List<int[]> winds = new ArrayList<>();
        public final List<Vehicle> vehicles = new ArrayList<>();
        public final List<Tool> tools = new ArrayList<>();
        public String name = "";
        /** Nachrichten für diesen Hof */
        public final List<String> outbox = new ArrayList<>();
    }

    /** Spielart: 0 allein / gemeinsamer Hof, 1 jeder eigener Hof, 2 eigene Höfe + helfen */
    public static final int MODE_SHARED = 0, MODE_OWN = 1, MODE_HELP = 2;
    public static final String[] MODE_NAMES = {"Gemeinsamer Hof", "Jeder hat einen eigenen Hof", "Eigene Höfe + gegenseitig helfen"};
    public static final int[] FARM_COLOR = {0xFFFFEB3B, 0xFF4FC3F7, 0xFFFF8A65};
    public int mode = MODE_SHARED;
    public Farm[] farms = {new Farm()};
    private int cur;
    /** Nachrichten an alle Höfe statt nur an den gewählten */
    private boolean broadcast;
    /** Mitspieler-Handy: rechnet nur zur Anzeige mit, Nachrichten kommen vom Gastgeber */
    public boolean muted;

    public World world;
    public double money;
    public double time;
    public int speedIdx = 3;
    public double[] storage;
    public final double[][] priceFactor = new double[Defs.BUYERS][Defs.PRODUCTS];
    public boolean[] stables;
    public double[][] stableFeed;
    /** Gülle (m³) bzw. Mist (t) je Stall */
    public double[] stableManure;
    /** Schnee-Bedeckung 0..1 */
    public double snowCover;
    /** Energie: heute bisher verdient */
    public double energyToday;
    private int[] lastManureWarn;
    public boolean pvHouse, pvHall;
    public boolean[] pvStable;
    public List<int[]> winds;
    public List<Vehicle> vehicles;
    public List<Tool> tools;
    public int nextId = 1;
    public transient Listener listener;
    private final Random rnd = new Random();
    private int lastDay;
    private int[] lastFeedWarn;
    /** Sonder-Nachfrage */
    public int bonusBuyer = -1, bonusProduct = -1;
    public double bonusFactor = 1;
    public int bonusUntil;
    /** Wetter heute: 0 Sonne, 1 Wolken, 2 Regen */
    public int weatherType;
    public double weatherSun = 0.7, weatherWind = 0.7;
    public double lastEnergy;

    // Verkehr und Feuer (nicht gespeichert)
    public final List<Car> cars = new ArrayList<>();
    public int trafficTarget = 12;
    public int fireField = -1;
    public float fireX, fireY, fireR;
    /** Stärke der Flammen 0..1 (sinkt beim Löschen) */
    public float firePower = 1;
    public FireTruck fireTruck;

    public static final int START_DAY = 60; // 1. März

    public Game() {
        this(1);
    }

    public Game(int nFarms) {
        for (double[] row : priceFactor) java.util.Arrays.fill(row, 1);
        farms = new Farm[Math.max(1, nFarms)];
        for (int i = 0; i < farms.length; i++) farms[i] = new Farm();
        cur = 0;
        load();
    }

    // ------------------------------------------------------------ Höfe wechseln

    public int farmCount() {
        return farms.length;
    }

    public int currentFarm() {
        return cur;
    }

    /** Hof p wählen: money, storage, vehicles … zeigen danach auf diesen Hof */
    public void select(int p) {
        if (p < 0 || p >= farms.length) p = 0;
        if (p != cur) {
            store();
            cur = p;
            load();
            for (Field f : world.fields) f.owned = f.owner == p;
            for (Forest fo : world.forests) fo.owned = fo.owner == p;
        }
        if (world != null && world.currentFarm() != p) world.useFarm(p);
    }

    /** Besitz der gewählten Höfe in owner übernehmen */
    public void syncOwner() {
        if (world == null) return;
        for (Field f : world.fields) {
            if (f.owned) f.owner = cur;
            else if (f.owner == cur) f.owner = -1;
        }
        for (Forest fo : world.forests) {
            if (fo.owned) fo.owner = cur;
            else if (fo.owner == cur) fo.owner = -1;
        }
    }

    private void store() {
        storeFarm();
        syncOwner();
    }

    private void storeFarm() {
        Farm f = farms[cur];
        f.money = money;
        f.pvHouse = pvHouse;
        f.pvHall = pvHall;
        f.energyToday = energyToday;
        f.lastEnergy = lastEnergy;
    }

    private void load() {
        Farm f = farms[cur];
        money = f.money;
        storage = f.storage;
        stables = f.stables;
        stableFeed = f.stableFeed;
        stableManure = f.stableManure;
        energyToday = f.energyToday;
        lastEnergy = f.lastEnergy;
        lastManureWarn = f.lastManureWarn;
        lastFeedWarn = f.lastFeedWarn;
        pvHouse = f.pvHouse;
        pvHall = f.pvHall;
        pvStable = f.pvStable;
        winds = f.winds;
        vehicles = f.vehicles;
        tools = f.tools;
    }

    /** Fahrzeug irgendeines Hofs (für die Anzeige) */
    public Vehicle anyVehicle(int id) {
        for (Farm f : farms) for (Vehicle v : f.vehicles) if (v.id == id) return v;
        return null;
    }

    /** zu welchem Hof gehört das Fahrzeug? */
    public int farmOfVehicle(int id) {
        for (int p = 0; p < farms.length; p++) for (Vehicle v : farms[p].vehicles) if (v.id == id) return p;
        return -1;
    }

    // ------------------------------------------------- Mitspieler: helfen

    /** Fahrzeug (mit angehängtem Gerät) an einen anderen Hof verleihen */
    public String lendVehicle(Vehicle v, int to) {
        if (to == cur || to < 0 || to >= farms.length) return "Diesen Hof gibt es nicht.";
        if (v.lentFrom >= 0) return "Das Fahrzeug ist selbst nur geliehen.";
        if (v.busy()) return v.name() + " ist gerade beschäftigt – erst anhalten.";
        int from = cur;
        Tool t = tool(v.tool);
        vehicles.remove(v);
        if (t != null) tools.remove(t);
        select(to);
        v.lentFrom = from;
        v.parkSlot = freeSlot();
        vehicles.add(v);
        if (t != null) tools.add(t);
        msg("🤝 " + farms[from].name + " leiht dir " + v.name() + (t != null ? " mit " + t.m().name : "") + ".");
        select(from);
        msg("🤝 " + v.name() + " ist jetzt an " + farms[to].name + " verliehen.");
        return null;
    }

    /** geliehenes Fahrzeug zurückgeben (vom Leiher) oder zurückholen (vom Besitzer) */
    public String returnVehicle(int vehicleId) {
        int holder = farmOfVehicle(vehicleId);
        if (holder < 0) return "Fahrzeug nicht gefunden.";
        int back = cur;
        select(holder);
        Vehicle v = vehicle(vehicleId);
        if (v == null || v.lentFrom < 0) {
            select(back);
            return "Das Fahrzeug ist nicht geliehen.";
        }
        if (back != holder && back != v.lentFrom) {
            select(back);
            return "Das ist nicht dein Fahrzeug.";
        }
        stop(v);
        Tool t = tool(v.tool);
        // nur das eigene Gerät des Besitzers mitnehmen
        vehicles.remove(v);
        if (t != null) tools.remove(t);
        msg("↩ " + v.name() + " geht zurück an " + farms[v.lentFrom].name + ".");
        int owner = v.lentFrom;
        select(owner);
        v.lentFrom = -1;
        v.parkSlot = freeSlot();
        vehicles.add(v);
        if (t != null) tools.add(t);
        sendHomeSilently(v);
        msg("↩ " + v.name() + " kommt zurück zu deinem Hof.");
        select(back);
        return null;
    }

    /** Ware direkt an einen anderen Hof verkaufen (wird mit dem Hof-Lkw geliefert) */
    public String tradeGoods(int seller, int buyer, int product, double tons, double pricePerT) {
        if (seller == buyer || buyer < 0 || buyer >= farms.length || seller < 0 || seller >= farms.length) return "Ungültig.";
        int back = cur;
        Farm s = farms[seller], b = farms[buyer];
        select(seller);
        select(buyer);
        select(back);
        if (s.storage[product] + 1e-6 < tons) return farms[seller].name + " hat nicht mehr so viel " + Defs.PRODUCT_NAMES[product] + ".";
        double total = tons * pricePerT;
        if (b.money < total) return farms[buyer].name + " hat zu wenig Geld.";
        s.storage[product] -= tons;
        b.storage[product] += tons;
        s.money += total;
        b.money -= total;
        load();
        String t = String.format(Locale.GERMANY, "%.1f t %s für %s", tons, Defs.PRODUCT_NAMES[product], euro(total));
        s.outbox.add("🤝 Verkauft an " + b.name + ": " + t);
        b.outbox.add("🤝 Gekauft von " + s.name + ": " + t);
        return null;
    }

    // ------------------------------------------------------------- Neues Spiel

    public static Game newGame(long seed) {
        return newGame(seed, MODE_SHARED, 1, new String[]{"Du"});
    }

    public static Game newGame(long seed, int mode, int players, String[] names) {
        int nFarms = mode == MODE_SHARED ? 1 : Math.max(1, Math.min(3, players));
        Game g = new Game(nFarms);
        g.mode = mode;
        g.world = new World(seed, nFarms);
        for (int p = 0; p < nFarms; p++) g.farms[p].name = p < names.length ? names[p] : "Hof " + (p + 1);
        if (mode == MODE_SHARED) g.farms[0].name = players > 1 ? "Euer Hof" : "Dein Hof";
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
        for (Forest fo : g.world.forests) {
            int k = r.nextInt(6);
            fo.species = r.nextInt(3);
            if (k == 0) {
                fo.state = Forest.CLEAR;
            } else {
                fo.state = Forest.GROWING;
                fo.growth = 0.3 + r.nextDouble() * 0.9;
            }
        }
        for (int p = 0; p < nFarms; p++) {
            g.select(p);
            g.money = Defs.START_MONEY;
            Field start = p == 0 ? g.fieldByNumber(1) : g.nearestFreeField();
            if (start != null) {
                start.owned = true;
                start.owner = p;
                start.state = Field.GROWING;
                start.crop = Defs.WHEAT;
                start.growth = 0.5;
                start.fertilized = false;
            }
            g.addVehicle(Defs.modelByName("Vilstal 105"));
            g.addTool(Defs.modelByName("Grubber 3,5 m"));
            g.addTool(Defs.modelByName("Sämaschine 3 m"));
            g.addTool(Defs.modelByName("Düngerstreuer 800 l"));
            g.addTool(Defs.modelByName("Kipper 10 t"));
            g.addVehicle(Defs.modelByName("Mähdrescher Ähre 2000"));
            g.stables[Defs.S_CHICKEN] = true;
            g.stableFeed[Defs.S_CHICKEN][Defs.WHEAT] = 2.0;
            g.storage[Defs.WHEAT] = 12;
        }
        g.select(0);
        g.newWeather();
        return g;
    }

    /** freies Feld nahe am gewählten Hof */
    private Field nearestFreeField() {
        int[] h = world.hof;
        Field best = null;
        double bd = 1e9;
        for (Field f : world.fields) {
            if (f.owner >= 0 || f.owned || f.pv) continue;
            double d = Math.abs(f.x + f.w / 2.0 - h[0]) + Math.abs(f.y + f.h / 2.0 - h[1]);
            if (d < bd) {
                bd = d;
                best = f;
            }
        }
        return best;
    }

    public Field fieldByNumber(int nr) {
        for (Field f : world.fields) if (f.number == nr) return f;
        return null;
    }

    private Vehicle addVehicle(int model) {
        Vehicle v = new Vehicle();
        v.id = nextId++;
        v.model = model;
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

    private Tool addTool(int model) {
        Tool t = new Tool();
        t.id = nextId++;
        t.model = model;
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

    public String weatherText() {
        String w = weatherType == 0 ? (daylight() > 0 ? "☀" : "🌙") : weatherType == 1 ? "⛅" : weatherType == 2 ? "🌧" : "❄";
        return w + (weatherWind > 1.0 ? " 💨" : "");
    }

    public boolean buys(int buyer, int product) {
        return Defs.BUY_BASE[buyer][product] > 0;
    }

    public double price(int buyer, int product) {
        double p = Defs.BUY_BASE[buyer][product] * priceFactor[buyer][product];
        if (buyer == bonusBuyer && product == bonusProduct) p *= bonusFactor;
        return p;
    }

    /** bester Händler für ein Produkt */
    public int bestBuyer(int product) {
        int best = -1;
        for (int b = 0; b < Defs.BUYERS; b++) if (buys(b, product) && (best < 0 || price(b, product) > price(best, product))) best = b;
        return best;
    }

    public double bestPrice(int product) {
        int b = bestBuyer(product);
        return b >= 0 ? price(b, product) : 0;
    }

    public String bonusText() {
        if (bonusProduct < 0) return null;
        int left = bonusUntil - day();
        return String.format(Locale.GERMANY, "📢 %s zahlt %.1f-fach für %s (noch %d %s)", Defs.BUYER_NAMES[bonusBuyer],
                bonusFactor, Defs.PRODUCT_NAMES[bonusProduct], left, left == 1 ? "Tag" : "Tage");
    }

    public double stableCap(int s, int p) {
        return Defs.S_FEED[s][p] * Defs.S_STORE_DAYS;
    }

    /** Wie viele Tage das Futter im Stall noch reicht */
    public double stableDays(int s) {
        double days = 999;
        for (int p = 0; p < 4; p++) {
            if (Defs.S_FEED[s][p] > 0) days = Math.min(days, stableFeed[s][p] / Defs.S_FEED[s][p]);
        }
        return days;
    }

    public int pvRoofs() {
        int n = (pvHouse ? 1 : 0) + (pvHall ? 1 : 0);
        for (int s = 0; s < Defs.STABLES; s++) if (stables[s] && pvStable[s]) n++;
        return n;
    }

    public double pvFieldHa() {
        double ha = 0;
        for (Field f : world.fields) if (f.pv) ha += f.hectares();
        return ha;
    }

    public static String euro(double v) {
        return String.format(Locale.GERMANY, "%,.0f €", v);
    }

    private void msg(String s) {
        if (muted) return;
        if (broadcast) {
            for (Farm f : farms) f.outbox.add(s);
        } else {
            farms[cur].outbox.add(s);
        }
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
        int back = cur;
        syncOwner();
        time += dt;
        for (Field f : world.fields) {
            if (f.state == Field.GROWING && !f.pv) {
                double max = f.owner >= 0 ? 1.0 : 1.2;
                if (f.growth < max) f.growth = Math.min(max, f.growth + dt / (Defs.GROW_DAYS[f.crop] * 86400));
            }
        }
        for (Forest fo : world.forests) {
            if (fo.state == Forest.GROWING) {
                double max = fo.owner >= 0 ? 1.0 : 1.25;
                if (fo.growth < max) fo.growth = Math.min(max, fo.growth + dt / (Defs.TREE_DAYS[fo.species] * 86400));
            }
        }
        for (int p = 0; p < farms.length; p++) {
            select(p);
            double e = energyRateNow() * dt / 86400.0;
            energyToday += e;
            money += e;
            for (Vehicle v : new ArrayList<>(vehicles)) {
                if (v.busy()) {
                    money -= (Defs.HELPER_WAGE + v.m().diesel) * dt / 3600.0;
                    updateVehicle(v, dt);
                }
            }
        }
        int d = day();
        if (d != lastDay) {
            if (lastDay != 0 && !muted) {
                syncOwner();
                broadcast = true;
                daily();
                broadcast = false;
                for (int p = 0; p < farms.length; p++) {
                    select(p);
                    farmDaily();
                }
            }
            lastDay = d;
        }
        select(back);
    }

    /** Tagesabrechnung eines Hofs: Ställe, Gülle, Energie */
    private void farmDaily() {
        // Ställe fressen aus ihrem eigenen Lager
        for (int s = 0; s < Defs.STABLES; s++) {
            if (!stables[s]) continue;
            double f = 1;
            for (int p = 0; p < 4; p++) {
                double need = Defs.S_FEED[s][p];
                if (need > 0) f = Math.min(f, stableFeed[s][p] / need);
            }
            f = Math.max(0, f);
            for (int p = 0; p < 4; p++) stableFeed[s][p] = Math.max(0, stableFeed[s][p] - Defs.S_FEED[s][p] * f);
            money += Defs.S_INCOME[s] * f - Defs.S_COST[s];
            // Gülle / Mist
            double prod = (Defs.S_SLURRY[s] + Defs.S_DUNG[s]) * f;
            double cap = manureCap(s);
            if (cap > 0) {
                stableManure[s] = Math.min(cap, stableManure[s] + prod);
                if (stableManure[s] >= cap * 0.9 && day() - lastManureWarn[s] >= 3) {
                    lastManureWarn[s] = day();
                    msg(Defs.S_ICON[s] + " " + (Defs.S_SLURRY[s] > 0 ? "Die Güllegrube" : "Der Misthaufen") + " am "
                            + Defs.S_NAMES[s] + " ist fast voll – bitte auf die Felder ausbringen!");
                }
            }
            double left = stableDays(s);
            if (f < 0.999 && day() - lastFeedWarn[s] >= 2) {
                lastFeedWarn[s] = day();
                msg(Defs.S_ICON[s] + " " + Defs.S_NAMES[s] + ": kein Futter mehr – die Tiere bringen nichts ein!");
            } else if (left < 3 && day() - lastFeedWarn[s] >= 2) {
                lastFeedWarn[s] = day();
                msg(String.format(Locale.GERMANY, "%s %s: Futter reicht nur noch %.0f Tage!", Defs.S_ICON[s], Defs.S_NAMES[s], left));
            }
        }
        // Energie (wird laufend verdient, hier nur Tageswert merken)
        lastEnergy = energyToday;
        energyToday = 0;
    }

    private void newWeather() {
        int month = (day() / 30) % 12;
        double roll = rnd.nextDouble();
        double mult;
        if (roll < 0.25) {
            weatherType = 2;
            mult = 0.25;
        } else if (roll < 0.55) {
            weatherType = 1;
            mult = 0.6;
        } else {
            weatherType = 0;
            mult = 1.0;
        }
        boolean winter = month == 11 || month <= 1;
        if (weatherType == 2 && winter) weatherType = 3; // Schnee statt Regen
        weatherSun = Defs.SUN_SEASON[month] * mult;
        weatherWind = 0.25 + rnd.nextDouble() * 1.0 + (weatherType >= 2 ? 0.3 : 0);
        if (weatherType == 3) snowCover = Math.min(1, snowCover + 0.5);
        else snowCover = Math.max(0, snowCover - (winter && weatherType != 0 ? 0.15 : 0.5));
    }

    /** Sonnenstand: 0 nachts, 1 mittags */
    public double daylight() {
        double hour = (time % 86400) / 3600.0;
        if (hour < 6 || hour > 20) return 0;
        return Math.max(0, Math.sin(Math.PI * (hour - 6) / 14));
    }

    /** aktuelle PV-Leistung 0..1 */
    public double pvNow() {
        return Math.max(0, Math.min(1, weatherSun * daylight() * (1 - 0.85 * snowCover)));
    }

    /** aktuelle Windrad-Leistung 0..1 */
    public double windNow() {
        double w = weatherWind / 1.3 * (0.85 + 0.15 * Math.sin(time / 900.0));
        if (w < 0.12) return 0;
        return Math.min(1, w);
    }

    /** Energie-Einnahmen pro Tag bei der aktuellen Leistung */
    public double energyRateNow() {
        double pv = pvNow() / 0.42; // Tagesmittel der Sonnenkurve ausgleichen
        return pvRoofs() * Defs.PV_ROOF_INCOME * pv + pvFieldHa() * Defs.PV_FIELD_INCOME_HA * pv
                + winds.size() * Defs.WIND_INCOME * windNow();
    }

    private void daily() {
        newWeather();
        // Preise schwanken
        for (int b = 0; b < Defs.BUYERS; b++) {
            for (int p = 0; p < Defs.PRODUCTS; p++) {
                double v = Defs.BUY_VOLATILITY[b];
                priceFactor[b][p] = Math.max(1 - 8 * v, Math.min(1 + 8 * v, priceFactor[b][p] + rnd.nextGaussian() * v));
            }
        }
        // Sonder-Nachfrage
        if (bonusProduct >= 0 && day() >= bonusUntil) {
            msg("📉 Die Sonder-Nachfrage nach " + Defs.PRODUCT_NAMES[bonusProduct] + " ist vorbei.");
            bonusProduct = -1;
            bonusBuyer = -1;
            bonusFactor = 1;
        } else if (bonusProduct < 0 && rnd.nextInt(10) == 0) {
            int b, p;
            do {
                b = rnd.nextInt(Defs.BUYERS);
                p = rnd.nextInt(Defs.PRODUCTS);
            } while (!buys(b, p));
            bonusBuyer = b;
            bonusProduct = p;
            bonusFactor = 1.3 + rnd.nextInt(4) * 0.1;
            int days = 3 + rnd.nextInt(4);
            bonusUntil = day() + days;
            msg(String.format(Locale.GERMANY, "📢 Hohe Nachfrage! %s zahlt %d Tage lang das %.1f-fache für %s – jetzt %s/t!",
                    Defs.BUYER_NAMES[b], days, bonusFactor, Defs.PRODUCT_NAMES[p], euro(price(b, p))));
        }
        // fremde Wälder
        for (Forest fo : world.forests) {
            if (fo.owner >= 0) continue;
            if (fo.state == Forest.GROWING && fo.growth >= 1.2) {
                fo.state = Forest.CLEAR;
                fo.growth = 0;
            } else if (fo.state == Forest.CLEAR && rnd.nextInt(6) == 0) {
                fo.state = Forest.GROWING;
                fo.species = rnd.nextInt(3);
                fo.growth = 0;
            }
        }
        // fremde Felder bewirtschaften sich selbst
        for (Field f : world.fields) {
            if (f.owner >= 0 || f.pv) continue;
            if (f.state == Field.GROWING && f.growth >= 1.1 + rnd.nextDouble() * 0.1) {
                f.state = Field.STUBBLE;
                f.idleDays = 0;
            } else if (f.state == Field.STUBBLE) {
                f.idleDays += 1;
                if (f.idleDays > 2 && fireField != f.index) {
                    f.state = Field.CULTIVATED;
                    f.burnR = 0;
                }
            } else if (f.state == Field.CULTIVATED) {
                f.idleDays += 1;
                if (f.idleDays > 4) {
                    f.state = Field.GROWING;
                    f.crop = rnd.nextInt(4);
                    f.growth = 0;
                    f.fertilized = rnd.nextBoolean();
                }
            }
        }
        // Stoppelfeldbrand bei trockenem, sonnigem Wetter
        if (fireField < 0 && fireTruck == null && weatherType == 0 && day() > START_DAY + 5 && rnd.nextInt(7) == 0) {
            List<Field> cand = new ArrayList<>();
            for (Field f : world.fields) {
                if (f.state == Field.STUBBLE && !f.pv && f.busyBy == -1 && f.burnR == 0) cand.add(f);
            }
            if (!cand.isEmpty()) startFire(cand.get(rnd.nextInt(cand.size())));
        }
    }

    // ---------------------------------------------------- Verkehr & Feuerwehr

    /** Läuft in „Ereigniszeit“ (Spielzeit, aber höchstens 10-fach), damit es sichtbar bleibt. */
    public void updateEvents(double dt) {
        broadcast = true;
        try {
            updateEventsInner(dt);
        } finally {
            broadcast = false;
        }
    }

    private void updateEventsInner(double dt) {
        if (dt <= 0) return;
        while (cars.size() < trafficTarget) cars.add(newCar(true));
        while (cars.size() > trafficTarget) cars.remove(cars.size() - 1);
        for (Car c : cars) {
            if (c.path == null || c.pathIdx >= c.path.size()) {
                int[] dest = world.randomRoad(rnd);
                c.path = world.path((int) (c.x / Defs.TILE), (int) (c.y / Defs.TILE), dest[0], dest[1], true);
                c.pathIdx = 0;
            }
            double left = dt;
            while (left > 1e-6 && c.pathIdx < c.path.size()) {
                float[] t = c.path.get(c.pathIdx);
                float dx = t[0] - c.x, dy = t[1] - c.y;
                double d = Math.hypot(dx, dy);
                if (d < 1e-3) {
                    c.pathIdx++;
                    continue;
                }
                c.heading = (float) Math.toDegrees(Math.atan2(dy, dx));
                double move = c.speed * left;
                if (move >= d) {
                    c.x = t[0];
                    c.y = t[1];
                    left -= d / c.speed;
                    c.pathIdx++;
                } else {
                    c.x += (float) (dx / d * move);
                    c.y += (float) (dy / d * move);
                    left = 0;
                }
            }
        }
        updateFire(dt);
    }

    private Car newCar(boolean anywhere) {
        Car c = new Car();
        int[] s = world.randomRoad(rnd);
        c.x = (s[0] + 0.5f) * Defs.TILE;
        c.y = (s[1] + 0.5f) * Defs.TILE;
        int k = rnd.nextInt(20);
        c.type = k < 13 ? CAR : k < 17 ? VAN : k < 19 ? TRUCK : BUS;
        int[] colors = {0xFFE53935, 0xFF1E88E5, 0xFFFAFAFA, 0xFF212121, 0xFF9E9E9E, 0xFF43A047, 0xFFFDD835, 0xFF6D4C41};
        c.color = c.type == BUS ? 0xFFFFB300 : c.type == TRUCK ? 0xFFECEFF1 : colors[rnd.nextInt(colors.length)];
        c.speed = c.type == CAR ? 13.9 : c.type == VAN ? 12.5 : 11.0;
        return c;
    }

    private void startFire(Field f) {
        fireField = f.index;
        fireX = (f.x + 1 + rnd.nextFloat() * (f.w - 2)) * Defs.TILE;
        fireY = (f.y + 1 + rnd.nextFloat() * (f.h - 2)) * Defs.TILE;
        fireR = 3;
        msg("🔥 Feld " + f.number + " brennt! Die Feuerwehr ist alarmiert.");
        fireTruck = new FireTruck();
        int[] g = world.fireGate != null ? world.fireGate : world.hofGate;
        fireTruck.x = (g[0] + 0.5f) * Defs.TILE;
        fireTruck.y = (g[1] + 0.5f) * Defs.TILE;
        // Standplatz: Straße (oder Wiese) direkt am Feldrand, möglichst nah am Feuer
        int[] stand = null;
        double bestD = Double.MAX_VALUE;
        for (int pass = 0; pass < 2 && stand == null; pass++) {
            for (int x = f.x - 3; x < f.x + f.w + 3; x++) {
                for (int y = f.y - 3; y < f.y + f.h + 3; y++) {
                    if (!world.in(x, y)) continue;
                    int tt = world.tile[x][y];
                    if (pass == 0 ? tt != World.ROAD : tt != World.GRASS) continue;
                    double d = Math.hypot((x + 0.5) * Defs.TILE - fireX, (y + 0.5) * Defs.TILE - fireY);
                    if (d < bestD) {
                        bestD = d;
                        stand = new int[]{x, y};
                    }
                }
            }
        }
        if (stand == null) stand = new int[]{f.x, f.y};
        fireTruck.path = world.path(g[0], g[1], stand[0], stand[1]);
        fireTruck.pathIdx = 0;
        firePower = 1;
    }

    private void updateFire(double dt) {
        if (fireField >= 0) {
            Field f = world.fields.get(fireField);
            if (fireTruck == null || fireTruck.state != 1) fireR += (float) (0.5 * dt);
            float diag = (float) Math.hypot(f.w * Defs.TILE, f.h * Defs.TILE);
            if (fireR > diag * 1.1f) endFire("🔥 Feld " + f.number + " ist komplett abgebrannt.");
        }
        FireTruck t = fireTruck;
        if (t == null) return;
        if (t.state == 0 || t.state == 2) {
            double left = dt;
            while (left > 1e-6 && t.pathIdx < t.path.size()) {
                float[] p = t.path.get(t.pathIdx);
                float dx = p[0] - t.x, dy = p[1] - t.y;
                double d = Math.hypot(dx, dy);
                if (d < 1e-3) {
                    t.pathIdx++;
                    continue;
                }
                t.heading = (float) Math.toDegrees(Math.atan2(dy, dx));
                double sp = 16 * world.speedFactor(t.x, t.y);
                double move = sp * left;
                if (move >= d) {
                    t.x = p[0];
                    t.y = p[1];
                    left -= d / sp;
                    t.pathIdx++;
                } else {
                    t.x += (float) (dx / d * move);
                    t.y += (float) (dy / d * move);
                    left = 0;
                }
            }
            if (t.pathIdx >= t.path.size()) {
                if (t.state == 0) {
                    if (fireField >= 0) {
                        t.state = 1;
                        t.timer = 0;
                        msg("🚒 Die Feuerwehr ist da und löscht.");
                    } else {
                        sendTruckHome();
                    }
                } else {
                    fireTruck = null;
                }
            }
        } else if (t.state == 1) {
            t.heading = (float) Math.toDegrees(Math.atan2(fireY - t.y, fireX - t.x));
            t.timer += dt;
            firePower = (float) Math.max(0, 1 - t.timer / 150);
            if (fireField < 0) {
                sendTruckHome();
            } else if (t.timer > 150) {
                Field f = world.fields.get(fireField);
                endFire("🚒 Feuer auf Feld " + f.number + " gelöscht. Danke, Feuerwehr!");
            }
        }
    }

    private void endFire(String message) {
        if (fireField < 0) return;
        Field f = world.fields.get(fireField);
        f.burnX = fireX;
        f.burnY = fireY;
        f.burnR = Math.max(f.burnR, fireR);
        fireField = -1;
        msg(message);
        if (fireTruck != null && fireTruck.state != 2) sendTruckHome();
    }

    private void sendTruckHome() {
        FireTruck t = fireTruck;
        if (t == null) return;
        int[] g = world.fireGate != null ? world.fireGate : world.hofGate;
        t.path = world.path((int) (t.x / Defs.TILE), (int) (t.y / Defs.TILE), g[0], g[1]);
        t.pathIdx = 0;
        t.state = 2;
    }

    // ---------------------------------------------------------- Fahrzeuge

    private void updateVehicle(Vehicle v, double dt) {
        int guard = 0;
        while (dt > 1e-6 && !v.steps.isEmpty() && guard++ < 30) {
            Step s = v.steps.get(0);
            switch (s.kind) {
                case GOTO: {
                    if (v.path == null) {
                        v.path = world.path((int) (v.x / Defs.TILE), (int) (v.y / Defs.TILE), s.a, s.b);
                        v.pathIdx = 0;
                        if (v.status == null || !v.status.contains("voll")) v.status = gotoText(s);
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
                        if (cur != null && cur.m().sub == s.a && cur.m().level <= v.m().level) t = cur;
                    }
                    if (t == null) t = reservedTool(v, s.a);
                    if (t == null) t = bestFreeTool(s.a, v.m().level, v.id);
                    if (t == null) {
                        abort(v, "⚠ " + v.name() + ": kein passendes freies Gerät vom Typ „" + Defs.T_KIND_NAMES[s.a] + "“.");
                        return;
                    }
                    Tool old = tool(v.tool);
                    if (old != null && old != t) old.attachedTo = -1;
                    t.attachedTo = v.id;
                    t.reservedBy = -1;
                    v.tool = t.id;
                    v.steps.remove(0);
                    break;
                }
                case DROP: {
                    if (v.loadProduct <= -2) {
                        v.load = 0;
                        v.loadProduct = -1;
                    }
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
                    if (v.loadProduct >= 0 && v.load > 0.01) {
                        storage[v.loadProduct] += v.load;
                        msg(String.format(Locale.GERMANY, "📦 %s hat %.1f t %s eingelagert.", v.name(), v.load,
                                Defs.PRODUCT_NAMES[v.loadProduct]));
                    }
                    v.load = 0;
                    v.loadProduct = -1;
                    v.steps.remove(0);
                    break;
                }
                case LOAD: {
                    Tool tr = tool(v.tool);
                    double amount = Math.min(storage[s.a], tr != null ? tr.m().cap : 0);
                    if (amount < 0.1) {
                        abort(v, "⚠ Kein " + Defs.PRODUCT_NAMES[s.a] + " mehr im Lager.");
                        sendHomeSilently(v);
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
                        double earn = v.load * price(s.a, v.loadProduct);
                        money += earn;
                        msg(String.format(Locale.GERMANY, "💶 %.1f t %s an %s verkauft: %s", v.load,
                                Defs.PRODUCT_NAMES[v.loadProduct], Defs.BUYER_NAMES[s.a], euro(earn)));
                    }
                    v.load = 0;
                    v.loadProduct = -1;
                    v.steps.remove(0);
                    break;
                }
                case REPEAT_SELL: {
                    v.steps.remove(0);
                    if (storage[s.a] >= 0.5) {
                        int[] park = world.parking(v.parkSlot);
                        int[] gate = world.traderGate[s.b];
                        v.steps.add(0, new Step(REPEAT_SELL, s.a, s.b, 0));
                        v.steps.add(0, new Step(GOTO, park[0], park[1], 1));
                        v.steps.add(0, new Step(SELL, s.b, 0, 0));
                        v.steps.add(0, new Step(GOTO, gate[0], gate[1], 3));
                        v.steps.add(0, new Step(LOAD, s.a, 0, 0));
                    } else {
                        msg("✅ " + v.name() + ": alles " + Defs.PRODUCT_NAMES[s.a] + " verkauft.");
                    }
                    break;
                }
                case LOAD_FEED: {
                    Tool tr = tool(v.tool);
                    double space = stableCap(s.b, s.a) - stableFeed[s.b][s.a];
                    double amount = Math.min(Math.min(storage[s.a], tr != null ? tr.m().cap : 0), space);
                    v.steps.remove(0);
                    if (amount < 0.005) {
                        // nichts mehr zu tun: Fahrt zum Stall und Abladen überspringen
                        if (!v.steps.isEmpty() && v.steps.get(0).kind == GOTO) v.steps.remove(0);
                        if (!v.steps.isEmpty() && v.steps.get(0).kind == DELIVER) v.steps.remove(0);
                        break;
                    }
                    storage[s.a] -= amount;
                    v.load = amount;
                    v.loadProduct = s.a;
                    break;
                }
                case DELIVER: {
                    if (v.loadProduct >= 0 && v.load > 0) {
                        double space = stableCap(s.a, v.loadProduct) - stableFeed[s.a][v.loadProduct];
                        double put = Math.min(space, v.load);
                        stableFeed[s.a][v.loadProduct] += put;
                        v.load -= put;
                        msg(String.format(Locale.GERMANY, "%s %.1f t %s in den %s gebracht (reicht %.0f Tage).",
                                Defs.S_ICON[s.a], put, Defs.PRODUCT_NAMES[v.loadProduct], Defs.S_NAMES[s.a], stableDays(s.a)));
                        if (v.load < 0.01) {
                            v.load = 0;
                            v.loadProduct = -1;
                        }
                    }
                    v.steps.remove(0);
                    break;
                }
                case REPEAT_FEED: {
                    v.steps.remove(0);
                    double space = stableCap(s.b, s.a) - stableFeed[s.b][s.a];
                    if (storage[s.a] >= 0.5 && space >= 0.01) {
                        int[] park = world.parking(v.parkSlot);
                        int[] gate = world.stableGate[s.b];
                        v.steps.add(0, new Step(REPEAT_FEED, s.a, s.b, 0));
                        v.steps.add(0, new Step(UNLOAD, 0, 0, 0));
                        v.steps.add(0, new Step(GOTO, park[0], park[1], 1));
                        v.steps.add(0, new Step(DELIVER, s.b, 0, 0));
                        v.steps.add(0, new Step(GOTO, gate[0], gate[1], 5));
                        v.steps.add(0, new Step(LOAD_FEED, s.a, s.b, 0));
                    } else {
                        msg("✅ " + v.name() + ": " + Defs.S_NAMES[s.b] + (space < 0.01 ? " ist randvoll (" + Math.round(Defs.S_STORE_DAYS) + " Tage)." : " – kein Futter mehr im Hoflager."));
                    }
                    break;
                }
                case WAIT_OVERLOAD: {
                    if (!helperComing(v)) {
                        v.steps.remove(0);
                        if (!v.steps.isEmpty() && v.steps.get(0).kind == WORK) {
                            insertUnloadTrip(v, world.fields.get(v.steps.get(0).a));
                            msg("⚠ " + v.name() + ": Überladewagen kommt nicht – fährt selbst zum Abladen (teurer Stillstand!).");
                        }
                        break;
                    }
                    if (v.load < capOf(v) * 0.9) {
                        v.steps.remove(0);
                        break;
                    }
                    v.status = "Bunker voll – wartet auf Überladewagen (Stillstand kostet!)";
                    dt = 0;
                    break;
                }
                case FOLLOW: {
                    Vehicle h = vehicle(s.a);
                    if (h == null || (!h.busy() && h.load < 0.05)) {
                        v.steps.remove(0);
                        if (!v.steps.isEmpty() && v.steps.get(0).kind == OVERLOAD) v.steps.remove(0);
                        v.path = null;
                        break;
                    }
                    double dist = Math.hypot(h.x - v.x, h.y - v.y);
                    if (dist < 14) {
                        v.path = null;
                        v.steps.remove(0);
                        break;
                    }
                    v.timer += dt;
                    if (v.path == null || v.pathIdx >= v.path.size() || v.timer > 20) {
                        v.path = world.path((int) (v.x / Defs.TILE), (int) (v.y / Defs.TILE),
                                (int) (h.x / Defs.TILE), (int) (h.y / Defs.TILE));
                        v.pathIdx = 0;
                        v.timer = 0;
                    }
                    v.status = "fährt zum Überladen zu " + h.name();
                    double before = dt;
                    dt = drive(v, dt);
                    if (dt >= before - 1e-9) dt = 0; // nicht hängen bleiben
                    break;
                }
                case OVERLOAD: {
                    Vehicle h = vehicle(s.a);
                    if (h == null) {
                        v.steps.remove(0);
                        break;
                    }
                    // neben der Erntemaschine mitfahren (links)
                    double rad = Math.toRadians(h.heading);
                    float side = h.m().wid / 2 + 3.5f;
                    v.x = h.x + (float) Math.sin(rad) * side;
                    v.y = h.y - (float) Math.cos(rad) * side;
                    v.heading = h.heading;
                    double room = capOf(v) - v.load;
                    double amount = Math.min(Math.min(h.load, room), 0.2 * dt);
                    if (amount > 0) {
                        if (v.load < 0.01) v.loadProduct = h.loadProduct;
                        v.load += amount;
                        h.load -= amount;
                        if (h.load < 1e-6) h.load = 0;
                    }
                    v.status = "überlädt während der Fahrt";
                    boolean hWorking = !h.steps.isEmpty() && (h.steps.get(0).kind == WORK || h.steps.get(0).kind == WAIT_OVERLOAD);
                    boolean full = v.load >= capOf(v) - 0.01;
                    if (full || (!hWorking && h.load < 0.05)) {
                        v.steps.remove(0);
                        if (h.overloader == v.id) h.overloader = -1;
                        if (!h.steps.isEmpty() && h.steps.get(0).kind == WAIT_OVERLOAD) h.steps.remove(0);
                        msg(String.format(Locale.GERMANY, "🚜 %s hat %.1f t übernommen und fährt zum Hof.", v.name(), v.load));
                    }
                    dt = 0;
                    break;
                }
                case LOAD_MANURE: {
                    int st = s.b;
                    double amount = Math.min(capOf(v) - v.load, stableManure[st]);
                    v.steps.remove(0);
                    if (amount < 0.05) break;
                    stableManure[st] -= amount;
                    v.load += amount;
                    v.loadProduct = s.a == Defs.FERT_SLURRY ? -2 : -3;
                    v.status = (s.a == Defs.FERT_SLURRY ? "pumpt Gülle" : "lädt Mist") + " am " + Defs.S_NAMES[st];
                    break;
                }
                case FWORK:
                    dt = forestWork(v, s, dt);
                    break;
                case POLTER: {
                    Forest fo = world.forests.get(s.a);
                    if (v.load > 0 && v.loadProduct >= Defs.WOOD) {
                        fo.polter += v.load;
                        fo.woodSpecies = v.loadProduct - Defs.WOOD;
                    }
                    v.load = 0;
                    v.loadProduct = -1;
                    v.steps.remove(0);
                    break;
                }
                case LOAD_POLTER: {
                    Forest fo = world.forests.get(s.a);
                    double amount = Math.min(capOf(v), fo.polter);
                    v.steps.remove(0);
                    if (amount < 0.1) {
                        abort(v, "⚠ Am Polter von Wald " + fo.number + " liegt kein Holz.");
                        sendHomeSilently(v);
                        return;
                    }
                    fo.polter -= amount;
                    v.load = amount;
                    v.loadProduct = Defs.WOOD + fo.woodSpecies;
                    break;
                }
                case REPEAT_WOOD: {
                    v.steps.remove(0);
                    Forest fo = world.forests.get(s.a);
                    if (fo.polter >= 0.5) {
                        int[] park = world.parking(v.parkSlot);
                        int[] saw = world.traderGate[Defs.B_SAWMILL];
                        v.steps.add(0, new Step(REPEAT_WOOD, s.a, 0, 0));
                        v.steps.add(0, new Step(GOTO, park[0], park[1], 1));
                        v.steps.add(0, new Step(SELL, Defs.B_SAWMILL, 0, 0));
                        v.steps.add(0, new Step(GOTO, saw[0], saw[1], 3));
                        v.steps.add(0, new Step(LOAD_POLTER, s.a, 0, 0));
                        v.steps.add(0, new Step(GOTO, fo.gate[0], fo.gate[1], 7));
                    } else {
                        msg("✅ " + v.name() + ": Polter von Wald " + fo.number + " ist leer – alles verkauft.");
                    }
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
        switch (s.c) {
            case 1:
                return "fährt zum Hof";
            case 2:
                return "fährt zum Feld";
            case 3:
                return "fährt zum Verkaufen";
            case 4:
                return "fährt zum Überladen";
            case 5:
                return "bringt Futter zum Stall";
            case 6:
                return "holt Gülle/Mist am Stall";
            case 7:
                return "fährt zum Wald";
            default:
                return "fährt";
        }
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
            double sp = v.m().speed * world.speedFactor(v.x, v.y);
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
        if (task == Defs.TASK_HARVEST && v.isHarvester()) return Defs.harvestRate(v.m(), f.crop);
        Tool t = tool(v.tool);
        if (t == null) return 0;
        return t.m().rate;
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
        boolean manure = s.b == Defs.TASK_FERTILIZE && s.c != Defs.FERT_MINERAL;
        double yieldHa = 0;
        if (harvest) {
            yieldHa = Defs.YIELD[f.crop] * (f.fertilized ? 1.0 : 0.8);
            double room = capOf(v) - v.load;
            double dpMax = room / (yieldHa * area);
            if (dp > dpMax) dp = Math.max(0, dpMax);
            v.loadProduct = f.crop;
        }
        double appRate = s.c == Defs.FERT_SLURRY ? Defs.APP_SLURRY : Defs.APP_DUNG;
        if (manure) {
            double dpMax = v.load / (appRate * area);
            if (dp > dpMax) dp = Math.max(0, dpMax);
        }
        dp = Math.min(dp, 1 - f.progress);
        f.progress += dp;
        if (harvest) v.load += dp * yieldHa * area;
        if (manure) v.load = Math.max(0, v.load - dp * appRate * area);
        float[] wp = f.workPos(f.progress);
        v.x = wp[0] * Defs.TILE;
        v.y = wp[1] * Defs.TILE;
        v.heading = wp[2];
        String what = s.b == Defs.TASK_FERTILIZE ? Defs.FERT_NAMES[s.c] + " ausbringen" : Defs.TASK_NAMES[s.b];
        v.status = "Feld " + f.number + ": " + what + " " + Math.round(f.progress * 100) + " %";

        if (f.progress >= 1 - 1e-9) {
            int task = f.task;
            f.finishTask();
            msg("✅ Feld " + f.number + " ist fertig " + Defs.TASK_DONE[task] + ".");
            v.steps.remove(0);
            return 0;
        }
        if (manure && v.load < 0.01) {
            int src = manureSource(s.c);
            if (src < 0) {
                msg("⚠ Keine " + Defs.FERT_NAMES[s.c] + " mehr am Stall – Feld " + f.number + " ist zu "
                        + Math.round(f.progress * 100) + " % gedüngt. Später weitermachen.");
                abort(v, null);
                sendHomeSilently(v);
                return 0;
            }
            int[] back = fieldTile(f);
            int[] gate = world.stableGate[src];
            v.steps.add(0, new Step(GOTO, back[0], back[1], 2));
            v.steps.add(0, new Step(LOAD_MANURE, s.c, src, 0));
            v.steps.add(0, new Step(GOTO, gate[0], gate[1], 6));
            return 0;
        }
        if (harvest && v.isHarvester()) {
            double cap = capOf(v);
            if (v.load >= cap * 0.8 && !helperComing(v)) {
                Vehicle helper = findOverloader(v);
                if (helper != null) {
                    startOverload(helper, v);
                    v.overloader = helper.id;
                    msg("🚜 " + v.name() + ": Bunker " + Math.round(v.load / cap * 100) + " % – " + helper.name()
                            + " kommt zum Überladen während der Fahrt.");
                }
            }
            if (v.load >= cap - 1e-6) {
                if (helperComing(v)) {
                    v.steps.add(0, new Step(WAIT_OVERLOAD, v.overloader, 0, 0));
                    msg("⏸ " + v.name() + ": Bunker voll – muss auf den Überladewagen warten.");
                } else {
                    insertUnloadTrip(v, f);
                    v.status = "Bunker voll – fährt selbst zum Abladen";
                    msg("⚠ " + v.name() + ": Bunker voll, kein Traktor mit Kipper frei – fährt selbst zum Abladen (teuer!).");
                }
            }
        } else if (harvest && v.load >= capOf(v) - 1e-6) {
            insertUnloadTrip(v, f);
            v.status = "Ladewagen voll – fährt zum Abladen";
            msg("🚜 " + v.name() + ": Ladewagen voll, fährt zum Abladen.");
        }
        return 0;
    }

    /** Kommt gerade ein Traktor zum Überladen (oder fährt schon nebenher)? */
    public boolean helperComing(Vehicle h) {
        Vehicle helper = vehicle(h.overloader);
        if (helper == null) return false;
        for (Step st : helper.steps) if ((st.kind == FOLLOW || st.kind == OVERLOAD) && st.a == h.id) return true;
        return false;
    }

    /** Stall mit der meisten Gülle bzw. Mist, oder -1 */
    public int manureSource(int mode) {
        int best = -1;
        for (int st = 0; st < Defs.STABLES; st++) {
            if (!stables[st]) continue;
            boolean slurry = Defs.S_SLURRY[st] > 0;
            if ((mode == Defs.FERT_SLURRY) != slurry) continue;
            if (stableManure[st] < 1) continue;
            if (best < 0 || stableManure[st] > stableManure[best]) best = st;
        }
        return best;
    }

    public double manureCap(int s) {
        return Defs.SLURRY_CAP[s] + Defs.DUNG_CAP[s];
    }

    public double manureAvailable(int mode) {
        double sum = 0;
        for (int st = 0; st < Defs.STABLES; st++) {
            if (!stables[st]) continue;
            if ((mode == Defs.FERT_SLURRY) == (Defs.S_SLURRY[st] > 0)) sum += stableManure[st];
        }
        return sum;
    }

    private void insertUnloadTrip(Vehicle v, Field f) {
        int[] park = world.parking(v.parkSlot);
        int[] back = fieldTile(f);
        v.steps.add(0, new Step(GOTO, back[0], back[1], 2));
        v.steps.add(0, new Step(UNLOAD, 0, 0, 0));
        v.steps.add(0, new Step(GOTO, park[0], park[1], 1));
    }

    private int[] fieldTile(Field f) {
        float[] wp = f.workPos(f.progress);
        return new int[]{(int) wp[0], (int) wp[1]};
    }

    /** Freier Traktor, der einen Kipper nehmen kann – der nächste zum Drescher */
    private Vehicle findOverloader(Vehicle h) {
        Vehicle best = null;
        double bestD = Double.MAX_VALUE;
        for (Vehicle v : vehicles) {
            if (v == h || v.busy() || !v.isTractor()) continue;
            if (toolProblem(v, Defs.T_TRAILER) != null) continue;
            if (v.load > 0) continue;
            double d = Math.hypot(v.x - h.x, v.y - h.y);
            if (d < bestD) {
                bestD = d;
                best = v;
            }
        }
        return best;
    }

    private void startOverload(Vehicle helper, Vehicle h) {
        int[] park = world.parking(helper.parkSlot);
        addTrailerSteps(helper, park);
        helper.steps.add(new Step(FOLLOW, h.id, 0, 0));
        helper.steps.add(new Step(OVERLOAD, h.id, 0, 0));
        helper.steps.add(new Step(GOTO, park[0], park[1], 1));
        helper.steps.add(new Step(UNLOAD, 0, 0, 0));
        helper.steps.add(new Step(DROP, 0, 0, 0));
        helper.timer = 0;
    }

    /** Schritte, damit ein Traktor einen Kipper bekommt (falls er keinen dran hat) */
    private void addTrailerSteps(Vehicle v, int[] park) {
        Tool cur = tool(v.tool);
        if (cur == null || cur.m().sub != Defs.T_TRAILER || cur.m().level > v.m().level) {
            Tool want = bestFreeTool(Defs.T_TRAILER, v.m().level, v.id);
            if (want != null) want.reservedBy = v.id;
            v.steps.add(new Step(GOTO, park[0], park[1], 1));
            if (cur != null) v.steps.add(new Step(DROP, 0, 0, 0));
            v.steps.add(new Step(PICKUP, Defs.T_TRAILER, 0, 0));
        } else if (!atHof(v)) {
            v.steps.add(new Step(GOTO, park[0], park[1], 1));
        }
    }

    public double capOf(Vehicle v) {
        if (v.isTractor()) {
            Tool t = tool(v.tool);
            return t != null ? t.m().cap : 0;
        }
        return v.m().cap;
    }

    private Tool reservedTool(Vehicle v, int sub) {
        for (Tool t : tools) if (t.reservedBy == v.id && t.m().sub == sub && t.attachedTo == -1) return t;
        return null;
    }

    private void releaseReservations(Vehicle v) {
        for (Tool t : tools) if (t.reservedBy == v.id) t.reservedBy = -1;
    }

    private Tool bestFreeTool(int sub, int level, int forVehicle) {
        Tool best = null;
        for (Tool t : tools) {
            Defs.Model m = t.m();
            if (m.sub != sub || t.attachedTo != -1 || m.level > level) continue;
            if (t.reservedBy != -1 && t.reservedBy != forVehicle) continue;
            if (best == null || m.rate + m.cap > best.m().rate + best.m().cap) best = t;
        }
        return best;
    }

    private String toolProblem(Vehicle v, int sub) {
        Tool cur = tool(v.tool);
        if (cur != null && cur.m().sub == sub && cur.m().level <= v.m().level) return null;
        if (bestFreeTool(sub, v.m().level, v.id) != null) return null;
        boolean any = false, anyFree = false;
        int minLevel = 99;
        for (Tool t : tools) {
            if (t.m().sub != sub) continue;
            any = true;
            if (t.attachedTo == -1 && t.reservedBy == -1) anyFree = true;
            minLevel = Math.min(minLevel, t.m().level);
        }
        String kind = "„" + Defs.T_KIND_NAMES[sub] + "“";
        if (!any) return "Du hast noch kein Gerät vom Typ " + kind + " (im Shop kaufen).";
        if (minLevel > v.m().level) return v.name() + " (Stufe " + v.m().level + ") ist zu schwach für deine Geräte vom Typ "
                + kind + " – die brauchen mindestens Stufe " + minLevel + ".";
        if (!anyFree) return "Alle deine Geräte vom Typ " + kind + " sind gerade im Einsatz – dafür bräuchtest du ein weiteres.";
        return "Kein passendes Gerät vom Typ " + kind + " frei für Stufe " + v.m().level + ".";
    }

    private void abort(Vehicle v, String message) {
        for (Forest fo : world.forests) if (fo.busyBy == v.id) {
            fo.busyBy = -1;
            if (fo.progress <= 0) fo.task = -1;
        }
        for (Field f : world.fields) if (f.busyBy == v.id) {
            f.busyBy = -1;
            if (f.progress <= 0) {
                f.task = -1;
                f.taskCrop = -1;
            }
        }
        v.steps.clear();
        v.path = null;
        v.timer = 0;
        releaseReservations(v);
        if (message != null) msg(message);
    }

    private void sendHomeSilently(Vehicle v) {
        int[] park = world.parking(v.parkSlot);
        v.steps.add(new Step(GOTO, park[0], park[1], 1));
        if (v.load > 0) v.steps.add(new Step(UNLOAD, 0, 0, 0));
        if (v.tool >= 0) v.steps.add(new Step(DROP, 0, 0, 0));
    }

    // --------------------------------------------------------------- Aufträge

    public int toolFor(int task, int crop) {
        if (task == Defs.TASK_CULTIVATE) return Defs.T_GRUBBER;
        if (task == Defs.TASK_SOW) return crop == Defs.POTATO ? Defs.T_PLANTER : Defs.T_SEEDER;
        if (task == Defs.TASK_FERTILIZE) {
            if (crop == Defs.FERT_SLURRY) return Defs.T_SLURRY;
            if (crop == Defs.FERT_DUNG) return Defs.T_MANURE;
            return Defs.T_SPREADER;
        }
        if (task == Defs.TASK_MOW) return Defs.T_MOWER;
        if (task == Defs.TASK_HARVEST) return Defs.T_LOADER;
        return -1;
    }

    public double taskCost(Field f, int task, int crop) {
        if (f.progress > 0) return 0;
        if (task == Defs.TASK_SOW) return Defs.SEED_COST[crop] * f.hectares();
        if (task == Defs.TASK_FERTILIZE && crop == Defs.FERT_MINERAL) return Defs.FERTILIZER_COST * f.hectares();
        return 0;
    }

    public String checkFieldJob(Vehicle v, Field f, int task, int crop) {
        if (!f.owned) return "Das Feld gehört dir nicht.";
        if (f.pv) return "Auf dem Feld steht ein Solarpark.";
        if (fireField == f.index) return "Das Feld brennt gerade!";
        if (!f.canDo(task)) return Defs.TASK_NAMES[task] + " geht auf diesem Feld gerade nicht.";
        if (f.busyBy != -1 && f.busyBy != v.id) return "Auf dem Feld arbeitet schon jemand.";
        if (v.busy()) return v.name() + " ist gerade beschäftigt.";
        if (task == Defs.TASK_HARVEST && f.crop == Defs.GRASS && v.isTractor()) {
            String p = toolProblem(v, Defs.T_LOADER);
            if (p != null) return p;
        } else if (task == Defs.TASK_HARVEST) {
            int need = Defs.harvesterFor(f.crop);
            if (!v.isHarvester() || v.m().sub != need) {
                if (f.crop == Defs.GRASS) return "Gemähtes Gras sammelst du mit einem Feldhäcksler oder einem Traktor mit Ladewagen ein.";
                return "Zum Ernten von " + Defs.CROP_NAMES[f.crop] + " brauchst du einen " + Defs.H_KIND_NAMES[need] + ".";
            }
        } else {
            if (!v.isTractor()) return "Dafür brauchst du einen Traktor.";
            String p = toolProblem(v, toolFor(task, crop));
            if (p != null) return p;
        }
        if (task == Defs.TASK_FERTILIZE && crop != Defs.FERT_MINERAL && manureSource(crop) < 0) {
            return crop == Defs.FERT_SLURRY ? "Keine Gülle da – die kommt aus Kuh- und Schweinestall."
                    : "Kein Mist da – der kommt aus Hühner- und Schafstall.";
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
        if (task == Defs.TASK_SOW || task == Defs.TASK_FERTILIZE) f.taskCrop = crop;
        int[] park = world.parking(v.parkSlot);
        if (v.isTractor()) {
            int tt = toolFor(task, crop);
            Tool cur = tool(v.tool);
            if (cur == null || cur.m().sub != tt || cur.m().level > v.m().level) {
                Tool want = bestFreeTool(tt, v.m().level, v.id);
                if (want != null) want.reservedBy = v.id;
                v.steps.add(new Step(GOTO, park[0], park[1], 1));
                if (cur != null) v.steps.add(new Step(DROP, 0, 0, 0));
                v.steps.add(new Step(PICKUP, tt, 0, 0));
            }
        }
        int[] ft = fieldTile(f);
        if (task == Defs.TASK_FERTILIZE && crop != Defs.FERT_MINERAL) {
            int src = manureSource(crop);
            int[] gate = world.stableGate[src];
            v.steps.add(new Step(GOTO, gate[0], gate[1], 6));
            v.steps.add(new Step(LOAD_MANURE, crop, src, 0));
        }
        v.steps.add(new Step(GOTO, ft[0], ft[1], 2));
        v.steps.add(new Step(WORK, f.index, task, crop));
        v.steps.add(new Step(GOTO, park[0], park[1], 1));
        if (task == Defs.TASK_HARVEST) v.steps.add(new Step(UNLOAD, 0, 0, 0));
        if (v.isTractor()) v.steps.add(new Step(DROP, 0, 0, 0));
        msg("👷 Helfer übernimmt: " + v.name() + " – " + (task == Defs.TASK_FERTILIZE ? Defs.FERT_NAMES[crop] + " ausbringen"
                : Defs.TASK_NAMES[task]) + " auf Feld " + f.number);
        return null;
    }

    public Vehicle findVehicleFor(Field f, int task, int crop) {
        Vehicle best = null;
        double bestScore = -1;
        for (Vehicle v : vehicles) {
            if (checkFieldJob(v, f, task, crop) != null) continue;
            double score;
            if (task == Defs.TASK_HARVEST && v.isHarvester()) {
                score = Defs.harvestRate(v.m(), f.crop) * 100;
            } else {
                int sub = toolFor(task, crop);
                Tool cur = tool(v.tool);
                Tool t = cur != null && cur.m().sub == sub ? cur : bestFreeTool(sub, v.m().level, v.id);
                score = (t != null ? t.m().rate : 0) * 100 - v.m().level;
            }
            if (score > bestScore) {
                bestScore = score;
                best = v;
            }
        }
        return best;
    }

    /** Freier Traktor, der einen Kipper nehmen kann (größter Kipper zuerst) */
    public Vehicle findTransportTractor() {
        Vehicle best = null;
        double bestCap = -1;
        for (Vehicle v : vehicles) {
            if (v.busy() || !v.isTractor() || toolProblem(v, Defs.T_TRAILER) != null) continue;
            Tool cur = tool(v.tool);
            Tool t = cur != null && cur.m().sub == Defs.T_TRAILER ? cur : bestFreeTool(Defs.T_TRAILER, v.m().level, v.id);
            double cap = t != null ? t.m().cap : 0;
            if (cap > bestCap) {
                bestCap = cap;
                best = v;
            }
        }
        return best;
    }

    /** Warum kann gerade kein Traktor mit Kipper fahren? */
    public String transportProblem() {
        String reason = "Du hast keinen Traktor.";
        for (Vehicle v : vehicles) {
            if (!v.isTractor()) continue;
            if (v.busy()) {
                reason = "Alle Traktoren sind gerade beschäftigt.";
                continue;
            }
            String p = toolProblem(v, Defs.T_TRAILER);
            if (p != null) reason = p;
        }
        return reason;
    }

    public String startSellJob(Vehicle v, int product, int buyer, boolean repeat) {
        if (!v.isTractor()) return "Zum Verkaufen brauchst du einen Traktor mit Kipper.";
        if (v.busy()) return v.name() + " ist gerade beschäftigt.";
        if (!buys(buyer, product)) return Defs.BUYER_NAMES[buyer] + " kauft kein " + Defs.PRODUCT_NAMES[product] + ".";
        String tp = toolProblem(v, Defs.T_TRAILER);
        if (tp != null) return tp;
        if (storage[product] < 0.5) return "Kein " + Defs.PRODUCT_NAMES[product] + " im Lager.";
        int[] park = world.parking(v.parkSlot);
        addTrailerSteps(v, park);
        if (atHof(v) && v.steps.isEmpty()) v.steps.add(new Step(GOTO, park[0], park[1], 1));
        int[] gate = world.traderGate[buyer];
        v.steps.add(new Step(LOAD, product, 0, 0));
        v.steps.add(new Step(GOTO, gate[0], gate[1], 3));
        v.steps.add(new Step(SELL, buyer, 0, 0));
        v.steps.add(new Step(GOTO, park[0], park[1], 1));
        if (repeat) v.steps.add(new Step(REPEAT_SELL, product, buyer, 0));
        v.steps.add(new Step(DROP, 0, 0, 0));
        msg("👷 Helfer übernimmt: " + v.name() + " fährt " + Defs.PRODUCT_NAMES[product] + " "
                + Defs.BUYER_TO[buyer] + (repeat ? " – bis das Lager leer ist." : " – eine Ladung."));
        return null;
    }

    public String checkFeed(int s, int product) {
        if (!stables[s]) return "Den " + Defs.S_NAMES[s] + " hast du noch nicht.";
        if (Defs.S_FEED[s][product] <= 0) return "Die Tiere im " + Defs.S_NAMES[s] + " fressen kein " + Defs.PRODUCT_NAMES[product] + ".";
        if (storage[product] < 0.5) return "Kein " + Defs.PRODUCT_NAMES[product] + " im Hoflager.";
        if (stableCap(s, product) - stableFeed[s][product] < 0.01) return "Das Futterlager im " + Defs.S_NAMES[s] + " ist randvoll.";
        return null;
    }

    public String startFeedJob(Vehicle v, int s, int product, boolean repeat) {
        if (!v.isTractor()) return "Zum Futter bringen brauchst du einen Traktor mit Kipper.";
        if (v.busy()) return v.name() + " ist gerade beschäftigt.";
        String err = checkFeed(s, product);
        if (err != null) return err;
        String tp = toolProblem(v, Defs.T_TRAILER);
        if (tp != null) return tp;
        int[] park = world.parking(v.parkSlot);
        addTrailerSteps(v, park);
        if (atHof(v) && v.steps.isEmpty()) v.steps.add(new Step(GOTO, park[0], park[1], 1));
        int[] gate = world.stableGate[s];
        v.steps.add(new Step(LOAD_FEED, product, s, 0));
        v.steps.add(new Step(GOTO, gate[0], gate[1], 5));
        v.steps.add(new Step(DELIVER, s, 0, 0));
        v.steps.add(new Step(GOTO, park[0], park[1], 1));
        v.steps.add(new Step(UNLOAD, 0, 0, 0));
        if (repeat) v.steps.add(new Step(REPEAT_FEED, product, s, 0));
        v.steps.add(new Step(DROP, 0, 0, 0));
        msg("👷 Helfer übernimmt: " + v.name() + " bringt " + Defs.PRODUCT_NAMES[product] + " zum " + Defs.S_NAMES[s]
                + (repeat ? " – bis er voll ist." : " – eine Ladung."));
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
        sendHomeSilently(v);
    }

    public void stop(Vehicle v) {
        // wer auf diesen Drescher wartet, soll wieder selbst fahren
        abort(v, null);
        v.status = atHof(v) ? "steht am Hof" : "wartet";
    }

    // ------------------------------------------------------------------ Wald

    public Forest forestByNumber(int nr) {
        for (Forest f : world.forests) if (f.number == nr) return f;
        return null;
    }

    private int[] forestTile(Forest fo) {
        float[] wp = fo.workPos(fo.progress);
        return new int[]{(int) wp[0], (int) wp[1]};
    }

    public int forestTool(int task) {
        if (task == Defs.FT_PLANT) return Defs.T_TREEPLANT;
        if (task == Defs.FT_FORWARD) return Defs.T_FWDTRAILER;
        if (task == Defs.FT_CHIP) return Defs.T_CHIPPER;
        return -1;
    }

    public double forestCost(Forest fo, int task, int species) {
        if (task == Defs.FT_PLANT && fo.progress <= 0) return Defs.SEEDLING_COST[species] * fo.hectares();
        return 0;
    }

    public String checkForestJob(Vehicle v, Forest fo, int task, int species) {
        if (!fo.owned) return "Das Waldstück gehört dir nicht.";
        if (!fo.canDo(task)) return Defs.FT_NAMES[task] + " geht in diesem Wald gerade nicht.";
        if (fo.busyBy != -1 && fo.busyBy != v.id) return "In dem Wald arbeitet schon jemand.";
        if (v.busy()) return v.name() + " ist gerade beschäftigt.";
        if (task == Defs.FT_FELL) {
            if (!v.isForestry() || v.m().sub != Defs.F_FELLER) return "Zum Fällen brauchst du einen Prozessor/Harvester.";
        } else if (task == Defs.FT_FORWARD && v.isForestry()) {
            if (v.m().sub != Defs.F_FORWARDER) return "Rücken geht mit einem Forwarder oder Traktor mit Rückewagen.";
        } else {
            if (!v.isTractor()) {
                return task == Defs.FT_FORWARD ? "Rücken geht mit einem Forwarder oder Traktor mit Rückewagen."
                        : "Dafür brauchst du einen Traktor.";
            }
            String p = toolProblem(v, forestTool(task));
            if (p != null) return p;
        }
        double cost = forestCost(fo, task, species);
        if (cost > 0 && money < cost) return "Zu wenig Geld (" + euro(cost) + " für Setzlinge).";
        return null;
    }

    public String startForestJob(Vehicle v, Forest fo, int task, int species) {
        String err = checkForestJob(v, fo, task, species);
        if (err != null) return err;
        double cost = forestCost(fo, task, species);
        if (cost > 0) {
            money -= cost;
            msg("🌱 Setzlinge gekauft: " + euro(cost));
        }
        fo.busyBy = v.id;
        fo.task = task;
        if (task == Defs.FT_PLANT) fo.taskSpecies = species;
        int[] park = world.parking(v.parkSlot);
        if (v.isTractor()) {
            int tt = forestTool(task);
            Tool cur = tool(v.tool);
            if (cur == null || cur.m().sub != tt || cur.m().level > v.m().level) {
                Tool want = bestFreeTool(tt, v.m().level, v.id);
                if (want != null) want.reservedBy = v.id;
                v.steps.add(new Step(GOTO, park[0], park[1], 1));
                if (cur != null) v.steps.add(new Step(DROP, 0, 0, 0));
                v.steps.add(new Step(PICKUP, tt, 0, 0));
            }
        }
        int[] ft = forestTile(fo);
        v.steps.add(new Step(GOTO, ft[0], ft[1], 7));
        v.steps.add(new Step(FWORK, fo.index, task, species));
        v.steps.add(new Step(GOTO, park[0], park[1], 1));
        if (task == Defs.FT_CHIP) v.steps.add(new Step(UNLOAD, 0, 0, 0));
        if (v.isTractor()) v.steps.add(new Step(DROP, 0, 0, 0));
        msg("👷 Helfer übernimmt: " + v.name() + " – " + Defs.FT_NAMES[task] + " in Wald " + fo.number);
        return null;
    }

    public Vehicle findVehicleForForest(Forest fo, int task, int species) {
        Vehicle best = null;
        double bestScore = -1;
        for (Vehicle v : vehicles) {
            if (checkForestJob(v, fo, task, species) != null) continue;
            double score = v.isForestry() ? v.m().rate * 100 : 1;
            if (v.isTractor()) {
                Tool cur = tool(v.tool);
                int sub = forestTool(task);
                Tool t = cur != null && cur.m().sub == sub ? cur : bestFreeTool(sub, v.m().level, v.id);
                score = t != null ? t.m().rate + t.m().cap : 0;
            }
            if (score > bestScore) {
                bestScore = score;
                best = v;
            }
        }
        return best;
    }

    private void finishForest(Forest fo, Vehicle v, String text) {
        fo.task = -1;
        fo.progress = 0;
        fo.busyBy = -1;
        fo.taskSpecies = -1;
        msg(text);
        v.steps.remove(0);
    }

    private double forestWork(Vehicle v, Step s, double dt) {
        Forest fo = world.forests.get(s.a);
        if (fo.busyBy != v.id) {
            abort(v, null);
            return 0;
        }
        double area = fo.hectares();
        int task = s.b;
        if (task == Defs.FT_PLANT || task == Defs.FT_FELL) {
            double rate = task == Defs.FT_FELL ? v.m().rate : (tool(v.tool) != null ? tool(v.tool).m().rate : 0);
            if (rate <= 0) {
                abort(v, "⚠ " + v.name() + " hat nicht das richtige Gerät.");
                return 0;
            }
            double dp = Math.min(rate * dt / 3600.0 / area, 1 - fo.progress);
            if (task == Defs.FT_FELL) {
                double wood = dp * area * Defs.WOOD_YIELD[fo.species] * Math.min(1, fo.growth);
                if (fo.logs < 0.01) fo.woodSpecies = fo.species;
                fo.logs += wood;
                fo.residue += wood * Defs.RESIDUE;
            }
            fo.progress += dp;
            float[] wp = fo.workPos(fo.progress);
            v.x = wp[0] * Defs.TILE;
            v.y = wp[1] * Defs.TILE;
            v.heading = wp[2];
            v.status = "Wald " + fo.number + ": " + Defs.FT_NAMES[task] + " " + Math.round(fo.progress * 100) + " %";
            if (fo.progress >= 1 - 1e-9) {
                if (task == Defs.FT_FELL) {
                    fo.state = Forest.FELLED;
                    fo.growth = 0;
                    finishForest(fo, v, String.format(Locale.GERMANY, "🪓 Wald %d gefällt: %.0f t %s liegen zum Rücken bereit.",
                            fo.number, fo.logs, Defs.SPECIES_NAMES[fo.woodSpecies]));
                } else {
                    fo.state = Forest.GROWING;
                    fo.species = fo.taskSpecies >= 0 ? fo.taskSpecies : s.c;
                    fo.growth = 0;
                    finishForest(fo, v, "🌱 Wald " + fo.number + " ist neu bepflanzt mit " + Defs.SPECIES_NAMES[fo.species] + ".");
                }
            }
            return 0;
        }
        if (task == Defs.FT_FORWARD) {
            double cap = capOf(v);
            double rate = v.isForestry() ? v.m().rate : (tool(v.tool) != null ? tool(v.tool).m().rate : 0);
            double total = fo.logs + v.load;
            if (total < 0.01) {
                finishForest(fo, v, "✅ Wald " + fo.number + ": alles Holz gerückt, liegt am Polter an der Straße.");
                return 0;
            }
            double amount = Math.min(Math.min(rate * dt / 3600.0, fo.logs), cap - v.load);
            fo.logs -= amount;
            v.load += amount;
            v.loadProduct = Defs.WOOD + fo.woodSpecies;
            // Fortschritt grob: wie viel vom Holz schon weg ist
            double startLogs = Math.max(1, fo.logs + v.load + fo.polter);
            fo.progress = Math.max(fo.progress, Math.min(0.99, 1 - (fo.logs + v.load) / startLogs));
            float[] wp = fo.workPos(fo.progress);
            v.x = wp[0] * Defs.TILE;
            v.y = wp[1] * Defs.TILE;
            v.heading = wp[2];
            v.status = String.format(Locale.GERMANY, "Wald %d: lädt Holz (%.0f t)", fo.number, v.load);
            if (v.load >= cap - 0.01 || fo.logs < 0.01) {
                int[] back = forestTile(fo);
                v.steps.add(0, new Step(GOTO, back[0], back[1], 7));
                v.steps.add(0, new Step(POLTER, fo.index, 0, 0));
                v.steps.add(0, new Step(GOTO, fo.gate[0], fo.gate[1], 7));
            }
            return 0;
        }
        // Hacken
        double cap = capOf(v);
        Tool t = tool(v.tool);
        double rate = t != null ? t.m().rate : 0;
        if (fo.residue < 0.01) {
            finishForest(fo, v, "✅ Wald " + fo.number + ": Restholz gehackt.");
            return 0;
        }
        double amount = Math.min(Math.min(rate * dt / 3600.0, fo.residue), cap - v.load);
        fo.residue -= amount;
        v.load += amount;
        v.loadProduct = Defs.CHIPS;
        fo.progress = Math.min(0.99, fo.progress + amount / Math.max(1, fo.residue + amount) * 0.5);
        float[] wp = fo.workPos(fo.progress);
        v.x = wp[0] * Defs.TILE;
        v.y = wp[1] * Defs.TILE;
        v.heading = wp[2];
        v.status = String.format(Locale.GERMANY, "Wald %d: hackt (%.0f t im Container)", fo.number, v.load);
        if (v.load >= cap - 0.01 && fo.residue >= 0.01) {
            int[] park = world.parking(v.parkSlot);
            int[] back = forestTile(fo);
            v.steps.add(0, new Step(GOTO, back[0], back[1], 7));
            v.steps.add(0, new Step(UNLOAD, 0, 0, 0));
            v.steps.add(0, new Step(GOTO, park[0], park[1], 1));
        }
        return 0;
    }

    /** Fahrzeug, das Holz vom Polter zum Sägewerk fahren kann? null = ja */
    public String checkWood(Vehicle v, Forest fo) {
        if (!fo.owned) return "Das Waldstück gehört dir nicht.";
        if (fo.polter < 0.5) return "Am Polter liegt kein Holz – erst fällen und rücken.";
        if (v.busy()) return v.name() + " ist gerade beschäftigt.";
        if (v.isForestry()) {
            if (v.m().sub == Defs.F_FELLER) return "Ein Prozessor kann kein Holz transportieren.";
            return null;
        }
        if (!v.isTractor()) return "Dafür brauchst du einen Holz-Lkw, Forwarder oder Traktor mit Kurzholzanhänger/Rückewagen.";
        if (toolProblem(v, Defs.T_LOGTRAILER) == null || toolProblem(v, Defs.T_FWDTRAILER) == null) return null;
        return toolProblem(v, Defs.T_LOGTRAILER);
    }

    public Vehicle findWoodVehicle(Forest fo) {
        Vehicle best = null;
        double bestCap = -1;
        for (Vehicle v : vehicles) {
            if (checkWood(v, fo) != null) continue;
            double cap = v.isForestry() ? v.m().cap : 0;
            if (v.isTractor()) {
                Tool t = bestFreeTool(Defs.T_LOGTRAILER, v.m().level, v.id);
                if (t == null) t = bestFreeTool(Defs.T_FWDTRAILER, v.m().level, v.id);
                Tool cur = tool(v.tool);
                if (cur != null && (cur.m().sub == Defs.T_LOGTRAILER || cur.m().sub == Defs.T_FWDTRAILER)) t = cur;
                cap = t != null ? t.m().cap : 0;
            }
            if (cap > bestCap) {
                bestCap = cap;
                best = v;
            }
        }
        return best;
    }

    public String startWoodJob(Vehicle v, Forest fo, boolean repeat) {
        String err = checkWood(v, fo);
        if (err != null) return err;
        int[] park = world.parking(v.parkSlot);
        if (v.isTractor()) {
            Tool cur = tool(v.tool);
            boolean ok = cur != null && (cur.m().sub == Defs.T_LOGTRAILER || cur.m().sub == Defs.T_FWDTRAILER)
                    && cur.m().level <= v.m().level;
            if (!ok) {
                int sub = toolProblem(v, Defs.T_LOGTRAILER) == null ? Defs.T_LOGTRAILER : Defs.T_FWDTRAILER;
                Tool want = bestFreeTool(sub, v.m().level, v.id);
                if (want != null) want.reservedBy = v.id;
                v.steps.add(new Step(GOTO, park[0], park[1], 1));
                if (cur != null) v.steps.add(new Step(DROP, 0, 0, 0));
                v.steps.add(new Step(PICKUP, sub, 0, 0));
            }
        }
        int[] saw = world.traderGate[Defs.B_SAWMILL];
        v.steps.add(new Step(GOTO, fo.gate[0], fo.gate[1], 7));
        v.steps.add(new Step(LOAD_POLTER, fo.index, 0, 0));
        v.steps.add(new Step(GOTO, saw[0], saw[1], 3));
        v.steps.add(new Step(SELL, Defs.B_SAWMILL, 0, 0));
        v.steps.add(new Step(GOTO, park[0], park[1], 1));
        if (repeat) v.steps.add(new Step(REPEAT_WOOD, fo.index, 0, 0));
        if (v.isTractor()) v.steps.add(new Step(DROP, 0, 0, 0));
        msg("👷 Helfer übernimmt: " + v.name() + " fährt Holz von Wald " + fo.number + " zum Sägewerk"
                + (repeat ? " – bis der Polter leer ist." : " – eine Ladung."));
        return null;
    }

    public String buyForest(Forest fo) {
        if (fo.owned) return "Gehört dir schon.";
        if (fo.owner >= 0) return "Der Wald gehört schon " + farms[fo.owner].name + ".";
        if (money < fo.price()) return "Zu wenig Geld.";
        money -= fo.price();
        fo.owned = true;
        fo.owner = cur;
        if (fo.growth > 1) fo.growth = 1;
        return null;
    }

    // ----------------------------------------------------------------- Kaufen

    public String buy(int modelId) {
        Defs.Model m = Defs.model(modelId);
        if (money < m.price) return "Zu wenig Geld.";
        money -= m.price;
        if (m.kind == Defs.K_TOOL) addTool(modelId);
        else addVehicle(modelId);
        return null;
    }

    public String buyStable(int s) {
        if (stables[s]) return "Den hast du schon.";
        if (money < Defs.S_PRICE[s]) return "Zu wenig Geld.";
        money -= Defs.S_PRICE[s];
        stables[s] = true;
        return null;
    }

    public String buyField(Field f) {
        if (f.owned) return "Gehört dir schon.";
        if (f.owner >= 0) return "Das Feld gehört schon " + farms[f.owner].name + ".";
        if (money < f.price()) return "Zu wenig Geld.";
        if (fireField == f.index) return "Das Feld brennt gerade.";
        money -= f.price();
        f.owned = true;
        f.owner = cur;
        if (f.growth > 1) f.growth = 1;
        return null;
    }

    /** which: 0 Wohnhaus, 1 Halle, 2+s Stall s */
    public String buyPvRoof(int which) {
        if (which == 0 && pvHouse || which == 1 && pvHall || which >= 2 && pvStable[which - 2]) return "Da ist schon PV drauf.";
        if (which >= 2 && !stables[which - 2]) return "Den Stall hast du noch nicht.";
        if (money < Defs.PV_ROOF_PRICE) return "Zu wenig Geld.";
        money -= Defs.PV_ROOF_PRICE;
        if (which == 0) pvHouse = true;
        else if (which == 1) pvHall = true;
        else pvStable[which - 2] = true;
        return null;
    }

    public double solarParkPrice(Field f) {
        return Math.round(f.hectares() * Defs.PV_FIELD_PRICE_HA / 1000.0) * 1000.0;
    }

    public String buildSolarPark(Field f) {
        if (!f.owned) return "Das Feld gehört dir nicht.";
        if (f.pv) return "Da steht schon ein Solarpark.";
        if (f.busyBy != -1) return "Auf dem Feld wird gerade gearbeitet.";
        if (fireField == f.index) return "Das Feld brennt gerade.";
        double price = solarParkPrice(f);
        if (money < price) return "Zu wenig Geld (" + euro(price) + ").";
        money -= price;
        f.pv = true;
        f.task = -1;
        f.progress = 0;
        return null;
    }

    public String buildWind(int tx, int ty) {
        if (!world.canBuildWind(tx, ty)) return "Hier ist kein Platz – ein Windrad braucht freie Wiese (3×3 Felder).";
        if (money < Defs.WIND_PRICE) return "Zu wenig Geld (" + euro(Defs.WIND_PRICE) + ").";
        money -= Defs.WIND_PRICE;
        world.buildWind(tx, ty);
        winds.add(new int[]{tx, ty});
        return null;
    }

    public String sellVehicle(Vehicle v) {
        if (v.busy()) return "Erst anhalten.";
        if (vehicles.size() <= 1) return "Dein letztes Fahrzeug behältst du lieber.";
        Tool t = tool(v.tool);
        if (t != null) t.attachedTo = -1;
        money += v.m().price * 0.5;
        vehicles.remove(v);
        return null;
    }

    public String sellTool(Tool t) {
        if (t.attachedTo != -1 || t.reservedBy != -1) return "Das Gerät ist gerade im Einsatz.";
        money += t.m().price * 0.5;
        tools.remove(t);
        return null;
    }

    // ------------------------------------------------------------- Speichern

    public JSONObject toJson() throws JSONException {
        int back = cur;
        syncOwner();
        JSONObject o = new JSONObject();
        o.put("v", 6);
        o.put("seed", world.seed);
        o.put("mode", mode);
        o.put("farms", farms.length);
        o.put("time", time);
        o.put("speed", speedIdx);
        o.put("nextId", nextId);
        o.put("lastDay", lastDay);
        o.put("bonusB", bonusBuyer);
        o.put("bonusP", bonusProduct);
        o.put("bonusF", bonusFactor);
        o.put("bonusU", bonusUntil);
        o.put("wType", weatherType);
        o.put("wSun", weatherSun);
        o.put("wWind", weatherWind);
        o.put("snow", snowCover);
        JSONArray pf = new JSONArray();
        for (int b = 0; b < Defs.BUYERS; b++) for (int p = 0; p < Defs.PRODUCTS; p++) pf.put(priceFactor[b][p]);
        o.put("prices", pf);
        JSONArray foa = new JSONArray();
        for (Forest fo : world.forests) {
            JSONObject j = new JSONObject();
            j.put("ow", fo.owner);
            j.put("s", fo.state);
            j.put("sp", fo.species);
            j.put("g", fo.growth);
            j.put("l", fo.logs);
            j.put("p", fo.polter);
            j.put("r", fo.residue);
            j.put("ws", fo.woodSpecies);
            j.put("t", fo.task);
            j.put("pr", fo.progress);
            j.put("b", fo.busyBy);
            j.put("ts", fo.taskSpecies);
            foa.put(j);
        }
        o.put("forests", foa);
        JSONArray fa = new JSONArray();
        for (Field f : world.fields) {
            JSONObject j = new JSONObject();
            j.put("ow", f.owner);
            j.put("s", f.state);
            j.put("c", f.crop);
            j.put("g", f.growth);
            j.put("f", f.fertilized);
            j.put("m", f.mowed);
            j.put("pv", f.pv);
            j.put("bx", f.burnX);
            j.put("by", f.burnY);
            j.put("br", f.burnR);
            j.put("i", f.idleDays);
            j.put("t", f.task);
            j.put("tc", f.taskCrop);
            j.put("p", f.progress);
            j.put("b", f.busyBy);
            fa.put(j);
        }
        o.put("fields", fa);
        o.put("fire", new JSONArray().put(fireField).put(fireX).put(fireY).put(fireR).put(firePower));
        JSONArray farmsA = new JSONArray();
        for (int p = 0; p < farms.length; p++) {
            select(p);
            farmsA.put(farmJson());
        }
        o.put("farmsData", farmsA);
        select(back);
        return o;
    }

    private JSONObject farmJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", farms[cur].name);
        o.put("money", money);
        o.put("eToday", energyToday);
        o.put("energy", lastEnergy);
        o.put("pvHouse", pvHouse);
        o.put("pvHall", pvHall);
        JSONArray sm = new JSONArray();
        for (int s = 0; s < Defs.STABLES; s++) sm.put(stableManure[s]);
        o.put("manure", sm);
        JSONArray st = new JSONArray();
        for (int i = 0; i < Defs.PRODUCTS; i++) st.put(storage[i]);
        o.put("storage", st);
        JSONArray sb = new JSONArray(), sf = new JSONArray(), sp = new JSONArray();
        for (int s = 0; s < Defs.STABLES; s++) {
            sb.put(stables[s]);
            sp.put(pvStable[s]);
            for (int p = 0; p < 4; p++) sf.put(stableFeed[s][p]);
        }
        o.put("stables", sb);
        o.put("stableFeed", sf);
        o.put("pvStable", sp);
        JSONArray wa = new JSONArray();
        for (int[] w : winds) wa.put(new JSONArray().put(w[0]).put(w[1]));
        o.put("winds", wa);
        JSONArray va = new JSONArray();
        for (Vehicle v : vehicles) {
            JSONObject j = new JSONObject();
            j.put("id", v.id);
            j.put("model", v.model);
            j.put("slot", v.parkSlot);
            j.put("x", v.x);
            j.put("y", v.y);
            j.put("h", v.heading);
            j.put("tool", v.tool);
            j.put("load", v.load);
            j.put("lp", v.loadProduct);
            j.put("timer", v.timer);
            j.put("ol", v.overloader);
            j.put("lf", v.lentFrom);
            j.put("st", v.status);
            JSONArray sa = new JSONArray();
            for (Step s : v.steps) sa.put(new JSONArray().put(s.kind).put(s.a).put(s.b).put(s.c));
            j.put("steps", sa);
            va.put(j);
        }
        o.put("vehicles", va);
        JSONArray ta = new JSONArray();
        for (Tool t : tools) ta.put(new JSONArray().put(t.id).put(t.model).put(t.attachedTo).put(t.reservedBy));
        o.put("tools", ta);
        return o;
    }

    public static Game fromJson(JSONObject o) throws JSONException {
        int v = o.optInt("v", 1);
        if (v < 5) throw new JSONException("alter Spielstand");
        int nFarms = v >= 6 ? o.getInt("farms") : 1;
        Game g = new Game(nFarms);
        g.world = new World(o.getLong("seed"), nFarms);
        g.applyState(o);
        return g;
    }

    /** Zustand übernehmen (beim Laden und auf Mitspieler-Handys bei jedem Update vom Gastgeber) */
    public void applyState(JSONObject o) throws JSONException {
        int v = o.optInt("v", 5);
        int back = cur;
        mode = o.optInt("mode", MODE_SHARED);
        time = o.getDouble("time");
        speedIdx = o.optInt("speed", 3);
        nextId = o.getInt("nextId");
        lastDay = o.optInt("lastDay", 0);
        bonusBuyer = o.optInt("bonusB", -1);
        bonusProduct = o.optInt("bonusP", -1);
        bonusFactor = o.optDouble("bonusF", 1);
        bonusUntil = o.optInt("bonusU", 0);
        weatherType = o.optInt("wType", 0);
        weatherSun = o.optDouble("wSun", 0.7);
        weatherWind = o.optDouble("wWind", 0.7);
        snowCover = o.optDouble("snow", 0);
        JSONArray pf = o.getJSONArray("prices");
        for (int b = 0; b < Defs.BUYERS; b++)
            for (int p = 0; p < Defs.PRODUCTS; p++) priceFactor[b][p] = pf.getDouble(b * Defs.PRODUCTS + p);
        JSONArray fire = o.optJSONArray("fire");
        if (fire != null && muted) {
            fireField = fire.getInt(0);
            fireX = (float) fire.getDouble(1);
            fireY = (float) fire.getDouble(2);
            fireR = (float) fire.getDouble(3);
            firePower = (float) fire.getDouble(4);
        }
        // alte Fahrzeuge merken (Wege behalten)
        java.util.Map<Integer, Vehicle> old = new java.util.HashMap<>();
        for (Farm f : farms) for (Vehicle ov : f.vehicles) old.put(ov.id, ov);
        JSONArray farmsA = v >= 6 ? o.getJSONArray("farmsData") : new JSONArray().put(o);
        for (int p = 0; p < farms.length && p < farmsA.length(); p++) {
            select(p);
            applyFarm(farmsA.getJSONObject(p), old);
        }
        storeFarm();
        JSONArray foa = o.getJSONArray("forests");
        for (int i = 0; i < foa.length() && i < world.forests.size(); i++) {
            JSONObject j = foa.getJSONObject(i);
            Forest fo = world.forests.get(i);
            fo.owner = j.has("ow") ? j.getInt("ow") : (j.optBoolean("o", false) ? 0 : -1);
            fo.state = j.getInt("s");
            fo.species = j.getInt("sp");
            fo.growth = j.getDouble("g");
            fo.logs = j.getDouble("l");
            fo.polter = j.getDouble("p");
            fo.residue = j.getDouble("r");
            fo.woodSpecies = j.getInt("ws");
            fo.task = j.getInt("t");
            fo.progress = j.getDouble("pr");
            fo.busyBy = j.getInt("b");
            fo.taskSpecies = j.getInt("ts");
        }
        JSONArray fa = o.getJSONArray("fields");
        for (int i = 0; i < fa.length() && i < world.fields.size(); i++) {
            JSONObject j = fa.getJSONObject(i);
            Field f = world.fields.get(i);
            f.owner = j.has("ow") ? j.getInt("ow") : (j.optBoolean("o", false) ? 0 : -1);
            f.state = j.getInt("s");
            f.crop = j.getInt("c");
            f.growth = j.getDouble("g");
            f.fertilized = j.getBoolean("f");
            f.mowed = j.optBoolean("m", false);
            f.pv = j.optBoolean("pv", false);
            f.burnX = (float) j.optDouble("bx", 0);
            f.burnY = (float) j.optDouble("by", 0);
            f.burnR = (float) j.optDouble("br", 0);
            f.idleDays = j.optDouble("i", 0);
            f.task = j.getInt("t");
            f.taskCrop = j.getInt("tc");
            f.progress = j.getDouble("p");
            f.busyBy = j.getInt("b");
        }
        cur = back;
        load();
        world.useFarm(cur);
        for (Field f : world.fields) f.owned = f.owner == cur;
        for (Forest fo : world.forests) fo.owned = fo.owner == cur;
    }

    private void applyFarm(JSONObject o, java.util.Map<Integer, Vehicle> old) throws JSONException {
        farms[cur].name = o.optString("name", farms[cur].name);
        money = o.getDouble("money");
        energyToday = o.optDouble("eToday", 0);
        lastEnergy = o.optDouble("energy", 0);
        pvHouse = o.optBoolean("pvHouse", false);
        pvHall = o.optBoolean("pvHall", false);
        JSONArray sm = o.getJSONArray("manure");
        for (int s = 0; s < Defs.STABLES; s++) stableManure[s] = sm.getDouble(s);
        JSONArray st = o.getJSONArray("storage");
        for (int i = 0; i < Defs.PRODUCTS; i++) storage[i] = st.getDouble(i);
        JSONArray sb = o.getJSONArray("stables"), sf = o.getJSONArray("stableFeed"), sp = o.getJSONArray("pvStable");
        for (int s = 0; s < Defs.STABLES; s++) {
            stables[s] = sb.getBoolean(s);
            pvStable[s] = sp.getBoolean(s);
            for (int p = 0; p < 4; p++) stableFeed[s][p] = sf.getDouble(s * 4 + p);
        }
        winds.clear();
        JSONArray wa = o.getJSONArray("winds");
        for (int i = 0; i < wa.length(); i++) {
            JSONArray w = wa.getJSONArray(i);
            int[] p = {w.getInt(0), w.getInt(1)};
            winds.add(p);
            world.buildWind(p[0], p[1]);
        }
        vehicles.clear();
        JSONArray va = o.getJSONArray("vehicles");
        for (int i = 0; i < va.length(); i++) {
            JSONObject j = va.getJSONObject(i);
            int id = j.getInt("id");
            Vehicle ov = old.get(id);
            Vehicle v = new Vehicle();
            v.id = id;
            v.model = j.getInt("model");
            v.parkSlot = j.getInt("slot");
            v.x = (float) j.getDouble("x");
            v.y = (float) j.getDouble("y");
            v.heading = (float) j.getDouble("h");
            v.tool = j.getInt("tool");
            v.load = j.getDouble("load");
            v.loadProduct = j.getInt("lp");
            v.timer = j.optDouble("timer", 0);
            v.overloader = j.optInt("ol", -1);
            v.lentFrom = j.optInt("lf", -1);
            if (j.has("st")) v.status = j.getString("st");
            JSONArray sa = j.getJSONArray("steps");
            for (int k = 0; k < sa.length(); k++) {
                JSONArray s = sa.getJSONArray(k);
                v.steps.add(new Step(s.getInt(0), s.getInt(1), s.getInt(2), s.getInt(3)));
            }
            if (ov != null && ov.path != null && !ov.steps.isEmpty() && !v.steps.isEmpty()) {
                Step a = ov.steps.get(0), b = v.steps.get(0);
                if (a.kind == b.kind && a.a == b.a && a.b == b.b && a.c == b.c) {
                    v.path = ov.path;
                    v.pathIdx = ov.pathIdx;
                    // sanft statt springen, wenn der Unterschied klein ist
                    if (Math.hypot(ov.x - v.x, ov.y - v.y) < 25) {
                        v.x = ov.x + (v.x - ov.x) * 0.5f;
                        v.y = ov.y + (v.y - ov.y) * 0.5f;
                    }
                }
            }
            vehicles.add(v);
        }
        tools.clear();
        JSONArray ta = o.getJSONArray("tools");
        for (int i = 0; i < ta.length(); i++) {
            JSONArray a = ta.getJSONArray(i);
            Tool t = new Tool();
            t.id = a.getInt(0);
            t.model = a.getInt(1);
            t.attachedTo = a.getInt(2);
            t.reservedBy = a.length() > 3 ? a.getInt(3) : -1;
            tools.add(t);
        }
    }
}
