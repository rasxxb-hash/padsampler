// JNI most prema Kotlin objektu com.example.padsampler.audio.NativeAudio
#include <jni.h>

#ifdef PADSAMPLER_DESKTOP
#include "desktop_audio_engine.h"  // Windows: RtAudio umjesto Oboe-a
#else
#include "audio_engine.h"
#endif
#include "decoder.h"
#include "link_sync.h"
#include "sample_bank.h"
#include "studio.h"
#include "synth.h"

namespace {
// Studio prije motora: uništava se POSLIJE njega (audio nit ga zove dok motor radi).
padsampler::Studio gStudio;  // Drums / Keys (pattern sequencer), svira kroz master
padsampler::AudioEngine gEngine;
padsampler::SampleBank gBank(gEngine.mixer());  // razvlačenje semplova na tempo (pozadinska nit)
padsampler::LinkSync gLink;                      // Ableton Link (kreira se tek pri prvom uključenju)
}

#ifdef PADSAMPLER_DESKTOP
// Za desktop/src/desktop_jni.cpp (podešavanja zvuka na računaru).
padsampler::AudioEngine& padsamplerEngine() { return gEngine; }
#endif

#define JNI_FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_example_padsampler_audio_NativeAudio_##name

JNI_FN(jboolean, start)(JNIEnv*, jobject) {
    gEngine.mixer().setAuxSource(&gStudio);
    return gEngine.start() ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(void, stop)(JNIEnv*, jobject) { gEngine.stop(); }

// loopMode: 0 = one-shot, 1 = loop, 2 = automatski (loop ako je dužina blizu cijelog broja taktova)
JNI_FN(jboolean, loadSample)(JNIEnv* env, jobject, jint slot, jfloatArray data, jint frames,
                             jint sampleRate, jdouble srcBpm, jint loopMode) {
    if (data == nullptr || frames <= 0) return JNI_FALSE;
    if (env->GetArrayLength(data) < frames * 2) return JNI_FALSE;
    jfloat* p = env->GetFloatArrayElements(data, nullptr);
    if (p == nullptr) return JNI_FALSE;
    const bool ok = gBank.setSample(slot, p, frames, sampleRate, srcBpm, loopMode) > 0;
    env->ReleaseFloatArrayElements(data, p, JNI_ABORT);  // ne kopiraj nazad
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Dekodira WAV/AIFF iz bajtova. Vraća broj frameova (> 0) ili 0 ako format nije podržan/neispravan.
JNI_FN(jint, loadFromBytes)(JNIEnv* env, jobject, jint slot, jbyteArray data, jdouble srcBpm,
                            jint loopMode) {
    if (data == nullptr) return 0;
    const jsize len = env->GetArrayLength(data);
    jbyte* p = env->GetByteArrayElements(data, nullptr);
    if (p == nullptr) return 0;
    padsampler::DecodedAudio audio;
    const bool decoded = padsampler::decodeAuto(reinterpret_cast<const uint8_t*>(p),
                                                static_cast<size_t>(len), audio);
    env->ReleaseByteArrayElements(data, p, JNI_ABORT);
    if (!decoded) return 0;
    if (gBank.setSample(slot, audio.data.data(), audio.frames, audio.sampleRate, srcBpm, loopMode) <= 0) {
        return 0;
    }
    return static_cast<jint>(audio.frames);
}

JNI_FN(void, clearSample)(JNIEnv*, jobject, jint slot) { gBank.remove(slot); }

JNI_FN(jfloat, sampleDurationSec)(JNIEnv*, jobject, jint slot) {
    return static_cast<jfloat>(gBank.originalDurationSec(slot));  // trajanje originala
}

JNI_FN(void, launch)(JNIEnv*, jobject, jint slot) { gEngine.mixer().launch(slot); }
JNI_FN(void, launchScene)(JNIEnv*, jobject, jint scene) { gEngine.mixer().launchScene(scene); }
JNI_FN(void, stopTrack)(JNIEnv*, jobject, jint track) { gEngine.mixer().stopTrack(track); }
JNI_FN(void, stopAll)(JNIEnv*, jobject) { gEngine.mixer().stopAll(); }
JNI_FN(void, killSlot)(JNIEnv*, jobject, jint slot) { gEngine.mixer().killSlot(slot); }
JNI_FN(void, setBpm)(JNIEnv*, jobject, jdouble bpm) {
    gEngine.mixer().setBpm(bpm);  // sat (takt) se mijenja odmah
    gBank.setTargetBpm(bpm);      // semplovi se razvuku u pozadini i preuzmu na sljedećem taktu
}
JNI_FN(void, setSlotBpm)(JNIEnv*, jobject, jint slot, jdouble bpm) { gBank.setSrcBpm(slot, bpm); }
JNI_FN(jboolean, slotIsLoop)(JNIEnv*, jobject, jint slot) { return gBank.isLoop(slot) ? JNI_TRUE : JNI_FALSE; }
JNI_FN(jdouble, slotSrcBpm)(JNIEnv*, jobject, jint slot) { return gBank.srcBpm(slot); }
JNI_FN(jint, stretchPending)(JNIEnv*, jobject) { return gBank.pending(); }
JNI_FN(void, setSlotLoop)(JNIEnv*, jobject, jint slot, jboolean loop) {
    gBank.setLoop(slot, loop == JNI_TRUE);
}
JNI_FN(void, setTrackPan)(JNIEnv*, jobject, jint track, jfloat pan) { gEngine.mixer().setTrackPan(track, pan); }
JNI_FN(void, setMasterFilter)(JNIEnv*, jobject, jfloat amount) { gEngine.mixer().setMasterFilter(amount); }
JNI_FN(void, setTrackReverbSend)(JNIEnv*, jobject, jint track, jfloat amount) { gEngine.mixer().setTrackReverbSend(track, amount); }
JNI_FN(void, setTrackDelaySend)(JNIEnv*, jobject, jint track, jfloat amount) { gEngine.mixer().setTrackDelaySend(track, amount); }
JNI_FN(void, setTrackEq)(JNIEnv*, jobject, jint track, jint band, jfloat amount) { gEngine.mixer().setTrackEq(track, band, amount); }
JNI_FN(void, setTrackGain)(JNIEnv*, jobject, jint track, jfloat gain) {
    gEngine.mixer().setTrackGain(track, gain);
}
JNI_FN(void, setTrackMute)(JNIEnv*, jobject, jint track, jboolean mute) {
    gEngine.mixer().setTrackMute(track, mute == JNI_TRUE);
}
JNI_FN(void, setTrackSolo)(JNIEnv*, jobject, jint track, jboolean solo) {
    gEngine.mixer().setTrackSolo(track, solo == JNI_TRUE);
}
JNI_FN(void, setMasterGain)(JNIEnv*, jobject, jfloat gain) { gEngine.mixer().setMasterGain(gain); }
JNI_FN(void, setMasterMute)(JNIEnv*, jobject, jboolean mute) {
    gEngine.mixer().setMasterMute(mute == JNI_TRUE);
}

// states[48] = SlotState po slotu.
// floats[0..7] = nivo trackova, [8..15] = napredak klipa po tracku, [16] = faza takta, [17] = master nivo
JNI_FN(void, pollState)(JNIEnv* env, jobject, jbyteArray states, jfloatArray floats) {
    uint8_t s[padsampler::kNumSlots];
    float levels[padsampler::kTracks];
    float progress[padsampler::kTracks];
    float phase = 0.0f, master = 0.0f;
    gEngine.mixer().readState(s, levels, progress, &phase, &master);

    if (states != nullptr && env->GetArrayLength(states) >= padsampler::kNumSlots) {
        env->SetByteArrayRegion(states, 0, padsampler::kNumSlots, reinterpret_cast<const jbyte*>(s));
    }
    if (floats != nullptr && env->GetArrayLength(floats) >= 18) {
        float out[18];
        for (int i = 0; i < padsampler::kTracks; ++i) {
            out[i] = levels[i];
            out[8 + i] = progress[i];
        }
        out[16] = phase;
        out[17] = master;
        env->SetFloatArrayRegion(floats, 0, 18, out);
    }
}

// ---- preslušavanje (audicija iz browsera)
JNI_FN(jint, previewFromBytes)(JNIEnv* env, jobject, jbyteArray data, jdouble srcBpm, jdouble targetBpm,
                               jint loopMode) {
    if (data == nullptr) return 0;
    const jsize len = env->GetArrayLength(data);
    jbyte* p = env->GetByteArrayElements(data, nullptr);
    if (p == nullptr) return 0;
    padsampler::DecodedAudio audio;
    const bool decoded = padsampler::decodeAuto(reinterpret_cast<const uint8_t*>(p), static_cast<size_t>(len), audio);
    env->ReleaseByteArrayElements(data, p, JNI_ABORT);
    if (!decoded) return 0;
    if (!padsampler::startPreview(gEngine.mixer(), audio.data.data(), audio.frames, audio.sampleRate, srcBpm,
                                  targetBpm, loopMode)) {
        return 0;
    }
    return static_cast<jint>(audio.frames);
}

JNI_FN(jboolean, previewSamples)(JNIEnv* env, jobject, jfloatArray data, jint frames, jint sampleRate,
                                 jdouble srcBpm, jdouble targetBpm, jint loopMode) {
    if (data == nullptr || frames <= 0) return JNI_FALSE;
    if (env->GetArrayLength(data) < frames * 2) return JNI_FALSE;
    jfloat* p = env->GetFloatArrayElements(data, nullptr);
    if (p == nullptr) return JNI_FALSE;
    const bool ok = padsampler::startPreview(gEngine.mixer(), p, frames, sampleRate, srcBpm, targetBpm, loopMode);
    env->ReleaseFloatArrayElements(data, p, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(void, previewStop)(JNIEnv*, jobject) { gEngine.mixer().stopPreview(); }
JNI_FN(jfloat, previewProgress)(JNIEnv*, jobject) { return gEngine.mixer().previewProgress(); }

JNI_FN(jint, sampleRate)(JNIEnv*, jobject) { return gEngine.sampleRate(); }
JNI_FN(jint, framesPerBurst)(JNIEnv*, jobject) { return gEngine.framesPerBurst(); }
JNI_FN(jint, bufferSize)(JNIEnv*, jobject) { return gEngine.bufferSize(); }

// ---- Ableton Link
// Uključen: tempo i faza takta dolaze od Linka. Tempo se mijenja samo preko Linka (linkSetTempo), a mikser ga
// preuzima u audio niti; Kotlin čita engineBpm() i javlja SampleBank-u (setBankBpm) da razvuče semplove.
JNI_FN(void, linkEnable)(JNIEnv*, jobject, jboolean on, jdouble bpm) {
    if (on == JNI_TRUE) {
        gLink.setLatencySource(gEngine.latencySource());
        gLink.setStopTarget(&gEngine.mixer());   // tuđi STOP zaustavlja naše klipove
        gEngine.mixer().setClockSource(&gLink);
        gEngine.mixer().setOutputTap(&gLink);    // master -> Link Audio (ako je uključeno)
        gLink.enable(true, bpm);
    } else {
        gLink.enable(false, bpm);
        gEngine.mixer().setOutputTap(nullptr);
        gEngine.mixer().setClockSource(nullptr);
    }
}
// Start/stop (kao Ableton): prvi klip pokreće transport na sljedećem taktu, STOP ALL ga zaustavlja kod svih.
JNI_FN(void, linkStart)(JNIEnv*, jobject) { gLink.requestStart(); }
JNI_FN(void, linkStop)(JNIEnv*, jobject) { gLink.requestStop(); }
JNI_FN(jboolean, linkPlaying)(JNIEnv*, jobject) { return gLink.transportPlaying() ? JNI_TRUE : JNI_FALSE; }
// Link Audio: master miks kao kanal "PadSampler Master"
JNI_FN(void, linkAudioEnable)(JNIEnv*, jobject, jboolean on) { gLink.setAudioSend(on == JNI_TRUE); }
JNI_FN(void, linkShutdown)(JNIEnv*, jobject) { gLink.shutdown(); }
JNI_FN(jint, linkPeers)(JNIEnv*, jobject) { return gLink.peers(); }
JNI_FN(void, linkSetTempo)(JNIEnv*, jobject, jdouble bpm) { gLink.setTempo(bpm); }
JNI_FN(void, linkSetOffsetMs)(JNIEnv*, jobject, jdouble ms) {
    gLink.setOffsetUs(static_cast<int64_t>(ms * 1000.0));
}
JNI_FN(jboolean, linkLocked)(JNIEnv*, jobject) { return gEngine.mixer().clockLocked() ? JNI_TRUE : JNI_FALSE; }
JNI_FN(jdouble, engineBpm)(JNIEnv*, jobject) { return gEngine.mixer().bpm(); }
JNI_FN(void, setBankBpm)(JNIEnv*, jobject, jdouble bpm) { gBank.setTargetBpm(bpm); }

// ---- Studio (Drums; Keys kasnije). part: 0 = drums, 1 = keys.
namespace {
std::shared_ptr<const padsampler::Sample> makeSample(const float* data, int64_t frames, int sampleRate) {
    if (data == nullptr || frames <= 0 || sampleRate <= 0) return nullptr;
    auto s = std::make_shared<padsampler::Sample>();
    s->data.assign(data, data + frames * 2);
    s->frames = frames;
    s->sampleRate = sampleRate;
    return s;
}
}  // namespace

// Zvuk trake (one-shot) iz WAV/AIFF bajtova. Vraća broj frameova ili 0.
JNI_FN(jint, studioLoadDrumFromBytes)(JNIEnv* env, jobject, jint lane, jbyteArray data) {
    if (data == nullptr) return 0;
    const jsize len = env->GetArrayLength(data);
    jbyte* p = env->GetByteArrayElements(data, nullptr);
    if (p == nullptr) return 0;
    padsampler::DecodedAudio audio;
    const bool decoded = padsampler::decodeAuto(reinterpret_cast<const uint8_t*>(p), static_cast<size_t>(len), audio);
    env->ReleaseByteArrayElements(data, p, JNI_ABORT);
    if (!decoded) return 0;
    auto s = makeSample(audio.data.data(), audio.frames, audio.sampleRate);
    if (!s) return 0;
    gStudio.drums().setSample(lane, s);
    return static_cast<jint>(audio.frames);
}

// Zvuk trake iz već dekodiranih podataka (mp3 preko dekodera platforme).
JNI_FN(jboolean, studioLoadDrum)(JNIEnv* env, jobject, jint lane, jfloatArray data, jint frames, jint sampleRate) {
    if (data == nullptr || frames <= 0 || env->GetArrayLength(data) < frames * 2) return JNI_FALSE;
    jfloat* p = env->GetFloatArrayElements(data, nullptr);
    if (p == nullptr) return JNI_FALSE;
    auto s = makeSample(p, frames, sampleRate);
    env->ReleaseFloatArrayElements(data, p, JNI_ABORT);
    if (!s) return JNI_FALSE;
    gStudio.drums().setSample(lane, s);
    return JNI_TRUE;
}

JNI_FN(void, studioClearDrum)(JNIEnv*, jobject, jint lane) { gStudio.drums().setSample(lane, nullptr); }
JNI_FN(void, studioSetDrumGain)(JNIEnv*, jobject, jint lane, jfloat gain) { gStudio.drums().setLaneGain(lane, gain); }

// notes = [tick, length, note] * n, velocities[n]
JNI_FN(void, studioSetPattern)(JNIEnv* env, jobject, jint part, jint bars, jintArray notes, jfloatArray velocities) {
    const jsize n = notes != nullptr ? env->GetArrayLength(notes) / 3 : 0;
    std::vector<padsampler::NoteEvent> ev(static_cast<size_t>(n));
    if (n > 0) {
        std::vector<jint> raw(static_cast<size_t>(n) * 3);
        env->GetIntArrayRegion(notes, 0, n * 3, raw.data());
        std::vector<jfloat> vel(static_cast<size_t>(n), 1.0f);
        if (velocities != nullptr && env->GetArrayLength(velocities) >= n) {
            env->GetFloatArrayRegion(velocities, 0, n, vel.data());
        }
        for (jsize i = 0; i < n; ++i) {
            ev[i].tick = raw[3 * i];
            ev[i].length = raw[3 * i + 1];
            ev[i].note = raw[3 * i + 2];
            ev[i].velocity = vel[i];
        }
    }
    gStudio.setPattern(part, bars, ev.data(), static_cast<int>(n));
}

JNI_FN(void, studioPlay)(JNIEnv*, jobject, jint part) { gStudio.play(part); }
JNI_FN(void, studioStop)(JNIEnv*, jobject, jint part) { gStudio.stop(part); }
JNI_FN(void, studioNoteOn)(JNIEnv*, jobject, jint part, jint note, jfloat velocity) { gStudio.noteOn(part, note, velocity); }
JNI_FN(void, studioNoteOff)(JNIEnv*, jobject, jint part, jint note) { gStudio.noteOff(part, note); }
JNI_FN(jint, studioTransport)(JNIEnv*, jobject, jint part) { return gStudio.transport(part); }
JNI_FN(jfloat, studioPosition)(JNIEnv*, jobject, jint part) { return gStudio.positionTicks(part); }
JNI_FN(jfloat, studioLevel)(JNIEnv*, jobject) { return gStudio.level(); }
JNI_FN(void, studioSetClick)(JNIEnv*, jobject, jboolean on) { gStudio.setClick(on == JNI_TRUE); }

// Pattern dijela renderovan offline (bez šava), interleaved stereo na sampleRate. Prazan niz ako nema šta.
JNI_FN(jfloatArray, studioRender)(JNIEnv* env, jobject, jint part, jdouble bpm, jint sampleRate) {
    const std::vector<float> out = gStudio.renderOffline(part, bpm, sampleRate);
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(out.size()));
    if (arr != nullptr && !out.empty()) env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(out.size()), out.data());
    return arr;
}

// ---- Sintisajzer (Keys). id = SynthParam (synth.h), vrijednost u jedinicama parametra (ograniči se na opseg).
JNI_FN(void, synthSetParam)(JNIEnv*, jobject, jint id, jfloat value) { gStudio.synth().setParam(id, value); }
JNI_FN(jfloat, synthParam)(JNIEnv*, jobject, jint id) { return gStudio.synth().param(id); }
JNI_FN(void, synthPitchBend)(JNIEnv*, jobject, jfloat semitones) { gStudio.synth().setPitchBend(semitones); }
JNI_FN(void, synthModWheel)(JNIEnv*, jobject, jfloat amount) { gStudio.synth().setModWheel(amount); }
