// JNI samo za računar: podešavanja zvuka (API, uređaj, bafer, frekvencija) i "ne gasi ekran".
// Kotlin strana: desktop-app/.../desktop/DesktopAudio.kt
#include <jni.h>

#include <condition_variable>
#include <cstdlib>
#include <mutex>
#include <string>
#include <thread>

#ifdef _WIN32
#include <windows.h>
#elif defined(__linux__)
#include <dirent.h>
#include <fstream>
#endif

#include "desktop_audio_engine.h"

padsampler::AudioEngine& padsamplerEngine();  // jni_bridge.cpp

#define DJNI_FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_example_padsampler_desktop_DesktopAudio_##name

namespace {

std::string str(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

jobjectArray strArray(JNIEnv* env, const std::vector<std::string>& v) {
    jclass cls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(jsize(v.size()), cls, nullptr);
    for (size_t i = 0; i < v.size(); i++) {
        jstring s = env->NewStringUTF(v[i].c_str());
        env->SetObjectArrayElement(arr, jsize(i), s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

// "Ne gasi ekran": SetThreadExecutionState važi dok nit koja ga je pozvala živi, pa ga drži posebna nit.
std::mutex gAwakeMutex;
std::condition_variable gAwakeCv;
std::thread gAwakeThread;
bool gAwakeOn = false;

}  // namespace

DJNI_FN(jobjectArray, apis)(JNIEnv* env, jobject) { return strArray(env, padsampler::AudioEngine::compiledApis()); }

// Svaki uređaj: "ime\t1|0 (podrazumijevani)\tfrekvencije odvojene zarezom"
DJNI_FN(jobjectArray, devices)(JNIEnv* env, jobject, jstring api) {
    std::vector<std::string> out;
    for (const auto& d : padsamplerEngine().listDevices(str(env, api))) {
        std::string rates;
        for (unsigned r : d.rates) {
            if (!rates.empty()) rates += ',';
            rates += std::to_string(r);
        }
        out.push_back(d.name + '\t' + (d.isDefault ? "1" : "0") + '\t' + rates);
    }
    return strArray(env, out);
}

DJNI_FN(void, setConfig)(JNIEnv* env, jobject, jstring api, jstring device, jint buffer, jint rate) {
    padsampler::AudioConfig c;
    c.api = str(env, api);
    c.device = str(env, device);
    c.buffer = buffer > 0 ? unsigned(buffer) : 0;
    c.rate = rate > 0 ? unsigned(rate) : 0;
    padsamplerEngine().setConfig(c);
}

// Zaustavi i pokreni sa novim podešavanjima. Ako ne uspije, vrati prethodna i pokreni opet; vraća false.
DJNI_FN(jboolean, apply)(JNIEnv* env, jobject, jstring api, jstring device, jint buffer, jint rate) {
    auto& e = padsamplerEngine();
    const padsampler::AudioConfig old = e.config();
    padsampler::AudioConfig c;
    c.api = str(env, api);
    c.device = str(env, device);
    c.buffer = buffer > 0 ? unsigned(buffer) : 0;
    c.rate = rate > 0 ? unsigned(rate) : 0;
    e.stop();
    e.setConfig(c);
    if (e.start()) return JNI_TRUE;
    e.stop();
    e.setConfig(old);
    e.start();
    return JNI_FALSE;
}

// "api\tuređaj\tnapomena" onoga što trenutno svira (napomena npr. kad je ASIO pao pa se prešlo na WASAPI)
DJNI_FN(jstring, current)(JNIEnv* env, jobject) {
    auto& e = padsamplerEngine();
    return env->NewStringUTF((e.currentApi() + '\t' + e.currentDevice() + '\t' + e.notice()).c_str());
}

DJNI_FN(jdouble, latencyMs)(JNIEnv*, jobject) { return double(padsamplerEngine().outputLatencyUs()) / 1000.0; }

DJNI_FN(void, keepAwake)(JNIEnv*, jobject, jboolean on) {
#ifdef _WIN32
    std::unique_lock<std::mutex> lock(gAwakeMutex);
    if ((on == JNI_TRUE) == gAwakeOn) return;
    gAwakeOn = (on == JNI_TRUE);
    if (gAwakeOn) {
        gAwakeThread = std::thread([] {
            SetThreadExecutionState(ES_CONTINUOUS | ES_DISPLAY_REQUIRED | ES_SYSTEM_REQUIRED);
            std::unique_lock<std::mutex> l(gAwakeMutex);
            gAwakeCv.wait(l, [] { return !gAwakeOn; });
            SetThreadExecutionState(ES_CONTINUOUS);
        });
    } else {
        gAwakeCv.notify_all();
        std::thread t = std::move(gAwakeThread);
        lock.unlock();
        if (t.joinable()) t.join();
    }
#else
    (void)on;
#endif
}

// Baterija laptopa (gornja traka): procenat 0..100, +1000 ako je punjač spojen; -1 = nema baterije
// (stoni računar) ili nepoznato. Poziva se rijetko (svakih 30 s) iz UI-a, ne sa audio niti.
DJNI_FN(jint, battery)(JNIEnv*, jobject) {
#ifdef _WIN32
    SYSTEM_POWER_STATUS s;
    if (!GetSystemPowerStatus(&s)) return -1;
    if (s.BatteryFlag == 128 || s.BatteryFlag == 255 || s.BatteryLifePercent > 100) return -1;  // 128 = nema baterije
    return int(s.BatteryLifePercent) + (s.ACLineStatus == 1 ? 1000 : 0);
#elif defined(__linux__)
    // /sys/class/power_supply: BAT* (capacity) i punjač (type "Mains", online 1)
    auto readStr = [](const std::string& path) {
        std::ifstream f(path);
        std::string s;
        std::getline(f, s);
        return s;
    };
    const std::string root = "/sys/class/power_supply/";
    DIR* d = opendir(root.c_str());
    if (!d) return -1;
    int percent = -1;
    bool ac = false;
    while (dirent* e = readdir(d)) {
        const std::string name = e->d_name;
        if (name.empty() || name[0] == '.') continue;
        const std::string type = readStr(root + name + "/type");
        if (type == "Battery" && percent < 0) {
            const std::string cap = readStr(root + name + "/capacity");
            if (!cap.empty()) percent = std::atoi(cap.c_str());
        } else if (type == "Mains" && readStr(root + name + "/online") == "1") {
            ac = true;
        }
    }
    closedir(d);
    if (percent < 0 || percent > 100) return -1;
    return percent + (ac ? 1000 : 0);
#else
    return -1;
#endif
}
