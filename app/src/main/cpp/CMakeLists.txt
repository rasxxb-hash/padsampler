cmake_minimum_required(VERSION 3.22.1)
project("padsampler")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Oboe dolazi kao prefab paket iz Gradle-a (com.google.oboe:oboe)
find_package(oboe REQUIRED CONFIG)

add_library(padsampler SHARED
        mixer.cpp
        studio.cpp
        synth.cpp
        decoder.cpp
        stretcher.cpp
        sample_bank.cpp
        audio_engine.cpp
        link_sync.cpp
        jni_bridge.cpp
        # Rubber Band (time-stretch). Jedan fajl koji uključuje cijelu biblioteku; GPL licenca, lična upotreba.
        rubberband-lib/single/RubberBandSingle.cpp)

# Uvijek optimizirano (i u Debug buildu): razvlačenje semplova bi inače bilo prespor
target_compile_options(padsampler PRIVATE -O2)

# Ableton Link (GPL v2+) + Asio (headeri, vendored u link-lib/). Samo zaglavlja, bez dodatnih biblioteka.
target_include_directories(padsampler PRIVATE link-lib link-lib/asio-include)
target_compile_definitions(padsampler PRIVATE
        LINK_PLATFORM_LINUX=1
        ASIO_NO_TYPEID=1
        ASIO_STANDALONE=1
        ASIO_VERSION_NAMESPACE=link_asio_1_38_2)

target_link_libraries(padsampler oboe::oboe android log atomic)
# Android 15+: stranice od 16 KB (inače upozorenje "not 16 KB aligned" i na takvim uređajima ne radi)
target_link_options(padsampler PRIVATE "-Wl,-z,max-page-size=16384")
