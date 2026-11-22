package de.spiasm.hofsim;

/** Alle festen Werte: Früchte, Maschinen, Preise, Geschwindigkeiten. */
public final class Defs {
    private Defs() {
    }

    public static final float TILE = 10f;          // 1 Kachel = 10 m
    public static final int MAP = 96;              // 96 x 96 Kacheln = 960 m
    public static final double HELPER_WAGE = 20;   // €/h
    public static final double START_MONEY = 200000;
    public static final double FIELD_PRICE_HA = 35000;

    // ------------------------------------------------------------- Früchte
    public static final int GRASS = 0, WHEAT = 1, POTATO = 2, MAIZE = 3;
    public static final String[] CROP_NAMES = {"Wiese/Gras", "Weizen", "Kartoffeln", "Mais"};
    public static final String[] PRODUCT_NAMES = {"Gras-Silage", "Weizen", "Kartoffeln", "Mais-Silage"};
    /** Wachstumsdauer in Tagen */
    public static final double[] GROW_DAYS = {45, 110, 120, 130};
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

    // ---------------------------------------------------------- Fahrzeuge
    public static final int V_TRACTOR_S = 0, V_TRACTOR_L = 1, V_COMBINE = 2, V_CHOPPER = 3, V_POTATO = 4;
    public static final String[] V_NAMES = {"Traktor 120 PS", "Traktor 240 PS", "Mähdrescher", "Feldhäcksler", "Kartoffelroder"};
    public static final double[] V_PRICE = {45000, 110000, 120000, 150000, 80000};
    /** Straßentempo m/s */
    public static final double[] V_SPEED = {11.1, 11.1, 6.0, 8.3, 5.5};
    /** Diesel €/h im Einsatz */
    public static final double[] V_DIESEL = {25, 45, 60, 90, 50};
    /** Arbeitsfaktor für gezogene Geräte */
    public static final double[] V_POWER = {1.0, 1.7, 0, 0, 0};
    /** Bunker/Tank t (nur Erntemaschinen) */
    public static final double[] V_TANK = {0, 0, 9, 30, 8};
    /** Größe in m (Länge, Breite) */
    public static final float[][] V_SIZE = {{4.5f, 2.4f}, {5.5f, 2.8f}, {8.5f, 3.6f}, {7f, 3.2f}, {8f, 3.3f}};

    // -------------------------------------------------------------- Geräte
    public static final int T_GRUBBER = 0, T_SEEDER = 1, T_PLANTER = 2, T_SPREADER = 3, T_TRAILER = 4;
    public static final String[] T_NAMES = {"Grubber", "Sämaschine", "Kartoffel-Legemaschine", "Düngerstreuer", "Anhänger 18 t"};
    public static final double[] T_PRICE = {9000, 15000, 18000, 6000, 12000};
    /** Flächenleistung ha/h mit kleinem Traktor */
    public static final double[] T_RATE = {2.0, 2.5, 1.0, 8.0, 0};
    public static final double TRAILER_CAP = 18;

    // ------------------------------------------------------------ Arbeiten
    public static final int TASK_CULTIVATE = 0, TASK_SOW = 1, TASK_FERTILIZE = 2, TASK_HARVEST = 3;
    public static final String[] TASK_NAMES = {"Grubbern", "Säen", "Düngen", "Ernten"};
    public static final String[] TASK_DONE = {"gegrubbert", "gesät", "gedüngt", "geerntet"};

    /** Erntemaschine für eine Frucht */
    public static int harvesterFor(int crop) {
        if (crop == WHEAT) return V_COMBINE;
        if (crop == POTATO) return V_POTATO;
        return V_CHOPPER;
    }

    /** Ernteleistung ha/h */
    public static double harvestRate(int vehicle, int crop) {
        if (vehicle == V_COMBINE) return 2.5;
        if (vehicle == V_POTATO) return 0.6;
        return crop == GRASS ? 3.0 : 2.0;
    }

    public static boolean isTractor(int v) {
        return v == V_TRACTOR_S || v == V_TRACTOR_L;
    }

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
