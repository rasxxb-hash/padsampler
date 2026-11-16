package com.example.padsampler.audio;

/** Samo za probu: Java kopija dijela Kotlin objekta NativeAudio (isti JNI simboli, instance metode). */
public class NativeAudio {
    public static final NativeAudio INSTANCE = new NativeAudio();
    static { System.loadLibrary("padsampler"); }

    public native boolean start();
    public native void stop();
    public native boolean loadSample(int slot, float[] data, int frames, int sampleRate, double srcBpm, int loopMode);
    public native void launch(int slot);
    public native void stopAll();
    public native void setBpm(double bpm);
    public native void pollState(byte[] states, float[] floats);
    public native int sampleRate();
    public native int bufferSize();
    public native int stretchPending();
}
