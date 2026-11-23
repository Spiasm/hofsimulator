package de.spiasm.hofsim;

/** Ein Waldstück (Rechteck aus Kacheln): Bäume wachsen, werden gefällt, gerückt und gehackt. */
public class Forest {
    public static final int CLEAR = 0, GROWING = 1, FELLED = 2;

    public final int index;
    public int number;
    public final int x, y, w, h;
    /** Zufahrt: Straßenkachel am Waldrand, dort liegt der Holzpolter */
    public int[] gate;

    public boolean owned;
    public int state = GROWING;
    public int species = Defs.SPRUCE;
    public double growth;
    /** Holz im Bestand nach dem Fällen (t), Holz am Polter an der Straße (t), Restholz für Hackschnitzel (t) */
    public double logs, polter, residue;
    /** Baumart des liegenden Holzes */
    public int woodSpecies = Defs.SPRUCE;

    public int task = -1;
    public double progress;
    public int busyBy = -1;
    public int taskSpecies = -1;

    public Forest(int index, int x, int y, int w, int h) {
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
        double base = hectares() * Defs.FOREST_PRICE_HA;
        if (state == GROWING) base += hectares() * Defs.WOOD_YIELD[species] * Math.min(1, growth) * 25;
        return Math.round(base / 1000.0) * 1000.0;
    }

    public boolean ripe() {
        return state == GROWING && growth >= 1;
    }

    public boolean canDo(int t) {
        if (task != -1 && progress > 0) return t == task;
        switch (t) {
            case Defs.FT_PLANT:
                return state == CLEAR || (state == FELLED && logs < 0.5 && residue < 0.5);
            case Defs.FT_FELL:
                return state == GROWING && growth >= 0.6;
            case Defs.FT_FORWARD:
                return logs >= 0.5;
            case Defs.FT_CHIP:
                return residue >= 0.5 && state == FELLED;
            default:
                return false;
        }
    }

    public String stateText() {
        String s;
        if (state == CLEAR) s = "Kahlfläche – bereit zum Pflanzen";
        else if (state == FELLED) s = "abgeholzt" + (logs < 0.5 && residue < 0.5 ? " – bereit zum Pflanzen" : "");
        else if (growth >= 1) s = Defs.SPECIES_NAMES[species] + " – hiebreif!";
        else s = Defs.SPECIES_NAMES[species] + " – wächst (" + Math.round(growth * 100) + " %)"
                    + (growth >= 0.6 ? ", Durchforstung möglich" : "");
        if (logs >= 0.1) s += String.format(java.util.Locale.GERMANY, "\nIm Wald liegen %.0f t %s (rücken!)", logs, Defs.SPECIES_NAMES[woodSpecies]);
        if (polter >= 0.1) s += String.format(java.util.Locale.GERMANY, "\nAm Polter an der Straße: %.0f t %s", polter, Defs.SPECIES_NAMES[woodSpecies]);
        if (residue >= 0.1) s += String.format(java.util.Locale.GERMANY, "\nRestholz zum Hacken: %.0f t", residue);
        if (task != -1) s += "\n" + Defs.FT_NAMES[task] + ": " + Math.round(progress * 100) + " % erledigt";
        return s;
    }

    public float[] workPos(double p) {
        p = Math.max(0, Math.min(0.99999, p));
        int lane = (int) Math.floor(p * h);
        double f = p * h - lane;
        boolean right = lane % 2 == 0;
        float px = (float) (x + 0.3 + (right ? f : 1 - f) * (w - 0.6));
        return new float[]{px, y + lane + 0.5f, right ? 0 : 180};
    }
}
