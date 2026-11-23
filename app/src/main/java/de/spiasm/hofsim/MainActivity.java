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

public class MainActivity extends Activity implements Game.Listener, MapView.TapListener, Net.Listener {
    private static final int BG = 0xFF1F2A1A;
    private static final int BTN = 0xFF3B4A30;
    private static final int GREEN = 0xFF2E7D32;
    private static final int BLUE = 0xFF1565C0;
    private static final int RED = 0xFFC62828;
    private static final int ORANGE = 0xFFEF6C00;

    private static final int PICK_NONE = 0, PICK_FIELD = 1, PICK_GOTO = 2, PICK_WIND = 3, PICK_FOREST = 4;
    private final Ambience sound = new Ambience();
    private boolean soundOn = true;
    private float volAmbient = 0.8f, volMachines = 0.8f;
    private int panelForest = -1;

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
        sound.stop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (game != null && map != null) startLoop();
    }

    private void updateSound() {
        sound.volAmbient = volAmbient;
        sound.volMachines = volMachines;
        if (!soundOn || (volAmbient <= 0 && volMachines <= 0) || paused || map == null) {
            sound.stop();
            return;
        }
        sound.start();
        float cxw = map.camX, cyw = map.camY, sc = map.scale;
        float viewR = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels) / sc / 2f;
        float zoomNear = Math.min(1f, Math.max(0.15f, sc / 3f));
        boolean winter = game.weatherType == 3;
        double day = game.daylight();
        World w = game.world;
        // Vögel: tagsüber, nicht im Winter/Regen
        sound.birds = (float) (day > 0 && !winter && game.weatherType != 2 ? 0.4 + 0.6 * Math.min(1, day * 2) : 0) * zoomNear;
        // Wind in Blättern: nahe Wald
        float forest = 0;
        for (Forest fo : w.forests) {
            float d = dist(cxw, cyw, (fo.x + fo.w / 2f) * Defs.TILE, (fo.y + fo.h / 2f) * Defs.TILE);
            if (fo.state == Forest.GROWING) forest = Math.max(forest, near(d, viewR * 1.3f) * (float) Math.min(1, fo.growth + 0.2));
        }
        sound.leaves = Math.max(0.12f, forest) * (float) Math.min(1.2, 0.4 + game.weatherWind) * zoomNear;
        sound.wind = (float) game.weatherWind;
        float vil = 0;
        for (int[] vc : w.villageCenters) vil = Math.max(vil, near(dist(cxw, cyw, vc[0] * Defs.TILE, vc[1] * Defs.TILE), viewR * 1.5f));
        sound.village = vil * (day > 0 ? 0.8f : 0.25f) * zoomNear;
        sound.rain = game.weatherType == 2 ? 0.8f : 0;
        float eng = 0;
        for (Game.Vehicle v : game.vehicles) {
            if (!v.busy()) continue;
            eng = Math.max(eng, near(dist(cxw, cyw, v.x, v.y), viewR));
        }
        sound.engine = eng * 0.8f * zoomNear;
        for (int s = 0; s < Defs.STABLES; s++) {
            float a = 0;
            if (game.stables[s]) {
                int[] lot = w.stableLot[s];
                a = near(dist(cxw, cyw, (lot[0] + lot[2] / 2f) * Defs.TILE, (lot[1] + lot[3] / 2f) * Defs.TILE), viewR) * zoomNear;
            }
            sound.animals[s] = a;
        }
        sound.siren = game.fireField >= 0 && game.fireTruck != null && game.fireTruck.state == 0 && fireAge < 25 ? 0.6f : 0;
        sound.horn = game.fireTruck != null && game.fireTruck.state != 1 ? 0.25f + 0.75f * near(dist(cxw, cyw, game.fireTruck.x, game.fireTruck.y), viewR * 1.5f) : 0;
        if (game.fireField >= 0) fireAge += 0.3f;
        else fireAge = 0;
    }

    private float fireAge;

    private static float dist(float ax, float ay, float bx, float by) {
        return (float) Math.hypot(ax - bx, ay - by);
    }

    /** 1 = mitten drin, 0 = weit weg */
    private static float near(float d, float r) {
        return Math.max(0, Math.min(1, 1.3f - d / Math.max(1, r)));
    }

    @Override
    public void onBackPressed() {
        if (pickMode != PICK_NONE) {
            pickMode = PICK_NONE;
            hidePanel();
        } else if (panel != null && panel.getVisibility() == View.VISIBLE) {
            hidePanel();
        } else if (game != null && map != null) {
            if (multiplayer()) {
                new AlertDialog.Builder(this).setMessage(isClient ? "Spiel verlassen?" : "Spiel speichern und beenden? Die Mitspieler fliegen dann raus.")
                        .setPositiveButton("Ja", (d, w) -> {
                            save(true);
                            running = false;
                            showStart();
                        }).setNegativeButton("Nein", null).show();
                return;
            }
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
        if (game == null || isClient) return;
        try {
            String s = game.toJson().toString();
            try (FileOutputStream out = new FileOutputStream(multiplayer() ? mpFile() : saveFile())) {
                out.write(s.getBytes(StandardCharsets.UTF_8));
            }
            if (announce) toast("💾 Gespeichert");
        } catch (Exception e) {
            if (announce) toast("Speichern hat nicht geklappt.");
        }
    }

    private Game load() {
        return loadFrom(saveFile());
    }

    private Game loadFrom(File f) {
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
        sound.stop();
        closeNet();
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
        add(root, button("👥  Mehrspieler (Bluetooth)", 0xFF6A1B9A, v -> showMultiMenu()));
        add(root, button("❓  Anleitung", BTN, v -> showHelp()));
        add(root, text("© 2026 SM", 13, 0xFF9DB08C, false));
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
                    + "🌲 Wald: Waldstück antippen → kaufen. Pflanzen (Fichte, Buche, Eiche) mit Traktor + Pflanzmaschine. "
                    + "Ist der Wald groß: Fällen mit Prozessor/Harvester → Rücken (Forwarder oder Traktor + Rückewagen) bringt die Stämme "
                    + "an den Polter an der Straße → Holz verkaufen fährt es mit Holz-Lkw oder Kurzholzanhänger zum Sägewerk. "
                    + "Restholz mit dem Holzhacker zu Hackschnitzeln machen (Biogas kauft sie).\n\n"
                    + "💩 Gülle & Mist: Kühe und Schweine machen Gülle, Hühner und Schafe Mist. Beim Düngen kannst du statt Kunstdünger "
                    + "Gülle (Güllefass) oder Mist (Miststreuer) nehmen – kostet nichts, muss aber vom Stall geholt werden.\n\n"
                    + "🚛 Überladen: Bei 80 % Bunker wird ein Traktor mit Kipper gerufen, der während der Fahrt überlädt.\n\n"
                    + "🏠 Hof antippen: 3D-Ansicht mit all deinen Maschinen, dazu Lager, Verkaufen und Futter.\n\n"
                    + "🌦 Wetter: Sonne, Wolken, Regen und im Winter Schnee – nur zum Anschauen, die Ernte leidet nicht. "
                    + "PV und Windräder zeigen ihre aktuelle Leistung in %.\n\n"
                    + "👥 Mehrspieler (bis 3 Handys per Bluetooth): Startbildschirm → Mehrspieler. Einer erstellt das Spiel, die anderen "
                    + "treten bei. Spielarten: gemeinsamer Hof, jeder ein eigener Hof, oder eigene Höfe + helfen (Fahrzeug antippen → "
                    + "„Verleihen“, Verkaufen → „an Mitspieler“). Die Felder der Mitspieler haben ihre Farbe (blau/orange). "
                    + "Der Gastgeber speichert das Spiel.\n\n"
                    + "🔊 Geräusche: im Menü ein- und ausschalten, Lautstärke für Umgebung und Maschinen getrennt einstellen.\n\n"
                    + "🔥 Bei trockenem Wetter kann ein Stoppelfeld brennen – die Feuerwehr kommt und löscht.\n\n"
                    + "🎨 Grafik: Im Menü zwischen Einfach, Mittel und Hoch umschalten.\n\n"
                    + "💾 Speichern: automatisch jede Minute, beim Verlassen und im Menü.\n\n"
                    + "⏩ Zeit: oben rechts. Weizen wächst ca. 7 Tage – bei 6000× knapp 2 Minuten.\n\n"
                    + "© 2026 SM";

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
        soundOn = getPreferences(MODE_PRIVATE).getBoolean("sound", true);
        volAmbient = getPreferences(MODE_PRIVATE).getFloat("volAmbient", 0.8f);
        volMachines = getPreferences(MODE_PRIVATE).getFloat("volMachines", 0.8f);
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
            act("pause", "b", !paused);
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
            drainMessages();
            if (net != null && net.host && (now - lastSnap > 1500 || (snapDirty && now - lastSnap > 250))) sendSnapshot();
            if (now - lastUi > 300) {
                lastUi = now;
                updateTop();
                refreshPanel();
                updateSound();
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
                    act("speed", "i", which + 1);
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
                String err = act("goto", "v", v.id, "x", tx, "y", ty);
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
                        String err = act("wind", "x", fx, "y", fy);
                        if (err != null) toast(err);
                        else message("💨 Neue Windkraftanlage in Betrieb!");
                        updateTop();
                    }).setNegativeButton("Abbrechen", null).show();
            hidePanel();
            return;
        }
        if (pickMode == PICK_FOREST) {
            Game.Vehicle v = game.vehicle(pickVehicle);
            if (!w.in(tx, ty) || w.forestAt[tx][ty] < 0) {
                toast("Bitte auf eines deiner Waldstücke tippen.");
                return;
            }
            pickMode = PICK_NONE;
            Forest fo = w.forests.get(w.forestAt[tx][ty]);
            if (v != null) chooseForestTaskFor(v, fo);
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
        for (int[] wd : game.winds) {
            if (Math.hypot((wd[0] + 0.5f) * Defs.TILE - mx, (wd[1] + 0.5f) * Defs.TILE - my) < Math.max(10, 30 / map.scale)) {
                showEnergyInfo();
                return;
            }
        }
        if (w.in(tx, ty) && w.fieldAt[tx][ty] >= 0) {
            Field ff = w.fields.get(w.fieldAt[tx][ty]);
            showFieldPanel(ff);
            return;
        }
        if (w.in(tx, ty) && w.forestAt[tx][ty] >= 0) {
            showForestPanel(w.forests.get(w.forestAt[tx][ty]));
            return;
        }
        for (int s = 0; s < Defs.STABLES; s++) {
            if (inRect(w.stableLot[s], tx, ty)) {
                showStablePanel(s);
                return;
            }
        }
        for (int b = 0; b < Defs.BUYERS; b++) {
            if (inRect(w.trader[b], tx, ty)) {
                showTraderInfo(b);
                return;
            }
        }
        for (int f = 0; f < game.farmCount(); f++) {
            if (f != game.currentFarm() && inRect(w.farmHof[f], tx, ty)) {
                showOtherHof(f);
                return;
            }
        }
        if (inRect(w.hof, tx, ty)) {
            showHof3D();
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
        map.selectedForest = -1;
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
        } else if (panelKind == 4 && panelForest >= 0) {
            panelText.setText(forestText(game.world.forests.get(panelForest)));
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
        if (v.load > 0) {
            String what = v.loadProduct == -2 ? "m³ Gülle" : v.loadProduct == -3 ? "t Mist"
                    : v.loadProduct >= 0 ? "t " + Defs.PRODUCT_NAMES[v.loadProduct] : "t";
            double cap = game.capOf(v);
            sb.append(String.format(Locale.GERMANY, "\nLadung: %.1f %s", v.load, what));
            if (cap > 0) sb.append(String.format(Locale.GERMANY, " (%.0f %%)", 100 * v.load / cap));
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
        if (!v.isForestry()) {
            bs.add(button("🌾 Feldarbeit", GREEN, x -> {
                pickMode = PICK_FIELD;
                pickVehicle = v.id;
                showPickHint("Tippe auf das Feld, auf dem " + v.name() + " arbeiten soll.");
            }));
        }
        if (v.isForestry() || v.isTractor()) {
            bs.add(button("🌲 Waldarbeit", GREEN, x -> {
                pickMode = PICK_FOREST;
                pickVehicle = v.id;
                showPickHint("Tippe auf das Waldstück, in dem " + v.name() + " arbeiten soll.");
            }));
        }
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
            act("home", "v", v.id);
            message("👷 " + v.name() + " fährt zum Hof.");
        }));
        bs.add(button("⏹ Anhalten", RED, x -> {
            act("stop", "v", v.id);
            message(v.name() + " hält an.");
        }));
        if (helping()) {
            if (v.lentFrom >= 0) {
                bs.add(button("↩ Zurückgeben an " + game.farms[v.lentFrom].name, BTN, x -> {
                    String err = act("ret", "v", v.id);
                    if (err != null) toast(err);
                    hidePanel();
                }));
            } else {
                bs.add(button("🤝 Verleihen", BTN, x -> chooseLend(v)));
            }
        }
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
        if (!f.owned && f.owner >= 0) s += "\nGehört " + game.farms[f.owner].name;
        else if (!f.owned) s += "\nGehört einem Nachbarn – Preis: " + Game.euro(f.price());
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
        if (!f.owned && f.owner >= 0) {
            // gehört einem Mitspieler
        } else if (!f.owned) {
            bs.add(button("🛒 Kaufen (" + Game.euro(f.price()) + ")", ORANGE, x -> {
                new AlertDialog.Builder(this)
                        .setMessage("Feld " + f.number + " (" + String.format(Locale.GERMANY, "%.1f", f.hectares())
                                + " ha) für " + Game.euro(f.price()) + " kaufen?")
                        .setPositiveButton("Kaufen", (d, w) -> {
                            String err = act("buyField", "f", f.index);
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
                                String err = act("solar", "f", f.index);
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
        } else if (task == Defs.TASK_FERTILIZE && f.progress <= 0) {
            chooseFert(f, mode -> autoTaskCrop(f, task, mode));
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
        String err = act("field", "v", v.id, "f", f.index, "t", task, "c", crop);
        if (err != null) toast(err);
        showFieldPanel(f);
    }

    interface CropCallback {
        void onCrop(int crop);
    }

    private void chooseFert(final Field f, final CropCallback cb) {
        String[] items = {
                "🧪 Kunstdünger – " + Game.euro(Defs.FERTILIZER_COST * f.hectares()) + " (Düngerstreuer)",
                String.format(Locale.GERMANY, "💧 Gülle – kostenlos, %.0f m³ in den Ställen (Güllefass)", game.manureAvailable(Defs.FERT_SLURRY)),
                String.format(Locale.GERMANY, "💩 Mist – kostenlos, %.0f t in den Ställen (Miststreuer)", game.manureAvailable(Defs.FERT_DUNG))};
        new AlertDialog.Builder(this).setTitle("Womit düngen auf Feld " + f.number + "?")
                .setItems(items, (d, which) -> cb.onCrop(which == 0 ? Defs.FERT_MINERAL : which == 1 ? Defs.FERT_SLURRY : Defs.FERT_DUNG))
                .show();
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
            if (t == Defs.TASK_FERTILIZE) probeCrop = Defs.FERT_MINERAL;
            String err = game.checkFieldJob(v, f, t, probeCrop);
            if (t == Defs.TASK_SOW && err != null) {
                String e2 = game.checkFieldJob(v, f, t, Defs.POTATO);
                if (e2 == null) err = null;
            }
            if (t == Defs.TASK_FERTILIZE && err != null) {
                if (game.checkFieldJob(v, f, t, Defs.FERT_SLURRY) == null || game.checkFieldJob(v, f, t, Defs.FERT_DUNG) == null) err = null;
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
                            String err = act("field", "v", v.id, "f", f.index, "t", task, "c", crop);
                            if (err != null) toast(err);
                            showVehiclePanel(v);
                        });
                    } else if (task == Defs.TASK_FERTILIZE && f.progress <= 0) {
                        chooseFert(f, mode -> {
                            String err = act("field", "v", v.id, "f", f.index, "t", task, "c", mode);
                            if (err != null) toast(err);
                            showVehiclePanel(v);
                        });
                    } else {
                        String err = act("field", "v", v.id, "f", f.index, "t", task, "c", f.taskCrop >= 0 ? f.taskCrop : Defs.WHEAT);
                        if (err != null) toast(err);
                        showVehiclePanel(v);
                    }
                }).show();
    }

    private void chooseSell(final Game.Vehicle v) {
        final List<Integer> prods = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int p = 0; p < Defs.PRODUCTS; p++) {
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
        for (int b = 0; b < Defs.BUYERS; b++) {
            if (!game.buys(b, product)) continue;
            buyers.add(b);
            labels.add(Defs.BUYER_NAMES[b] + " – " + Game.euro(game.price(b, product)) + "/t" + (b == best ? "  ⭐ bester Preis" : "")
                    + (b == game.bonusBuyer && product == game.bonusProduct ? "  📢" : ""));
        }
        if (helping()) {
            for (int f = 0; f < game.farmCount(); f++) {
                if (f == game.currentFarm()) continue;
                buyers.add(100 + f);
                labels.add("🤝 an Mitspieler " + game.farms[f].name + " (Preis selbst festlegen)");
            }
        }
        if (buyers.size() == 1) {
            chooseAmount(v, product, buyers.get(0));
            return;
        }
        new AlertDialog.Builder(this).setTitle("Wohin mit dem " + Defs.PRODUCT_NAMES[product] + "?")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    int b = buyers.get(which);
                    if (b >= 100) askTrade(product, b - 100);
                    else chooseAmount(v, product, b);
                }).show();
    }

    private void chooseAmount(final Game.Vehicle v, final int product, final int buyer) {
        String[] items = {"🚛 Nur 1 Ladung", String.format(Locale.GERMANY, "🔁 Fahren bis leer (%.1f t)", game.storage[product])};
        new AlertDialog.Builder(this).setTitle(Defs.PRODUCT_NAMES[product] + " " + Defs.BUYER_TO[buyer])
                .setItems(items, (d, which) -> {
                    Game.Vehicle tr = v != null ? v : game.findTransportTractor();
                    if (tr == null) {
                        toast(game.transportProblem());
                        return;
                    }
                    String err = act("sell", "v", tr.id, "p", product, "b", buyer, "r", which == 1);
                    if (err != null) toast(err);
                    showVehiclePanel(tr);
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
                                String e2 = act("feed", "v", tr.id, "s", s, "p", p, "r", w2 == 1);
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
        if (game.manureCap(s) > 0) {
            boolean slurry = Defs.S_SLURRY[s] > 0;
            sb.append(String.format(Locale.GERMANY, "\n%s: %.0f von %.0f %s", slurry ? "💧 Güllegrube" : "💩 Misthaufen",
                    game.stableManure[s], game.manureCap(s), slurry ? "m³" : "t"));
            if (game.stableManure[s] > 0.9 * game.manureCap(s)) sb.append(" ⚠ fast voll – zum Düngen ausbringen!");
        }
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
                        String e = act("buyStable", "s", s);
                        if (e == null) handler.post(() -> showStablePanel(s));
                        return e;
                    })));
        } else {
            bs.add(button("🚜 Futter bringen", GREEN, x -> chooseFeed(null, s)));
            if (!game.pvStable[s]) {
                bs.add(button("☀ PV aufs Dach (" + Game.euro(Defs.PV_ROOF_PRICE) + ")", BTN, x ->
                        confirmBuy("PV-Anlage auf dem " + Defs.S_NAMES[s], Defs.PV_ROOF_PRICE, () -> {
                            String e = act("pv", "w", 2 + s);
                            if (e == null) handler.post(() -> showStablePanel(s));
                            return e;
                        })));
            }
        }
        addPanelButtons(bs.toArray(new Button[0]));
    }

    private void showTraderInfo(final int b) {
        StringBuilder sb = new StringBuilder();
        for (int p = 0; p < Defs.PRODUCTS; p++) {
            if (!game.buys(b, p)) continue;
            sb.append(String.format(Locale.GERMANY, "• %s: %s/t", Defs.PRODUCT_NAMES[p], Game.euro(game.price(b, p))));
            if (b == game.bonusBuyer && p == game.bonusProduct) sb.append("  📢 Sonder-Nachfrage!");
            sb.append("\n");
        }
        if (b == Defs.B_SAWMILL) sb.append("\nHolz bringst du direkt vom Polter am Wald hierher: Waldstück antippen → „Holz verkaufen“.");
        new AlertDialog.Builder(this).setTitle(Defs.BUYER_NAMES[b] + " kauft:").setMessage(sb.toString())
                .setPositiveButton("OK", null)
                .setNeutralButton("💰 Hierhin verkaufen", (d, w) -> sellTo(b)).show();
    }

    /** Vom Händler aus verkaufen: Ware wählen, Traktor wird automatisch gesucht */
    private void sellTo(final int b) {
        if (b == Defs.B_SAWMILL) {
            final List<Forest> fs = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            for (Forest fo : game.world.forests) {
                if (!fo.owned || fo.polter < 0.5) continue;
                fs.add(fo);
                labels.add(String.format(Locale.GERMANY, "🌲 Wald %d – %.0f t %s am Polter", fo.number, fo.polter, Defs.SPECIES_NAMES[fo.woodSpecies]));
            }
            if (fs.isEmpty() && game.storage[Defs.CHIPS] < 0.5) {
                toast("Gerade liegt kein Holz am Polter.");
                return;
            }
            if (fs.isEmpty()) {
                chooseAmount(null, Defs.CHIPS, b);
                return;
            }
            new AlertDialog.Builder(this).setTitle("Holz von welchem Wald?")
                    .setItems(labels.toArray(new String[0]), (d, which) -> chooseWoodAmount(fs.get(which))).show();
            return;
        }
        final List<Integer> prods = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int p = 0; p < Defs.PRODUCTS; p++) {
            if (!game.buys(b, p) || game.storage[p] < 0.5) continue;
            prods.add(p);
            labels.add(String.format(Locale.GERMANY, "%s – %.1f t im Lager, %s/t", Defs.PRODUCT_NAMES[p], game.storage[p],
                    Game.euro(game.price(b, p))));
        }
        if (prods.isEmpty()) {
            toast("Du hast nichts im Lager, was " + Defs.BUYER_NAMES[b] + " kauft.");
            return;
        }
        new AlertDialog.Builder(this).setTitle("Was " + Defs.BUYER_TO[b] + "?")
                .setItems(labels.toArray(new String[0]), (d, which) -> chooseAmount(null, prods.get(which), b)).show();
    }

    // ------------------------------------------------------------------ Wald

    private String forestText(Forest fo) {
        String s = String.format(Locale.GERMANY, "%.1f ha • ", fo.hectares()) + fo.stateText();
        if (!fo.owned && fo.owner >= 0) s += "\nGehört " + game.farms[fo.owner].name;
        else if (!fo.owned) s += "\nGehört einem Nachbarn – Preis: " + Game.euro(fo.price());
        if (fo.busyBy >= 0) {
            Game.Vehicle v = game.vehicle(fo.busyBy);
            if (v != null) s += "\n👷 " + v.name() + ": " + v.status;
        }
        if (fo.owned && fo.state == Forest.GROWING && fo.growth < 1) {
            s += String.format(Locale.GERMANY, "\nHiebreif in ca. %.0f Tagen", (1 - fo.growth) * Defs.TREE_DAYS[fo.species]);
        }
        return s;
    }

    private void showForestPanel(final Forest fo) {
        panelKind = 4;
        panelForest = fo.index;
        map.selectedForest = fo.index;
        map.selectedField = -1;
        map.selectedVehicle = -1;
        openPanel((fo.owned ? "★ " : "") + "🌲 Wald " + fo.number, forestText(fo));
        List<Button> bs = new ArrayList<>();
        if (!fo.owned && fo.owner >= 0) {
            // gehört einem Mitspieler
        } else if (!fo.owned) {
            bs.add(button("🛒 Kaufen (" + Game.euro(fo.price()) + ")", ORANGE, x ->
                    confirmBuy("Wald " + fo.number, fo.price(), () -> {
                        String e = act("buyForest", "fo", fo.index);
                        if (e == null) handler.post(() -> showForestPanel(fo));
                        return e;
                    })));
        } else {
            for (int t = 0; t < 4; t++) {
                if (!fo.canDo(t)) continue;
                final int task = t;
                String icon = t == Defs.FT_PLANT ? "🌱" : t == Defs.FT_FELL ? "🪓" : t == Defs.FT_FORWARD ? "🚜" : "🪵";
                bs.add(button(icon + " " + Defs.FT_NAMES[t] + (fo.progress > 0 && fo.task == t ? " (weiter)" : ""), GREEN,
                        x -> autoForest(fo, task)));
            }
            if (fo.polter >= 0.5) {
                bs.add(button("💰 Holz verkaufen", ORANGE, x -> chooseWoodAmount(fo)));
            }
        }
        if (!bs.isEmpty()) addPanelButtons(bs.toArray(new Button[0]));
    }

    private void autoForest(final Forest fo, final int task) {
        if (task == Defs.FT_PLANT && fo.progress <= 0) {
            chooseSpecies(fo, sp -> autoForestSp(fo, task, sp));
        } else {
            autoForestSp(fo, task, fo.taskSpecies >= 0 ? fo.taskSpecies : Defs.SPRUCE);
        }
    }

    private void autoForestSp(Forest fo, int task, int species) {
        Game.Vehicle v = game.findVehicleForForest(fo, task, species);
        if (v == null) {
            String reason = null;
            for (Game.Vehicle c : game.vehicles) {
                String e = game.checkForestJob(c, fo, task, species);
                if (reason == null || (e != null && !e.contains("beschäftigt"))) reason = e;
            }
            if (task == Defs.FT_FELL) {
                boolean have = false;
                for (Game.Vehicle c : game.vehicles) if (c.isForestry() && c.m().sub == Defs.F_FELLER) have = true;
                if (!have) reason = "Zum Fällen brauchst du einen Prozessor oder Harvester (Shop → 🌲 Forstmaschinen).";
            }
            new AlertDialog.Builder(this).setTitle("Keine Maschine frei")
                    .setMessage(reason != null ? reason : "Gerade ist keine passende Maschine frei.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String err = act("forest", "v", v.id, "fo", fo.index, "t", task, "sp", species);
        if (err != null) toast(err);
        showForestPanel(fo);
    }

    private void chooseSpecies(final Forest fo, final CropCallback cb) {
        String[] items = new String[3];
        for (int i = 0; i < 3; i++) {
            items[i] = String.format(Locale.GERMANY, "%s %s – Setzlinge %s, wächst ca. %.0f Tage, %s/t", Defs.SPECIES_ICON[i],
                    Defs.SPECIES_NAMES[i], Game.euro(Defs.SEEDLING_COST[i] * fo.hectares()), Defs.TREE_DAYS[i],
                    Game.euro(Defs.BUY_BASE[Defs.B_SAWMILL][Defs.WOOD + i]));
        }
        new AlertDialog.Builder(this).setTitle("Was pflanzen in Wald " + fo.number + "?")
                .setItems(items, (d, which) -> cb.onCrop(which)).show();
    }

    private void chooseForestTaskFor(final Game.Vehicle v, final Forest fo) {
        final List<Integer> tasks = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        String firstErr = fo.owned ? null : "Wald " + fo.number + " gehört dir nicht. Tippe ihn an, um ihn zu kaufen.";
        for (int t = 0; t < 4 && fo.owned; t++) {
            if (!fo.canDo(t)) continue;
            String err = game.checkForestJob(v, fo, t, Defs.SPRUCE);
            if (err == null) {
                tasks.add(t);
                labels.add(Defs.FT_NAMES[t]);
            } else if (firstErr == null) firstErr = err;
        }
        if (fo.owned && fo.polter >= 0.5 && game.checkWood(v, fo) == null) {
            tasks.add(-1);
            labels.add("💰 Holz zum Sägewerk fahren");
        }
        if (tasks.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Geht gerade nicht")
                    .setMessage(firstErr != null ? firstErr : "In Wald " + fo.number + " gibt es gerade nichts zu tun.\n" + fo.stateText())
                    .setPositiveButton("OK", null).show();
            showVehiclePanel(v);
            return;
        }
        new AlertDialog.Builder(this).setTitle(v.name() + " in Wald " + fo.number)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    int task = tasks.get(which);
                    if (task == -1) {
                        String[] items = {"🚛 Nur 1 Ladung", "🔁 Fahren bis der Polter leer ist"};
                        new AlertDialog.Builder(this).setTitle("Holz zum Sägewerk").setItems(items, (d2, w2) -> {
                            String err = act("wood", "v", v.id, "fo", fo.index, "r", w2 == 1);
                            if (err != null) toast(err);
                            showVehiclePanel(v);
                        }).show();
                    } else if (task == Defs.FT_PLANT && fo.progress <= 0) {
                        chooseSpecies(fo, sp -> {
                            String err = act("forest", "v", v.id, "fo", fo.index, "t", task, "sp", sp);
                            if (err != null) toast(err);
                            showVehiclePanel(v);
                        });
                    } else {
                        String err = act("forest", "v", v.id, "fo", fo.index, "t", task, "sp", fo.taskSpecies >= 0 ? fo.taskSpecies : Defs.SPRUCE);
                        if (err != null) toast(err);
                        showVehiclePanel(v);
                    }
                }).show();
    }

    private void chooseWoodAmount(final Forest fo) {
        String[] items = {"🚛 Nur 1 Ladung", String.format(Locale.GERMANY, "🔁 Fahren bis der Polter leer ist (%.0f t)", fo.polter)};
        double price = game.price(Defs.B_SAWMILL, Defs.WOOD + fo.woodSpecies);
        new AlertDialog.Builder(this).setTitle(Defs.SPECIES_NAMES[fo.woodSpecies] + "holz zum Sägewerk – " + Game.euro(price) + "/t")
                .setItems(items, (d, which) -> {
                    Game.Vehicle v = game.findWoodVehicle(fo);
                    if (v == null) {
                        String reason = "Du brauchst einen Holz-Lkw, Forwarder oder Traktor mit Kurzholzanhänger/Rückewagen – und der muss frei sein.";
                        for (Game.Vehicle c : game.vehicles) {
                            String e = game.checkWood(c, fo);
                            if (e != null && !e.contains("beschäftigt") && !e.contains("Dafür brauchst")) reason = e;
                        }
                        toast(reason);
                        return;
                    }
                    String err = act("wood", "v", v.id, "fo", fo.index, "r", which == 1);
                    if (err != null) toast(err);
                    showForestPanel(fo);
                }).show();
    }

    // ----------------------------------------------------------- Hof in 3D

    private void showHof3D() {
        hidePanel();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final AlertDialog[] dlg = new AlertDialog[1];
        Hof3DView v3 = new Hof3DView(this, game);
        v3.listener = v -> {
            if (dlg[0] != null) dlg[0].dismiss();
            map.centerOn(v.x, v.y);
            showVehiclePanel(v);
        };
        box.addView(v3, new LinearLayout.LayoutParams(-1, dp(260)));
        StringBuilder sb = new StringBuilder();
        sb.append("📦 Lager: ");
        boolean any = false;
        for (int p = 0; p < Defs.PRODUCTS; p++) {
            if (game.storage[p] < 0.05) continue;
            if (any) sb.append(" • ");
            sb.append(String.format(Locale.GERMANY, "%s %.1f t", Defs.PRODUCT_NAMES[p], game.storage[p]));
            any = true;
        }
        if (!any) sb.append("leer");
        sb.append(String.format(Locale.GERMANY, "\n🚜 %d Fahrzeuge, %d Geräte", game.vehicles.size(), game.tools.size()));
        sb.append(String.format(Locale.GERMANY, "\n⚡ Strom gerade: %s pro Tag • heute bisher %s", Game.euro(game.energyRateNow()),
                Game.euro(game.energyToday)));
        TextView tv = text(sb.toString(), 14, 0xFF222222, false);
        tv.setPadding(dp(16), dp(10), dp(16), 0);
        box.addView(tv);
        dlg[0] = new AlertDialog.Builder(this).setTitle("🏠 Dein Hof").setView(box)
                .setPositiveButton("💰 Verkaufen", (d, w) -> chooseSell(null))
                .setNeutralButton("🐾 Futter bringen", (d, w) -> chooseFeed(null, -1))
                .setNegativeButton("📦 Lager & Preise", (d, w) -> showStorage())
                .show();
    }

    private void showEnergyInfo() {
        double pv = game.pvNow(), wn = game.windNow();
        String sun = game.daylight() <= 0 ? "Nacht – keine Sonne" : game.snowCover > 0.3 ? "Schnee liegt auf den Modulen"
                : game.weatherType == 0 ? "sonnig" : game.weatherType == 1 ? "bewölkt" : game.weatherType == 2 ? "Regen" : "Schneefall";
        String wind = wn <= 0 ? "Flaute – das Windrad steht" : wn > 0.8 ? "kräftiger Wind" : wn > 0.4 ? "mäßiger Wind" : "schwacher Wind";
        String msg = String.format(Locale.GERMANY,
                "💨 Windräder: %d Stück, gerade %.0f %% Leistung (%s)\n\n☀ PV: %d Dächer + %.1f ha Solarpark, gerade %.0f %% (%s)\n\n"
                        + "⚡ Bei dieser Leistung: %s pro Tag\nHeute bisher: %s\nGestern: %s",
                game.winds.size(), wn * 100, wind, game.pvRoofs(), game.pvFieldHa(), pv * 100, sun,
                Game.euro(game.energyRateNow()), Game.euro(game.energyToday), Game.euro(game.lastEnergy));
        new AlertDialog.Builder(this).setTitle("⚡ Energie").setMessage(msg).setPositiveButton("OK", null).show();
    }

    // ----------------------------------------------------------------- Dialoge

    private void showShop() {
        final String[] cats = {"🚜 Traktoren", "🌾 Mähdrescher", "🌽 Feldhäcksler", "🥔 Kartoffelroder", "⛏ Grubber",
                "🌱 Sämaschinen", "🥔 Kartoffel-Legemaschinen", "🧪 Düngerstreuer", "🚛 Kipper", "✂ Mähwerke", "🌿 Ladewagen",
                "🐄 Ställe", "🏗 Bauen: Windrad & PV", "🌾 Felder", "💸 Maschinen & Geräte verkaufen",
                "💧 Güllefässer", "💩 Miststreuer", "🌲 Forstmaschinen", "🌱 Pflanzmaschine", "🪵 Rückewagen", "🪵 Holzhacker",
                "🚛 Kurzholzanhänger", "🌲 Wald kaufen"};
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
                        case 14: sellStuff(); break;
                        case 15: shopModels(cats[15], Defs.K_TOOL, Defs.T_SLURRY); break;
                        case 16: shopModels(cats[16], Defs.K_TOOL, Defs.T_MANURE); break;
                        case 17: shopModels(cats[17], Defs.K_FORESTRY, -1); break;
                        case 18: shopModels(cats[18], Defs.K_TOOL, Defs.T_TREEPLANT); break;
                        case 19: shopModels(cats[19], Defs.K_TOOL, Defs.T_FWDTRAILER); break;
                        case 20: shopModels(cats[20], Defs.K_TOOL, Defs.T_CHIPPER); break;
                        case 21: shopModels(cats[21], Defs.K_TOOL, Defs.T_LOGTRAILER); break;
                        default:
                            new AlertDialog.Builder(this).setMessage(
                                    "Wald kaufst du direkt auf der Karte: dunkelgrünes Waldstück (🌲 mit Nummer) antippen → „Kaufen“.\nPreis: ca. "
                                            + Game.euro(Defs.FOREST_PRICE_HA) + " pro Hektar.").setPositiveButton("OK", null).show();
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
            if (kind != Defs.K_TRACTOR && kind != Defs.K_FORESTRY && m.sub != sub) continue;
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
            confirmBuy(m.name, m.price, () -> act("buy", "m", m.id));
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
                        confirmBuy("PV-Anlage", Defs.PV_ROOF_PRICE, () -> act("pv", "w", code));
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
                    String err = o instanceof Game.Vehicle ? act("sellVeh", "v", ((Game.Vehicle) o).id) : act("sellTool", "t", ((Game.Tool) o).id);
                    if (err != null) toast(err);
                    else message("💸 Verkauft.");
                    hidePanel();
                }).show();
    }

    private void showStorage() {
        StringBuilder sb = new StringBuilder();
        sb.append("📦 Hoflager:\n");
        for (int p = 0; p < Defs.PRODUCTS; p++) {
            if (p >= Defs.WOOD) continue;
            sb.append(String.format(Locale.GERMANY, "• %s: %.1f t\n", Defs.PRODUCT_NAMES[p], game.storage[p]));
        }
        sb.append("\n💶 Preise heute (€/t):\n");
        if (game.bonusText() != null) sb.append(game.bonusText()).append("\n");
        for (int p = 0; p < Defs.PRODUCTS; p++) {
            sb.append("• ").append(Defs.PRODUCT_NAMES[p]).append(": ");
            boolean first = true;
            for (int b = 0; b < Defs.BUYERS; b++) {
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
        sb.append(String.format(Locale.GERMANY, "\n⚡ Gerade: ☀ %.0f %% • 💨 %.0f %% → %s/Tag, heute bisher %s",
                game.pvNow() * 100, game.windNow() * 100, Game.euro(game.energyRateNow()), Game.euro(game.energyToday)));
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
                .setNegativeButton("💰 Verkaufen", (d, w) -> chooseSell(null))
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
        for (int f = 0; f < game.farmCount(); f++) {
            if (f == game.currentFarm()) continue;
            for (Game.Vehicle v : game.farms[f].vehicles) {
                if (v.lentFrom != game.currentFarm()) continue;
                things.add(Integer.valueOf(v.id));
                items.add("🤝 " + v.name() + "\nverliehen an " + game.farms[f].name + " – antippen zum Zurückholen");
                imgs.add(thumb(v.m(), null));
            }
        }
        showImageList("Fuhrpark: " + game.vehicles.size() + " Fahrzeuge, " + game.tools.size() + " Geräte", items, imgs, which -> {
            Object o = things.get(which);
            if (o instanceof Integer) {
                final int vid = (Integer) o;
                new AlertDialog.Builder(this).setMessage("Fahrzeug zurückholen? Es hört sofort mit der Arbeit auf.")
                        .setPositiveButton("Zurückholen", (d, w) -> {
                            String err = act("ret", "v", vid);
                            if (err != null) toast(err);
                        }).setNegativeButton("Abbrechen", null).show();
            } else if (o instanceof Game.Vehicle) {
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
                    String err = act("sellTool", "t", t.id);
                    if (err != null) toast(err);
                    else message("💸 " + t.m().name + " verkauft.");
                }).show();
    }

    private void applyDetail(int d) {
        map.detail = d;
        game.trafficTarget = d == 0 ? 0 : d == 1 ? 12 : 22;
        getPreferences(MODE_PRIVATE).edit().putInt("detail", d).apply();
    }

    private void showVolume() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));
        final android.widget.CheckBox on = new android.widget.CheckBox(this);
        on.setText("Geräusche an");
        on.setTextSize(16);
        on.setChecked(soundOn);
        box.addView(on);
        final TextView la = text("", 15, 0xFF333333, false);
        final TextView lm = text("", 15, 0xFF333333, false);
        final android.widget.SeekBar sa = new android.widget.SeekBar(this), sm = new android.widget.SeekBar(this);
        sa.setMax(100);
        sm.setMax(100);
        sa.setProgress(Math.round(volAmbient * 100));
        sm.setProgress(Math.round(volMachines * 100));
        Runnable upd = () -> {
            la.setText("🐦 Umgebung (Vögel, Wind, Dorf, Tiere, Regen): " + sa.getProgress() + " %");
            lm.setText("🚜 Maschinen (Motoren, Feuerwehr): " + sm.getProgress() + " %");
        };
        upd.run();
        android.widget.SeekBar.OnSeekBarChangeListener l = new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar b, int p, boolean user) {
                volAmbient = sa.getProgress() / 100f;
                volMachines = sm.getProgress() / 100f;
                upd.run();
                updateSound();
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar b) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar b) {
            }
        };
        sa.setOnSeekBarChangeListener(l);
        sm.setOnSeekBarChangeListener(l);
        on.setOnCheckedChangeListener((b, c) -> {
            soundOn = c;
            updateSound();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        box.addView(la, lp);
        box.addView(sa);
        box.addView(lm, lp);
        box.addView(sm);
        new AlertDialog.Builder(this).setTitle("🔊 Geräusche").setView(box)
                .setPositiveButton("OK", null)
                .setOnDismissListener(d -> getPreferences(MODE_PRIVATE).edit().putBoolean("sound", soundOn)
                        .putFloat("volAmbient", volAmbient).putFloat("volMachines", volMachines).apply())
                .show();
    }

    private void showGameMenu() {
        String[] names = {"Einfach", "Mittel", "Hoch"};
        String[] items = {"💾 Speichern", "🎨 Grafik: " + names[map.detail], "❓ Anleitung", "🏠 Karte zum Hof", "🚪 Speichern & zum Hauptmenü",
                soundOn ? "🔊 Geräusche & Lautstärke" : "🔇 Geräusche: aus"};
        new AlertDialog.Builder(this).setTitle("Menü").setItems(items, (d, which) -> {
            if (which == 5) showVolume();
            else if (which == 0) save(true);
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


    // ============================================================ Mehrspieler

    private static final int REQ_PERM = 11, REQ_ENABLE = 12;
    private static final String PERM_CONNECT = "android.permission.BLUETOOTH_CONNECT";
    private android.bluetooth.BluetoothAdapter adapter;
    private Runnable pendingBt;
    private Net net;
    /** dieses Handy ist Mitspieler (nicht Gastgeber) */
    private boolean isClient;
    private int myPlayer;
    private final String[] playerNames = new String[3];
    private int lobbyMode = Game.MODE_SHARED;
    private boolean lobbyContinue;
    private LinearLayout lobbyList;
    private long lastSnap;
    private boolean snapDirty;

    private boolean multiplayer() {
        return net != null;
    }

    private int farmOfPlayer(int p) {
        if (game == null || game.mode == Game.MODE_SHARED) return 0;
        return p < game.farmCount() ? p : 0;
    }

    private String myName() {
        String n = null;
        try {
            if (adapter != null) n = adapter.getName();
        } catch (SecurityException ignored) {
        }
        return n == null || n.isEmpty() ? "Spieler" : n;
    }

    private void withBluetooth(Runnable r) {
        if (adapter == null) {
            android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            adapter = bm == null ? null : bm.getAdapter();
        }
        if (adapter == null) {
            toast("Dieses Handy hat kein Bluetooth.");
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(PERM_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingBt = r;
            requestPermissions(new String[]{PERM_CONNECT}, REQ_PERM);
            return;
        }
        if (!adapter.isEnabled()) {
            pendingBt = r;
            try {
                startActivityForResult(new android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE);
            } catch (Exception e) {
                toast("Bitte Bluetooth einschalten.");
            }
            return;
        }
        r.run();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_PERM) return;
        Runnable p = pendingBt;
        pendingBt = null;
        if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED && p != null) withBluetooth(p);
        else toast("Ohne die Erlaubnis „Geräte in der Nähe“ geht Bluetooth nicht.");
    }

    @Override
    protected void onActivityResult(int code, int result, android.content.Intent data) {
        if (code != REQ_ENABLE) return;
        Runnable p = pendingBt;
        pendingBt = null;
        if (adapter != null && adapter.isEnabled() && p != null) p.run();
        else toast("Bluetooth ist aus.");
    }

    private void closeNet() {
        if (net != null) {
            net.close();
            net = null;
        }
        isClient = false;
        myPlayer = 0;
    }

    private LinearLayout screen(String title) {
        map = null;
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(40), dp(20), dp(30));
        sv.addView(root);
        add(root, text(title, 26, Color.WHITE, true));
        setContentView(sv);
        return root;
    }

    private File mpFile() {
        return new File(getFilesDir(), "mehrspieler.json");
    }

    private void showMultiMenu() {
        closeNet();
        LinearLayout root = screen("👥 Mehrspieler");
        add(root, text("Bis zu 3 Handys per Bluetooth. Koppelt die Handys einmal in den Bluetooth-Einstellungen. "
                + "Einer erstellt das Spiel (Gastgeber), die anderen treten bei.", 15, 0xFFC5D6B8, false));
        add(root, button("🛰  Spiel erstellen (Gastgeber)", GREEN, v -> withBluetooth(this::showHostSetup)));
        add(root, button("🔗  Spiel beitreten", BLUE, v -> withBluetooth(this::showJoinPick)));
        add(root, button("⚙  Bluetooth-Einstellungen", BTN, v -> {
            try {
                startActivity(new android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
            } catch (Exception ignored) {
            }
        }));
        add(root, button("Zurück", BTN, v -> showStart()));
    }

    private void showHostSetup() {
        LinearLayout root = screen("🛰 Spiel erstellen");
        if (mpFile().exists()) {
            add(root, button("▶  Gespeichertes Mehrspieler-Spiel fortsetzen", ORANGE, v -> {
                lobbyContinue = true;
                showLobby();
            }));
            add(root, text("…oder neue Welt mit Spielart:", 15, 0xFFC5D6B8, false));
        } else {
            add(root, text("Wie wollt ihr spielen?", 15, 0xFFC5D6B8, false));
        }
        String[] desc = {"Alle arbeiten zusammen: gemeinsames Geld, gemeinsame Maschinen und Felder.",
                "Gleiche Karte, aber jeder hat seinen eigenen Hof, eigenes Geld und eigene Felder.",
                "Eigene Höfe – und ihr könnt euch Maschinen leihen und Ware verkaufen."};
        String[] icons = {"🤝", "🏠🏠", "🏠🤝🏠"};
        int[] cols = {GREEN, BLUE, 0xFF6A1B9A};
        for (int m = 0; m < 3; m++) {
            final int mode = m;
            Button b = button(icons[m] + "  " + Game.MODE_NAMES[m] + "\n" + desc[m], cols[m], v -> {
                lobbyContinue = false;
                lobbyMode = mode;
                showLobby();
            });
            b.setTextSize(14);
            add(root, b);
        }
        add(root, button("Zurück", BTN, v -> showMultiMenu()));
    }

    private void showLobby() {
        closeNet();
        net = new Net(this, true);
        net.startHost(adapter);
        playerNames[0] = myName();
        playerNames[1] = playerNames[2] = null;
        LinearLayout root = screen("⏳ Warte auf Mitspieler…");
        add(root, text((lobbyContinue ? "Gespeichertes Spiel" : Game.MODE_NAMES[lobbyMode])
                + "\n\nDie anderen tippen jetzt „Mehrspieler → Spiel beitreten“ und wählen dein Handy: „" + playerNames[0] + "“",
                15, 0xFFC5D6B8, false));
        lobbyList = new LinearLayout(this);
        lobbyList.setOrientation(LinearLayout.VERTICAL);
        add(root, lobbyList);
        refreshLobby();
        add(root, button("▶  Spiel starten", GREEN, v -> startHosted()));
        add(root, button("Abbrechen", RED, v -> showMultiMenu()));
    }

    private void refreshLobby() {
        if (lobbyList == null) return;
        lobbyList.removeAllViews();
        for (int p = 0; p < 3; p++) {
            String n = playerNames[p];
            lobbyList.addView(text((n != null ? "✅ " : "⬜ ") + "Spieler " + (p + 1) + ": " + (n != null ? n : "–")
                    + (p == 0 ? " (du)" : ""), 17, n != null ? Color.WHITE : 0xFF8FA37F, false));
        }
    }

    private void startHosted() {
        int players = 1;
        for (int p = 1; p < 3; p++) if (playerNames[p] != null) players = p + 1;
        if (players < 2) {
            toast("Es ist noch kein Mitspieler da.");
            return;
        }
        net.stopAccepting();
        Game g;
        if (lobbyContinue) {
            g = loadFrom(mpFile());
            if (g == null) {
                toast("Der gespeicherte Spielstand passt nicht mehr – bitte neue Welt.");
                return;
            }
        } else {
            String[] names = new String[players];
            for (int p = 0; p < players; p++) names[p] = playerNames[p] != null ? playerNames[p] : "Spieler " + (p + 1);
            g = Game.newGame(System.nanoTime(), lobbyMode, players, names);
        }
        lobbyList = null;
        isClient = false;
        myPlayer = 0;
        g.select(0);
        startGame(g);
        for (int p = 1; p < 3; p++) if (playerNames[p] != null) net.send(p, "START " + p);
        sendSnapshot();
        message("👥 Mehrspieler: " + Game.MODE_NAMES[game.mode]);
    }

    private void showJoinPick() {
        closeNet();
        LinearLayout root = screen("🔗 Wem beitreten?");
        java.util.Set<android.bluetooth.BluetoothDevice> devices;
        try {
            devices = adapter.getBondedDevices();
        } catch (SecurityException e) {
            devices = null;
        }
        if (devices == null || devices.isEmpty()) {
            add(root, text("Keine gekoppelten Geräte gefunden. Koppelt die Handys zuerst in den Bluetooth-Einstellungen.", 16, 0xFFFFB74D, false));
        } else {
            add(root, text("Tippe auf das Handy des Gastgebers. Er muss vorher „Spiel erstellen“ getippt haben.", 15, 0xFFC5D6B8, false));
            for (final android.bluetooth.BluetoothDevice d : devices) {
                String name;
                try {
                    name = d.getName();
                } catch (SecurityException e) {
                    name = null;
                }
                final String label = name == null ? d.getAddress() : name;
                add(root, button("📱  " + label, BLUE, v -> {
                    closeNet();
                    net = new Net(this, false);
                    isClient = true;
                    net.join(d, adapter);
                    LinearLayout r2 = screen("⏳ Verbinde mit " + label + "…");
                    add(r2, button("Abbrechen", RED, w -> showMultiMenu()));
                }));
            }
        }
        add(root, button("Zurück", BTN, v -> showMultiMenu()));
    }

    // ------------------------------------------------------- Net.Listener

    @Override
    public void onJoin(int player, String name) {
        if (net == null) return;
        if (net.host) {
            // Name kommt mit HI, bis dahin Gerätename
            playerNames[player] = name;
            refreshLobby();
            if (game != null && map != null) {
                // ins laufende Spiel (z. B. nach Verbindungsabbruch)
                net.send(player, "START " + player);
                sendSnapshot();
                message("👋 " + name + " ist wieder dabei.");
            }
        } else {
            net.send(0, "HI " + myName());
            LinearLayout root = screen("✅ Verbunden mit " + name);
            add(root, text("Warte, bis der Gastgeber das Spiel startet…", 16, 0xFFC5D6B8, false));
            add(root, button("Abbrechen", RED, v -> showMultiMenu()));
        }
    }

    @Override
    public void onLine(int player, String line) {
        if (net == null) return;
        try {
            if (net.host) {
                if (line.startsWith("HI ")) {
                    playerNames[player] = line.substring(3).trim();
                    refreshLobby();
                } else if (line.startsWith("C ") && game != null) {
                    String err = exec(player, new JSONObject(line.substring(2)));
                    if (err != null) net.send(player, "E " + err);
                    snapDirty = true;
                }
            } else {
                if (line.startsWith("START ")) {
                    myPlayer = Integer.parseInt(line.substring(6).trim());
                } else if (line.startsWith("S ")) {
                    JSONObject o = new JSONObject(line.substring(2));
                    JSONObject st = o.getJSONObject("g");
                    boolean first = game == null || map == null;
                    if (first) {
                        Game g = Game.fromJson(st);
                        g.muted = true;
                        g.select(farmOfPlayerFor(g, myPlayer));
                        startGame(g);
                        message("👥 Mehrspieler: " + Game.MODE_NAMES[g.mode]);
                    } else {
                        game.applyState(st);
                    }
                    paused = o.optBoolean("pz", false);
                } else if (line.startsWith("M ")) {
                    message(line.substring(2));
                } else if (line.startsWith("E ")) {
                    toast(line.substring(2));
                } else if (line.startsWith("O ")) {
                    showOffer(new JSONObject(line.substring(2)));
                }
            }
        } catch (Exception e) {
            // kaputte Zeile ignorieren
        }
    }

    private static int farmOfPlayerFor(Game g, int p) {
        if (g.mode == Game.MODE_SHARED) return 0;
        return p < g.farmCount() ? p : 0;
    }

    @Override
    public void onLeft(int player, String reason) {
        if (net == null) return;
        if (net.host) {
            String n = player >= 0 ? playerNames[player] : null;
            if (game != null && map != null) {
                if (n != null) message("👋 " + n + " hat das Spiel verlassen. (Kann wieder beitreten.)");
                if (player > 0) playerNames[player] = null;
                // wieder Beitritte annehmen
                net.startHost(adapter);
            } else {
                if (player > 0) playerNames[player] = null;
                refreshLobby();
                if (player < 0) toast(reason);
            }
        } else {
            boolean inGame = game != null && map != null;
            closeNet();
            game = null;
            running = false;
            showStart();
            new AlertDialog.Builder(this).setTitle("Verbindung weg")
                    .setMessage(inGame ? "Die Verbindung zum Gastgeber ist abgebrochen." : reason)
                    .setPositiveButton("OK", null).show();
        }
    }

    private void sendSnapshot() {
        if (net == null || !net.host || game == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("g", game.toJson());
            o.put("pz", paused);
            net.broadcast("S " + o);
        } catch (Exception ignored) {
        }
        lastSnap = SystemClock.uptimeMillis();
        snapDirty = false;
    }

    /** Nachrichten der Höfe verteilen */
    private void drainMessages() {
        if (game == null) return;
        int mine = game.currentFarm();
        for (int f = 0; f < game.farmCount(); f++) {
            List<String> box = game.farms[f].outbox;
            if (box.isEmpty()) continue;
            for (String m : box) {
                if (f == mine) message(m);
                if (net != null && net.host) {
                    for (int p = 1; p < 3; p++) if (playerNames[p] != null && farmOfPlayer(p) == f) net.send(p, "M " + m);
                }
            }
            box.clear();
        }
    }

    // ------------------------------------------------------------- Befehle

    /** Aktion ausführen: allein oder als Gastgeber sofort, als Mitspieler an den Gastgeber schicken */
    private String act(String op, Object... kv) {
        try {
            JSONObject o = new JSONObject();
            o.put("op", op);
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
            if (isClient) {
                if (net != null) net.send(0, "C " + o);
                return null;
            }
            String err = exec(0, o);
            snapDirty = true;
            return err;
        } catch (Exception e) {
            return "Fehler: " + e.getMessage();
        }
    }

    private String exec(int player, JSONObject o) throws org.json.JSONException {
        int back = game.currentFarm();
        int farm = farmOfPlayer(player);
        game.select(farm);
        try {
            String op = o.getString("op");
            Game.Vehicle v = o.has("v") ? game.vehicle(o.getInt("v")) : null;
            if (o.has("v") && v == null && !op.equals("ret")) return "Das Fahrzeug gibt es nicht (mehr).";
            Field f = o.has("f") ? game.world.fields.get(o.getInt("f")) : null;
            Forest fo = o.has("fo") ? game.world.forests.get(o.getInt("fo")) : null;
            switch (op) {
                case "goto":
                    return game.startGoto(v, o.getInt("x"), o.getInt("y"));
                case "wind":
                    return game.buildWind(o.getInt("x"), o.getInt("y"));
                case "home":
                    game.sendHome(v);
                    return null;
                case "stop":
                    game.stop(v);
                    return null;
                case "buyField":
                    return game.buyField(f);
                case "solar":
                    return game.buildSolarPark(f);
                case "field":
                    return game.startFieldJob(v, f, o.getInt("t"), o.getInt("c"));
                case "sell":
                    return game.startSellJob(v, o.getInt("p"), o.getInt("b"), o.getBoolean("r"));
                case "feed":
                    return game.startFeedJob(v, o.getInt("s"), o.getInt("p"), o.getBoolean("r"));
                case "buyStable":
                    return game.buyStable(o.getInt("s"));
                case "pv":
                    return game.buyPvRoof(o.getInt("w"));
                case "buyForest":
                    return game.buyForest(fo);
                case "forest":
                    return game.startForestJob(v, fo, o.getInt("t"), o.getInt("sp"));
                case "wood":
                    return game.startWoodJob(v, fo, o.getBoolean("r"));
                case "buy":
                    return game.buy(o.getInt("m"));
                case "sellVeh":
                    return game.sellVehicle(v);
                case "sellTool": {
                    Game.Tool t = game.tool(o.getInt("t"));
                    return t == null ? "Das Gerät gibt es nicht (mehr)." : game.sellTool(t);
                }
                case "speed":
                    game.speedIdx = o.getInt("i");
                    paused = false;
                    return null;
                case "pause":
                    paused = o.getBoolean("b");
                    return null;
                case "lend":
                    return game.lendVehicle(v, o.getInt("to"));
                case "ret":
                    return game.returnVehicle(o.getInt("v"));
                case "offer": {
                    int to = o.getInt("to"), p = o.getInt("p");
                    double t = o.getDouble("t"), pr = o.getDouble("pr");
                    if (game.storage[p] + 1e-6 < t) return "So viel " + Defs.PRODUCT_NAMES[p] + " hast du nicht.";
                    JSONObject offer = new JSONObject();
                    offer.put("from", farm);
                    offer.put("p", p);
                    offer.put("t", t);
                    offer.put("pr", pr);
                    int target = -1;
                    for (int q = 0; q < 3; q++) if (farmOfPlayer(q) == to && (q == 0 || playerNames[q] != null)) target = q;
                    if (target < 0) return "Der Mitspieler ist gerade nicht verbunden.";
                    if (target == 0) handler.post(() -> showOffer(offer));
                    else net.send(target, "O " + offer);
                    game.farms[farm].outbox.add("📨 Angebot an " + game.farms[to].name + " geschickt – warte auf Antwort.");
                    return null;
                }
                case "accept":
                    return game.tradeGoods(o.getInt("from"), farm, o.getInt("p"), o.getDouble("t"), o.getDouble("pr"));
                case "decline": {
                    int from = o.getInt("from");
                    game.farms[from].outbox.add("❌ " + game.farms[farm].name + " hat dein Angebot abgelehnt.");
                    return null;
                }
                default:
                    return "Unbekannter Befehl.";
            }
        } finally {
            game.select(back);
        }
    }

    // ------------------------------------------------- Helfen: Handel & Leihen

    private void showOffer(final JSONObject o) {
        if (game == null) return;
        try {
            final int from = o.getInt("from"), p = o.getInt("p");
            final double t = o.getDouble("t"), pr = o.getDouble("pr");
            String msg = String.format(Locale.GERMANY, "%s bietet dir %.1f t %s an – für %s pro t (zusammen %s).\n\nAnnehmen?",
                    game.farms[from].name, t, Defs.PRODUCT_NAMES[p], Game.euro(pr), Game.euro(t * pr));
            new AlertDialog.Builder(this).setTitle("🤝 Angebot").setMessage(msg)
                    .setPositiveButton("Annehmen", (d, w) -> {
                        String err = act("accept", "from", from, "p", p, "t", t, "pr", pr);
                        if (err != null) toast(err);
                    })
                    .setNegativeButton("Ablehnen", (d, w) -> act("decline", "from", from))
                    .setCancelable(false).show();
        } catch (Exception ignored) {
        }
    }

    private void askTrade(final int product, final int toFarm) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final android.widget.EditText tons = new android.widget.EditText(this), price = new android.widget.EditText(this);
        tons.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        price.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        tons.setText(String.format(Locale.US, "%.0f", Math.floor(Math.min(game.storage[product], 20))));
        price.setText(String.format(Locale.US, "%.0f", game.bestPrice(product)));
        box.addView(text(String.format(Locale.GERMANY, "Wie viel Tonnen? (du hast %.1f t)", game.storage[product]), 14, 0xFF333333, false));
        box.addView(tons);
        box.addView(text("Preis pro Tonne in € (Händler zahlt gerade bis " + Game.euro(game.bestPrice(product)) + ")", 14, 0xFF333333, false));
        box.addView(price);
        new AlertDialog.Builder(this).setTitle(Defs.PRODUCT_NAMES[product] + " an " + game.farms[toFarm].name)
                .setView(box)
                .setPositiveButton("Angebot schicken", (d, w) -> {
                    double t, pr;
                    try {
                        t = Double.parseDouble(tons.getText().toString().replace(',', '.'));
                        pr = Double.parseDouble(price.getText().toString().replace(',', '.'));
                    } catch (Exception e) {
                        toast("Bitte Zahlen eingeben.");
                        return;
                    }
                    if (t <= 0 || pr < 0) {
                        toast("Bitte eine Menge über 0 eingeben.");
                        return;
                    }
                    String err = act("offer", "to", toFarm, "p", product, "t", t, "pr", pr);
                    if (err != null) toast(err);
                })
                .setNegativeButton("Abbrechen", null).show();
    }

    private boolean helping() {
        return game != null && game.mode == Game.MODE_HELP && game.farmCount() > 1;
    }

    private void chooseLend(final Game.Vehicle v) {
        final List<Integer> fs = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int f = 0; f < game.farmCount(); f++) {
            if (f == game.currentFarm()) continue;
            fs.add(f);
            labels.add("🤝 an " + game.farms[f].name);
        }
        new AlertDialog.Builder(this).setTitle(v.name() + " verleihen")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    String err = act("lend", "v", v.id, "to", fs.get(which));
                    if (err != null) toast(err);
                    hidePanel();
                }).show();
    }

    private void showOtherHof(int f) {
        Game.Farm fm = game.farms[f];
        int fields = 0;
        for (Field x : game.world.fields) if (x.owner == f) fields++;
        String msg = String.format(Locale.GERMANY, "%d Fahrzeuge, %d Geräte, %d Felder", fm.vehicles.size(), fm.tools.size(), fields);
        int lent = 0;
        for (Game.Vehicle v : fm.vehicles) if (v.lentFrom == game.currentFarm()) lent++;
        if (lent > 0) msg += "\nVon dir geliehen: " + lent + " Fahrzeug(e)";
        new AlertDialog.Builder(this).setTitle("🏠 Hof von " + fm.name).setMessage(msg).setPositiveButton("OK", null).show();
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
