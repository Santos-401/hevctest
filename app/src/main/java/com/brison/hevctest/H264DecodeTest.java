package com.brison.hevctest;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;
import android.util.Pair;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class H264DecodeTest {
    private static final String TAG = "H264DecodeTest";
    private static final long TIMEOUT_US = 10000;

    // New method for URI-based I/O
    public static int decodeStreamToStream(Context context, Uri inputUri, Uri outputUri) throws IOException {
        if (context == null || inputUri == null || outputUri == null) {
            throw new IllegalArgumentException("Context, input URI, or output URI cannot be null.");
        }

        byte[] rawData;
        try (InputStream inputStream = context.getContentResolver().openInputStream(inputUri);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            if (inputStream == null) {
                throw new IOException("Failed to open InputStream for input URI: " + inputUri);
            }
            byte[] buffer = new byte[8192]; // 8KB buffer
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            rawData = baos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Error reading input URI: " + inputUri, e);
            throw new IOException("Error reading input stream from URI: " + e.getMessage(), e);
        }

        if (rawData.length == 0) {
            throw new IOException("Input data is empty from URI: " + inputUri);
        }

        List<byte[]> nals = splitNalUnits(rawData);
        byte[][] csd = extractSpsAndPps(nals);
        if (csd[0] == null || csd[1] == null) {
            Log.e(TAG, "Failed to extract SPS or PPS from URI: " + inputUri);
            return 0; // Or throw an exception
        }

        Pair<Integer, Integer> resPair = H264ResolutionExtractor.extractResolution(rawData);
        int width, height;
        if (resPair != null) {
            width = resPair.first;
            height = resPair.second;
        } else {
            Log.w(TAG, "Resolution extraction failed for URI " + inputUri + ", using default 352x288");
            width = 352;
            height = 288;
        }
        Log.i(TAG, "Resolution for URI " + inputUri + ": " + width + "x" + height);

        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        byte[] spsData = csd[0];
        byte[] ppsData = csd[1];

        int spsOffset = getStartCodeOffset(spsData, "SPS for URI " + inputUri);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(spsData, spsOffset, spsData.length - spsOffset));
        int ppsOffset = getStartCodeOffset(ppsData, "PPS for URI " + inputUri);
        format.setByteBuffer("csd-1", ByteBuffer.wrap(ppsData, ppsOffset, ppsData.length - ppsOffset));

        try (OutputStream finalOutputStream = context.getContentResolver().openOutputStream(outputUri)) {
            if (finalOutputStream == null) {
                throw new IOException("Failed to open OutputStream for output URI: " + outputUri);
            }

            String hwCodec = selectHardwareCodecName(width, height);
            try {
                Log.i(TAG, "Attempting HW decode for URI " + inputUri + " to " + outputUri + " with codec: " + hwCodec);
                return decodeWithCodec(nals, format, hwCodec, finalOutputStream);
            } catch (Exception e) {
                Log.w(TAG, "Hardware decode failed for URI " + inputUri + " with " + hwCodec + ", fallback to software.", e);
                String swCodec = selectSoftwareCodecName();
                Log.i(TAG, "Attempting SW decode for URI " + inputUri + " to " + outputUri + " with codec: " + swCodec);
                // The finalOutputStream is managed by try-with-resources and will be closed once.
                // If HW fails, SW will attempt to write to the same (potentially new) stream instance if openOutputStream is called again.
                // However, since finalOutputStream is defined outside the SW try-catch, it refers to the initially opened stream.
                // This means SW will write to the same stream HW attempted, overwriting any partial data.
                return decodeWithCodec(nals, format, swCodec, finalOutputStream);
            }
        }
    }

    /**
     * Original path-based method, now uses the refactored decodeWithCodec.
     */
    public static int decodeRawBitstream(String inputPath, String outputDirPath) throws IOException {
        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            Log.e(TAG, "Input file not found: " + inputPath);
            return 0;
        }
        // 1. ファイル読み込み
        byte[] rawData = new byte[(int) inputFile.length()];
        try (FileInputStream fis = new FileInputStream(inputFile)) {
            fis.read(rawData);
        }
        // 2. NAL分割 & SPS/PPS抽出
        List<byte[]> nals = splitNalUnits(rawData);
        byte[][] csd = extractSpsAndPps(nals);
        if (csd[0] == null || csd[1] == null) {
            Log.e(TAG, "Failed to extract SPS or PPS");
            return 0;
        }
        // 3. 解像度取得 (H264ResolutionExtractor を利用)
        Pair<Integer, Integer> resPair = H264ResolutionExtractor.extractResolution(rawData);
        int width;
        int height;
        if (resPair != null) {
            width  = resPair.first;
            height = resPair.second;
        } else {
            Log.w(TAG, "解像度抽出失敗、デフォルト 352x288 を使用");
            width  = 352;
            height = 288;
        }
        Log.i(TAG, "Resolution: " + width + "x" + height);
        // 4. MediaFormat作成
        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height);

        byte[] spsData = csd[0];
        byte[] ppsData = csd[1];

        // Determine offset for SPS (csd[0])
        int spsOffset = getStartCodeOffset(spsData, "SPS for file " + inputPath);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(spsData, spsOffset, spsData.length - spsOffset));
        int ppsOffset = getStartCodeOffset(ppsData, "PPS for file " + inputPath);
        format.setByteBuffer("csd-1", ByteBuffer.wrap(ppsData, ppsOffset, ppsData.length - ppsOffset));

        File outDir = new File(outputDirPath);
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("Could not create output directory: " + outputDirPath);
        }
        File hwOutFile = new File(outDir, inputFile.getName() + ".yuv");

        String hwCodec = selectHardwareCodecName(width, height);
        try (FileOutputStream fosHw = new FileOutputStream(hwOutFile)) {
            Log.i(TAG, "Attempting HW decode for file " + inputPath + " to " + hwOutFile.getAbsolutePath() + " with " + hwCodec);
            return decodeWithCodec(nals, format, hwCodec, fosHw);
        } catch (Exception e) {
            Log.w(TAG, "Hardware decode failed for file " + inputPath + " with " + hwCodec + ", fallback to software.", e);
            if (hwOutFile.exists() && !hwOutFile.delete()) { // Attempt to clean up failed HW output
                Log.w(TAG, "Could not delete failed HW output file: " + hwOutFile.getAbsolutePath());
            }
            String swCodec = selectSoftwareCodecName();
            File swOutFile = new File(outDir, inputFile.getName() + "_sw.yuv");
            try (FileOutputStream fosSw = new FileOutputStream(swOutFile)) {
                Log.i(TAG, "Attempting SW decode for file " + inputPath + " to " + swOutFile.getAbsolutePath() + " with " + swCodec);
                return decodeWithCodec(nals, format, swCodec, fosSw);
            }
        }
    }

    // Helper to get the offset of NAL unit data after the start code
    private static int getStartCodeOffset(byte[] data, String nalUnitNameForLog) throws IOException {
        if (data.length > 4 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1) {
            return 4;
        } else if (data.length > 3 && data[0] == 0 && data[1] == 0 && data[2] == 1) {
            return 3;
        }
        throw new IOException("Invalid start code format in " + nalUnitNameForLog);
    }

    /**
     * Decodes NAL units using the specified codec and writes YUV data to the given OutputStream.
     */
    private static int decodeWithCodec(
            List<byte[]> nals,
            MediaFormat format,
            String codecName,
            OutputStream outputStream // Changed from File outputFile
    ) throws IOException {
        MediaCodec decoder = null;
        AtomicInteger frameCount = new AtomicInteger(0);

        try {
            decoder = MediaCodec.createByCodecName(codecName);
            decoder.configure(format, null, null, 0);
            decoder.start();

            // The provided outputStream is used directly.
            // It's the responsibility of the caller to manage (close) this stream.
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEOS = false;
            boolean outputEOS = false;
            int nalUnitIdx = 0;
            long presentationTimeUs = 0;

            while (!outputEOS) {
                // Input Handling
                if (!inputEOS) {
                    int inIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inIndex >= 0) {
                        if (nalUnitIdx < nals.size()) {
                            byte[] nal = nals.get(nalUnitIdx);
                            ByteBuffer ib = decoder.getInputBuffer(inIndex);
                            if (ib != null) {
                                ib.clear();
                                ib.put(nal);
                                decoder.queueInputBuffer(inIndex, 0, nal.length, presentationTimeUs, 0);
                                presentationTimeUs += 1000000L / 30; // 30 FPS assumption
                            } else {
                                Log.e(TAG, "H.264: getInputBuffer returned null for index " + inIndex);
                            }
                            nalUnitIdx++;
                        } else { // All NALs have been queued
                            Log.i(TAG, "H.264: All NALs sent, queuing EOS for input.");
                            decoder.queueInputBuffer(inIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEOS = true;
                        }
                    } else {
                        // Log.d(TAG, "H.264: Input buffer not available.");
                    }
                }

                // Output Handling
                int outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                switch (outIndex) {
                    case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                        Log.i(TAG, "H.264: Output format changed to: " + decoder.getOutputFormat());
                        break;
                    case MediaCodec.INFO_TRY_AGAIN_LATER:
                        // Log.d(TAG, "H.264: No output buffer available yet.");
                        break;
                    case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                        Log.d(TAG, "H.264: Output buffers changed (deprecated).");
                        break;
                    default:
                        if (outIndex < 0) {
                            Log.w(TAG, "H.264: dequeueOutputBuffer returned an unexpected index: " + outIndex);
                            break;
                        }
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            Log.d(TAG, "H.264: Skipping codec config buffer.");
                            decoder.releaseOutputBuffer(outIndex, false);
                            break;
                        }
                        ByteBuffer outBuf = decoder.getOutputBuffer(outIndex);
                        if (outBuf != null && info.size > 0) {
                            byte[] yuvData = new byte[info.size];
                            outBuf.get(yuvData);
                            outputStream.write(yuvData); // Use the passed OutputStream
                            frameCount.incrementAndGet();
                        } else if (outBuf == null && info.size > 0) {
                            Log.w(TAG, "H.264: Output buffer was null for index " + outIndex + " but info.size was " + info.size);
                        }
                        decoder.releaseOutputBuffer(outIndex, false);

                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.i(TAG, "H.264: Output EOS reached.");
                            outputEOS = true;
                        }
                        break;
                }
            } // End of while(!outputEOS)
            // outputStream is NOT closed here; it's managed by the calling method.
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "H.264: MediaCodec configuration/operation failed (IllegalArgumentException): " + e.getMessage(), e);
            throw new IOException("H.264: MediaCodec configuration/operation failed (IllegalArgumentException): " + e.getMessage(), e);
        } catch (MediaCodec.CodecException e) {
            Log.e(TAG, "H.264: MediaCodec operation failed (CodecException): " + e.getMessage() + ", DiagnosticInfo: " + e.getDiagnosticInfo(), e);
            throw new IOException("H.264: MediaCodec operation failed (CodecException): " + e.getMessage(), e);
        } catch (IllegalStateException e) {
            Log.e(TAG, "H.264: MediaCodec operation failed (IllegalStateException): " + e.getMessage(), e);
            throw new IOException("H.264: MediaCodec operation failed (IllegalStateException): " + e.getMessage(), e);
        } catch (IOException e) { // To catch fos related IOExceptions
            Log.e(TAG, "H.264: IOException during decoding: " + e.getMessage(), e);
            throw e; // Re-throw original IOException
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (IllegalStateException e) {
                    Log.e(TAG, "H.264: IllegalStateException on codec.stop()", e);
                }
                decoder.release();
                Log.d(TAG, "H.264: MediaCodec stopped and released.");
            }
        }
        return frameCount.get();
    }

    // --- Utility methods ---
    private static String selectHardwareCodecName(int width, int height) {
        MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (info.isEncoder()) continue;
            String name = info.getName();
            // Prefer hardware decoders, often indicated by not being "OMX.google."
            // This is a heuristic and might need adjustment based on specific device behaviors.
            if (!name.toLowerCase().contains("avc.decoder") || name.toLowerCase().startsWith("omx.google.")) continue;
            try {
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
                if (caps.getVideoCapabilities().isSizeSupported(width, height)) {
                    Log.d(TAG, "Selected HW H.264 decoder: " + name);
                    return name;
                }
            } catch (Exception ignored) {}
        }
        Log.w(TAG, "No specific HW H.264 decoder found for " + width + "x" + height + ", using default type.");
        return MediaFormat.MIMETYPE_VIDEO_AVC; // Fallback, may still pick a HW decoder by type.
    }

    private static String selectSoftwareCodecName() {
        MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (info.isEncoder()) continue;
            String name = info.getName();
            if (name.equalsIgnoreCase("OMX.google.h264.decoder")) {
                Log.d(TAG, "Selected SW H.264 decoder: " + name);
                return name;
            }
        }
        Log.w(TAG, "OMX.google.h264.decoder not found, using default type for SW.");
        return MediaFormat.MIMETYPE_VIDEO_AVC; // Fallback
    }

    private static List<byte[]> splitNalUnits(byte[] data) {
        List<Integer> starts = new ArrayList<>();
        // Find all start codes (0001 or 001)
        for (int i = 0; i < data.length - 3; ) { // Ensure at least 3 bytes remain
            if (data[i] == 0 && data[i+1] == 0) {
                if (data[i+2] == 1) { // 001
                    starts.add(i);
                    i += 3; // Move past this start code
                    continue;
                } else if (i + 3 < data.length && data[i+2] == 0 && data[i+3] == 1) { // 0001
                    starts.add(i);
                    i += 4; // Move past this start code
                    continue;
                }
            }
            i++;
        }
        return createNalListFromStarts(data, starts);
    }

    // This findStartCode is not actively used by the main decoding logic anymore,
    // but kept if it's useful for other purposes or direct NAL parsing.
    private static int findStartCode(byte[] data, int offset) {
        for (int i = offset; i < data.length - 3; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) return i; // Found 001
                if (i + 3 < data.length && data[i + 2] == 0 && data[i + 3] == 1) return i; // Found 0001
            }
        }
        return -1;
    }

    // Helper to create NAL list from data and identified start indices
    private static List<byte[]> createNalListFromStarts(byte[] data, List<Integer> starts) {
        List<byte[]> units = new ArrayList<>();
        if (starts.isEmpty()) {
            // If no start codes, but data exists, treat the whole data as one NAL unit?
            // Or return empty? For H.264, this usually means malformed.
            // Log.w(TAG, "No start codes found in data, returning empty NAL list.");
            return units;
        }
        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i);
            int end = (i + 1 < starts.size()) ? starts.get(i + 1) : data.length;
            byte[] unit = new byte[end - start];
            System.arraycopy(data, start, unit, 0, end - start);
            units.add(unit);
        }
        return units;
    }

    private static byte[][] extractSpsAndPps(List<byte[]> nals) {
        byte[] sps = null, pps = null;
        for (byte[] nal : nals) {
            if (nal.length < 4) continue; // Basic check, NAL type byte is after start code

            int offset = getStartCodeOffsetSafe(nal); // Get offset to NAL unit data
            if (offset == -1 || nal.length <= offset) { // Check if offset is valid and there's data after start code
                Log.w(TAG, "extractSpsAndPps: Invalid NAL unit or too short to determine type.");
                continue;
            }

            int nalType = nal[offset] & 0x1F; // NAL unit type (lower 5 bits of the first byte after start code)

            if (nalType == 7) { // SPS
                sps = nal;
                Log.d(TAG, "SPS NAL unit found, length: " + nal.length);
            } else if (nalType == 8) { // PPS
                pps = nal;
                Log.d(TAG, "PPS NAL unit found, length: " + nal.length);
            }
            if (sps != null && pps != null) break; // Found both
        }
        if (sps == null) Log.w(TAG, "SPS NAL unit not found in the provided NAL units.");
        if (pps == null) Log.w(TAG, "PPS NAL unit not found in the provided NAL units.");
        return new byte[][]{sps, pps};
    }

    // Helper to determine the offset of NAL data after the start code (001 or 0001)
    // Returns -1 if no valid start code is found at the beginning of 'data'.
    private static int getStartCodeOffsetSafe(byte[] data) {
        if (data.length >= 4 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1) {
            return 4; // 4-byte start code (0001)
        }
        if (data.length >= 3 && data[0] == 0 && data[1] == 0 && data[2] == 1) {
            return 3; // 3-byte start code (001)
        }
        return -1; // No valid start code at the beginning
    }
}