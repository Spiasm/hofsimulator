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

    private static final int PICK_NONE = 0, PICK_FIELD = 1, PICK_GOTO = 2, PICK_WIND = 3;

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
    /** Was das Panel zeigt: 0 nichts, 1 Fahrzeug, 2 Feld, 3 Stall */
    private int panelKind;
    private int panelStable;

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
                    + "Gras: erst mit dem Mähwerk mähen, dann mit Ladewagen oder Feldhäcksler einsammeln. Danach wächst es nach.\n\n"
                    + "🚜 Maschinen: Antippen, dann wählen: Feldarbeit, Verkaufen, Futter bringen oder Hinfahren.\n"
                    + "Oder direkt auf einem Feld z. B. „Grubbern“ tippen – dann wird eine freie Maschine geschickt.\n"
                    + "Ein Helfer fährt (20 €/h + Diesel). Traktoren holen sich das passende Gerät am Hof.\n"
                    + "⚙ Leistungsstufen 1–6: Jedes Gerät braucht eine Mindest-Stufe am Traktor.\n"
                    + "Ein Gerät hängt immer nur an einem Traktor – mit einem Grubber also nur ein Feld gleichzeitig.\n\n"
                    + "🌾 Ernte: Weizen = Mähdrescher, Mais & Gras = Feldhäcksler, Kartoffeln = Kartoffelroder.\n"
                    + "Ist der Bunker voll, kommt ein freier Traktor mit Kipper aufs Feld zum Überladen. "
                    + "Ist keiner frei, fährt die Erntemaschine selbst zum Hof.\n\n"
                    + "💶 Verkaufen: Traktor + Kipper bringt Ware zum Landhandel (Weizen, Kartoffeln), zur Mühle (Weizen) "
                    + "oder zur Biogasanlage (Mais, Gras). Wähle „1 Ladung“ oder „bis leer“. Preise schwanken, manchmal gibt es "
                    + "eine Sonder-Nachfrage (gelbe Zeile oben).\n\n"
                    + "🐔🐄🐖🐑 Ställe stehen auf eigenen Bauplätzen. Jeder Stall hat ein Futterlager – das Futter musst du mit "
                    + "Traktor und Kipper hinbringen (Stall antippen → Futter bringen). Milch, Eier usw. werden automatisch verkauft.\n\n"
                    + "☀💨 Energie: PV aufs Dach, Solarpark auf ein eigenes Feld oder ein Windrad auf freie Wiese (Kaufen/Verkaufen → Bauen). "
                    + "Bringt jeden Tag Geld – je nach Sonne und Wind.\n\n"
                    + "🔥 Bei trockenem Wetter kann ein Stoppelfeld brennen – die Feuerwehr kommt und löscht.\n\n"
                    + "🎨 Grafik: Im Menü zwischen Einfach, Mittel und Hoch umschalten.\n\n"
                    + "💾 Speichern: automatisch jede Minute, beim Verlassen und im Menü.\n\n"
                    + "⏩ Zeit: oben rechts. Weizen wächst ca. 7 Tage – bei 6000× knapp 2 Minuten.";

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
        applyDetail(getPreferences(MODE_PRIVATE).getInt("detail", 1));
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
        bar.addView(barButton("🛒\nKaufen/Verkaufen", v -> showShop()), new LinearLayout.LayoutParams(0, -2, 1f));
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
            if (!paused) {
                int sp = Defs.SPEEDS[game.speedIdx];
                game.update(dt * sp);
                game.updateEvents(dt * Math.min(sp, 10));
            }
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
            handler.postDelayed(this, map.detail == 0 ? 50 : 33);
        }
    };

    private void updateTop() {
        if (game == null || moneyText == null) return;
        moneyText.setText("💶 " + Game.euro(game.money));
        moneyText.setTextColor(game.money < 0 ? 0xFFFF8A80 : 0xFFFFEB3B);
        dateText.setText("📅 " + game.dateText() + "   " + game.weatherText());
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
        if (pickMode == PICK_WIND) {
            pickMode = PICK_NONE;
            final int fx = tx, fy = ty;
            if (!w.canBuildWind(tx, ty)) {
                toast("Hier ist kein Platz – ein Windrad braucht freie Wiese (nicht an Straße, Feld oder Wald).");
                hidePanel();
                return;
            }
            new AlertDialog.Builder(this).setMessage("Windkraftanlage hier bauen für " + Game.euro(Defs.WIND_PRICE) + "?")
                    .setPositiveButton("Bauen", (d, x) -> {
                        String err = game.buildWind(fx, fy);
                        if (err != null) toast(err);
                        else message("💨 Neue Windkraftanlage in Betrieb!");
                        updateTop();
                    }).setNegativeButton("Abbrechen", null).show();
            hidePanel();
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
        for (int s = 0; s < Defs.STABLES; s++) {
            if (inRect(w.stableLot[s], tx, ty)) {
                showStablePanel(s);
                return;
            }
        }
        for (int b = 0; b < 3; b++) {
            if (inRect(w.trader[b], tx, ty)) {
                showTraderInfo(b);
                return;
            }
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
        } else if (panelKind == 3) {
            panelText.setText(stableText(panelStable));
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
        if (v.isTractor()) {
            bs.add(button("💰 Ware verkaufen", ORANGE, x -> chooseSell(v)));
            bs.add(button("🐾 Futter bringen", ORANGE, x -> chooseFeed(v, -1)));
        }
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
            s += String.format(Locale.GERMANY, "\nErwartete Ernte: ca. %.0f t (%s)", y, Game.euro(y * game.bestPrice(f.crop)));
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
            if (!f.pv && f.busyBy < 0 && game.fireField != f.index) {
                bs.add(button("☀ Solarpark (" + Game.euro(game.solarParkPrice(f)) + ")", BTN, x -> {
                    new AlertDialog.Builder(this).setTitle("Solarpark auf Feld " + f.number + "?")
                            .setMessage(String.format(Locale.GERMANY, "Kostet %s und bringt bei voller Sonne ca. %s pro Tag.\n"
                                            + "Auf dem Feld kann danach nichts mehr angebaut werden.",
                                    Game.euro(game.solarParkPrice(f)), Game.euro(f.hectares() * Defs.PV_FIELD_INCOME_HA)))
                            .setPositiveButton("Bauen", (d, w) -> {
                                String err = game.buildSolarPark(f);
                                if (err != null) toast(err);
                                else message("☀ Solarpark auf Feld " + f.number + " in Betrieb!");
                                showFieldPanel(f);
                            }).setNegativeButton("Abbrechen", null).show();
                }));
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
            labels.add(String.format(Locale.GERMANY, "%s – %.1f t im Lager, bis %s/t", Defs.PRODUCT_NAMES[p],
                    game.storage[p], Game.euro(game.bestPrice(p))));
        }
        if (prods.isEmpty()) {
            toast("Dein Hoflager ist leer.");
            return;
        }
        new AlertDialog.Builder(this).setTitle("Was verkaufen?")
                .setItems(labels.toArray(new String[0]), (d, which) -> chooseBuyer(v, prods.get(which))).show();
    }

    private void chooseBuyer(final Game.Vehicle v, final int product) {
        final List<Integer> buyers = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        int best = game.bestBuyer(product);
        for (int b = 0; b < 3; b++) {
            if (!game.buys(b, product)) continue;
            buyers.add(b);
            labels.add(Defs.BUYER_NAMES[b] + " – " + Game.euro(game.price(b, product)) + "/t" + (b == best ? "  ⭐ bester Preis" : "")
                    + (b == game.bonusBuyer && product == game.bonusProduct ? "  📢" : ""));
        }
        if (buyers.size() == 1) {
            chooseAmount(v, product, buyers.get(0));
            return;
        }
        new AlertDialog.Builder(this).setTitle("Wohin mit dem " + Defs.PRODUCT_NAMES[product] + "?")
                .setItems(labels.toArray(new String[0]), (d, which) -> chooseAmount(v, product, buyers.get(which))).show();
    }

    private void chooseAmount(final Game.Vehicle v, final int product, final int buyer) {
        String[] items = {"🚛 Nur 1 Ladung", String.format(Locale.GERMANY, "🔁 Fahren bis leer (%.1f t)", game.storage[product])};
        new AlertDialog.Builder(this).setTitle(Defs.PRODUCT_NAMES[product] + " " + Defs.BUYER_TO[buyer])
                .setItems(items, (d, which) -> {
                    String err = game.startSellJob(v, product, buyer, which == 1);
                    if (err != null) toast(err);
                    showVehiclePanel(v);
                }).show();
    }

    /** Futter bringen: v = Traktor (oder null = automatisch), stable = -1 → Stall wählen */
    private void chooseFeed(final Game.Vehicle v, int stable) {
        if (stable < 0) {
            final List<Integer> owned = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            for (int s = 0; s < Defs.STABLES; s++) {
                if (!game.stables[s]) continue;
                owned.add(s);
                labels.add(String.format(Locale.GERMANY, "%s %s – Futter reicht %.0f Tage", Defs.S_ICON[s], Defs.S_NAMES[s],
                        game.stableDays(s)));
            }
            if (owned.isEmpty()) {
                toast("Du hast noch keinen Stall.");
                return;
            }
            new AlertDialog.Builder(this).setTitle("Zu welchem Stall?")
                    .setItems(labels.toArray(new String[0]), (d, which) -> chooseFeed(v, owned.get(which))).show();
            return;
        }
        final int s = stable;
        final List<Integer> prods = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int p = 0; p < 4; p++) {
            if (Defs.S_FEED[s][p] <= 0) continue;
            prods.add(p);
            labels.add(String.format(Locale.GERMANY, "%s – im Stall %.1f / %.0f t, im Hoflager %.1f t", Defs.PRODUCT_NAMES[p],
                    game.stableFeed[s][p], game.stableCap(s, p), game.storage[p]));
        }
        new AlertDialog.Builder(this).setTitle(Defs.S_ICON[s] + " Welches Futter?")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    final int p = prods.get(which);
                    String err = game.checkFeed(s, p);
                    if (err != null) {
                        toast(err);
                        return;
                    }
                    String[] items = {"🚛 Nur 1 Ladung", "🔁 Fahren bis der Stall voll ist"};
                    new AlertDialog.Builder(this).setTitle(Defs.PRODUCT_NAMES[p] + " zum " + Defs.S_NAMES[s])
                            .setItems(items, (d2, w2) -> {
                                Game.Vehicle tr = v != null ? v : game.findTransportTractor();
                                if (tr == null) {
                                    toast(game.transportProblem());
                                    return;
                                }
                                String e2 = game.startFeedJob(tr, s, p, w2 == 1);
                                if (e2 != null) toast(e2);
                                else if (v == null) showVehiclePanel(tr);
                            }).show();
                }).show();
    }

    private String stableText(int s) {
        StringBuilder sb = new StringBuilder();
        sb.append(Defs.S_ANIMALS[s]).append(" • bringt ").append(Defs.S_PRODUCE[s]).append("\n");
        if (!game.stables[s]) {
            sb.append("Bauplatz – noch nicht gebaut.\nPreis: ").append(Game.euro(Defs.S_PRICE[s])).append("\nFrisst pro Tag: ");
            for (int p = 0; p < 4; p++) {
                if (Defs.S_FEED[s][p] > 0) sb.append(String.format(Locale.GERMANY, "%.2f t %s  ", Defs.S_FEED[s][p], Defs.PRODUCT_NAMES[p]));
            }
            sb.append(String.format(Locale.GERMANY, "\nEinnahmen bis %s/Tag, Kosten %s/Tag", Game.euro(Defs.S_INCOME[s]),
                    Game.euro(Defs.S_COST[s])));
            return sb.toString();
        }
        for (int p = 0; p < 4; p++) {
            if (Defs.S_FEED[s][p] <= 0) continue;
            sb.append(String.format(Locale.GERMANY, "%s: %.1f von %.0f t (%.2f t/Tag)\n", Defs.PRODUCT_NAMES[p],
                    game.stableFeed[s][p], game.stableCap(s, p), Defs.S_FEED[s][p]));
        }
        double days = game.stableDays(s);
        sb.append(String.format(Locale.GERMANY, "%s Futter reicht noch %.0f Tage", days < 3 ? "⚠" : "✅", days));
        sb.append("\nEinnahmen bis ").append(Game.euro(Defs.S_INCOME[s])).append("/Tag");
        if (game.pvStable[s]) sb.append(" • ☀ PV auf dem Dach");
        return sb.toString();
    }

    private void showStablePanel(final int s) {
        panelKind = 3;
        panelStable = s;
        map.selectedVehicle = -1;
        map.selectedField = -1;
        openPanel(Defs.S_ICON[s] + " " + Defs.S_NAMES[s], stableText(s));
        List<Button> bs = new ArrayList<>();
        if (!game.stables[s]) {
            bs.add(button("🛒 Bauen (" + Game.euro(Defs.S_PRICE[s]) + ")", ORANGE, x ->
                    confirmBuy(Defs.S_NAMES[s], Defs.S_PRICE[s], () -> {
                        String e = game.buyStable(s);
                        if (e == null) handler.post(() -> showStablePanel(s));
                        return e;
                    })));
        } else {
            bs.add(button("🚜 Futter bringen", GREEN, x -> chooseFeed(null, s)));
            if (!game.pvStable[s]) {
                bs.add(button("☀ PV aufs Dach (" + Game.euro(Defs.PV_ROOF_PRICE) + ")", BTN, x ->
                        confirmBuy("PV-Anlage auf dem " + Defs.S_NAMES[s], Defs.PV_ROOF_PRICE, () -> {
                            String e = game.buyPvRoof(2 + s);
                            if (e == null) handler.post(() -> showStablePanel(s));
                            return e;
                        })));
            }
        }
        addPanelButtons(bs.toArray(new Button[0]));
    }

    private void showTraderInfo(int b) {
        StringBuilder sb = new StringBuilder();
        for (int p = 0; p < 4; p++) {
            if (!game.buys(b, p)) continue;
            sb.append(String.format(Locale.GERMANY, "• %s: %s/t", Defs.PRODUCT_NAMES[p], Game.euro(game.price(b, p))));
            if (b == game.bonusBuyer && p == game.bonusProduct) sb.append("  📢 Sonder-Nachfrage!");
            sb.append("\n");
        }
        sb.append("\nZum Verkaufen: Traktor antippen → „Ware verkaufen“.");
        new AlertDialog.Builder(this).setTitle(Defs.BUYER_NAMES[b] + " kauft:").setMessage(sb.toString())
                .setPositiveButton("OK", null).show();
    }

    // ----------------------------------------------------------------- Dialoge

    private void showShop() {
        final String[] cats = {"🚜 Traktoren", "🌾 Mähdrescher", "🌽 Feldhäcksler", "🥔 Kartoffelroder", "⛏ Grubber",
                "🌱 Sämaschinen", "🥔 Kartoffel-Legemaschinen", "🧪 Düngerstreuer", "🚛 Kipper", "✂ Mähwerke", "🌿 Ladewagen",
                "🐄 Ställe", "🏗 Bauen: Windrad & PV", "🌾 Felder", "💸 Maschinen & Geräte verkaufen"};
        new AlertDialog.Builder(this).setTitle("Kaufen/Verkaufen – Kontostand " + Game.euro(game.money))
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
                        case 12: shopBuild(); break;
                        case 13:
                            new AlertDialog.Builder(this).setMessage(
                                    "Felder kaufst du direkt auf der Karte: Feld antippen → „Kaufen“.\nPreis: ca. "
                                            + Game.euro(Defs.FIELD_PRICE_HA) + " pro Hektar.").setPositiveButton("OK", null).show();
                            break;
                        default: sellStuff();
                    }
                }).show();
    }

    interface PickAction {
        void pick(int index);
    }

    /** Liste mit kleinen Bildern */
    private void showImageList(String title, final List<String> texts, final List<android.graphics.Bitmap> images, final PickAction action) {
        android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
            @Override
            public int getCount() {
                return texts.size();
            }

            @Override
            public Object getItem(int i) {
                return texts.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View convert, android.view.ViewGroup parent) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(8), dp(12), dp(8));
                android.widget.ImageView img = new android.widget.ImageView(MainActivity.this);
                android.graphics.Bitmap b = images.get(i);
                if (b != null) img.setImageBitmap(b);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xFF6FA84E);
                bg.setCornerRadius(dp(8));
                img.setBackground(bg);
                img.setPadding(dp(4), dp(4), dp(4), dp(4));
                row.addView(img, new LinearLayout.LayoutParams(dp(96), dp(52)));
                TextView tv = new TextView(MainActivity.this);
                tv.setText(texts.get(i));
                tv.setTextSize(14);
                tv.setPadding(dp(12), 0, 0, 0);
                row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));
                return row;
            }
        };
        new AlertDialog.Builder(this).setTitle(title).setAdapter(adapter, (d, which) -> action.pick(which)).show();
    }

    private android.graphics.Bitmap thumb(Defs.Model m, Defs.Model tool) {
        return map.thumbnail(m, tool, dp(88), dp(44));
    }

    private void shopModels(String title, int kind, int sub) {
        final List<Defs.Model> list = new ArrayList<>();
        for (Defs.Model m : Defs.MODELS) {
            if (m.kind != kind) continue;
            if (kind != Defs.K_TRACTOR && m.sub != sub) continue;
            list.add(m);
        }
        List<String> texts = new ArrayList<>();
        List<android.graphics.Bitmap> imgs = new ArrayList<>();
        for (Defs.Model m : list) {
            int have = 0;
            for (Game.Vehicle v : game.vehicles) if (v.model == m.id) have++;
            for (Game.Tool t : game.tools) if (t.model == m.id) have++;
            texts.add(m.name + " – " + Game.euro(m.price) + (have > 0 ? "  (hast du: " + have + ")" : "") + "\n" + m.info);
            imgs.add(thumb(m, null));
        }
        String head = kind == Defs.K_TOOL ? title + " (Stufe = nötige Traktor-Leistung)" : title;
        showImageList(head, texts, imgs, which -> {
            final Defs.Model m = list.get(which);
            confirmBuy(m.name, m.price, () -> game.buy(m.id));
        });
    }

    private void shopStables() {
        String[] items = new String[Defs.STABLES];
        for (int i = 0; i < Defs.STABLES; i++) {
            items[i] = Defs.S_ICON[i] + " " + Defs.S_NAMES[i] + " (" + Defs.S_ANIMALS[i] + ") – "
                    + (game.stables[i] ? "✔ hast du" : Game.euro(Defs.S_PRICE[i]));
        }
        new AlertDialog.Builder(this).setTitle("Ställe (jeder hat einen eigenen Bauplatz)").setItems(items, (d, which) -> {
            int[] lot = game.world.stableLot[which];
            map.centerOn((lot[0] + lot[2] / 2f) * Defs.TILE, (lot[1] + lot[3] / 2f) * Defs.TILE);
            showStablePanel(which);
        }).show();
    }

    private void shopBuild() {
        final List<Integer> codes = new ArrayList<>();
        List<String> items = new ArrayList<>();
        codes.add(-1);
        items.add(String.format(Locale.GERMANY, "💨 Windkraftanlage – %s\n   bis %s/Tag bei gutem Wind", Game.euro(Defs.WIND_PRICE),
                Game.euro(Defs.WIND_INCOME)));
        if (!game.pvHouse) {
            codes.add(0);
            items.add("☀ PV aufs Wohnhaus – " + Game.euro(Defs.PV_ROOF_PRICE));
        }
        if (!game.pvHall) {
            codes.add(1);
            items.add("☀ PV auf die Maschinenhalle – " + Game.euro(Defs.PV_ROOF_PRICE));
        }
        for (int s = 0; s < Defs.STABLES; s++) {
            if (game.stables[s] && !game.pvStable[s]) {
                codes.add(2 + s);
                items.add("☀ PV auf den " + Defs.S_NAMES[s] + " – " + Game.euro(Defs.PV_ROOF_PRICE));
            }
        }
        codes.add(-2);
        items.add("☀ Solarpark auf einem eigenen Feld – Feld antippen");
        new AlertDialog.Builder(this).setTitle("Bauen – Kontostand " + Game.euro(game.money))
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    int code = codes.get(which);
                    if (code == -1) {
                        pickMode = PICK_WIND;
                        panelKind = 0;
                        openPanel("💨 Windrad bauen", "Tippe auf eine freie Wiese (nicht direkt an Straße, Feld oder Wald).");
                        addPanelButtons(button("Abbrechen", RED, x -> {
                            pickMode = PICK_NONE;
                            hidePanel();
                        }));
                    } else if (code == -2) {
                        toast("Tippe auf eines deiner Felder und dann auf „☀ Solarpark“.");
                    } else {
                        confirmBuy("PV-Anlage", Defs.PV_ROOF_PRICE, () -> game.buyPvRoof(code));
                    }
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
        sb.append("📦 Hoflager:\n");
        for (int p = 0; p < 4; p++) {
            sb.append(String.format(Locale.GERMANY, "• %s: %.1f t\n", Defs.PRODUCT_NAMES[p], game.storage[p]));
        }
        sb.append("\n💶 Preise heute (€/t):\n");
        if (game.bonusText() != null) sb.append(game.bonusText()).append("\n");
        for (int p = 0; p < 4; p++) {
            sb.append("• ").append(Defs.PRODUCT_NAMES[p]).append(": ");
            boolean first = true;
            for (int b = 0; b < 3; b++) {
                if (!game.buys(b, p)) continue;
                if (!first) sb.append(" | ");
                sb.append(Defs.BUYER_NAMES[b]).append(" ").append(Game.euro(game.price(b, p)));
                first = false;
            }
            sb.append("\n");
        }
        sb.append("\n🐾 Ställe:\n");
        boolean any = false;
        for (int s = 0; s < Defs.STABLES; s++) {
            if (!game.stables[s]) continue;
            any = true;
            double days = game.stableDays(s);
            sb.append(String.format(Locale.GERMANY, "%s %s – Futter reicht %.0f Tage %s\n", Defs.S_ICON[s], Defs.S_NAMES[s], days,
                    days < 3 ? "⚠" : ""));
        }
        if (!any) sb.append("keine\n");
        sb.append(String.format(Locale.GERMANY, "\n⚡ Energie gestern: %s  (%d PV-Dächer, %.1f ha Solarpark, %d Windräder)",
                Game.euro(game.lastEnergy), game.pvRoofs(), game.pvFieldHa(), game.winds.size()));
        sb.append("\n🌦 Wetter heute: ").append(game.weatherText());
        int owned = 0;
        double ha = 0;
        for (Field f : game.world.fields) if (f.owned) {
            owned++;
            ha += f.hectares();
        }
        sb.append(String.format(Locale.GERMANY, "\n🌾 Deine Felder: %d (%.1f ha)", owned, ha));
        new AlertDialog.Builder(this).setTitle("Lager & Preise").setMessage(sb.toString())
                .setPositiveButton("OK", null)
                .setNeutralButton("🐾 Futter bringen", (d, w) -> chooseFeed(null, -1))
                .show();
    }

    private void showFleet() {
        final List<Object> things = new ArrayList<>();
        List<String> items = new ArrayList<>();
        List<android.graphics.Bitmap> imgs = new ArrayList<>();
        for (Game.Vehicle v : game.vehicles) {
            Game.Tool t = game.tool(v.tool);
            things.add(v);
            items.add(v.name() + (v.isTractor() ? " (Stufe " + v.m().level + ")" : "") + "\n" + v.status
                    + (t != null ? " • mit " + t.m().name : ""));
            imgs.add(thumb(v.m(), t != null ? t.m() : null));
        }
        for (Game.Tool t : game.tools) {
            String where;
            Game.Vehicle at = game.vehicle(t.attachedTo);
            Game.Vehicle res = game.vehicle(t.reservedBy);
            if (at != null) where = "hängt an " + at.name();
            else if (res != null) where = "reserviert für " + res.name();
            else where = "steht am Hof";
            things.add(t);
            items.add(t.m().name + " (braucht Stufe " + t.m().level + ")\n" + where);
            imgs.add(thumb(t.m(), null));
        }
        showImageList("Fuhrpark: " + game.vehicles.size() + " Fahrzeuge, " + game.tools.size() + " Geräte", items, imgs, which -> {
            Object o = things.get(which);
            if (o instanceof Game.Vehicle) {
                Game.Vehicle v = (Game.Vehicle) o;
                map.centerOn(v.x, v.y);
                showVehiclePanel(v);
            } else {
                showToolInfo((Game.Tool) o);
            }
        });
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

    private void applyDetail(int d) {
        map.detail = d;
        game.trafficTarget = d == 0 ? 0 : d == 1 ? 12 : 22;
        getPreferences(MODE_PRIVATE).edit().putInt("detail", d).apply();
    }

    private void showGameMenu() {
        String[] names = {"Einfach", "Mittel", "Hoch"};
        String[] items = {"💾 Speichern", "🎨 Grafik: " + names[map.detail], "❓ Anleitung", "🏠 Karte zum Hof", "🚪 Speichern & zum Hauptmenü"};
        new AlertDialog.Builder(this).setTitle("Menü").setItems(items, (d, which) -> {
            if (which == 0) save(true);
            else if (which == 1) {
                String[] opts = {"Einfach – für ältere Handys (schlichte Felder, kein Verkehr)",
                        "Mittel – mit Verkehr", "Hoch – mit Schatten, Staub und Tieren an den Ställen"};
                new AlertDialog.Builder(this).setTitle("Grafik-Detail").setItems(opts, (d2, w2) -> {
                    applyDetail(w2);
                    message("🎨 Grafik: " + names[w2]);
                }).show();
            } else if (which == 2) showHelp();
            else if (which == 3) {
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
