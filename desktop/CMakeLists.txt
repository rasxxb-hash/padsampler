# PadSampler za računar (Windows): jezgro (mikser, razvlačenje, Link) kao biblioteka + testovi.
# Koristi ISTE izvorne fajlove kao Android verzija (app/src/main/cpp), bez Oboe-a i JNI-ja.
#
#   Windows (Visual Studio Build Tools + CMake):
#     cmake -S desktop -B desktop/build -G "Visual Studio 17 2022" -A x64
#     cmake --build desktop/build --config Release
#     ctest --test-dir desktop/build -C Release --output-on-failure
#   Linux (za provjeru):  cmake -S desktop -B desktop/build -DCMAKE_BUILD_TYPE=Release && cmake --build desktop/build && ctest --test-dir desktop/build
cmake_minimum_required(VERSION 3.20)
# Windows: statički C++ runtime (/MT). JVM (jbr) učitava vlastiti, stariji msvcp140.dll i s njim se
# naš DLL sudara (pad u msvcp140.dll pri pokretanju). Statički runtime to izbjegava, a ne traži ni VC++ Redistributable.
cmake_policy(SET CMP0091 NEW)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
project(padsampler_desktop LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE Release)
endif()

set(CPP ${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp)
set(TESTS ${CMAKE_CURRENT_SOURCE_DIR}/../native-tests)

add_library(padsampler_core STATIC
  ${CPP}/mixer.cpp
  ${CPP}/studio.cpp
  ${CPP}/synth.cpp
  ${CPP}/decoder.cpp
  ${CPP}/stretcher.cpp
  ${CPP}/sample_bank.cpp
  ${CPP}/link_sync.cpp
  ${CMAKE_CURRENT_SOURCE_DIR}/src/mp3_decoder.cpp
  # Rubber Band (GPL): jedan fajl koji uključuje cijelu biblioteku
  ${CPP}/rubberband-lib/single/RubberBandSingle.cpp)

target_include_directories(padsampler_core PUBLIC ${CPP} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/third_party/dr_mp3 ${CPP}/link-lib ${CPP}/link-lib/asio-include)
target_compile_definitions(padsampler_core PRIVATE PADSAMPLER_MP3=1 ASIO_NO_TYPEID=1 ASIO_STANDALONE=1 ASIO_VERSION_NAMESPACE=link_asio_1_38_2)
find_package(Threads REQUIRED)
target_link_libraries(padsampler_core PUBLIC Threads::Threads)

if(WIN32)
  # Ableton Link na Windowsu (Winsock + IP helper), Windows 7 ili novije
  target_compile_definitions(padsampler_core PRIVATE LINK_PLATFORM_WINDOWS=1 _WIN32_WINNT=0x0A00 NOMINMAX _USE_MATH_DEFINES _SCL_SECURE_NO_WARNINGS)
  target_link_libraries(padsampler_core PUBLIC ws2_32 iphlpapi winmm)
elseif(APPLE)
  target_compile_definitions(padsampler_core PRIVATE LINK_PLATFORM_MACOSX=1)
else()
  target_compile_definitions(padsampler_core PRIVATE LINK_PLATFORM_LINUX=1)
  target_link_libraries(padsampler_core PUBLIC atomic)
endif()

if(MSVC)
  target_compile_options(padsampler_core PRIVATE /O2 /utf-8 /EHsc /W3 /wd4244 /wd4267 /wd4996)
else()
  target_compile_options(padsampler_core PRIVATE -O2)
endif()

# ---- Testovi (isti kao na hostu za Android) ----
enable_testing()
foreach(t mixer_test decoder_test bank_test link_test studio_test synth_test)
  add_executable(${t} ${TESTS}/${t}.cpp)
  target_link_libraries(${t} PRIVATE padsampler_core)
  target_include_directories(${t} PRIVATE ${CPP} ${CPP}/link-lib ${CPP}/link-lib/asio-include)
  if(WIN32)
    target_compile_definitions(${t} PRIVATE _USE_MATH_DEFINES NOMINMAX LINK_PLATFORM_WINDOWS=1 _WIN32_WINNT=0x0A00
      ASIO_NO_TYPEID=1 ASIO_STANDALONE=1 ASIO_VERSION_NAMESPACE=link_asio_1_38_2)
  elseif(NOT APPLE)
    target_compile_definitions(${t} PRIVATE LINK_PLATFORM_LINUX=1 ASIO_NO_TYPEID=1 ASIO_STANDALONE=1 ASIO_VERSION_NAMESPACE=link_asio_1_38_2)
  endif()
  if(MSVC)
    target_compile_options(${t} PRIVATE /utf-8 /EHsc /wd4244 /wd4267 /wd4996)
  endif()
  add_test(NAME ${t} COMMAND ${t})
endforeach()

# ---- Zvuk: RtAudio (WASAPI na Windowsu; ASIO kasnije) + probni program ----
include(FetchContent)
set(RTAUDIO_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(RTAUDIO_BUILD_STATIC_LIBS ON CACHE BOOL "" FORCE)
set(RTAUDIO_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
if(WIN32)
  # WASAPI (podrazumijevano) + ASIO. RtAudio u sebi nosi ASIO zaglavlja, pa Steinbergov SDK ne treba za build.
  set(RTAUDIO_API_ASIO ON CACHE BOOL "" FORCE)
  set(RTAUDIO_API_WASAPI ON CACHE BOOL "" FORCE)
elseif(NOT APPLE)
  # Linux: ALSA (direktno, najmanja latencija), JACK i PulseAudio (PipeWire daje oba).
  # Paketi za build (Debian/Ubuntu): libasound2-dev libjack-jackd2-dev libpulse-dev
  set(RTAUDIO_API_ALSA ON CACHE BOOL "" FORCE)
  set(RTAUDIO_API_PULSE ON CACHE BOOL "" FORCE)
  set(RTAUDIO_API_JACK ON CACHE BOOL "" FORCE)
else()
  set(RTAUDIO_API_ALSA OFF CACHE BOOL "" FORCE)
  set(RTAUDIO_API_PULSE OFF CACHE BOOL "" FORCE)
  set(RTAUDIO_API_JACK OFF CACHE BOOL "" FORCE)
endif()
FetchContent_Declare(rtaudio GIT_REPOSITORY https://github.com/thestk/rtaudio.git GIT_TAG 6.0.1 GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(rtaudio)

add_executable(audio_test src/audio_test.cpp)
if(WIN32)
  target_compile_definitions(audio_test PRIVATE NOMINMAX)
endif()
target_link_libraries(audio_test PRIVATE padsampler_core rtaudio)
if(MSVC)
  target_compile_options(audio_test PRIVATE /utf-8 /EHsc /wd4244 /wd4267 /wd4996)
endif()

# ---- padsampler.dll: isti JNI most kao na Androidu (jni_bridge.cpp), ali sa RtAudio motorom ----
# Windows: JNI zaglavlja su u third_party/jni (jbr iz Android Studia nema include folder). Drugdje: iz JDK-a.
if(WIN32)
  set(JNI_INCLUDE_DIRS ${CMAKE_CURRENT_SOURCE_DIR}/third_party/jni)
else()
  find_package(JNI REQUIRED)
endif()
add_library(padsampler SHARED ${CPP}/jni_bridge.cpp src/desktop_audio_engine.cpp src/desktop_jni.cpp)
target_include_directories(padsampler PRIVATE ${JNI_INCLUDE_DIRS} src ${CPP}/link-lib ${CPP}/link-lib/asio-include)
target_compile_definitions(padsampler PRIVATE PADSAMPLER_DESKTOP=1 ASIO_NO_TYPEID=1 ASIO_STANDALONE=1 ASIO_VERSION_NAMESPACE=link_asio_1_38_2)
target_link_libraries(padsampler PRIVATE padsampler_core rtaudio)
if(WIN32)
  target_compile_definitions(padsampler PRIVATE NOMINMAX _USE_MATH_DEFINES LINK_PLATFORM_WINDOWS=1 _WIN32_WINNT=0x0A00 _DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR)
elseif(NOT APPLE)
  target_compile_definitions(padsampler PRIVATE LINK_PLATFORM_LINUX=1)
endif()
if(MSVC)
  target_compile_options(padsampler PRIVATE /utf-8 /EHsc /wd4244 /wd4267 /wd4996)
endif()
