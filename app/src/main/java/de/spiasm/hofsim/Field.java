package de.spiasm.hofsim;

/** Ein Feld (Rechteck aus Kacheln) und sein Zustand. */
public class Field {
    public static final int STUBBLE = 0, CULTIVATED = 1, GROWING = 2;

    public final int index;
    public int number;
    public final int x, y, w, h;

    public boolean owned;
    /** Besitzer: -1 niemand, sonst Hof-Nummer (Mehrspieler) */
    public int owner = -1;
    public int state = STUBBLE;
    /** Frucht (oder -1) – bei Stoppeln die zuletzt geerntete Frucht */
    public int crop = Defs.WHEAT;
    public double growth;
    public boolean fertilized;
    /** Gras gemäht und liegt im Schwad */
    public boolean mowed;
    /** Solarpark statt Ackerbau */
    public boolean pv;
    /** verbrannte Fläche (Kreis in Metern), r = 0: nichts verbrannt */
    public float burnX, burnY, burnR;
    /** Fremde Felder: Tage seit Ernte */
    public double idleDays;

    /** laufende Arbeit */
    public int task = -1;
    public int taskCrop = -1;
    public double progress;
    public int busyBy = -1;

    public Field(int index, int x, int y, int w, int h) {
        this.index = index;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public double hectares() {
        return w * h * Defs.TILE * Defs.TILE / 10000.0;
    }

    public double price() {
        return Math.round(hectares() * Defs.FIELD_PRICE_HA / 1000.0) * 1000.0;
    }

    public boolean ripe() {
        return state == GROWING && growth >= 1;
    }

    /** Welche Arbeiten sind gerade möglich? */
    public boolean canDo(int t) {
        if (pv) return false;
        if (task != -1 && progress > 0) return t == task;
        switch (t) {
            case Defs.TASK_CULTIVATE:
                return state == STUBBLE || (state == GROWING && crop == Defs.GRASS);
            case Defs.TASK_SOW:
                return state == CULTIVATED;
            case Defs.TASK_FERTILIZE:
                return state == GROWING && !fertilized && growth < Defs.FERTILIZE_UNTIL;
            case Defs.TASK_MOW:
                return ripe() && crop == Defs.GRASS && !mowed;
            case Defs.TASK_HARVEST:
                return ripe() && (crop != Defs.GRASS || mowed);
            default:
                return false;
        }
    }

    public String stateText() {
        if (pv) return "☀ Solarpark";
        String s;
        switch (state) {
            case STUBBLE:
                s = crop >= 0 ? "Stoppeln (" + Defs.CROP_NAMES[crop] + " abgeerntet)" : "brach";
                if (burnR > 0) s += ", teilweise abgebrannt";
                break;
            case CULTIVATED:
                s = "gegrubbert – bereit zum Säen";
                break;
            default:
                if (growth >= 1 && crop == Defs.GRASS) s = mowed ? "Gras gemäht – bereit zum Einsammeln" : "Gras – bereit zum Mähen!";
                else if (growth >= 1) s = Defs.CROP_NAMES[crop] + " – erntereif!";
                else s = Defs.CROP_NAMES[crop] + " – wächst (" + Math.round(growth * 100) + " %)";
                if (fertilized) s += ", gedüngt";
        }
        if (task != -1) {
            s += "\n" + Defs.TASK_NAMES[task] + ": " + Math.round(progress * 100) + " % erledigt";
        }
        return s;
    }

    /** Zustand nach Abschluss einer Arbeit anwenden */
    public void finishTask() {
        switch (task) {
            case Defs.TASK_CULTIVATE:
                state = CULTIVATED;
                mowed = false;
                burnR = 0;
                fertilized = false;
                growth = 0;
                break;
            case Defs.TASK_SOW:
                state = GROWING;
                crop = taskCrop;
                growth = 0;
                fertilized = false;
                break;
            case Defs.TASK_FERTILIZE:
                fertilized = true;
                break;
            case Defs.TASK_MOW:
                mowed = true;
                break;
            case Defs.TASK_HARVEST:
                mowed = false;
                if (crop == Defs.GRASS) {
                    state = GROWING;
                    growth = 0.25;
                } else {
                    state = STUBBLE;
                    growth = 0;
                }
                fertilized = false;
                break;
            default:
                break;
        }
        task = -1;
        taskCrop = -1;
        progress = 0;
        busyBy = -1;
        idleDays = 0;
    }

    /** Position (Kachelkoordinaten, Kommazahl) der Maschine bei Fortschritt p: Bahn für Bahn hin und her. */
    public float[] workPos(double p) {
        p = Math.max(0, Math.min(0.99999, p));
        double lanes = h;
        int lane = (int) Math.floor(p * lanes);
        double f = p * lanes - lane;
        boolean right = lane % 2 == 0;
        float px = (float) (x + 0.3 + (right ? f : 1 - f) * (w - 0.6));
        float py = y + lane + 0.5f;
        return new float[]{px, py, right ? 0 : 180};
    }
}
