package com.example.padsampler.audio;

/** Svira petlju kroz JNI -> padsampler.dll -> Mixer -> RtAudio. Argument: trajanje u sekundama (zadano 15). */
public class SmokeTest {
    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 15;
        NativeAudio n = NativeAudio.INSTANCE;
        if (!n.start()) { System.err.println("start() nije uspio"); System.exit(1); }
        int rate = n.sampleRate();
        double bpm = 120.0, beat = 60.0 / bpm;
        int frames = (int) Math.round(beat * 8 * rate);          // 2 takta
        float[] d = new float[frames * 2];
        for (int b = 0; b < 8; b++) {                              // kick na svakoj dobi
            int at = (int) Math.round(b * beat * rate);
            double ph = 0;
            for (int i = 0; i < rate / 4 && at + i < frames; i++) {
                double t = (double) i / rate;
                ph += 2 * Math.PI * (45 + 110 * Math.exp(-t * 28)) / rate;
                float fade = (float) Math.min(1.0, (rate / 4 - i) / (0.01 * rate));  // mekan kraj, bez klika
                float v = (float) (0.9 * Math.sin(ph) * Math.exp(-t * 9)) * fade;
                d[(at + i) * 2] += v; d[(at + i) * 2 + 1] += v;
            }
        }
        n.setBpm(bpm);
        if (!n.loadSample(0, d, frames, rate, bpm, 1)) { System.err.println("loadSample nije uspio"); System.exit(1); }
        n.launch(0);
        byte[] states = new byte[48]; float[] f = new float[18];
        float peak = 0;
        for (int s = 0; s < seconds * 10; s++) {
            Thread.sleep(100);
            n.pollState(states, f);
            peak = Math.max(peak, f[17]);
            if (s % 10 == 9) System.out.printf("  %2d s  slot0 stanje=%d  master nivo=%.2f  faza takta=%.2f%n", (s + 1) / 10, states[0], f[17], f[16]);
        }
        n.stopAll(); Thread.sleep(200); n.stop();
        System.out.println(peak > 0.05f ? "OK: zvuk je prošao kroz mikser (vršni nivo " + peak + ")" : "GREŠKA: mikser nije dao zvuk");
        System.exit(peak > 0.05f ? 0 : 1);
    }
}
