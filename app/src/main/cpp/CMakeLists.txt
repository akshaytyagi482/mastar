cmake_minimum_required(VERSION 3.22.1)
project(mastar_engine CXX)

# Oboe arrives via Gradle prefab (see app/build.gradle.kts buildFeatures.prefab).
find_package(oboe REQUIRED CONFIG)

add_library(mastar_engine SHARED
        mastar_engine.cpp
        transition_renderer.cpp
        audio_engine.cpp)

target_compile_options(mastar_engine PRIVATE -Wall -Wextra -O2)

target_link_libraries(mastar_engine
        android
        log
        GLESv3
        EGL
        oboe::oboe)
