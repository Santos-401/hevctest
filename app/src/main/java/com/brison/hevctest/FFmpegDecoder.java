package com.brison.hevctest;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

public class FFmpegDecoder {

    private static final String TAG = "FFmpegDecoderJava";

    // Load the native library
    static {
        try {
            System.loadLibrary("ffmpeg_jni");
            Log.i(TAG, "ffmpeg_jni library loaded successfully.");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load ffmpeg_jni library", e);
            // Handle library load failure, perhaps by disabling FFmpeg features
        }
    }

    // Native methods corresponding to JNI functions
    // These must match the C/C++ function signatures including package and class name

    /**
     * Initializes FFmpeg components. Call this once if needed.
     * @return 0 on success, negative value on error.
     */
    public native int initFFmpeg();

    /**
     * Releases FFmpeg resources. Call this when FFmpeg is no longer needed.
     */
    public native void releaseFFmpeg();

    /**
     * Decodes a video file specified by its path to a YUV output file.
     *
     * @param inputFilePath  Absolute path to the input video file.
     * @param outputFilePath Absolute path for the output YUV file.
     * @param codecName      Name of the codec to use (e.g., "h264", "hevc").
     * @return 0 on success (e.g. frames written), negative value on error.
     */
    public native int decodeFile(String inputFilePath, String outputFilePath, String codecName);

    /**
     * Decodes a video file specified by an input URI to an output URI.
     * Internally, this may use temporary files and file descriptors.
     *
     * @param context    Application or Activity context to access ContentResolver.
     * @param inputUri   Uri of the input video file.
     * @param outputUri  Uri for the output YUV file.
     * @param codecName  Name of the codec to use (e.g., "h264", "hevc").
     * @return 0 on success (e.g. frames written), negative value on error.
     */
    public native int decodeUri(Context context, Uri inputUri, Uri outputUri, String codecName);

    // Example of a higher-level wrapper method if needed
    public boolean decodeVideoFile(String inputPath, String outputPath, String codec) {
        Log.d(TAG, "Requesting decodeFile: " + inputPath + " -> " + outputPath + " with codec " + codec);
        // Optional: Call initFFmpeg() here if it's not called globally
        // initFFmpeg();
        int result = decodeFile(inputPath, outputPath, codec);
        // Optional: Call releaseFFmpeg() here if init was per-call
        // releaseFFmpeg();
        if (result == 0) {
            Log.i(TAG, "decodeFile successful for: " + inputPath);
            return true;
        } else {
            Log.e(TAG, "decodeFile failed for: " + inputPath + ", error code: " + result);
            return false;
        }
    }

    public boolean decodeVideoUri(Context context, Uri input, Uri output, String codec) {
        Log.d(TAG, "Requesting decodeUri: " + input + " -> " + output + " with codec " + codec);
        // Optional: initFFmpeg();
        int result = decodeUri(context, input, output, codec);
        // Optional: releaseFFmpeg();
        if (result == 0) {
            Log.i(TAG, "decodeUri successful for: " + input);
            return true;
        } else {
            Log.e(TAG, "decodeUri failed for: " + input + ", error code: " + result);
            return false;
        }
    }
}
