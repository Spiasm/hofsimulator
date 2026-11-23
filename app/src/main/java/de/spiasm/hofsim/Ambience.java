package de.spiasm.hofsim;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * Umgebungsgeräusche, live im Programm erzeugt (keine Sounddateien):
 * Vögel, Blätterrauschen, Dorfgemurmel, Regen, Motoren, Tiere, Feuerwehrsirene und Martinshorn.
 * Die Lautstärken (0..1) setzt das Spiel laufend, je nachdem was gerade im Bild ist.
 */
public class Ambience implements Runnable {
    private static final int RATE = 22050;

    public volatile float birds, leaves, village, rain, engine, siren, horn;
    public final float[] animals = new float[4]; // Huhn, Kuh, Schwein, Schaf
    public volatile float wind = 0.5f;

    private volatile boolean running;
    private Thread thread;

    // geglättete Lautstärken
    private float cBirds, cLeaves, cVillage, cRain, cEngine, cSiren, cHorn;
    private final float[] cAnimals = new float[4];

    private int seed = 12345;

    private float noise() {
        seed ^= seed << 13;
        seed ^= seed >>> 17;
        seed ^= seed << 5;
        return (seed & 0xFFFF) / 32768f - 1f;
    }

    private float rnd() {
        return (noise() + 1) * 0.5f;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this, "Ambience");
        thread.start();
    }

    public synchronized void stop() {
        running = false;
    }

    // ---------------------------------------------------------- Zustände

    private float lpLeaves, gustPhase, lpRain, lpEngine, engPhase, sirenT, hornT;

    // Vogel-Zwitschern
    private int chirpLeft, chirpGap, chirpRepeats;
    private float chirpPhase, chirpFreq, chirpSweep;
    private int chirpLen, chirpPos;

    // Dorf: 3 Stimmen
    private final float[] vPhase = new float[3], vPitch = new float[3], vLp = new float[3], vEnv = new float[3];
    private final int[] vTimer = new int[3];
    private final boolean[] vOn = new boolean[3];

    // Tier-Laute (eine Stimme je Tierart)
    private final int[] aLeft = new int[4], aLen = new int[4];
    private final float[] aPhase = new float[4], aLp = new float[4];

    @Override
    public void run() {
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(Math.max(min, 4096) * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.play();
        } catch (Exception e) {
            running = false;
            return;
        }
        short[] buf = new short[1024];
        while (running) {
            smooth();
            for (int i = 0; i < buf.length; i++) {
                float s = sample();
                s = Math.max(-1f, Math.min(1f, s * 0.7f));
                buf[i] = (short) (s * 32000);
            }
            try {
                track.write(buf, 0, buf.length);
            } catch (Exception e) {
                break;
            }
        }
        try {
            track.stop();
        } catch (Exception ignored) {
        }
        track.release();
    }

    private void smooth() {
        float k = 0.06f;
        cBirds += (birds - cBirds) * k;
        cLeaves += (leaves - cLeaves) * k;
        cVillage += (village - cVillage) * k;
        cRain += (rain - cRain) * k;
        cEngine += (engine - cEngine) * k;
        cSiren += (siren - cSiren) * 0.15f;
        cHorn += (horn - cHorn) * 0.15f;
        for (int i = 0; i < 4; i++) cAnimals[i] += (animals[i] - cAnimals[i]) * k;
    }

    private float sample() {
        float out = 0;
        float n = noise();

        // Wind in den Blättern: helles Rauschen mit Böen
        if (cLeaves > 0.005f) {
            lpLeaves += 0.08f * (n - lpLeaves);
            gustPhase += 0.25f / RATE * (0.5f + wind);
            float gust = 0.5f + 0.5f * (float) Math.sin(gustPhase * 2 * Math.PI) * (float) Math.sin(gustPhase * 0.7 * 2 * Math.PI);
            out += (n - lpLeaves) * 0.22f * cLeaves * (0.3f + 0.7f * gust);
        }

        // Regen
        if (cRain > 0.005f) {
            lpRain += 0.35f * (n - lpRain);
            out += lpRain * 0.3f * cRain;
            if (rnd() < 0.0006f) out += noise() * 0.5f * cRain;
        }

        // Vögel
        if (cBirds > 0.01f) {
            if (chirpLeft <= 0 && chirpRepeats <= 0 && rnd() < cBirds * 0.00025f) {
                chirpRepeats = 1 + (int) (rnd() * 5);
                chirpFreq = 2400 + rnd() * 2400;
                chirpSweep = (rnd() - 0.3f) * 3000;
                chirpGap = 0;
            }
            if (chirpLeft <= 0 && chirpRepeats > 0) {
                if (chirpGap > 0) {
                    chirpGap--;
                } else {
                    chirpLen = (int) (RATE * (0.04f + rnd() * 0.08f));
                    chirpLeft = chirpLen;
                    chirpPos = 0;
                    chirpRepeats--;
                    chirpGap = (int) (RATE * (0.03f + rnd() * 0.08f));
                }
            }
            if (chirpLeft > 0) {
                float p = chirpPos / (float) chirpLen;
                float f = chirpFreq + chirpSweep * p;
                chirpPhase += f / RATE;
                float env = (float) Math.sin(Math.PI * p);
                out += (float) Math.sin(chirpPhase * 2 * Math.PI) * env * 0.18f * cBirds;
                chirpLeft--;
                chirpPos++;
            }
        }

        // Dorf: Stimmengemurmel
        if (cVillage > 0.01f) {
            for (int v = 0; v < 3; v++) {
                if (vTimer[v] <= 0) {
                    vOn[v] = !vOn[v] && rnd() < 0.85f;
                    vTimer[v] = (int) (RATE * (vOn[v] ? 0.08f + rnd() * 0.2f : 0.05f + rnd() * 0.35f));
                    if (vPitch[v] == 0 || rnd() < 0.05f) vPitch[v] = 100 + rnd() * 130;
                }
                vTimer[v]--;
                vEnv[v] += ((vOn[v] ? 1f : 0f) - vEnv[v]) * 0.004f;
                vPhase[v] += vPitch[v] * (1 + 0.04f * (float) Math.sin(vPhase[v] * 0.01)) / RATE;
                float saw = 2 * (vPhase[v] - (float) Math.floor(vPhase[v])) - 1;
                vLp[v] += 0.06f * (saw - vLp[v]);
                out += vLp[v] * vEnv[v] * 0.12f * cVillage;
            }
        }

        // Motor
        if (cEngine > 0.01f) {
            engPhase += (36 + 4 * (float) Math.sin(engPhase * 0.002)) / RATE;
            float saw = 2 * (engPhase - (float) Math.floor(engPhase)) - 1;
            lpEngine += 0.03f * (saw + n * 0.4f - lpEngine);
            out += lpEngine * 0.35f * cEngine;
        }

        // Tiere
        for (int a = 0; a < 4; a++) {
            float vol = cAnimals[a];
            if (vol < 0.01f) continue;
            float chance = a == 0 ? 0.00012f : 0.00004f;
            if (aLeft[a] <= 0 && rnd() < vol * chance) {
                aLen[a] = (int) (RATE * (a == 0 ? 0.35f : a == 1 ? 1.4f : a == 2 ? 0.45f : 0.9f));
                aLeft[a] = aLen[a];
                aPhase[a] = 0;
            }
            if (aLeft[a] > 0) {
                float p = 1 - aLeft[a] / (float) aLen[a];
                float s;
                switch (a) {
                    case 0: { // Huhn: gack-gack
                        float pulse = (p * 4) % 1f;
                        float env = pulse < 0.4f ? (float) Math.sin(Math.PI * pulse / 0.4f) : 0;
                        aPhase[a] += (650 + 250 * (1 - pulse)) / RATE;
                        s = ((aPhase[a] % 1f) < 0.5f ? 1 : -1) * env * 0.12f;
                        break;
                    }
                    case 1: { // Kuh: Muh
                        float env = (float) Math.sin(Math.PI * p);
                        aPhase[a] += (110 - 18 * p) / RATE;
                        float saw = 2 * (aPhase[a] % 1f) - 1;
                        aLp[a] += 0.05f * (saw - aLp[a]);
                        s = aLp[a] * env * 0.6f;
                        break;
                    }
                    case 2: { // Schwein: Grunzen
                        float pulse = (p * 2) % 1f;
                        float env = pulse < 0.6f ? (float) Math.sin(Math.PI * pulse / 0.6f) : 0;
                        aPhase[a] += 85f / RATE;
                        float saw = 2 * (aPhase[a] % 1f) - 1;
                        aLp[a] += 0.08f * (saw * 0.6f + n * 0.6f - aLp[a]);
                        s = aLp[a] * env * 0.6f;
                        break;
                    }
                    default: { // Schaf: Mäh
                        float env = (float) Math.sin(Math.PI * p);
                        aPhase[a] += (300 + 25 * (float) Math.sin(p * 2 * Math.PI * 6)) / RATE;
                        float saw = 2 * (aPhase[a] % 1f) - 1;
                        aLp[a] += 0.15f * (saw - aLp[a]);
                        s = aLp[a] * env * 0.25f;
                    }
                }
                out += s * vol;
                aLeft[a]--;
            }
        }

        // Feuerwehrsirene (auf- und abschwellend)
        if (cSiren > 0.01f) {
            sirenT += 1f / RATE;
            float f = 450 + 650 * (0.5f - 0.5f * (float) Math.cos(2 * Math.PI * sirenT / 6));
            chirpPhaseSiren += f / RATE;
            out += (float) Math.sin(chirpPhaseSiren * 2 * Math.PI) * 0.22f * cSiren;
        }

        // Martinshorn: Ta-tü-ta-ta
        if (cHorn > 0.01f) {
            hornT += 1f / RATE;
            float f = ((int) (hornT / 0.6f)) % 2 == 0 ? 440f : 587f;
            hornPhase += f / RATE;
            float ph = hornPhase * 2 * (float) Math.PI;
            out += ((float) Math.sin(ph) + 0.35f * (float) Math.sin(3 * ph) + 0.15f * (float) Math.sin(5 * ph)) * 0.16f * cHorn;
        }
        return out;
    }

    private float chirpPhaseSiren, hornPhase;
}
