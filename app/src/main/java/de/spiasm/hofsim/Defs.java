package de.spiasm.hofsim;

/** Alle festen Werte: Früchte, Maschinen, Preise, Geschwindigkeiten. */
public final class Defs {
    private Defs() {
    }

    public static final float TILE = 10f;          // 1 Kachel = 10 m
    public static final int MAP = 128;             // 128 x 128 Kacheln = 1,28 km
    public static final double HELPER_WAGE = 20;   // €/h
    public static final double START_MONEY = 200000;
    public static final double FIELD_PRICE_HA = 35000;

    // ------------------------------------------------------------- Früchte
    public static final int GRASS = 0, WHEAT = 1, POTATO = 2, MAIZE = 3;
    public static final String[] CROP_NAMES = {"Wiese/Gras", "Weizen", "Kartoffeln", "Mais"};
    public static final String[] PRODUCT_NAMES = {"Gras-Silage", "Weizen", "Kartoffeln", "Mais-Silage"};
    /** Wachstumsdauer in Tagen */
    public static final double[] GROW_DAYS = {12, 28, 30, 32};
    /** Ertrag in t/ha (gedüngt; ungedüngt 80 %) */
    public static final double[] YIELD = {9, 8.5, 42, 48};
    /** Saatgut €/ha */
    public static final double[] SEED_COST = {130, 110, 1300, 230};
    /** Grundpreis €/t */
    public static final double[] BASE_PRICE = {40, 230, 190, 48};
    /** Wer kauft: 0 = Landhandel, 1 = Biogasanlage */
    public static final int[] BUYER = {1, 0, 0, 1};
    public static final double FERTILIZER_COST = 180; // €/ha
    public static final double FERTILIZE_UNTIL = 0.6; // Düngen bis 60 % Wachstum

    // ------------------------------------------------- Maschinen-Katalog
    public static final int K_TRACTOR = 0, K_TOOL = 1, K_HARVESTER = 2;
    /** Geräte-Arten */
    public static final int T_GRUBBER = 0, T_SEEDER = 1, T_PLANTER = 2, T_SPREADER = 3, T_TRAILER = 4, T_MOWER = 5, T_LOADER = 6;
    public static final String[] T_KIND_NAMES = {"Grubber", "Sämaschine", "Kartoffel-Legemaschine", "Düngerstreuer", "Kipper",
            "Mähwerk", "Ladewagen"};
    /** Erntemaschinen-Arten */
    public static final int H_COMBINE = 0, H_CHOPPER = 1, H_POTATO = 2;
    public static final String[] H_KIND_NAMES = {"Mähdrescher", "Feldhäcksler", "Kartoffelroder"};

    public static final class Model {
        public final int id, kind, sub, level;
        public final String name, info;
        public final double price, rate, rate2, cap, speed, diesel;
        public final float len, wid;
        public final int color;

        Model(int id, int kind, int sub, int level, String name, String info, double price, double rate, double rate2,
              double cap, double speed, double diesel, float len, float wid, int color) {
            this.id = id;
            this.kind = kind;
            this.sub = sub;
            this.level = level;
            this.name = name;
            this.info = info;
            this.price = price;
            this.rate = rate;
            this.rate2 = rate2;
            this.cap = cap;
            this.speed = speed;
            this.diesel = diesel;
            this.len = len;
            this.wid = wid;
            this.color = color;
        }
    }

    private static int mid = 0;

    private static Model tractor(String name, int ps, int level, double price, double kmh, double diesel, float len, float wid, int color) {
        return new Model(mid++, K_TRACTOR, 0, level, name, ps + " PS • Leistungsstufe " + level + " • " + (int) kmh + " km/h",
                price, 0, 0, 0, kmh / 3.6, diesel, len, wid, color);
    }

    private static Model tool(int sub, String name, String size, int level, double price, double rate, double cap, float len, float wid) {
        String info = size + " • braucht Stufe " + level + (sub == T_TRAILER ? "" : " • " + fmt(rate) + " ha/h");
        return new Model(mid++, K_TOOL, sub, level, name, info, price, rate, 0, cap, 0, 0, len, wid, 0);
    }

