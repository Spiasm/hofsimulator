package de.spiasm.hofsim;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bluetooth für bis zu 3 Spieler. Der Gastgeber (Spieler 0) nimmt bis zu 2 Mitspieler an (Spieler 1 und 2).
 * Nachrichten sind Textzeilen.
 */
@SuppressLint("MissingPermission")
public class Net {
    public interface Listener {
        /** Gastgeber: Mitspieler player ist da. Mitspieler: mit dem Gastgeber verbunden (player = 0). */
        void onJoin(int player, String name);

        void onLine(int player, String line);

        void onLeft(int player, String reason);
    }

    private static final UUID ID = UUID.fromString("3b9d6e21-4c7a-4f1e-9a2d-8c5b0e7f1a63");
    public static final int MAX_GUESTS = 2;

    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Conn[] conns = new Conn[MAX_GUESTS + 1];
    private volatile BluetoothServerSocket server;
    private volatile boolean closed, accepting;
    public final boolean host;

    private class Conn {
        final int player;
        final BluetoothSocket socket;
        final ExecutorService writer = Executors.newSingleThreadExecutor();
        OutputStream out;
        volatile boolean dead;

        Conn(int player, BluetoothSocket s) {
            this.player = player;
            this.socket = s;
        }

        void send(String line) {
            if (dead) return;
            try {
                writer.execute(() -> {
                    if (dead || out == null) return;
                    try {
                        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } catch (IOException e) {
                        drop(this, "Verbindung getrennt.");
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
            }
        }
    }

    public Net(Listener listener, boolean host) {
        this.listener = listener;
        this.host = host;
    }

    /** Gastgeber: auf Mitspieler warten (läuft, bis stopAccepting() oder 2 Mitspieler da sind) */
    public void startHost(final BluetoothAdapter adapter) {
        accepting = true;
        new Thread(() -> {
            try {
                server = adapter.listenUsingRfcommWithServiceRecord("Hofsimulator", ID);
            } catch (IOException | SecurityException e) {
                main.post(() -> listener.onLeft(-1, "Bluetooth-Warten hat nicht geklappt."));
                return;
            }
            while (accepting && !closed) {
                BluetoothSocket s;
                try {
                    s = server.accept();
                } catch (IOException | SecurityException e) {
                    break;
                }
                int slot = -1;
                synchronized (conns) {
                    for (int i = 1; i <= MAX_GUESTS; i++) {
                        if (conns[i] == null) {
                            slot = i;
                            break;
                        }
                    }
                }
                if (slot < 0 || !accepting) {
                    quietClose(s);
                    continue;
                }
                final Conn c = new Conn(slot, s);
                synchronized (conns) {
                    conns[slot] = c;
                }
                new Thread(() -> run(c)).start();
                boolean full;
                synchronized (conns) {
                    full = conns[1] != null && conns[2] != null;
                }
                if (full) break;
            }
            quietClose(server);
        }).start();
    }

    public void stopAccepting() {
        accepting = false;
        quietClose(server);
    }

    /** Mitspieler: mit dem Gastgeber verbinden */
    public void join(final BluetoothDevice device, final BluetoothAdapter adapter) {
        new Thread(() -> {
            BluetoothSocket s;
            try {
                try {
                    adapter.cancelDiscovery();
                } catch (SecurityException ignored) {
                }
                s = device.createRfcommSocketToServiceRecord(ID);
                s.connect();
            } catch (IOException | SecurityException e) {
                main.post(() -> listener.onLeft(0, "Verbindung hat nicht geklappt. Hat der Gastgeber schon „Mehrspieler → Spiel erstellen“ getippt?"));
                return;
            }
            Conn c = new Conn(0, s);
            synchronized (conns) {
                conns[0] = c;
            }
            run(c);
        }).start();
    }

    private void run(Conn c) {
        try {
            c.out = c.socket.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(c.socket.getInputStream(), StandardCharsets.UTF_8), 1 << 16);
            String name;
            try {
                name = c.socket.getRemoteDevice().getName();
            } catch (SecurityException e) {
                name = null;
            }
            final String peer = name == null ? "Spieler " + (c.player + 1) : name;
            main.post(() -> {
                if (!closed && !c.dead) listener.onJoin(c.player, peer);
            });
            String line;
            while ((line = in.readLine()) != null) {
                final String l = line;
                main.post(() -> {
                    if (!closed && !c.dead) listener.onLine(c.player, l);
                });
            }
        } catch (IOException ignored) {
        }
        drop(c, "Verbindung getrennt.");
    }

    private void drop(Conn c, String reason) {
        if (c.dead) return;
        c.dead = true;
        synchronized (conns) {
            if (conns[c.player] == c) conns[c.player] = null;
        }
        quietClose(c.socket);
        c.writer.shutdown();
        if (!closed) main.post(() -> listener.onLeft(c.player, reason));
    }

    public void send(int player, String line) {
        Conn c;
        synchronized (conns) {
            c = player >= 0 && player < conns.length ? conns[player] : null;
        }
        if (c != null) c.send(line);
    }

    public void broadcast(String line) {
        for (int i = 1; i <= MAX_GUESTS; i++) send(i, line);
    }

    public boolean connected(int player) {
        synchronized (conns) {
            return conns[player] != null && !conns[player].dead;
        }
    }

    public void close() {
        closed = true;
        accepting = false;
        quietClose(server);
        synchronized (conns) {
            for (int i = 0; i < conns.length; i++) {
                if (conns[i] != null) {
                    conns[i].dead = true;
                    quietClose(conns[i].socket);
                    conns[i].writer.shutdown();
                    conns[i] = null;
                }
            }
        }
    }

    private static void quietClose(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
