# Собирает нативную часть Cardboard SDK (libGfxPluginCardboard.so)
# и JNI-обёртку для Java API (libcardboard_sdk_jni.so), как в примере paperscope.
cmake_minimum_required(VERSION 3.22.1)
project(cardboard_android LANGUAGES CXX)

add_subdirectory(sdk)

find_library(ANDROID_LIB android)
find_library(LOG_LIB log)
find_library(GLESv2_LIB GLESv2)

add_library(cardboard_sdk_jni SHARED
    java_api/src/main/jni/cardboard_jni.cc
    sdk/jni_utils/android/jni_utils.cc
)

target_include_directories(cardboard_sdk_jni PRIVATE sdk)

target_link_libraries(cardboard_sdk_jni PRIVATE
    GfxPluginCardboard
    ${ANDROID_LIB}
    ${LOG_LIB}
    ${GLESv2_LIB}
)

target_link_options(cardboard_sdk_jni PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(GfxPluginCardboard PRIVATE "-Wl,-z,max-page-size=16384")