    private static Model harvester(int sub, String name, double price, double rate, double rate2, double tank,
                                   double kmh, double diesel, float len, float wid, int color) {
        String info = (sub == H_CHOPPER ? "Mais " + fmt(rate) + " ha/h, Gras " + fmt(rate2) + " ha/h" : fmt(rate) + " ha/h")
                + " • Bunker " + (int) tank + " t";
        return new Model(mid++, K_HARVESTER, sub, 0, name, info, price, rate, rate2, tank, kmh / 3.6, diesel, len, wid, color);
    }

    private static String fmt(double v) {
        return (v == Math.floor(v) ? String.valueOf((int) v) : String.valueOf(v)).replace('.', ',');
    }

    public static final Model[] MODELS = {
            // Traktoren (Leistungsstufe 1–6)
            tractor("Isartaler 70", 70, 1, 25000, 35, 12, 3.8f, 2.0f, 0xFF1E88E5),
            tractor("Vilstal 105", 105, 2, 42000, 40, 18, 4.3f, 2.3f, 0xFF2E7D32),
            tractor("Spies 140", 140, 3, 68000, 40, 25, 4.8f, 2.5f, 0xFFC62828),
            tractor("M.Mayer 190", 190, 4, 105000, 50, 33, 5.2f, 2.6f, 0xFFF9A825),
            tractor("Hofmann 260", 260, 5, 165000, 50, 45, 5.8f, 2.8f, 0xFF33691E),
            tractor("Titan 400", 400, 6, 260000, 50, 65, 6.8f, 3.0f, 0xFF37474F),
            // Grubber
            tool(T_GRUBBER, "Grubber 2,5 m", "2,5 m", 1, 6000, 1.6, 0, 1.8f, 2.5f),
            tool(T_GRUBBER, "Grubber 3,5 m", "3,5 m", 2, 9000, 2.3, 0, 2.0f, 3.5f),
            tool(T_GRUBBER, "Grubber 5 m", "5 m", 4, 18000, 3.4, 0, 2.4f, 5f),
            tool(T_GRUBBER, "Grubber 7 m", "7 m", 6, 32000, 4.8, 0, 2.8f, 7f),
            // Sämaschinen
            tool(T_SEEDER, "Sämaschine 3 m", "3 m", 1, 12000, 2.0, 0, 2.2f, 3f),
            tool(T_SEEDER, "Sämaschine 4 m", "4 m", 3, 22000, 2.8, 0, 2.6f, 4f),
            tool(T_SEEDER, "Sämaschine 6 m", "6 m", 5, 45000, 4.2, 0, 3.0f, 6f),
            // Kartoffel-Legemaschinen
            tool(T_PLANTER, "Legemaschine 2-reihig", "2-reihig", 2, 15000, 0.8, 0, 2.6f, 2.4f),
            tool(T_PLANTER, "Legemaschine 4-reihig", "4-reihig", 4, 38000, 1.6, 0, 3.2f, 3.6f),
            // Düngerstreuer
            tool(T_SPREADER, "Düngerstreuer 800 l", "800 l, 12 m", 1, 4000, 6, 0, 1.6f, 2.0f),
            tool(T_SPREADER, "Düngerstreuer 3000 l", "3000 l, 24 m", 3, 14000, 12, 0, 2.2f, 2.6f),
            // Kipper
            tool(T_TRAILER, "Kipper 6 t", "6 t", 1, 5000, 0, 6, 4.5f, 2.2f),
            tool(T_TRAILER, "Kipper 10 t", "10 t", 2, 9000, 0, 10, 5.5f, 2.4f),
            tool(T_TRAILER, "Kipper 14 t", "14 t", 3, 14000, 0, 14, 6.5f, 2.5f),
            tool(T_TRAILER, "Kipper „Martin“ 20 t", "20 t", 4, 22000, 0, 20, 7.5f, 2.55f),
            tool(T_TRAILER, "Kipper 26 t", "26 t, Tandem", 5, 32000, 0, 26, 8.5f, 2.55f),
            tool(T_TRAILER, "Kipper 33 t", "33 t, Tridem", 6, 45000, 0, 33, 9.5f, 2.55f),
            // Mähwerke (Gras mähen)
            tool(T_MOWER, "Mähwerk 3 m", "3 m", 1, 8000, 2.5, 0, 1.6f, 3.0f),
            tool(T_MOWER, "Mähwerk-Kombination 9 m", "9 m (Front + 2× Heck)", 4, 42000, 7.0, 0, 2.0f, 9.0f),
            // Ladewagen (gemähtes Gras einsammeln)
            tool(T_LOADER, "Ladewagen 25 m³", "25 m³ ≈ 6 t", 2, 28000, 2.5, 6, 7.0f, 2.5f),
            tool(T_LOADER, "Ladewagen 45 m³", "45 m³ ≈ 11 t", 4, 62000, 4.0, 11, 9.0f, 2.55f),
            // Mähdrescher
            harvester(H_COMBINE, "Mähdrescher Ähre 2000", 55000, 1.6, 0, 5, 20, 40, 7.5f, 3.2f, 0xFF2E7D32),
            harvester(H_COMBINE, "Mähdrescher Ähre 3500", 140000, 2.6, 0, 8, 22, 60, 8.5f, 3.5f, 0xFF388E3C),
            harvester(H_COMBINE, "Mähdrescher Ähre 6000", 290000, 3.8, 0, 12, 25, 85, 9.5f, 3.9f, 0xFF1B5E20),
            // Feldhäcksler
            harvester(H_CHOPPER, "Feldhäcksler Häcksel 300", 110000, 1.4, 2.2, 15, 30, 70, 6.5f, 3.0f, 0xFF9E9D24),
            harvester(H_CHOPPER, "Feldhäcksler Häcksel 700", 260000, 2.6, 4.0, 30, 35, 120, 7.5f, 3.4f, 0xFF7CB342),
            // Kartoffelroder
            harvester(H_POTATO, "Kartoffelroder Knolle 1", 60000, 0.35, 0, 5, 20, 35, 7f, 3.0f, 0xFFFFA000),
            harvester(H_POTATO, "Kartoffelroder Knolle 2", 120000, 0.6, 0, 8, 20, 50, 8f, 3.3f, 0xFFEF6C00),
            harvester(H_POTATO, "Kartoffelroder Knolle 4", 280000, 1.1, 0, 15, 25, 75, 9.5f, 3.6f, 0xFFE65100),
    };

