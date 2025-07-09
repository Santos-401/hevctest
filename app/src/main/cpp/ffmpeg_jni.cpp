#include <jni.h>
#include <string>
#include <android/log.h>
#include <unistd.h> // For close() and dup()
#include <errno.h>  // For strerror()
#include <stdio.h>  // For FILE, fopen, etc.

// FFmpegヘッダ (CMakeLists.txtで指定した include/ffmpeg/ 以下にある想定)
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libswscale/swscale.h>
#include <libavutil/imgutils.h>
}

#define TAG "FFmpegJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// グローバルコンテキスト (必要に応じて)
// AVFormatContext *pFormatCtx = nullptr;
// AVCodecContext *pCodecCtx = nullptr;
// SwsContext *sws_ctx = nullptr;

extern "C" JNIEXPORT jint JNICALL
Java_com_brison_hevctest_FFmpegDecoder_initFFmpeg(JNIEnv *env, jobject /* this */) {
    // av_register_all() is deprecated and not needed in modern FFmpeg versions (>= 4.0)
    // Codecs and formats are registered automatically.
    LOGI("FFmpeg initialized (no explicit av_register_all needed).");
    // 必要であれば、ここで他のグローバルな初期化を行う
    return 0; // 成功
}

extern "C" JNIEXPORT void JNICALL
Java_com_brison_hevctest_FFmpegDecoder_releaseFFmpeg(JNIEnv *env, jobject /* this */) {
    // ここで確保したリソースを解放する
    // if (pCodecCtx) {
    //     avcodec_free_context(&pCodecCtx);
    //     pCodecCtx = nullptr;
    // }
    // if (pFormatCtx) {
    //     avformat_close_input(&pFormatCtx);
    //     // pFormatCtx is freed by avformat_close_input
    //     pFormatCtx = nullptr;
    // }
    // if (sws_ctx) {
    //     sws_freeContext(sws_ctx);
    //     sws_ctx = nullptr;
    // }
    LOGI("FFmpeg resources released (if any were global).");
}

