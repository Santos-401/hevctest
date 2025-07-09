# CMake最小バージョンを指定
cmake_minimum_required(VERSION 3.22.1)

# プロジェクト名を定義 (JNIライブラリ名にも影響)
project("ffmpeg_jni")

# JNIラッパーのソースファイル
add_library(
        ffmpeg_jni
        SHARED
        ffmpeg_jni.cpp) # JNIラッパーファイル名

# FFmpegヘッダファイルのインクルードディレクトリ
include_directories(include/ffmpeg) # app/src/main/cpp/include/ffmpeg を指す

# FFmpegライブラリへのパスを指定
# ${ANDROID_ABI} はビルド時に適切なアーキテクチャ名 (armeabi-v7a, arm64-v8aなど) に置き換えられる
set(FFMPEG_LIBS_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

# ターゲットライブラリにリンクするFFmpegライブラリ
# ここでは主要なものをリストアップ。実際にビルドしたライブラリ名に合わせて調整が必要
# 例: libavcodec.so, libavformat.so, libavutil.so, libswscale.so
# find_library を使って各ライブラリを見つけ、ターゲットにリンクする
# または、直接パスを指定することも可能だが、find_library の方が柔軟

# avcodec
find_library(
        avcodec-lib
        avcodec
        PATHS ${FFMPEG_LIBS_DIR}
        NO_DEFAULT_PATH)
# avformat
find_library(
        avformat-lib
        avformat
        PATHS ${FFMPEG_LIBS_DIR}
        NO_DEFAULT_PATH)
# avutil
find_library(
        avutil-lib
        avutil
        PATHS ${FFMPEG_LIBS_DIR}
        NO_DEFAULT_PATH)
# swscale
find_library(
        swscale-lib
        swscale
        PATHS ${FFMPEG_LIBS_DIR}
        NO_DEFAULT_PATH)
# swresample (もしオーディオ処理も行う場合)
# find_library(
#         swresample-lib
#         swresample
#         PATHS ${FFMPEG_LIBS_DIR}
#         NO_DEFAULT_PATH)

# JNIライブラリ (ffmpeg_jni) にFFmpegライブラリをリンク
target_link_libraries(
        ffmpeg_jni
        android # Android標準ライブラリ
        log     # Android Logcat用
        ${avcodec-lib}
        ${avformat-lib}
        ${avutil-lib}
        ${swscale-lib}
        # ${swresample-lib} # もし使うなら
        )

# C++標準の指定 (オプション、必要に応じて)
# set(CMAKE_CXX_STANDARD 17)
# set(CMAKE_CXX_STANDARD_REQUIRED True)