    public static Model model(int id) {
        return MODELS[id];
    }

    public static int modelByName(String name) {
        for (Model m : MODELS) if (m.name.equals(name)) return m.id;
        return 0;
    }

    /** Erntemaschinen-Art für eine Frucht */
    public static int harvesterFor(int crop) {
        if (crop == WHEAT) return H_COMBINE;
        if (crop == POTATO) return H_POTATO;
        return H_CHOPPER;
    }

    /** Ernteleistung ha/h einer Erntemaschine für eine Frucht */
    public static double harvestRate(Model m, int crop) {
        if (m.sub == H_CHOPPER && crop == GRASS) return m.rate2;
        return m.rate;
    }

    // ------------------------------------------------------------ Arbeiten
    public static final int TASK_CULTIVATE = 0, TASK_SOW = 1, TASK_FERTILIZE = 2, TASK_HARVEST = 3, TASK_MOW = 4;
    public static final String[] TASK_NAMES = {"Grubbern", "Säen", "Düngen", "Ernten", "Mähen"};
    public static final String[] TASK_DONE = {"gegrubbert", "gesät", "gedüngt", "geerntet", "gemäht"};
    /** Reihenfolge der Arbeiten in Menüs */
    public static final int[] TASK_ORDER = {TASK_CULTIVATE, TASK_SOW, TASK_FERTILIZE, TASK_MOW, TASK_HARVEST};

    // ---------------------------------------------------------------- Ställe
    public static final int S_CHICKEN = 0, S_COW = 1;
    public static final String[] S_NAMES = {"Hühnerstall (1000 Hennen)", "Kuhstall (40 Kühe)"};
    public static final double[] S_PRICE = {90000, 350000};
    /** Futter t/Tag je Produkt {Gras, Weizen, Kartoffeln, Mais} */
    public static final double[][] S_FEED = {{0, 0.12, 0, 0}, {2.0, 0, 0, 0.9}};
    /** Einnahmen €/Tag bei vollem Futter */
    public static final double[] S_INCOME = {126, 538};
    /** Kosten €/Tag (Strom, Tierarzt, …) */
    public static final double[] S_COST = {30, 120};

    // --------------------------------------------------------------- Zeit
    public static final int[] SPEEDS = {0, 1, 10, 60, 300, 1500, 6000};
    public static final String[] MONTHS = {"Jan", "Feb", "Mär", "Apr", "Mai", "Jun", "Jul", "Aug", "Sep", "Okt", "Nov", "Dez"};
}
