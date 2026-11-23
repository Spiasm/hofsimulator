package de.spiasm.hofsim;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.List;

/** Balken je Tag: grün = Einnahmen, rot = Ausgaben, Punkt = Gewinn/Verlust */
public class FinanceChart extends View {
    private final double[][] days; // ältester zuerst
    private final String[] labels;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    public FinanceChart(Context c, List<double[]> hist, double[] today, int max) {
        super(c);
        int n = Math.min(max - 1, hist.size());
        days = new double[n + 1][];
        labels = new String[n + 1];
        for (int i = 0; i < n; i++) {
            days[i] = hist.get(n - 1 - i);
            labels[i] = "-" + (n - i);
        }
        days[n] = today;
        labels[n] = "heute";
    }

    static double in(double[] d) {
        double s = 0;
        for (int i = 0; i < Game.FIN_FIRST_OUT; i++) s += d[i];
        return s;
    }

    static double out(double[] d) {
        double s = 0;
        for (int i = Game.FIN_FIRST_OUT; i < Game.FIN_CATS; i++) s += d[i];
        return s;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float d = getResources().getDisplayMetrics().density;
        float top = 18 * d, bottom = h - 18 * d, mid = (top + bottom) / 2;
        double max = 1;
        for (double[] x : days) max = Math.max(max, Math.max(in(x), out(x)));
        p.setColor(0xFFF5F5F5);
        c.drawRect(0, 0, w, h, p);
        p.setColor(0xFF9E9E9E);
        p.setStrokeWidth(1);
        c.drawLine(0, mid, w, mid, p);
        int n = days.length;
        float slot = w / Math.max(1, n), bw = slot * 0.55f;
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(10 * d);
        for (int i = 0; i < n; i++) {
            float cx = slot * (i + 0.5f);
            double vi = in(days[i]), vo = out(days[i]);
            float hi = (float) (vi / max) * (mid - top), ho = (float) (vo / max) * (bottom - mid);
            p.setColor(0xFF43A047);
            c.drawRect(cx - bw / 2, mid - hi, cx + bw / 2, mid, p);
            p.setColor(0xFFE53935);
            c.drawRect(cx - bw / 2, mid, cx + bw / 2, mid + ho, p);
            double net = vi - vo;
            float ny = mid - (float) (net / max) * (net >= 0 ? (mid - top) : (bottom - mid));
            p.setColor(0xFF212121);
            c.drawCircle(cx, ny, 3.5f * d, p);
            p.setColor(0xFF616161);
            c.drawText(labels[i], cx, h - 4 * d, p);
        }
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(0xFF2E7D32);
        c.drawText("▲ Einnahmen", 4 * d, 12 * d, p);
        p.setColor(0xFFC62828);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText("Ausgaben ▼", w - 4 * d, 12 * d, p);
    }
}
