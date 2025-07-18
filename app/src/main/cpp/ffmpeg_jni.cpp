\
#include <jni.h>
#include <string>
#include <android/log.h>
#include <unistd.h> // For close(), dup(), fsync()
#include <errno.h>  // For strerror()
#include <stdio.h>  // For FILE, fopen, etc.
#include <string.h> // For strlen

// FFmpeg headers (assuming they are in include/ffmpeg/ as specified in CMakeLists.txt)
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libswscale/swscale.h>
#include <libavutil/imgutils.h>
}

#define TAG "FFmpegJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

extern "C" JNIEXPORT jint JNICALL
Java_com_brison_hevctest_FFmpegDecoder_initFFmpeg(JNIEnv *env, jobject /* this */) {
    LOGI("FFmpeg initialized (no explicit av_register_all needed).");
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_brison_hevctest_FFmpegDecoder_releaseFFmpeg(JNIEnv *env, jobject /* this */) {
    LOGI("FFmpeg resources released (if any were global).");
}

static void save_yuv_frame(FILE *outfile, AVFrame *pFrame) {
    for (int i = 0; i < pFrame->height; i++) {
        fwrite(pFrame->data[0] + i * pFrame->linesize[0], 1, pFrame->width, outfile);
    }
    for (int i = 0; i < pFrame->height / 2; i++) {
        fwrite(pFrame->data[1] + i * pFrame->linesize[1], 1, pFrame->width / 2, outfile);
    }
    for (int i = 0; i < pFrame->height / 2; i++) {
        fwrite(pFrame->data[2] + i * pFrame->linesize[2], 1, pFrame->width / 2, outfile);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_brison_hevctest_FFmpegDecoder_decodeFile(JNIEnv *env, jobject /* this */,
                                                  jstring input_file_path,
                                                  jstring output_file_path,
                                                  jstring codec_name_jstr) {
    const char *inputFile = nullptr;
    const char *outputFile = nullptr;
    const char *codecName = nullptr;

    AVFormatContext *pFormatCtx = nullptr;
    AVCodecContext *pCodecCtx = nullptr;
    const AVCodec *pCodec = nullptr;
    AVPacket *pPacket = nullptr;
    AVFrame *pFrame = nullptr;
    FILE *outfile_ptr = nullptr;
    int videoStream = -1;
    int ret = -1;
    int frame_count = 0;
    AVCodecParameters *pCodecParams = nullptr;

    if (input_file_path) inputFile = env->GetStringUTFChars(input_file_path, nullptr);
    if (output_file_path) outputFile = env->GetStringUTFChars(output_file_path, nullptr);
    if (codec_name_jstr) codecName = env->GetStringUTFChars(codec_name_jstr, nullptr);

    if (!inputFile || !outputFile || !codecName) {
        LOGE("Failed to get UTF chars from JNI strings. inputFile=%p, outputFile=%p, codecName=%p", inputFile, outputFile, codecName);
        ret = -1;
        goto end;
    }

    LOGI("decodeFile START: in=%s, out=%s, codec=%s", inputFile, outputFile, codecName);

    if (avformat_open_input(&pFormatCtx, inputFile, nullptr, nullptr) != 0) {
        LOGE("Couldn\\'t open input file %s", inputFile);
        ret = -1;
        goto end;
    }

    if (avformat_find_stream_info(pFormatCtx, nullptr) < 0) {
        LOGE("Couldn\\'t find stream information");
        ret = -2;
        goto end;
    }

    for (unsigned int i = 0; i < pFormatCtx->nb_streams; i++) {
        if (pFormatCtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            videoStream = i;
            break;
        }
    }
    if (videoStream == -1) {
        LOGE("Didn\\'t find a video stream");
        ret = -3;
        goto end;
    }

    pCodecParams = pFormatCtx->streams[videoStream]->codecpar;
    if (strcmp(codecName, "h264") == 0) {
        pCodec = avcodec_find_decoder(AV_CODEC_ID_H264);
    } else if (strcmp(codecName, "hevc") == 0 || strcmp(codecName, "h265") == 0) {
        pCodec = avcodec_find_decoder(AV_CODEC_ID_HEVC);
    } else {
        pCodec = avcodec_find_decoder(pCodecParams->codec_id);
    }

    if (pCodec == nullptr) {
        LOGE("Unsupported codec or codec not found by name: %s (or by id: %d)", codecName, pCodecParams->codec_id);
        ret = -4;
        goto end;
    }
    LOGI("Using codec: %s (ID: %d)", pCodec->name, pCodec->id);

    pCodecCtx = avcodec_alloc_context3(pCodec);
    if (!pCodecCtx) {
        LOGE("Could not allocate video codec context");
        ret = -5;
        goto end;
    }

    if (avcodec_parameters_to_context(pCodecCtx, pCodecParams) < 0) {
        LOGE("Could not copy codec parameters to context");
        ret = -6;
        goto end;
    }

    if (avcodec_open2(pCodecCtx, pCodec, nullptr) < 0) {
        LOGE("Could not open codec");
        ret = -7;
        goto end;
    }

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

    outfile_ptr = fopen(outputFile, "wb");
    if (outfile_ptr == nullptr) {
        LOGE("Could not open output file %s: %s", outputFile, strerror(errno));
        ret = -10;
        goto end;
    }
    LOGI("Output file %s opened for writing.", outputFile);

    LOGI("Starting frame decoding loop. Video stream index: %d", videoStream);
    while (av_read_frame(pFormatCtx, pPacket) >= 0) {
        if (pPacket->stream_index == videoStream) {
            ret = avcodec_send_packet(pCodecCtx, pPacket);
            if (ret < 0) {
                LOGE("Error sending a packet for decoding: %s", av_err2str(ret));
            }

            while (ret >= 0) {
                ret = avcodec_receive_frame(pCodecCtx, pFrame);
                if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                    break;
                } else if (ret < 0) {
                    LOGE("Error during decoding (receiving frame): %s", av_err2str(ret));
                    goto end_decode_loop;
                }

                LOGI("Frame %d decoded (width %d, height %d, format %d)",
                     frame_count, pFrame->width, pFrame->height, pFrame->format);

                if (pFrame->format == AV_PIX_FMT_YUV420P10LE) {
                    struct SwsContext* sws_ctx = sws_getContext(
                            pFrame->width, pFrame->height, (AVPixelFormat)pFrame->format,
                            pFrame->width, pFrame->height, AV_PIX_FMT_YUV420P,
                            SWS_BILINEAR, nullptr, nullptr, nullptr);
                    if (sws_ctx) {
                        AVFrame* pFrame8bit = av_frame_alloc();
                        pFrame8bit->width = pFrame->width;
                        pFrame8bit->height = pFrame->height;
                        pFrame8bit->format = AV_PIX_FMT_YUV420P;
                        av_image_alloc(pFrame8bit->data, pFrame8bit->linesize, pFrame->width, pFrame->height, AV_PIX_FMT_YUV420P, 32);
                        sws_scale(sws_ctx, (const uint8_t* const*)pFrame->data, pFrame->linesize, 0, pFrame->height, pFrame8bit->data, pFrame8bit->linesize);
                        save_yuv_frame(outfile_ptr, pFrame8bit);
                        av_freep(&pFrame8bit->data[0]);
                        av_frame_free(&pFrame8bit);
                        sws_freeContext(sws_ctx);
                        frame_count++;
                    } else {
                        LOGE("Could not initialize the conversion context.");
                    }
                } else if (pFrame->format == AV_PIX_FMT_YUV420P ||
                           pFrame->format == AV_PIX_FMT_YUVJ420P) {
                    save_yuv_frame(outfile_ptr, pFrame);
                    frame_count++;
                } else {
                    LOGE("Unsupported pixel format %d for direct saving.", pFrame->format);
                }
                av_frame_unref(pFrame);
            }
        }
        av_packet_unref(pPacket);
    }
    end_decode_loop:;

    LOGI("Flushing decoder...");
    ret = avcodec_send_packet(pCodecCtx, nullptr);

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
        if (pFrame->format == AV_PIX_FMT_YUV420P10LE) {
            struct SwsContext* sws_ctx = sws_getContext(
                    pFrame->width, pFrame->height, (AVPixelFormat)pFrame->format,
                    pFrame->width, pFrame->height, AV_PIX_FMT_YUV420P,
                    SWS_BILINEAR, nullptr, nullptr, nullptr);
            if (sws_ctx) {
                AVFrame* pFrame8bit = av_frame_alloc();
                pFrame8bit->width = pFrame->width;
                pFrame8bit->height = pFrame->height;
                pFrame8bit->format = AV_PIX_FMT_YUV420P;
                av_image_alloc(pFrame8bit->data, pFrame8bit->linesize, pFrame->width, pFrame->height, AV_PIX_FMT_YUV420P, 32);
                sws_scale(sws_ctx, (const uint8_t* const*)pFrame->data, pFrame->linesize, 0, pFrame->height, pFrame8bit->data, pFrame8bit->linesize);
                save_yuv_frame(outfile_ptr, pFrame8bit);
                av_freep(&pFrame8bit->data[0]);
                av_frame_free(&pFrame8bit);
                sws_freeContext(sws_ctx);
                frame_count++;
            } else {
                LOGE("Could not initialize the conversion context for flushed frame.");
            }
        } else if (pFrame->format == AV_PIX_FMT_YUV420P || pFrame->format == AV_PIX_FMT_YUVJ420P) {
            save_yuv_frame(outfile_ptr, pFrame);
            frame_count++;
        } else {
            LOGE("Unsupported pixel format %d for direct saving during flush.", pFrame->format);
        }
        av_frame_unref(pFrame);
    }

    LOGI("Decoding finished. Total frames written: %d", frame_count);
    ret = frame_count > 0 ? 0 : -11;

    end:
    if (outfile_ptr) {
        fclose(outfile_ptr);
        LOGI("Output file %s closed.", outputFile ? outputFile : "null");
    }
    if (pPacket) {
        av_packet_free(&pPacket);
    }
    if (pFrame) {
        av_frame_free(&pFrame);
    }
    if (pCodecCtx) {
        avcodec_free_context(&pCodecCtx);
    }
    if (pFormatCtx) {
        avformat_close_input(&pFormatCtx);
    }

    if (inputFile && input_file_path) {
        env->ReleaseStringUTFChars(input_file_path, inputFile);
    }
    if (outputFile && output_file_path) {
        env->ReleaseStringUTFChars(output_file_path, outputFile);
    }
    if (codecName && codec_name_jstr) {
        env->ReleaseStringUTFChars(codec_name_jstr, codecName);
    }

    LOGI("decodeFile END: ret=%d", ret);
    return ret;
}


static int get_fd_from_uri(JNIEnv *env, jobject context, jobject uri, const char *mode) {
    jclass uriClass = nullptr;
    jclass contextClass = nullptr;
    jobject contentResolver = nullptr;
    jclass contentResolverClass = nullptr;
    jstring modeStr = nullptr;
    jobject parcelFileDescriptor = nullptr;
    jclass pfdClass = nullptr;
    jmethodID getContentResolverMethod = 0; // Moved to top and initialized
    jmethodID openFileDescriptorMethod = 0;
    jmethodID getFdMethod = 0;
    jmethodID closeMethod = 0; // Will be obtained in cleanup block if needed

    int fd = -1;
    int dup_fd = -1;

    LOGI("get_fd_from_uri: Attempting to get FD for URI with mode \\'%s\\'", mode);

    uriClass = env->FindClass("android/net/Uri");
    if (!uriClass) { LOGE("Failed to find android/net/Uri class"); goto get_fd_cleanup; }

    contextClass = env->GetObjectClass(context);
    if (!contextClass) { LOGE("Failed to get context class"); goto get_fd_cleanup; }

    // Assign value here, not declare+initialize
    getContentResolverMethod = env->GetMethodID(contextClass, "getContentResolver", "()Landroid/content/ContentResolver;");
    if (!getContentResolverMethod) { LOGE("Failed to get getContentResolver method"); goto get_fd_cleanup; }

    contentResolver = env->CallObjectMethod(context, getContentResolverMethod);
    if (env->ExceptionCheck() || !contentResolver) {
        if(env->ExceptionCheck()) env->ExceptionClear();
        LOGE("Failed to get ContentResolver");
        goto get_fd_cleanup;
    }

    contentResolverClass = env->GetObjectClass(contentResolver);
    if (!contentResolverClass) { LOGE("Failed to get contentResolverClass"); goto get_fd_cleanup; }

    openFileDescriptorMethod = env->GetMethodID(contentResolverClass, "openFileDescriptor", "(Landroid/net/Uri;Ljava/lang/String;)Landroid/os/ParcelFileDescriptor;");
    if (!openFileDescriptorMethod) { LOGE("Failed to get openFileDescriptorMethod"); goto get_fd_cleanup; }

    modeStr = env->NewStringUTF(mode);
    if (!modeStr) { LOGE("Failed to create modeStr"); goto get_fd_cleanup; }

    parcelFileDescriptor = env->CallObjectMethod(contentResolver, openFileDescriptorMethod, uri, modeStr);
    if (env->ExceptionCheck() || !parcelFileDescriptor) {
        if(env->ExceptionCheck()) env->ExceptionClear();
        LOGE("Failed to open ParcelFileDescriptor for URI");
        goto get_fd_cleanup;
    }

    pfdClass = env->GetObjectClass(parcelFileDescriptor); // pfdClass is needed for closeMethod
    if (!pfdClass) { LOGE("Failed to get pfdClass"); goto get_fd_cleanup; }

    getFdMethod = env->GetMethodID(pfdClass, "getFd", "()I");
    if (!getFdMethod) { LOGE("Failed to get getFdMethod"); goto get_fd_cleanup; }

    fd = env->CallIntMethod(parcelFileDescriptor, getFdMethod);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("Failed to get file descriptor from ParcelFileDescriptor");
        fd = -1; // Let it proceed to dup_fd logic and cleanup
    }

    if (fd >= 0) {
        dup_fd = dup(fd);
        if (dup_fd < 0) {
            LOGE("Failed to dup file descriptor %d: %s (errno %d)", fd, strerror(errno), errno);
            // fd is still open here, ParcelFileDescriptor.close() below should close it.
        } else {
            LOGI("Successfully duplicated FD %d to %d", fd, dup_fd);
        }
    } else {
        LOGE("Original FD was invalid (%d), cannot dup.", fd);
        dup_fd = -1;
    }
    // ParcelFileDescriptor.close() will be called in the cleanup section
    // to ensure it\'s closed even if dup fails or fd is invalid.

    get_fd_cleanup:
    if (parcelFileDescriptor) {
        if (pfdClass) { // pfdClass should have been obtained if parcelFileDescriptor is not null
            closeMethod = env->GetMethodID(pfdClass, "close", "()V");
            if (closeMethod) {
                env->CallVoidMethod(parcelFileDescriptor, closeMethod);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    // Log warning, dup_fd might still be valid if dup succeeded before this.
                    LOGW("Exception closing ParcelFileDescriptor. dup_fd: %d", dup_fd);
                } else {
                    LOGI("ParcelFileDescriptor closed.");
                }
            } else {
                LOGE("Failed to get closeMethod for ParcelFileDescriptor. Original FD might be leaked if not dup-ed and closed elsewhere.");
            }
        } else {
            // This case (parcelFileDescriptor != null but pfdClass == null) should ideally not happen
            // if pfdClass is obtained right after parcelFileDescriptor.
            // However, if it could, ParcelFileDescriptor cannot be closed via JNI here.
            LOGE("pfdClass is null, cannot call close on ParcelFileDescriptor. Original FD might be leaked.");
        }
        env->DeleteLocalRef(parcelFileDescriptor); // Delete ref after attempting close
        parcelFileDescriptor = nullptr;
    }

    if (pfdClass) env->DeleteLocalRef(pfdClass);
    if (modeStr) env->DeleteLocalRef(modeStr);
    if (contentResolver) env->DeleteLocalRef(contentResolver);
    if (contentResolverClass) env->DeleteLocalRef(contentResolverClass);
    if (uriClass) env->DeleteLocalRef(uriClass);
    if (contextClass) env->DeleteLocalRef(contextClass);

    if (dup_fd < 0) {
        LOGE("get_fd_from_uri returning invalid FD %d", dup_fd);
        return -1; // Ensure a clear error status
    }
    LOGI("get_fd_from_uri returning duplicated FD %d for URI with mode %s", dup_fd, mode);
    return dup_fd;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_brison_hevctest_FFmpegDecoder_decodeUri(JNIEnv *env, jobject /* this */,
                                                 jobject context_obj, // Renamed to avoid conflict
                                                 jobject input_uri,
                                                 jobject output_uri,
                                                 const jstring codec_name_jstr) {
    const char *codecName = nullptr;
    jstring tempInputPathJStr = nullptr;
    jstring tempOutputPathJStr = nullptr;
    jstring cacheDirJStr = nullptr;
    const char* cacheDirStr = nullptr;

    jclass fileClass = nullptr;
    jmethodID getCacheDirMethod = 0;
    jobject cacheDirFile = nullptr;
    jmethodID getAbsolutePathMethod = 0;
    jclass localContextClassForCacheDir = nullptr;

    int inputFd = -1;
    int outputFd = -1;
    int ret = -1; // Default to a generic error
    char tempInputPath[1024] = {0};
    char tempOutputPath[1024] = {0};
    FILE* tempInputFile = nullptr;
    FILE* tempOutputFile = nullptr;

    char copyBuffer[4096]; // Buffer for copying data between FDs
    ssize_t bytesRead_copy;     // For reading from inputFd / tempOutputFile
    ssize_t bytesWritten_copy;  // For writing to tempInputFile / outputFd
    ssize_t totalBytesWritten_copy;


    if (codec_name_jstr) codecName = env->GetStringUTFChars(codec_name_jstr, nullptr);
    if (!codecName) {
        LOGE("Failed to get codec name string");
        ret = -101;
        goto cleanup;
    }
    LOGI("decodeUri START: codec=%s", codecName);

    inputFd = get_fd_from_uri(env, context_obj, input_uri, "r");
    if (inputFd < 0) {
        LOGE("Failed to get input file descriptor from URI.");
        ret = -102;
        goto cleanup;
    }
    LOGI("Obtained input FD: %d", inputFd);

    fileClass = env->FindClass("java/io/File");
    if (!fileClass) {
        LOGE("Failed to find java/io/File class");
        ret = -103;
        goto cleanup;
    }

    localContextClassForCacheDir = env->GetObjectClass(context_obj);
    if (!localContextClassForCacheDir) {
        LOGE("Failed to get local context class for getCacheDir");
        ret = -104;
        goto cleanup;
    }
    getCacheDirMethod = env->GetMethodID(localContextClassForCacheDir, "getCacheDir", "()Ljava/io/File;");
    // No longer need localContextClassForCacheDir after getting method ID
    // env->DeleteLocalRef(localContextClassForCacheDir);
    // localContextClassForCacheDir = nullptr;

    if(!getCacheDirMethod) {
        LOGE("Failed to find getCacheDir method");
        // Release localContextClassForCacheDir if obtained
        if (localContextClassForCacheDir) env->DeleteLocalRef(localContextClassForCacheDir);
        ret = -105;
        goto cleanup;
    }

    cacheDirFile = env->CallObjectMethod(context_obj, getCacheDirMethod);
    if (localContextClassForCacheDir) { // Release after use if not already released
        env->DeleteLocalRef(localContextClassForCacheDir);
        localContextClassForCacheDir = nullptr;
    }
    if(!cacheDirFile) {
        LOGE("Failed to get cache directory file object");
        ret = -106;
        goto cleanup;
    }

    getAbsolutePathMethod = env->GetMethodID(fileClass, "getAbsolutePath", "()Ljava/lang/String;");
    if(!getAbsolutePathMethod) {
        LOGE("Failed to find getAbsolutePath method");
        ret = -107;
        goto cleanup;
    }
    cacheDirJStr = (jstring) env->CallObjectMethod(cacheDirFile, getAbsolutePathMethod);
    if(!cacheDirJStr) {
        LOGE("Failed to get cache directory path string");
        ret = -108;
        goto cleanup;
    }
    cacheDirStr = env->GetStringUTFChars(cacheDirJStr, nullptr);
    if(!cacheDirStr) {
        LOGE("Failed to get C string from cache directory path");
        ret = -109;
        goto cleanup;
    }
    snprintf(tempInputPath, sizeof(tempInputPath) -1, "%s/temp_input.video", cacheDirStr);
    snprintf(tempOutputPath, sizeof(tempOutputPath) -1, "%s/temp_output.yuv", cacheDirStr);

    // Release cacheDirStr as soon as it\'s used to form paths
    // env->ReleaseStringUTFChars(cacheDirJStr, cacheDirStr); // Will be released in cleanup
    // cacheDirStr = nullptr;
    // env->DeleteLocalRef(cacheDirJStr); // Will be released in cleanup
    // cacheDirJStr = nullptr;

    LOGI("Temporary input file path: %s", tempInputPath);
    LOGI("Temporary output file path: %s", tempOutputPath);

    tempInputFile = fopen(tempInputPath, "wb");
    if (!tempInputFile) {
        LOGE("Failed to create temporary input file \\'%s\\': %s", tempInputPath, strerror(errno));
        ret = -110;
        goto cleanup;
    }
    LOGI("Opened temporary input file \\'%s\\' for writing.", tempInputPath);

    totalBytesWritten_copy = 0;
    LOGI("Starting copy from input FD %d to temporary file \\'%s\\'", inputFd, tempInputPath);
    while ((bytesRead_copy = read(inputFd, copyBuffer, sizeof(copyBuffer))) > 0) {
        bytesWritten_copy = fwrite(copyBuffer, 1, bytesRead_copy, tempInputFile);
        if (bytesWritten_copy < bytesRead_copy) {
            LOGE("Error writing to temporary input file '%s'. Expected %zd, wrote %zd: %s", tempInputPath, bytesRead_copy, bytesWritten_copy, strerror(ferror(tempInputFile) ? errno : EIO));
            // fclose(tempInputFile); tempInputFile will be closed in cleanup
            // tempInputFile = nullptr;
            ret = -111;
            goto cleanup;
        }
        totalBytesWritten_copy += bytesWritten_copy;
    }
    if (bytesRead_copy < 0) {
        LOGE("Error reading from input FD %d: %s (errno %d)", inputFd, strerror(errno), errno);
        ret = -112;
        goto cleanup;
    }
    LOGI("Finished copying from input FD %d to temporary file \\'%s\\'. Total bytes written: %zd", inputFd, tempInputPath, totalBytesWritten_copy);

    if (fclose(tempInputFile) != 0) {
        LOGW("fclose failed for tempInputFile \\'%s\\': %s", tempInputPath, strerror(errno));
        // Not critical enough to override primary error if one occurred, but good to log.
    }
    tempInputFile = nullptr;
    LOGI("Closed temporary input file \\'%s\\'.", tempInputPath);

    tempInputPathJStr = env->NewStringUTF(tempInputPath);
    if(!tempInputPathJStr) {
        LOGE("Failed to create Java String for temp input path");
        ret = -113;
        goto cleanup;
    }
    tempOutputPathJStr = env->NewStringUTF(tempOutputPath);
    if(!tempOutputPathJStr) {
        LOGE("Failed to create Java String for temp output path");
        ret = -114;
        goto cleanup;
    }

    LOGI("Calling decodeFile with tempInput: \\'%s\\', tempOutput: \\'%s\\'", tempInputPath, tempOutputPath);
    ret = Java_com_brison_hevctest_FFmpegDecoder_decodeFile(env, nullptr, tempInputPathJStr, tempOutputPathJStr, codec_name_jstr);
    if (ret != 0) {
        LOGE("Decoding from temp file to temp file failed with code %d", ret);
        // \'ret\' already holds the error from decodeFile
        goto cleanup;
    }
    LOGI("decodeFile call finished successfully (ret=%d).", ret);


    outputFd = get_fd_from_uri(env, context_obj, output_uri, "w");
    if (outputFd < 0) {
        LOGE("Failed to get output file descriptor from URI for writing.");
        ret = -115; // Specific error
        goto cleanup;
    }
    LOGI("Obtained output FD: %d for writing.", outputFd);

    tempOutputFile = fopen(tempOutputPath, "rb");
    if (!tempOutputFile) {
        LOGE("Failed to open temporary output file \\'%s\\' for reading: %s", tempOutputPath, strerror(errno));
        ret = -116;
        goto cleanup;
    }
    LOGI("Opened temporary output file \\'%s\\' for reading to copy to output FD.", tempOutputPath);

    totalBytesWritten_copy = 0;
    LOGI("Starting copy from temporary output file \\'%s\\' to output FD %d", tempOutputPath, outputFd);
    while ((bytesRead_copy = fread(copyBuffer, 1, sizeof(copyBuffer), tempOutputFile)) > 0) {
        ssize_t remainingToWrite = bytesRead_copy;
        char *current_pos = copyBuffer;
        while (remainingToWrite > 0) {
            bytesWritten_copy = write(outputFd, current_pos, remainingToWrite);
            if (bytesWritten_copy < 0) {
                LOGE("Error writing to final output FD %d: %s (errno %d)", outputFd, strerror(errno), errno);
                ret = -117;
                goto cleanup;
            } else if (bytesWritten_copy == 0) {
                LOGE("write() returned 0 to FD %d. This usually means the operation would block or an error.", outputFd);
                ret = -118;
                goto cleanup;
            }
            remainingToWrite -= bytesWritten_copy;
            current_pos += bytesWritten_copy;
            totalBytesWritten_copy += bytesWritten_copy;
        }
    }
    if (ferror(tempOutputFile)) {
        LOGE("Error reading from temporary output file \\'%s\\' after loop: %s (errno %d)", tempOutputPath, strerror(errno), errno);
        ret = -119;
        goto cleanup;
    }
    LOGI("Finished copying from temporary output file \\'%s\\' to output FD %d. Total bytes written: %zd", tempOutputPath, outputFd, totalBytesWritten_copy);

    LOGI("Attempting to fsync output FD %d", outputFd);
    if (fsync(outputFd) == -1) {
        LOGW("fsync failed for output FD %d: %s (errno %d). Output might be incomplete.", outputFd, strerror(errno), errno);
        // Not overriding \'ret\' for fsync warning for now, but it\'s a critical indicator.
        if (ret == 0) ret = -120; // Consider fsync failure an error if everything else was ok
    } else {
        LOGI("fsync successful for output FD %d.", outputFd);
    }

    // If we reached here, \'ret\' should be 0 if decodeFile and subsequent operations were successful.
    LOGI("Successfully copied decoded data to output URI via FD %d. Current ret value: %d", outputFd, ret);


    cleanup:
    LOGI("decodeUri cleanup started. Current ret = %d", ret);
    if (tempInputFile) { // Should be null if already closed
        LOGI("Closing tempInputFile \\'%s\\' (should already be closed if logic is correct)", tempInputPath);
        fclose(tempInputFile);
        tempInputFile = nullptr;
    }
    if (tempOutputFile) {
        LOGI("Closing tempOutputFile \\'%s\\'", tempOutputPath);
        if (fclose(tempOutputFile) != 0) {
            LOGW("fclose failed for tempOutputFile \\'%s\\': %s", tempOutputPath, strerror(errno));
        }
        tempOutputFile = nullptr;
    }

    if (strlen(tempInputPath) > 0) {
        LOGI("Removing temporary input file: %s", tempInputPath);
        if(remove(tempInputPath) != 0) {
            LOGW("Failed to remove temporary input file \\'%s\\': %s", tempInputPath, strerror(errno));
        }
    }
    if (strlen(tempOutputPath) > 0) {
        LOGI("Removing temporary output file: %s", tempOutputPath);
        if(remove(tempOutputPath) != 0) {
            LOGW("Failed to remove temporary output file \\'%s\\': %s", tempOutputPath, strerror(errno));
        }
    }

    if (inputFd >= 0) {
        LOGI("Closing input FD %d", inputFd);
        if (close(inputFd) == -1) {
            LOGW("Failed to close input FD %d: %s (errno %d)", inputFd, strerror(errno), errno);
            if (ret == 0) ret = -121;
        } else {
            LOGI("Input FD %d closed.", inputFd);
        }
    }
    if (outputFd >= 0) {
        LOGI("Closing output FD %d", outputFd);
        if (close(outputFd) == -1) {
            LOGW("Failed to close output FD %d: %s (errno %d)", outputFd, strerror(errno), errno);
            // Prioritize FD close error if primary operation was success or a less critical error.
            if (ret == 0 || ret > -110 || ret == -120) {
                ret = -122;
            }
        } else {
            LOGI("Output FD %d closed.", outputFd);
        }
    }

    if (codecName && codec_name_jstr) {
        env->ReleaseStringUTFChars(codec_name_jstr, codecName);
    }
    if (cacheDirStr && cacheDirJStr) { // Release C string obtained from Java string
        env->ReleaseStringUTFChars(cacheDirJStr, cacheDirStr);
        cacheDirStr = nullptr;
    }


    // Delete JNI Local References
    if (tempInputPathJStr) env->DeleteLocalRef(tempInputPathJStr);
    if (tempOutputPathJStr) env->DeleteLocalRef(tempOutputPathJStr);
    if (cacheDirJStr) env->DeleteLocalRef(cacheDirJStr); // JNI string for cache dir path
    if (cacheDirFile) env->DeleteLocalRef(cacheDirFile); // JNI object for cache dir File
    if (fileClass) env->DeleteLocalRef(fileClass); // JNI class for java.io.File
    if (localContextClassForCacheDir) env->DeleteLocalRef(localContextClassForCacheDir); // Class of context_obj (if not already released)

    LOGI("decodeUri END: final ret=%d", ret);
    return ret;
}
