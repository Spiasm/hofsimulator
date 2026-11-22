package de.spiasm.hofsim;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements Game.Listener, MapView.TapListener {
    private static final int BG = 0xFF1F2A1A;
    private static final int BTN = 0xFF3B4A30;
    private static final int GREEN = 0xFF2E7D32;
    private static final int BLUE = 0xFF1565C0;
    private static final int RED = 0xFFC62828;
    private static final int ORANGE = 0xFFEF6C00;

    private static final int PICK_NONE = 0, PICK_FIELD = 1, PICK_GOTO = 2;

    private Game game;
    private MapView map;
    private TextView moneyText, dateText, tickerText, bonusText, panelTitle, panelText;
    private Button speedBtn, pauseBtn;
    private LinearLayout panel, panelButtons;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastFrame, lastUi, lastSave, tickerUntil;
    private boolean running;
    private boolean paused;
    private int pickMode = PICK_NONE;
    private int pickVehicle = -1;
    /** Was das Panel zeigt: 0 nichts, 1 Fahrzeug, 2 Feld */
    private int panelKind;

    // ------------------------------------------------------------------ Start

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showStart();
    }

    @Override
    protected void onPause() {
        super.onPause();
        save(false);
        running = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (game != null && map != null) startLoop();
    }

    @Override
    public void onBackPressed() {
        if (pickMode != PICK_NONE) {
            pickMode = PICK_NONE;
            hidePanel();
        } else if (panel != null && panel.getVisibility() == View.VISIBLE) {
            hidePanel();
        } else if (game != null && map != null) {
            save(true);
            running = false;
            showStart();
        } else {
            super.onBackPressed();
        }
    }

    private File saveFile() {
        return new File(getFilesDir(), "spielstand.json");
    }

    private void save(boolean announce) {
        if (game == null) return;
        try {
            String s = game.toJson().toString();
            try (FileOutputStream out = new FileOutputStream(saveFile())) {
                out.write(s.getBytes(StandardCharsets.UTF_8));
            }
            if (announce) toast("💾 Gespeichert");
        } catch (Exception e) {
            if (announce) toast("Speichern hat nicht geklappt.");
        }
    }

    private Game load() {
        File f = saveFile();
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            return Game.fromJson(new JSONObject(new String(data, 0, off, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }

    private void showStart() {
        map = null;
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(40), dp(20), dp(30));
        sv.addView(root);
        add(root, text("🚜 Hofsimulator", 34, Color.WHITE, true));
        add(root, text("Felder bestellen, ernten, verkaufen –\nmit Helfern, Maschinen und Ställen.", 16, 0xFFC5D6B8, false));
        final boolean hasSave = saveFile().exists();
        if (hasSave) {
            add(root, button("▶  Weiterspielen", GREEN, v -> {
                Game g = load();
                if (g == null) {
                    toast("Der Spielstand ist von einer älteren Version und passt nicht mehr – bitte ein neues Spiel starten.");
                    return;
                }
                startGame(g);
            }));
        }
        add(root, button("🆕  Neues Spiel (neue Welt)", BLUE, v -> {
            if (hasSave) {
                new AlertDialog.Builder(this)
                        .setMessage("Neues Spiel starten? Der alte Spielstand wird überschrieben.")
                        .setPositiveButton("Ja, neue Welt", (d, w) -> startGame(Game.newGame(System.nanoTime())))
                        .setNegativeButton("Abbrechen", null)
                        .show();
            } else {
                startGame(Game.newGame(System.nanoTime()));
            }
        }));
        add(root, button("❓  Anleitung", BTN, v -> showHelp()));
        setContentView(sv);
    }

    private static final String HELP =
            "🗺 Karte: Mit einem Finger verschieben, mit zwei Fingern zoomen.\n\n"
                    + "🌾 Felder: Antippen zeigt Zustand und Preis. Deine Felder haben einen gelben Rand und ★.\n"
                    + "Ablauf: Grubbern → Säen → (Düngen) → wachsen lassen → Ernten → von vorn.\n"
                    + "Gras: erst mit dem Mähwerk mähen, dann mit Ladewagen oder Feldhäcksler einsammeln. Danach wächst es von selbst nach.\n\n"
                    + "🚜 Maschinen: Antippen, dann wählen: Feldarbeit, Verkaufen oder Hinfahren – dann aufs Ziel tippen.\n"
                    + "Du kannst auch direkt auf einem Feld z. B. „Grubbern“ tippen – dann wird automatisch eine freie Maschine geschickt.\n"
                    + "Ein Helfer fährt (20 €/h + Diesel). Traktoren holen sich das passende Gerät am Hof.\n\n"
                    + "Geräte: Grubber, Sämaschine (Weizen, Mais, Gras), Kartoffel-Legemaschine, Düngerstreuer, Kipper – je in mehreren Größen.\n"
                    + "⚙ Leistungsstufen: Jeder Traktor hat eine Stufe von 1 bis 6, jedes Gerät braucht eine Mindest-Stufe. "
                    + "Ein kleiner Traktor kann also keinen großen Kipper oder breiten Grubber ziehen. "
                    + "Die Helfer nehmen automatisch das größte Gerät, das der Traktor schafft.\n"
                    + "Ernten: Weizen = Mähdrescher, Mais & Gras = Feldhäcksler, Kartoffeln = Kartoffelroder.\n"
                    + "Ist der Bunker voll, fährt die Erntemaschine zum Hof und lädt ab.\n\n"
                    + "💶 Verkaufen: Traktor + Kipper fährt Ware vom Hof zum Landhandel (Weizen, Kartoffeln) "
                    + "oder zur Biogasanlage (Mais, Gras). Die Preise schwanken täglich. Manchmal gibt es eine Sonder-Nachfrage – "
                    + "dann zahlt ein Händler ein paar Tage lang deutlich mehr (Meldung oben).\n\n"
                    + "Ein Gerät kann immer nur an einem Traktor hängen – mit einem Grubber also nur ein Feld gleichzeitig.\n\n"
                    + "💾 Speichern: automatisch jede Minute und beim Verlassen, oder im Menü. „Weiterspielen“ lädt den Stand.\n\n"
                    + "🐔 Ställe: Hühner fressen Weizen, Kühe Gras und Mais – aus deinem Lager. Dafür gibt's jeden Tag Geld.\n\n"
                    + "⏩ Zeit: Oben rechts die Geschwindigkeit wählen. Weizen wächst ca. 4 Wochen – bei 6000× sind das gut 6 Minuten.";

    private void showHelp() {
        new AlertDialog.Builder(this).setTitle("Anleitung").setMessage(HELP).setPositiveButton("OK", null).show();
    }

    // -------------------------------------------------------------- Spielansicht

    private void startGame(Game g) {
        game = g;
        game.listener = this;
        FrameLayout frame = new FrameLayout(this);
        map = new MapView(this);
        map.game = game;
        map.tapListener = this;
        int[] h = game.world.hof;
        map.centerOn((h[0] + h[2] / 2f) * Defs.TILE, (h[1] + h[3] / 2f) * Defs.TILE + 40);
        frame.addView(map, new FrameLayout.LayoutParams(-1, -1));

        // Leiste oben
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(0xCC10180C);
        top.setPadding(dp(10), dp(28), dp(10), dp(6));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        moneyText = text("", 16, 0xFFFFEB3B, true);
        dateText = text("", 14, Color.WHITE, false);
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.addView(moneyText);
        info.addView(dateText);
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
        pauseBtn = small("⏸", BTN);
        pauseBtn.setOnClickListener(v -> {
            paused = !paused;
            updateTop();
        });
        speedBtn = small("", BTN);
        speedBtn.setOnClickListener(v -> chooseSpeed());
        row.addView(pauseBtn, new LinearLayout.LayoutParams(dp(52), dp(44)));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(92), dp(44));
        sp.leftMargin = dp(6);
        row.addView(speedBtn, sp);
        top.addView(row);
        bonusText = text("", 13, 0xFFFFD54F, true);
        bonusText.setVisibility(View.GONE);
        top.addView(bonusText);
        tickerText = text("", 13, 0xFFC5E1A5, false);
        top.addView(tickerText);
        frame.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // unten: Panel + Leiste
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xEE18220F);
        panel.setPadding(dp(12), dp(10), dp(12), dp(8));
        panelTitle = text("", 17, Color.WHITE, true);
        panelText = text("", 14, 0xFFDCE8D0, false);
        panelButtons = new LinearLayout(this);
        panelButtons.setOrientation(LinearLayout.VERTICAL);
        panel.addView(panelTitle);
        panel.addView(panelText);
        panel.addView(panelButtons);
        panel.setVisibility(View.GONE);
        bottom.addView(panel);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xEE10180C);
        bar.setPadding(dp(6), dp(6), dp(6), dp(14));
        bar.addView(barButton("🛒\nKaufen", v -> showShop()), new LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(barButton("📦\nLager", v -> showStorage()), new LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(barButton("🚜\nFuhrpark", v -> showFleet()), new LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(barButton("☰\nMenü", v -> showGameMenu()), new LinearLayout.LayoutParams(0, -2, 1f));
        bottom.addView(bar);
        frame.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        setContentView(frame);
        paused = false;
        updateTop();
        message("Willkommen auf deinem Hof! Tipp auf ❓ im Menü für die Anleitung.");
        startLoop();
    }

    private void startLoop() {
        if (running) return;
        running = true;
        lastFrame = SystemClock.uptimeMillis();
        lastSave = lastFrame;
        handler.post(loop);
    }

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            if (!running || game == null || map == null) {
                running = false;
                return;
            }
            long now = SystemClock.uptimeMillis();
            double dt = Math.min(0.25, (now - lastFrame) / 1000.0);
            lastFrame = now;
            if (!paused) game.update(dt * Defs.SPEEDS[game.speedIdx]);
            map.invalidate();
            if (now - lastUi > 300) {
                lastUi = now;
                updateTop();
                refreshPanel();
                if (now > tickerUntil) tickerText.setText("");
            }
            if (now - lastSave > 60000) {
                lastSave = now;
                save(false);
            }
            handler.postDelayed(this, 33);
        }
    };

    private void updateTop() {
        if (game == null || moneyText == null) return;
        moneyText.setText("💶 " + Game.euro(game.money));
        moneyText.setTextColor(game.money < 0 ? 0xFFFF8A80 : 0xFFFFEB3B);
        dateText.setText("📅 " + game.dateText());
        String bt = game.bonusText();
        bonusText.setVisibility(bt != null ? View.VISIBLE : View.GONE);
        if (bt != null) bonusText.setText(bt);
        pauseBtn.setText(paused ? "▶" : "⏸");
        int s = Defs.SPEEDS[game.speedIdx];
        speedBtn.setText("⏩ " + s + "×");
    }

    private void chooseSpeed() {
        String[] items = new String[Defs.SPEEDS.length - 1];
        String[] desc = {"Echtzeit", "1 Std = 6 Min", "1 Std = 1 Min", "1 Tag = 4,8 Min", "1 Tag ≈ 1 Min", "1 Tag ≈ 14 Sek"};
        for (int i = 1; i < Defs.SPEEDS.length; i++) items[i - 1] = Defs.SPEEDS[i] + "×   (" + desc[i - 1] + ")";
        new AlertDialog.Builder(this).setTitle("Zeit-Geschwindigkeit")
                .setItems(items, (d, which) -> {
                    game.speedIdx = which + 1;
                    paused = false;
                    updateTop();
                }).show();
    }

    @Override
    public void message(String s) {
        if (tickerText == null) return;
        tickerText.setText(s);
        tickerUntil = SystemClock.uptimeMillis() + 7000;
    }

    // ---------------------------------------------------------------- Tippen

    @Override
    public void onTapWorld(float mx, float my) {
        int tx = (int) (mx / Defs.TILE), ty = (int) (my / Defs.TILE);
        World w = game.world;
        if (pickMode == PICK_GOTO) {
            Game.Vehicle v = game.vehicle(pickVehicle);
            pickMode = PICK_NONE;
            if (v != null) {
                String err = game.startGoto(v, tx, ty);
                if (err != null) toast(err);
                else message("👷 " + v.name() + " fährt los.");
                showVehiclePanel(v);
            }
            return;
        }
        if (pickMode == PICK_FIELD) {
            Game.Vehicle v = game.vehicle(pickVehicle);
            if (!w.in(tx, ty) || w.fieldAt[tx][ty] < 0) {
                toast("Bitte auf eines deiner Felder tippen.");
                return;
            }
            pickMode = PICK_NONE;
            Field f = w.fields.get(w.fieldAt[tx][ty]);
            if (v != null) chooseTaskFor(v, f);
            return;
        }
        // Fahrzeug getroffen?
        Game.Vehicle hit = null;
        float best = Math.max(9f, 40f / map.scale);
        for (Game.Vehicle v : game.vehicles) {
            float d = (float) Math.hypot(v.x - mx, v.y - my);
            if (d < best) {
                best = d;
                hit = v;
            }
        }
        if (hit != null) {
            showVehiclePanel(hit);
            return;
        }
        if (w.in(tx, ty) && w.fieldAt[tx][ty] >= 0) {
            showFieldPanel(w.fields.get(w.fieldAt[tx][ty]));
            return;
        }
        if (inRect(w.landhandel, tx, ty) || inRect(w.biogas, tx, ty)) {
            showStorage();
            return;
        }
        if (inRect(w.hof, tx, ty)) {
            showStorage();
            return;
        }
        hidePanel();
    }

    private static boolean inRect(int[] r, int x, int y) {
        return r != null && x >= r[0] && y >= r[1] && x < r[0] + r[2] && y < r[1] + r[3];
    }

    // ---------------------------------------------------------------- Panels

    private void hidePanel() {
        panelKind = 0;
        map.selectedVehicle = -1;
        map.selectedField = -1;
        panel.setVisibility(View.GONE);
    }

    private void openPanel(String title, String body) {
        panelTitle.setText(title);
        panelText.setText(body);
        panelButtons.removeAllViews();
        panel.setVisibility(View.VISIBLE);
    }

    private void addPanelButtons(Button... bs) {
        for (int i = 0; i < bs.length; i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = i; k < Math.min(bs.length, i + 2); k++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
                lp.setMargins(dp(3), dp(4), dp(3), 0);
                row.addView(bs[k], lp);
            }
            if (bs.length - i == 1) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            panelButtons.addView(row);
        }
    }

    private void refreshPanel() {
        if (panel == null || panel.getVisibility() != View.VISIBLE || pickMode != PICK_NONE) return;
        if (panelKind == 1) {
            Game.Vehicle v = game.vehicle(map.selectedVehicle);
            if (v != null) panelText.setText(vehicleText(v));
        } else if (panelKind == 2 && map.selectedField >= 0) {
            panelText.setText(fieldText(game.world.fields.get(map.selectedField)));
        }
    }

    private String vehicleText(Game.Vehicle v) {
        StringBuilder sb = new StringBuilder();
        sb.append("Status: ").append(v.status);
        Game.Tool t = game.tool(v.tool);
        if (v.isTractor()) {
            sb.append("\nLeistungsstufe ").append(v.m().level).append(" • Gerät: ").append(t != null ? t.m().name : "keins");
        } else {
            sb.append("\n").append(v.m().info);
        }
        if (v.load > 0 && v.loadProduct >= 0) {
            sb.append(String.format(Locale.GERMANY, "\nLadung: %.1f t %s", v.load, Defs.PRODUCT_NAMES[v.loadProduct]));
        }
        if (v.busy()) sb.append("\n👷 Helfer arbeitet");
        return sb.toString();
    }

    private void showVehiclePanel(final Game.Vehicle v) {
        pickMode = PICK_NONE;
        panelKind = 1;
        map.selectedVehicle = v.id;
        map.selectedField = -1;
        openPanel("🚜 " + v.name(), vehicleText(v));
        List<Button> bs = new ArrayList<>();
        bs.add(button("🌾 Feldarbeit", GREEN, x -> {
            pickMode = PICK_FIELD;
            pickVehicle = v.id;
            showPickHint("Tippe auf das Feld, auf dem " + v.name() + " arbeiten soll.");
        }));
        if (v.isTractor()) bs.add(button("💰 Ware verkaufen", ORANGE, x -> chooseSell(v)));
        bs.add(button("📍 Hinfahren", BLUE, x -> {
            pickMode = PICK_GOTO;
            pickVehicle = v.id;
            showPickHint("Tippe auf die Stelle, wo " + v.name() + " hinfahren soll.");
        }));
        bs.add(button("🏠 Zum Hof", BTN, x -> {
            game.sendHome(v);
            message("👷 " + v.name() + " fährt zum Hof.");
        }));
        bs.add(button("⏹ Anhalten", RED, x -> {
            game.stop(v);
            message(v.name() + " hält an.");
        }));
        addPanelButtons(bs.toArray(new Button[0]));
    }

    private void showPickHint(String s) {
        panelKind = 0;
        openPanel("👆 Ziel wählen", s);
        addPanelButtons(button("Abbrechen", RED, x -> {
            pickMode = PICK_NONE;
            Game.Vehicle v = game.vehicle(pickVehicle);
            if (v != null) showVehiclePanel(v);
            else hidePanel();
        }));
    }

    private String fieldText(Field f) {
        String s = String.format(Locale.GERMANY, "%.1f ha • ", f.hectares()) + f.stateText();
        if (!f.owned) s += "\nGehört einem Nachbarn – Preis: " + Game.euro(f.price());
        if (f.busyBy >= 0) {
            Game.Vehicle v = game.vehicle(f.busyBy);
            if (v != null) s += "\n👷 " + v.name() + ": " + v.status;
        }
        if (f.owned && f.state == Field.GROWING && f.growth < 1) {
            double days = (1 - f.growth) * Defs.GROW_DAYS[f.crop];
            s += String.format(Locale.GERMANY, "\nErntereif in ca. %.0f Tagen", days);
        }
        if (f.owned && f.ripe()) {
            double y = Defs.YIELD[f.crop] * (f.fertilized ? 1 : 0.8) * f.hectares();
            s += String.format(Locale.GERMANY, "\nErwartete Ernte: ca. %.0f t (%s)", y, Game.euro(y * game.price(f.crop)));
        }
        return s;
    }

    private void showFieldPanel(final Field f) {
        panelKind = 2;
        map.selectedField = f.index;
        map.selectedVehicle = -1;
        openPanel((f.owned ? "★ " : "") + "Feld " + f.number, fieldText(f));
        List<Button> bs = new ArrayList<>();
        if (!f.owned) {
            bs.add(button("🛒 Kaufen (" + Game.euro(f.price()) + ")", ORANGE, x -> {
                new AlertDialog.Builder(this)
                        .setMessage("Feld " + f.number + " (" + String.format(Locale.GERMANY, "%.1f", f.hectares())
                                + " ha) für " + Game.euro(f.price()) + " kaufen?")
                        .setPositiveButton("Kaufen", (d, w) -> {
                            String err = game.buyField(f);
                            if (err != null) toast(err);
                            else message("🎉 Feld " + f.number + " gehört jetzt dir!");
                            showFieldPanel(f);
                        })
                        .setNegativeButton("Abbrechen", null).show();
            }));
        } else {
            for (int t : Defs.TASK_ORDER) {
                if (!f.canDo(t)) continue;
                final int task = t;
                bs.add(button(taskIcon(t) + " " + Defs.TASK_NAMES[t] + (f.progress > 0 ? " (weiter)" : ""), GREEN,
                        x -> autoTask(f, task)));
            }
            if (bs.isEmpty()) {
                if (f.busyBy >= 0) panelText.append("\n\nGerade wird hier gearbeitet.");
                else if (f.state == Field.GROWING) panelText.append("\n\nJetzt heißt es: wachsen lassen ⏩");
            }
        }
        if (!bs.isEmpty()) addPanelButtons(bs.toArray(new Button[0]));
    }

    private static String taskIcon(int t) {
        switch (t) {
            case Defs.TASK_CULTIVATE:
                return "⛏";
            case Defs.TASK_SOW:
                return "🌱";
            case Defs.TASK_FERTILIZE:
                return "🧪";
            case Defs.TASK_MOW:
                return "✂";
            default:
                return "🌾";
        }
    }

    /** Feldarbeit vom Feld aus: passende Maschine automatisch wählen */
    private void autoTask(final Field f, final int task) {
        if (task == Defs.TASK_SOW && f.progress <= 0) {
            chooseCrop(f, crop -> autoTaskCrop(f, task, crop));
        } else {
            autoTaskCrop(f, task, f.taskCrop >= 0 ? f.taskCrop : Defs.WHEAT);
        }
    }

    private void autoTaskCrop(Field f, int task, int crop) {
        Game.Vehicle v = game.findVehicleFor(f, task, crop);
        if (v == null) {
            // Grund herausfinden
            String reason = null;
            for (Game.Vehicle c : game.vehicles) {
                String e = game.checkFieldJob(c, f, task, crop);
                if (reason == null || (e != null && !e.contains("beschäftigt"))) reason = e;
            }
            if (task == Defs.TASK_HARVEST && f.crop == Defs.GRASS) {
                if (reason == null || reason.contains("beschäftigt"))
                    reason = "Zum Einsammeln brauchst du einen Feldhäcksler oder einen Traktor mit Ladewagen – und die müssen frei sein.";
            } else if (task == Defs.TASK_HARVEST) {
                int need = Defs.harvesterFor(f.crop);
                boolean have = false;
                for (Game.Vehicle c : game.vehicles) if (c.isHarvester() && c.m().sub == need) have = true;
                if (!have) reason = "Du brauchst einen " + Defs.H_KIND_NAMES[need] + " (im Shop kaufen).";
                else if (reason == null) reason = "Dein " + Defs.H_KIND_NAMES[need] + " ist gerade beschäftigt.";
            }
            new AlertDialog.Builder(this).setTitle("Keine Maschine frei")
                    .setMessage(reason != null ? reason : "Gerade ist keine passende Maschine frei.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String err = game.startFieldJob(v, f, task, crop);
        if (err != null) toast(err);
        showFieldPanel(f);
    }

    interface CropCallback {
        void onCrop(int crop);
    }

    private void chooseCrop(final Field f, final CropCallback cb) {
        String[] items = new String[4];
        int[] order = {Defs.WHEAT, Defs.MAIZE, Defs.POTATO, Defs.GRASS};
        for (int i = 0; i < 4; i++) {
            int c = order[i];
            items[i] = Defs.CROP_NAMES[c] + " – Saatgut " + Game.euro(Defs.SEED_COST[c] * f.hectares())
                    + (c == Defs.POTATO ? " (Legemaschine)" : "");
        }
        new AlertDialog.Builder(this).setTitle("Was säen auf Feld " + f.number + "?")
                .setItems(items, (d, which) -> cb.onCrop(order[which])).show();
    }

    /** Maschine → Feld → Arbeit wählen */
    private void chooseTaskFor(final Game.Vehicle v, final Field f) {
        List<String> labels = new ArrayList<>();
        final List<Integer> tasks = new ArrayList<>();
        String firstErr = null;
        for (int t : Defs.TASK_ORDER) {
            if (!f.owned || !f.canDo(t)) continue;
            int probeCrop = t == Defs.TASK_SOW ? (v.tool >= 0 && game.tool(v.tool) != null
                    && game.tool(v.tool).m().sub == Defs.T_PLANTER ? Defs.POTATO : Defs.WHEAT) : Defs.WHEAT;
            String err = game.checkFieldJob(v, f, t, probeCrop);
            if (t == Defs.TASK_SOW && err != null) {
                String e2 = game.checkFieldJob(v, f, t, Defs.POTATO);
                if (e2 == null) err = null;
            }
            if (err == null) {
                labels.add(taskIcon(t) + " " + Defs.TASK_NAMES[t]);
                tasks.add(t);
            } else if (firstErr == null) {
                firstErr = err;
            }
        }
        if (!f.owned) firstErr = "Feld " + f.number + " gehört dir nicht. Tippe es an, um es zu kaufen.";
        if (tasks.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Geht gerade nicht")
                    .setMessage(firstErr != null ? firstErr : "Auf Feld " + f.number + " gibt es gerade nichts zu tun.\n" + f.stateText())
                    .setPositiveButton("OK", null).show();
            showVehiclePanel(v);
            return;
        }
        new AlertDialog.Builder(this).setTitle(v.name() + " auf Feld " + f.number)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    int task = tasks.get(which);
                    if (task == Defs.TASK_SOW && f.progress <= 0) {
                        chooseCrop(f, crop -> {
                            String err = game.startFieldJob(v, f, task, crop);
                            if (err != null) toast(err);
                            showVehiclePanel(v);
                        });
                    } else {
                        String err = game.startFieldJob(v, f, task, f.taskCrop >= 0 ? f.taskCrop : Defs.WHEAT);
                        if (err != null) toast(err);
                        showVehiclePanel(v);
                    }
                }).show();
    }

    private void chooseSell(final Game.Vehicle v) {
        final List<Integer> prods = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int p = 0; p < 4; p++) {
            if (game.storage[p] < 0.5) continue;
            prods.add(p);
            labels.add(String.format(Locale.GERMANY, "%s – %.1f t im Lager, %s/t (%s)", Defs.PRODUCT_NAMES[p],
                    game.storage[p], Game.euro(game.price(p)), Defs.BUYER[p] == 0 ? "Landhandel" : "Biogas"));
        }
        if (prods.isEmpty()) {
            toast("Dein Lager ist leer.");
            return;
        }
        new AlertDialog.Builder(this).setTitle("Was verkaufen? (so viel der Kipper fasst)")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    String err = game.startSellJob(v, prods.get(which));
                    if (err != null) toast(err);
                    showVehiclePanel(v);
                }).show();
    }

    // ----------------------------------------------------------------- Dialoge

    private void showShop() {
        final String[] cats = {"🚜 Traktoren", "🌾 Mähdrescher", "🌽 Feldhäcksler", "🥔 Kartoffelroder", "⛏ Grubber",
                "🌱 Sämaschinen", "🥔 Kartoffel-Legemaschinen", "🧪 Düngerstreuer", "🚛 Kipper", "✂ Mähwerke", "🌿 Ladewagen",
                "🐄 Ställe", "🌾 Felder", "💸 Etwas verkaufen"};
        new AlertDialog.Builder(this).setTitle("Kaufen – Kontostand " + Game.euro(game.money))
                .setItems(cats, (d, which) -> {
                    switch (which) {
                        case 0: shopModels(cats[0], Defs.K_TRACTOR, 0); break;
                        case 1: shopModels(cats[1], Defs.K_HARVESTER, Defs.H_COMBINE); break;
                        case 2: shopModels(cats[2], Defs.K_HARVESTER, Defs.H_CHOPPER); break;
                        case 3: shopModels(cats[3], Defs.K_HARVESTER, Defs.H_POTATO); break;
                        case 4: shopModels(cats[4], Defs.K_TOOL, Defs.T_GRUBBER); break;
                        case 5: shopModels(cats[5], Defs.K_TOOL, Defs.T_SEEDER); break;
                        case 6: shopModels(cats[6], Defs.K_TOOL, Defs.T_PLANTER); break;
                        case 7: shopModels(cats[7], Defs.K_TOOL, Defs.T_SPREADER); break;
                        case 8: shopModels(cats[8], Defs.K_TOOL, Defs.T_TRAILER); break;
                        case 9: shopModels(cats[9], Defs.K_TOOL, Defs.T_MOWER); break;
                        case 10: shopModels(cats[10], Defs.K_TOOL, Defs.T_LOADER); break;
                        case 11: shopStables(); break;
                        case 12:
                            new AlertDialog.Builder(this).setMessage(
                                    "Felder kaufst du direkt auf der Karte: Feld antippen → „Kaufen“.\nPreis: ca. "
                                            + Game.euro(Defs.FIELD_PRICE_HA) + " pro Hektar.").setPositiveButton("OK", null).show();
                            break;
                        default: sellStuff();
                    }
                }).show();
    }

    private void shopModels(String title, int kind, int sub) {
        final List<Defs.Model> list = new ArrayList<>();
        for (Defs.Model m : Defs.MODELS) {
            if (m.kind != kind) continue;
            if (kind != Defs.K_TRACTOR && m.sub != sub) continue;
            list.add(m);
        }
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Defs.Model m = list.get(i);
            int have = 0;
            for (Game.Vehicle v : game.vehicles) if (v.model == m.id) have++;
            for (Game.Tool t : game.tools) if (t.model == m.id) have++;
            items[i] = m.name + " – " + Game.euro(m.price) + (have > 0 ? "  (hast du: " + have + ")" : "") + "\n   " + m.info;
        }
        String head = kind == Defs.K_TOOL ? title + "\n(Stufe = nötige Traktor-Leistung)" : title;
        new AlertDialog.Builder(this).setTitle(head).setItems(items, (d, which) -> {
            final Defs.Model m = list.get(which);
            confirmBuy(m.name, m.price, () -> game.buy(m.id));
        }).show();
    }

    private void shopStables() {
        String[] items = new String[2];
        String[] info = {"frisst 120 kg Weizen/Tag, bringt bis 126 €/Tag (Eier)",
                "frisst 2 t Gras + 0,9 t Mais/Tag, bringt bis 538 €/Tag (Milch)"};
        for (int i = 0; i < 2; i++) {
            items[i] = Defs.S_NAMES[i] + " – " + (game.stables[i] ? "✔ hast du" : Game.euro(Defs.S_PRICE[i])) + "\n   " + info[i];
        }
        new AlertDialog.Builder(this).setTitle("Ställe").setItems(items, (d, which) -> {
            if (game.stables[which]) return;
            confirmBuy(Defs.S_NAMES[which], Defs.S_PRICE[which], () -> game.buyStable(which));
        }).show();
    }

    interface BuyAction {
        String run();
    }

    private void confirmBuy(String name, double price, final BuyAction action) {
        new AlertDialog.Builder(this).setMessage(name + " für " + Game.euro(price) + " kaufen?")
                .setPositiveButton("Kaufen", (d, w) -> {
                    String err = action.run();
                    if (err != null) toast(err);
                    else message("🎉 Gekauft: " + name);
                    updateTop();
                })
                .setNegativeButton("Abbrechen", null).show();
    }

    private void sellStuff() {
        final List<Object> things = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Game.Vehicle v : game.vehicles) {
            things.add(v);
            labels.add("🚜 " + v.name() + " – bringt " + Game.euro(v.m().price * 0.5));
        }
        for (Game.Tool t : game.tools) {
            things.add(t);
            labels.add("🔧 " + t.m().name + (t.attachedTo != -1 ? " (in Benutzung)" : "") + " – bringt " + Game.euro(t.m().price * 0.5));
        }
        new AlertDialog.Builder(this).setTitle("Verkaufen (halber Preis)")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    Object o = things.get(which);
                    String err = o instanceof Game.Vehicle ? game.sellVehicle((Game.Vehicle) o) : game.sellTool((Game.Tool) o);
                    if (err != null) toast(err);
                    else message("💸 Verkauft.");
                    hidePanel();
                }).show();
    }

    private void showStorage() {
        StringBuilder sb = new StringBuilder();
        sb.append("📦 Lager am Hof:\n");
        for (int p = 0; p < 4; p++) {
            sb.append(String.format(Locale.GERMANY, "• %s: %.1f t\n", Defs.PRODUCT_NAMES[p], game.storage[p]));
        }
        sb.append("\n💶 Preise heute:\n");
        if (game.bonusText() != null) sb.append(game.bonusText()).append("\n");
        for (int p = 0; p < 4; p++) {
            double pct = (game.priceFactor[p] - 1) * 100;
            sb.append(String.format(Locale.GERMANY, "• %s: %s/t (%+.0f %%) – %s\n", Defs.PRODUCT_NAMES[p],
                    Game.euro(game.price(p)), pct, Defs.BUYER[p] == 0 ? "Landhandel" : "Biogas"));
        }
        sb.append("\n🐔🐄 Ställe:\n");
        boolean any = false;
        for (int s = 0; s < 2; s++) {
            if (!game.stables[s]) continue;
            any = true;
            sb.append("• ").append(Defs.S_NAMES[s]).append("\n   Futter/Tag: ");
            for (int p = 0; p < 4; p++) {
                if (Defs.S_FEED[s][p] > 0) {
                    double days = game.storage[p] / Defs.S_FEED[s][p];
                    sb.append(String.format(Locale.GERMANY, "%.2f t %s (reicht %.0f Tage) ", Defs.S_FEED[s][p],
                            Defs.PRODUCT_NAMES[p], days));
                }
            }
            sb.append("\n   Einnahmen bis ").append(Game.euro(Defs.S_INCOME[s])).append("/Tag, Kosten ")
                    .append(Game.euro(Defs.S_COST[s])).append("/Tag\n");
        }
        if (!any) sb.append("keine\n");
        int owned = 0;
        double ha = 0;
        for (Field f : game.world.fields) if (f.owned) {
            owned++;
            ha += f.hectares();
        }
        sb.append(String.format(Locale.GERMANY, "\n🌾 Deine Felder: %d (%.1f ha)", owned, ha));
        new AlertDialog.Builder(this).setTitle("Lager & Preise").setMessage(sb.toString()).setPositiveButton("OK", null).show();
    }

    private void showFleet() {
        final List<Object> things = new ArrayList<>();
        List<String> items = new ArrayList<>();
        for (Game.Vehicle v : game.vehicles) {
            Game.Tool t = game.tool(v.tool);
            things.add(v);
            items.add("🚜 " + v.name() + (v.isTractor() ? " (Stufe " + v.m().level + ")" : "") + "\n      " + v.status
                    + (t != null ? " • mit " + t.m().name : ""));
        }
        for (Game.Tool t : game.tools) {
            String where;
            Game.Vehicle at = game.vehicle(t.attachedTo);
            Game.Vehicle res = game.vehicle(t.reservedBy);
            if (at != null) where = "hängt an " + at.name();
            else if (res != null) where = "reserviert für " + res.name();
            else where = "steht am Hof";
            things.add(t);
            items.add("🔧 " + t.m().name + " (braucht Stufe " + t.m().level + ")\n      " + where);
        }
        new AlertDialog.Builder(this).setTitle("Fuhrpark: " + game.vehicles.size() + " Fahrzeuge, " + game.tools.size() + " Geräte")
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    Object o = things.get(which);
                    if (o instanceof Game.Vehicle) {
                        Game.Vehicle v = (Game.Vehicle) o;
                        map.centerOn(v.x, v.y);
                        showVehiclePanel(v);
                    } else {
                        showToolInfo((Game.Tool) o);
                    }
                }).show();
    }

    private void showToolInfo(final Game.Tool t) {
        Game.Vehicle at = game.vehicle(t.attachedTo);
        if (at != null) {
            map.centerOn(at.x, at.y);
        } else {
            int[] h = game.world.hof;
            map.centerOn((h[0] + h[2] / 2f) * Defs.TILE, (h[1] + h[3] / 2f) * Defs.TILE);
        }
        String msg = t.m().name + "\n" + t.m().info + "\n\n" + (at != null ? "Hängt gerade an " + at.name() + "." : "Steht am Hof.");
        new AlertDialog.Builder(this).setTitle("🔧 Gerät").setMessage(msg)
                .setPositiveButton("OK", null)
                .setNegativeButton("Verkaufen (" + Game.euro(t.m().price * 0.5) + ")", (d, w) -> {
                    String err = game.sellTool(t);
                    if (err != null) toast(err);
                    else message("💸 " + t.m().name + " verkauft.");
                }).show();
    }

    private void showGameMenu() {
        String[] items = {"💾 Speichern", "❓ Anleitung", "🏠 Karte zum Hof", "🚪 Speichern & zum Hauptmenü"};
        new AlertDialog.Builder(this).setTitle("Menü").setItems(items, (d, which) -> {
            if (which == 0) save(true);
            else if (which == 1) showHelp();
            else if (which == 2) {
                int[] h = game.world.hof;
                map.centerOn((h[0] + h[2] / 2f) * Defs.TILE, (h[1] + h[3] / 2f) * Defs.TILE);
            } else {
                save(true);
                running = false;
                showStart();
            }
        }).show();
    }

    // ---------------------------------------------------------------- Helfer

    private void add(LinearLayout root, View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        root.addView(v, lp);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.1f);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String s, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(6), dp(10), dp(6), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        return b;
    }

    private Button small(String s, int color) {
        Button b = button(s, color, null);
        b.setPadding(0, 0, 0, 0);
        b.setTextSize(15);
        return b;
    }

    private Button barButton(String s, View.OnClickListener l) {
        Button b = button(s, 0x00000000, l);
        b.setTextSize(13);
        b.setPadding(0, dp(4), 0, dp(4));
        return b;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
