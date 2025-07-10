set(CMAKE_FIND_DEBUG_MODE TRUE) # 詳細なデバッグ出力を有効化
cmake_minimum_required(VERSION 3.18.1)

project("ffmpeg_jni")

# Calculate the absolute path for FFMPEG_LIBS_DIR
get_filename_component(ABSOLUTE_FFMPEG_LIBS_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI} ABSOLUTE)

message(STATUS "CMAKE_SOURCE_DIR is: ${CMAKE_SOURCE_DIR}")
message(STATUS "Calculated relative FFmpeg libs dir: ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
message(STATUS "Calculated absolute FFmpeg libs dir: ${ABSOLUTE_FFMPEG_LIBS_DIR}")
message(STATUS "Attempting to use FFmpeg libs from: ${ABSOLUTE_FFMPEG_LIBS_DIR} for ABI: ${ANDROID_ABI}")

# CMakeが使用するライブラリの接頭辞と接尾辞を表示
message(STATUS "CMAKE_FIND_LIBRARY_PREFIXES: ${CMAKE_FIND_LIBRARY_PREFIXES}")
message(STATUS "CMAKE_FIND_LIBRARY_SUFFIXES: ${CMAKE_FIND_LIBRARY_SUFFIXES}")

find_library(
        avcodec-lib # 変数名は変更しない
        NAMES avcodec # 実際のライブラリ名 (libavcodec.so を探す)
        PATHS ${ABSOLUTE_FFMPEG_LIBS_DIR}
        NO_DEFAULT_PATH)
if(NOT avcodec-lib)
        message(WARNING "avcodec-lib NOT FOUND. Searched for 'avcodec' in ${ABSOLUTE_FFMPEG_LIBS_DIR}")
else()
        message(STATUS "Found avcodec-lib: ${avcodec-lib}")
endif()

find_library(
        avformat-lib # 変数名は変更しない
        NAMES avformat # 実際のライブラリ名 (libavformat.so を探す)
        PATHS ${ABSOLUTE_FFMPEG_LIBS_DIR}
        NO_DEFAULT_PATH)
if(NOT avformat-lib)
        message(WARNING "avformat-lib NOT FOUND. Searched for 'avformat' in ${ABSOLUTE_FFMPEG_LIBS_DIR}")
else()
        message(STATUS "Found avformat-lib: ${avformat-lib}")
endif()

find_library(
        avutil-lib # 変数名は変更しない
        NAMES avutil # 実際のライブラリ名 (libavutil.so を探す)
        PATHS ${ABSOLUTE_FFMPEG_LIBS_DIR}
        NO_DEFAULT_PATH)
if(NOT avutil-lib)
        message(WARNING "avutil-lib NOT FOUND. Searched for 'avutil' in ${ABSOLUTE_FFMPEG_LIBS_DIR}")
else()
        message(STATUS "Found avutil-lib: ${avutil-lib}")
endif()

find_library(
        swscale-lib # 変数名は変更しない
        NAMES swscale # 実際のライブラリ名 (libswscale.so を探す)
        PATHS ${ABSOLUTE_FFMPEG_LIBS_DIR}
        NO_DEFAULT_PATH)
if(NOT swscale-lib)
        message(WARNING "swscale-lib NOT FOUND. Searched for 'swscale' in ${ABSOLUTE_FFMPEG_LIBS_DIR}")
else()
        message(STATUS "Found swscale-lib: ${swscale-lib}")
endif()

add_library(ffmpeg_jni SHARED ffmpeg_jni.cpp)

target_link_libraries(ffmpeg_jni
        PRIVATE
        ${avcodec-lib}
        ${avformat-lib}
        ${avutil-lib}
        ${swscale-lib}
        android
        log)