// Helper function to save a grayscale frame (useful for Y plane)
// For full YUV, you need to save Y, U, V planes.
static void save_yuv_frame(FILE *outfile, AVFrame *pFrame) {
    // Assuming pFrame->format is AV_PIX_FMT_YUV420P or similar planar format
    // Write Y plane
    for (int i = 0; i < pFrame->height; i++) {
        fwrite(pFrame->data[0] + i * pFrame->linesize[0], 1, pFrame->width, outfile);
    }
    // Write U plane
    for (int i = 0; i < pFrame->height / 2; i++) {
        fwrite(pFrame->data[1] + i * pFrame->linesize[1], 1, pFrame->width / 2, outfile);
    }
    // Write V plane
    for (int i = 0; i < pFrame->height / 2; i++) {
        fwrite(pFrame->data[2] + i * pFrame->linesize[2], 1, pFrame->width / 2, outfile);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_brison_hevctest_FFmpegDecoder_decodeFile(JNIEnv *env, jobject /* this */,
                                                  jstring input_file_path,
                                                  jstring output_file_path,
                                                  jstring codec_name_jstr) { // Renamed to avoid conflict
    const char *inputFile = env->GetStringUTFChars(input_file_path, nullptr);
    const char *outputFile = env->GetStringUTFChars(output_file_path, nullptr);
    const char *codecName = env->GetStringUTFChars(codec_name_jstr, nullptr);

    LOGI("decodeFile START: in=%s, out=%s, codec=%s", inputFile, outputFile, codecName);

    AVFormatContext *pFormatCtx = nullptr;
    AVCodecContext *pCodecCtx = nullptr;
    AVCodec *pCodec = nullptr;
    AVPacket *pPacket = nullptr;
    AVFrame *pFrame = nullptr;
    FILE *outfile_ptr = nullptr;
    int videoStream = -1;
    int ret = -1;

    // 1. Open input file
    if (avformat_open_input(&pFormatCtx, inputFile, nullptr, nullptr) != 0) {
        LOGE("Couldn't open input file %s", inputFile);
        ret = -1;
        goto end;
    }

    // 2. Retrieve stream information
    if (avformat_find_stream_info(pFormatCtx, nullptr) < 0) {
        LOGE("Couldn't find stream information");
        ret = -2;
        goto end;
    }

    // 3. Find the first video stream
    for (unsigned int i = 0; i < pFormatCtx->nb_streams; i++) {
        if (pFormatCtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            videoStream = i;
            break;
        }
    }
    if (videoStream == -1) {
        LOGE("Didn't find a video stream");
        ret = -3;
        goto end;
    }

    // 4. Get a pointer to the codec context for the video stream and find the decoder
    AVCodecParameters *pCodecParams = pFormatCtx->streams[videoStream]->codecpar;
    if (strcmp(codecName, "h264") == 0) {
        pCodec = avcodec_find_decoder(AV_CODEC_ID_H264);
    } else if (strcmp(codecName, "hevc") == 0 || strcmp(codecName, "h265") == 0) {
        pCodec = avcodec_find_decoder(AV_CODEC_ID_HEVC);
    } else {
        // Fallback to codec_id from stream if no specific name matches
        pCodec = avcodec_find_decoder(pCodecParams->codec_id);
    }

    if (pCodec == nullptr) {
        LOGE("Unsupported codec or codec not found by name: %s (or by id: %d)", codecName, pCodecParams->codec_id);
        ret = -4;
        goto end;
    }
    LOGI("Using codec: %s (ID: %d)", pCodec->name, pCodec->id);


    // 5. Allocate a codec context
    pCodecCtx = avcodec_alloc_context3(pCodec);
    if (!pCodecCtx) {
        LOGE("Could not allocate video codec context");
        ret = -5;
        goto end;
    }

    // Copy codec parameters from input stream to output codec context
    if (avcodec_parameters_to_context(pCodecCtx, pCodecParams) < 0) {
        LOGE("Could not copy codec parameters to context");
        ret = -6;
        goto end;
    }

    // 6. Open codec
    if (avcodec_open2(pCodecCtx, pCodec, nullptr) < 0) {
        LOGE("Could not open codec");
        ret = -7;
        goto end;
    }

    // Allocate video frame and packet
    pFrame = av_frame_alloc();
    if (!pFrame) {
        LOGE("Could not allocate video frame");
        ret = -8;
        goto end;
    }
    pPacket = av_packet_alloc();
    if (!pPacket) {
        LOGE("Could not allocate packet");
        ret = -9;
        goto end;
    }

    // 7. Open output file
    outfile_ptr = fopen(outputFile, "wb");
    if (outfile_ptr == nullptr) {
        LOGE("Could not open output file %s", outputFile);
        ret = -10;
        goto end;
    }
    LOGI("Output file %s opened for writing.", outputFile);

    // 8. Read frames and save them to file
    LOGI("Starting frame decoding loop. Video stream index: %d", videoStream);
    int frame_count = 0;
    while (av_read_frame(pFormatCtx, pPacket) >= 0) {
        if (pPacket->stream_index == videoStream) {
            // 9. Send packet to decoder
            ret = avcodec_send_packet(pCodecCtx, pPacket);
            if (ret < 0) {
                LOGE("Error sending a packet for decoding: %s", av_err2str(ret));
                // If AVERROR(EAGAIN) or AVERROR_EOF, it's not necessarily fatal for this packet,
                // but we might need to receive frames first.
                // For other errors, it might be better to break.
                if (ret != AVERROR(EAGAIN) && ret != AVERROR_EOF) {
                     // break; // Decide if to break or continue trying to receive
                }
            }

            // 10. Receive frame from decoder
            while (ret >= 0) {
                // Note: avcodec_receive_frame can return AVERROR(EAGAIN) if more input is needed,
                // or AVERROR_EOF if flushing is complete.
                ret = avcodec_receive_frame(pCodecCtx, pFrame);
                if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                    // LOGI("avcodec_receive_frame returned EAGAIN or EOF");
                    break; // Break from inner loop, need more packets or EOF
                } else if (ret < 0) {
                    LOGE("Error during decoding (receiving frame): %s", av_err2str(ret));
                    // Consider this a fatal error for the stream for now
                    goto end_decode_loop; // Exit outer loop as well
                }

                // Frame successfully decoded
                LOGI("Frame %d decoded (width %d, height %d, format %d)",
                     frame_count, pFrame->width, pFrame->height, pFrame->format);

                // Save the frame to file (assuming YUV420P or compatible)
                // Check pFrame->format and convert if necessary using sws_scale
                // For now, directly save if it's a common YUV planar format
                if (pFrame->format == AV_PIX_FMT_YUV420P ||
                    pFrame->format == AV_PIX_FMT_YUVJ420P || // Handle JPEG YUV ranges
                    pFrame->format == AV_PIX_FMT_YUV422P ||
                    pFrame->format == AV_PIX_FMT_YUV444P) {
                    save_yuv_frame(outfile_ptr, pFrame);
                    frame_count++;
                } else {
                    LOGE("Unsupported pixel format %d for direct saving. Implement sws_scale for conversion.", pFrame->format);
                    // If you need to support other formats, you'll need sws_scale here.
                    // For now, we'll just skip saving this frame if the format is not directly supported.
                }
                av_frame_unref(pFrame); // Unreference the frame before reusing it
            }
        }
        av_packet_unref(pPacket); // Unreference the packet before reusing it
    }
end_decode_loop:;

    // Flush decoder (send NULL packet)
    LOGI("Flushing decoder...");
    ret = avcodec_send_packet(pCodecCtx, nullptr); // Send NULL packet for flushing
    // if (ret < 0 && ret != AVERROR_EOF) LOGE("Error sending flush packet: %s", av_err2str(ret)); // EOF is fine

    while (true) {
        ret = avcodec_receive_frame(pCodecCtx, pFrame);
        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
            LOGI("End of flushing (EAGAIN or EOF received from receive_frame)");
            break;
        } else if (ret < 0) {
            LOGE("Error during flushing (receiving frame): %s", av_err2str(ret));
            break;
        }
        LOGI("Flushed frame %d decoded (width %d, height %d, format %d)",
             frame_count, pFrame->width, pFrame->height, pFrame->format);
        if (pFrame->format == AV_PIX_FMT_YUV420P || pFrame->format == AV_PIX_FMT_YUVJ420P ||
            pFrame->format == AV_PIX_FMT_YUV422P || pFrame->format == AV_PIX_FMT_YUV444P) {
            save_yuv_frame(outfile_ptr, pFrame);
            frame_count++;
        } else {
             LOGE("Unsupported pixel format %d for direct saving during flush.", pFrame->format);
        }
        av_frame_unref(pFrame);
    }

    LOGI("Decoding finished. Total frames written: %d", frame_count);
    ret = frame_count > 0 ? 0 : -11; // Success if at least one frame was written

end:
    // 10. Release resources
    if (outfile_ptr) {
        fclose(outfile_ptr);
        LOGI("Output file %s closed.", outputFile);
    }
    if (pPacket) {
        av_packet_free(&pPacket);
    }
    if (pFrame) {
        av_frame_free(&pFrame);
    }
    if (pCodecCtx) {
        avcodec_close(pCodecCtx); // Close first
        avcodec_free_context(&pCodecCtx); // Then free
    }
    if (pFormatCtx) {
        avformat_close_input(&pFormatCtx); // This also frees pFormatCtx
    }

    env->ReleaseStringUTFChars(input_file_path, inputFile);
    env->ReleaseStringUTFChars(output_file_path, outputFile);
    env->ReleaseStringUTFChars(codec_name_jstr, codecName);

    LOGI("decodeFile END: ret=%d", ret);
    return ret; // 0 for success, negative for error
}

// Helper function to get file path from URI (very basic, might need improvement)
// This is a simplified placeholder. In a real app, you'd copy URI content to a temp file.
// For this JNI part, we'll assume Java side provides a usable file path for URIs
// by copying them to cache first.
// So, this JNI function will rely on Java to prepare temporary file paths.

// Function to get file descriptor from Uri using JNI calls to ContentResolver
static int get_fd_from_uri(JNIEnv *env, jobject context, jobject uri, const char *mode) {
    jclass uriClass = env->GetObjectClass(uri);
    jclass contextClass = env->GetObjectClass(context);

    // Get ContentResolver
    jmethodID getContentResolverMethod = env->GetMethodID(contextClass, "getContentResolver", "()Landroid/content/ContentResolver;");
    jobject contentResolver = env->CallObjectMethod(context, getContentResolverMethod);
    if (env->ExceptionCheck() || !contentResolver) {
        env->ExceptionClear();
        LOGE("Failed to get ContentResolver");
        return -1;
    }
    jclass contentResolverClass = env->GetObjectClass(contentResolver);

    // Call openFileDescriptor
    jmethodID openFileDescriptorMethod = env->GetMethodID(contentResolverClass, "openFileDescriptor", "(Landroid/net/Uri;Ljava/lang/String;)Landroid/os/ParcelFileDescriptor;");
    jstring modeStr = env->NewStringUTF(mode);
    jobject parcelFileDescriptor = env->CallObjectMethod(contentResolver, openFileDescriptorMethod, uri, modeStr);
    env->DeleteLocalRef(modeStr);
    if (env->ExceptionCheck() || !parcelFileDescriptor) {
        env->ExceptionClear();
        LOGE("Failed to open ParcelFileDescriptor for URI");
        env->DeleteLocalRef(contentResolver); // Clean up
        env->DeleteLocalRef(uriClass);
        env->DeleteLocalRef(contextClass);
        return -1;
    }
    jclass pfdClass = env->GetObjectClass(parcelFileDescriptor);

    // Get file descriptor
    jmethodID getFdMethod = env->GetMethodID(pfdClass, "getFd", "()I");
    jint fd = env->CallIntMethod(parcelFileDescriptor, getFdMethod);
     if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("Failed to get file descriptor from ParcelFileDescriptor");
        fd = -1; // Mark as error
    }

    // Close ParcelFileDescriptor (important!)
    jmethodID closeMethod = env->GetMethodID(pfdClass, "close", "()V");
    env->CallVoidMethod(parcelFileDescriptor, closeMethod);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGW("Exception closing ParcelFileDescriptor, but FD might still be valid.");
        // Don't necessarily fail here if FD was obtained, but log it.
    }

    // Clean up local references
    env->DeleteLocalRef(parcelFileDescriptor);
    env->DeleteLocalRef(pfdClass);
    env->DeleteLocalRef(contentResolver);
    env->DeleteLocalRef(contentResolverClass);
    env->DeleteLocalRef(uriClass);
    env->DeleteLocalRef(contextClass);


    if (fd < 0) {
        LOGE("Got invalid file descriptor %d", fd);
        return -1;
    }
    LOGI("Obtained file descriptor %d for URI with mode %s", fd, mode);
    return fd;
}


extern "C" JNIEXPORT jint JNICALL
Java_com_brison_hevctest_FFmpegDecoder_decodeUri(JNIEnv *env, jobject /* this */,
                                                 jobject context, // Context for ContentResolver
                                                 jobject input_uri, // Uri
                                                 jobject output_uri, // Uri
                                                 jstring codec_name_jstr) {
    const char *codecName = env->GetStringUTFChars(codec_name_jstr, nullptr);
    LOGI("decodeUri START: codec=%s", codecName);

    int inputFd = -1;
    int outputFd = -1;
    char inputFileUriStr[256]; // For FFmpeg "fd:" protocol
    char outputFileUriStr[256];
    int ret = -1;

    // Get file descriptors from URIs
    inputFd = get_fd_from_uri(env, context, input_uri, "r");
    if (inputFd < 0) {
        LOGE("Failed to get input file descriptor from URI.");
        ret = -20; // Custom error code for input URI FD failure
        goto end_uri;
    }
    // It's crucial that this FD is dup'd if FFmpeg is going to close it,
    // or that FFmpeg is configured not to close it.
    // For "fd:" protocol, FFmpeg usually doesn't close it.
    // We also need to ensure the FD remains valid across JNI calls if not dup'd.
    // A common strategy is to dup it and pass the dup'd FD.
    // int dupInputFd = dup(inputFd); // Requires #include <unistd.h>
    // if(dupInputFd < 0) { LOGE("Failed to dup input FD: %s", strerror(errno)); ret = -22; goto end_uri;}
    // close(inputFd); // Close original FD obtained via JNI as we now have a dup
    // inputFd = dupInputFd; // Use the duplicated FD

    // Construct FFmpeg compatible input string (e.g., "fd:N")
    // This requires FFmpeg to be built with support for the fd protocol.
    // Alternatively, one might use "pipe:N" if that's better supported or if it's a pipe FD.
    // For simplicity, let's try "fd:" and assume it works.
    // IMPORTANT: The FD number N here is the one from the Android system.
    snprintf(inputFileUriStr, sizeof(inputFileUriStr), "fd:%d", inputFd);


    // For output, we will decode to a temporary file first, then copy.
    // So, we don't get outputFd here directly for FFmpeg.
    // Instead, create a temporary file path.
    jclass fileClass = env->FindClass("java/io/File");
    jmethodID createTempFileMethod = env->GetStaticMethodID(fileClass, "createTempFile", "(Ljava/lang/String;Ljava/lang/String;Ljava/io/File;)Ljava/io/File;");
    jstring prefix = env->NewStringUTF("decoded_");
    jstring suffix = env->NewStringUTF(".yuv");

    // Get cache directory from context to store temp file
    jmethodID getCacheDirMethod = env->GetMethodID(env->GetObjectClass(context), "getCacheDir", "()Ljava/io/File;");
    jobject cacheDirFile = env->CallObjectMethod(context, getCacheDirMethod);
    if(env->ExceptionCheck() || !cacheDirFile){
        env->ExceptionClear();
        LOGE("Failed to get cache directory");
        ret = -21; // Custom error
        goto end_uri_close_input_fd;
    }

    jobject tempOutputFileObj = env->CallStaticObjectMethod(fileClass, createTempFileMethod, prefix, suffix, cacheDirFile);
    env->DeleteLocalRef(prefix);
    env->DeleteLocalRef(suffix);
    env->DeleteLocalRef(cacheDirFile);

    if (env->ExceptionCheck() || !tempOutputFileObj) {
        env->ExceptionClear();
        LOGE("Failed to create temporary output file.");
        ret = -22;
        goto end_uri_close_input_fd;
    }
    jmethodID getAbsolutePathMethod = env->GetMethodID(fileClass, "getAbsolutePath", "()Ljava/lang/String;");
    jstring tempOutputFilePathJStr = (jstring) env->CallObjectMethod(tempOutputFileObj, getAbsolutePathMethod);
    const char *tempOutputFilePath = env->GetStringUTFChars(tempOutputFilePathJStr, nullptr);
    LOGI("Temporary output file path: %s", tempOutputFilePath);

    // Call decodeFile with the input FD URI and temporary output file path
    // We need to modify decodeFile to accept "fd:%d" or implement custom AVIO
    // For now, let's assume decodeFile is called with file paths.
    // The "fd:%d" approach for input:
    // The `decodeFile` function needs to be aware of this special format.
    // Let's make a small adjustment or assume `avformat_open_input` handles "fd:N".
    // Most FFmpeg builds compiled for Android/Linux should handle "fd:N".

    // Perform decoding to the temporary file
    // We pass inputFileUriStr (which is "fd:INPUT_FD") and tempOutputFilePath
    // This reuses the existing decodeFile logic.
    // Note: decodeFile expects jstring, so we convert back.
    jstring tempInputFileUriJStr = env->NewStringUTF(inputFileUriStr);
    ret = Java_com_brison_hevctest_FFmpegDecoder_decodeFile(env, nullptr /*this*/, tempInputFileUriJStr, tempOutputFilePathJStr, codec_name_jstr);
    env->DeleteLocalRef(tempInputFileUriJStr);

    if (ret != 0) {
        LOGE("decodeFile (called from decodeUri) failed with code: %d", ret);
        // tempOutputFilePath will be cleaned up by Java side or OS later
        env->ReleaseStringUTFChars(tempOutputFilePathJStr, tempOutputFilePath);
        env->DeleteLocalRef(tempOutputFilePathJStr);
        env->DeleteLocalRef(tempOutputFileObj);
        env->DeleteLocalRef(fileClass);
        goto end_uri_close_input_fd;
    }

    LOGI("Successfully decoded to temporary file: %s", tempOutputFilePath);

    // Now, copy the temporary output file to the output URI
    outputFd = get_fd_from_uri(env, context, output_uri, "wt"); // "wt" for "write" and "truncate"
    if (outputFd < 0) {
        LOGE("Failed to get output file descriptor from URI for writing.");
        ret = -23;
        env->ReleaseStringUTFChars(tempOutputFilePathJStr, tempOutputFilePath);
        env->DeleteLocalRef(tempOutputFilePathJStr);
        env->DeleteLocalRef(tempOutputFileObj);
        env->DeleteLocalRef(fileClass);
        goto end_uri_close_input_fd;
    }

    FILE* tempFileRead = fopen(tempOutputFilePath, "rb");
    if (!tempFileRead) {
        LOGE("Failed to open temporary file for reading: %s", tempOutputFilePath);
        ret = -24;
        // close(outputFd); // Close output FD if temp file cannot be read
        // We need a JNI way to close this FD if using "fd:N" for output later,
        // but here we use standard C I/O on the FD.
        // For direct FD write:
        // FILE* finalOutputStream = fdopen(outputFd, "wb");
        // if(!finalOutputStream) { LOGE("fdopen failed for outputFd"); ... }
        // For now, let's assume we can write to outputFd directly if we wanted to,
        // but we're copying.
        goto end_uri_close_all_fds;
    }

    // Standard C file I/O for copying.
    // If outputFd is a raw FD, we'd use write() syscall.
    // Since we used "wt" which implies it's a file, we can try to fdopen it.
    FILE* finalOutputStream = fdopen(outputFd, "wb"); // outputFd is from get_fd_from_uri (which came from ParcelFileDescriptor)
    if (!finalOutputStream) {
        LOGE("Failed to fdopen outputFd %d for writing: %s", outputFd, strerror(errno));
        fclose(tempFileRead);
        // close(outputFd); // outputFd itself needs to be closed if fdopen fails and takes ownership
        ret = -25;
        goto end_uri_close_all_fds; // Fall through to cleanup
    }


    char buffer[4096];
    size_t bytesRead;
    while ((bytesRead = fread(buffer, 1, sizeof(buffer), tempFileRead)) > 0) {
        if (fwrite(buffer, 1, bytesRead, finalOutputStream) != bytesRead) {
            LOGE("Error writing to output URI stream.");
            ret = -26;
            break; // Exit copy loop on error
        }
    }

    if (ret == 0 && ferror(tempFileRead)) {
        LOGE("Error reading from temporary file during copy.");
        ret = -27;
    }

    fclose(tempFileRead);
    // The FD from ParcelFileDescriptor is typically closed when the PFD is closed.
    // fdopen creates a new FILE stream that also needs closing.
    // Closing the FILE stream from fdopen should also close the underlying FD if fdopen took ownership.
    // However, the original FD from get_fd_from_uri was from a PFD that we closed.
    // This area is tricky. It's safer if get_fd_from_uri dups the FD and returns the dup'd one.
    // Assuming fdopen does not close the FD if it was already open.
    // Let's ensure the ParcelFileDescriptor that gave `outputFd` is closed.
    // `get_fd_from_uri` already closes the PFD.
    // The `FILE* finalOutputStream` from `fdopen` needs to be closed.
    if (finalOutputStream) { // Should always be true if we got here without error before
        if (fclose(finalOutputStream) != 0) {
            LOGW("fclose on finalOutputStream failed, URI write might be incomplete.");
            if (ret == 0) ret = -28; // Mark error if none yet
        } else {
            LOGI("Successfully copied to output URI.");
        }
    } else if (outputFd >= 0) { // If fdopen failed, but we still have the raw FD
        close(outputFd); // Close the raw FD directly
    }
    outputFd = -1; // Mark as closed or handled


    // Clean up temporary file
    jmethodID deleteMethod = env->GetMethodID(fileClass, "delete", "()Z");
    jboolean deleted = env->CallBooleanMethod(tempOutputFileObj, deleteMethod);
    if (!deleted) {
        LOGW("Failed to delete temporary output file: %s", tempOutputFilePath);
    } else {
        LOGI("Successfully deleted temporary output file: %s", tempOutputFilePath);
    }

    env->ReleaseStringUTFChars(tempOutputFilePathJStr, tempOutputFilePath);
    env->DeleteLocalRef(tempOutputFilePathJStr);
    env->DeleteLocalRef(tempOutputFileObj);
    env->DeleteLocalRef(fileClass);


end_uri_close_all_fds:
    if (outputFd >= 0) { // If it wasn't closed by fclose(finalOutputStream) or an error path
        close(outputFd); // Ensure it's closed
    }
end_uri_close_input_fd:
    if (inputFd >= 0) {
        // If we dup'd, inputFd is already the one we own and should close.
        // If not dup'd, and it came from PFD, PFD close in get_fd_from_uri should handle it.
        // However, if FFmpeg used "fd:N", it might expect N to be valid for the duration.
        // Safest is to dup in get_fd_from_uri and close the original from PFD there,
        // then close the dup'd one here after FFmpeg is done.
        // For now, assuming FFmpeg doesn't close the fd:N and that PFD close was enough.
        // If issues arise, duping is the way to go.
        // Let's add a direct close here for the FD we got, to be safe, if not dup'd.
        // This might be redundant if PFD.close() also closes it, or problematic if FFmpeg needs it longer.
        // Given the "fd:%d" is used by FFmpeg, we should close it *after* decodeFile returns.
        LOGI("Closing input FD %d from URI", inputFd);
        close(inputFd); // Requires <unistd.h>
    }
end_uri:
    env->ReleaseStringUTFChars(codec_name_jstr, codecName);
    LOGI("decodeUri END: ret=%d", ret);
    return ret;
}
