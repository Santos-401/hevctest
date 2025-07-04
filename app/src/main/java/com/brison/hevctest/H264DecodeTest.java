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

    public static int decodeStreamToStream(Context context, Uri inputUri, Uri outputUri) throws IOException {
        if (context == null || inputUri == null || outputUri == null) {
            Log.e(TAG, "Context, input URI, or output URI cannot be null.");
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
            Log.e(TAG, "Input data is empty from URI: " + inputUri);
            throw new IOException("Input data is empty from URI: " + inputUri);
        }

        List<byte[]> nals = splitNalUnits(rawData);
        Log.i(TAG, "decodeStreamToStream: Found " + nals.size() + " NAL units after splitting for URI: " + inputUri);
        // Add initial NAL unit logging similar to decodeRawBitstream if needed for URIs

        byte[][] csd = extractSpsAndPps(nals);
        if (csd[0] == null || csd[1] == null) {
            Log.e(TAG, "Failed to extract SPS or PPS from URI: " + inputUri + (csd[0] == null ? " (SPS missing)" : "") + (csd[1] == null ? " (PPS missing)" : ""));
            return 0;
        }

        Pair<Integer, Integer> resPair = H264ResolutionExtractor.extractResolution(rawData); // Consider passing SPS if extractor supports it
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
        Log.d(TAG, "Set csd-0 for URI " + inputUri + " with offset: " + spsOffset + ", length: " + (spsData.length - spsOffset));

        int ppsOffset = getStartCodeOffset(ppsData, "PPS for URI " + inputUri);
        format.setByteBuffer("csd-1", ByteBuffer.wrap(ppsData, ppsOffset, ppsData.length - ppsOffset));
        Log.d(TAG, "Set csd-1 for URI " + inputUri + " with offset: " + ppsOffset + ", length: " + (ppsData.length - ppsOffset));

        try (OutputStream finalOutputStream = context.getContentResolver().openOutputStream(outputUri)) {
            if (finalOutputStream == null) {
                throw new IOException("Failed to open OutputStream for output URI: " + outputUri);
            }

            // Using GitHub logic for decoder selection and fallback
            String hwCodec = selectHardwareCodecName(width, height);
            try {
                Log.i(TAG, "Attempting HW decode for URI " + inputUri + " to " + outputUri + " with codec: " + hwCodec);
                return decodeWithCodec(nals, format, hwCodec, finalOutputStream);
            } catch (Exception e) {
                Log.w(TAG, "Hardware decode failed for URI " + inputUri + " with " + hwCodec + ", fallback to software.", e);
                String swCodec = selectSoftwareCodecName();
                Log.i(TAG, "Attempting SW decode for URI " + inputUri + " to " + outputUri + " with codec: " + swCodec);
                return decodeWithCodec(nals, format, swCodec, finalOutputStream);
            }
        }
    }

    public static int decodeRawBitstream(String inputPath, String outputDirPath) throws IOException {
        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            Log.e(TAG, "Input file not found: " + inputPath);
            return 0;
        }

        byte[] rawData = new byte[(int) inputFile.length()];
        try (FileInputStream fis = new FileInputStream(inputFile)) {
            fis.read(rawData);
        }

        List<byte[]> nals = splitNalUnits(rawData);
        Log.i(TAG, "decodeRawBitstream: Found " + nals.size() + " NAL units after splitting for file: " + inputPath);
        for (int i = 0; i < Math.min(nals.size(), 5); i++) {
            byte[] nal = nals.get(i);
            if (nal == null || nal.length < 1) { // Basic check
                Log.w(TAG, "decodeRawBitstream: NAL unit " + i + " is null or empty. Length: " + (nal == null ? "null" : nal.length));
                continue;
            }
            int nalType = -1;
            int nalHeaderOffset = getStartCodeOffsetSafe(nal);

            if (nalHeaderOffset != -1 && nal.length > nalHeaderOffset) {
                nalType = nal[nalHeaderOffset] & 0x1F;
                Log.i(TAG, "decodeRawBitstream: NAL unit " + i + " - Type: " + nalType + ", Length: " + nal.length);
            } else {
                StringBuilder sb = new StringBuilder();
                for(int j=0; j < Math.min(nal.length, 10); j++) {
                    sb.append(String.format("%02X ", nal[j]));
                }
                Log.w(TAG, "decodeRawBitstream: NAL unit " + i + " - Could not determine type (headerOffset=" + nalHeaderOffset + "). Length: " + nal.length + ". First bytes: " + sb.toString());
            }
        }

        byte[][] csd = extractSpsAndPps(nals);
        if (csd[0] == null || csd[1] == null) {
            Log.e(TAG, "Failed to extract SPS or PPS for file " + inputPath + (csd[0] == null ? " (SPS missing)" : "") + (csd[1] == null ? " (PPS missing)" : ""));
            return 0;
        }

        Pair<Integer, Integer> resPair = H264ResolutionExtractor.extractResolution(rawData); // Consider passing SPS
        int width, height;
        if (resPair != null) {
            width  = resPair.first;
            height = resPair.second;
        } else {
            Log.w(TAG, "Resolution extraction failed for file " + inputPath + ", using default 352x288");
            width  = 352;
            height = 288;
        }
        Log.i(TAG, "Resolution for file " + inputPath + ": " + width + "x" + height);

        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        byte[] spsData = csd[0];
        byte[] ppsData = csd[1];

        int spsOffset = getStartCodeOffset(spsData, "SPS for file " + inputPath);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(spsData, spsOffset, spsData.length - spsOffset));
        Log.d(TAG, "Set csd-0 for file " + inputPath + " with offset: " + spsOffset + ", length: " + (spsData.length - spsOffset));

        int ppsOffset = getStartCodeOffset(ppsData, "PPS for file " + inputPath);
        format.setByteBuffer("csd-1", ByteBuffer.wrap(ppsData, ppsOffset, ppsData.length - ppsOffset));
        Log.d(TAG, "Set csd-1 for file " + inputPath + " with offset: " + ppsOffset + ", length: " + (ppsData.length - ppsOffset));

        File outDir = new File(outputDirPath);
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("Could not create output directory: " + outputDirPath);
        }
        File hwOutFile = new File(outDir, inputFile.getName() + ".yuv");
        File swOutFile = new File(outDir, inputFile.getName() + "_sw.yuv");

        if (inputFile.getName().equals("BA3_SVA_C.264")) {
            Log.i(TAG, "Forcing software decoder for BA3_SVA_C.264");
            String swCodec = selectSoftwareCodecName();
            Log.i(TAG, "Attempting BA3_SVA_C.264 with software codec: " + swCodec);
            try (FileOutputStream fosSw = new FileOutputStream(swOutFile)) {
                return decodeWithCodec(nals, format, swCodec, fosSw);
            } catch (Exception e) {
                Log.e(TAG, "Software decode failed for BA3_SVA_C.264 with " + swCodec, e);
                throw e;
            }
        } else {
            String hwCodec = selectHardwareCodecName(width, height);
            try (FileOutputStream fosHw = new FileOutputStream(hwOutFile)) {
                Log.i(TAG, "Attempting HW decode for file " + inputPath + " to " + hwOutFile.getAbsolutePath() + " with " + hwCodec);
                return decodeWithCodec(nals, format, hwCodec, fosHw);
            } catch (Exception e) {
                Log.w(TAG, "Hardware decode failed for file " + inputPath + " with " + hwCodec + ", fallback to software.", e);
                if (hwOutFile.exists() && !hwOutFile.delete()) {
                    Log.w(TAG, "Could not delete failed HW output file: " + hwOutFile.getAbsolutePath());
                }
                String swCodec = selectSoftwareCodecName();
                try (FileOutputStream fosSw = new FileOutputStream(swOutFile)) {
                    Log.i(TAG, "Attempting SW decode for file " + inputPath + " to " + swOutFile.getAbsolutePath() + " with " + swCodec);
                    return decodeWithCodec(nals, format, swCodec, fosSw);
                }
            }
        }
    }

    private static int getStartCodeOffset(byte[] data, String nalUnitNameForLog) throws IOException {
        if (data.length >= 4 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1) {
            return 4;
        } else if (data.length >= 3 && data[0] == 0 && data[1] == 0 && data[2] == 1) {
            return 3;
        }
        throw new IOException("Invalid start code format in " + nalUnitNameForLog + ". Data length: " + data.length);
    }

    private static int decodeWithCodec(
            List<byte[]> nals,
            MediaFormat format,
            String codecName,
            OutputStream outputStream // Takes OutputStream for flexibility
    ) throws IOException {
        MediaCodec decoder = null;
        AtomicInteger frameCount = new AtomicInteger(0);
        Log.i(TAG, "H.264: Decoding with codec: " + codecName + ", Total NAL units to process: " + nals.size());

        try {
            decoder = MediaCodec.createByCodecName(codecName);
            decoder.configure(format, null, null, 0);
            decoder.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEOS = false;
            boolean outputEOS = false;
            int nalUnitIdx = 0;
            long presentationTimeUs = 0;

            while (!outputEOS) {
                if (!inputEOS) {
                    int inIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inIndex >= 0) {
                        if (nalUnitIdx < nals.size()) {
                            byte[] nal = nals.get(nalUnitIdx);
                            if (nal == null || nal.length == 0) {
                                Log.w(TAG, "H.264: Skipping empty NAL unit at index " + nalUnitIdx);
                                nalUnitIdx++;
                                continue;
                            }

                            int nalType = -1;
                            int nalHeaderOffset = getStartCodeOffsetSafe(nal);
                            if (nalHeaderOffset != -1 && nal.length > nalHeaderOffset) {
                                nalType = nal[nalHeaderOffset] & 0x1F;
                                Log.d(TAG, "H.264: Queuing NAL unit " + nalUnitIdx + "/" + nals.size() + ", Type: " + nalType + ", Length: " + nal.length + ", PTS: " + presentationTimeUs);
                            } else {
                                Log.w(TAG, "H.264: Queuing NAL unit " + nalUnitIdx + "/" + nals.size() + " with invalid start code or insufficient length. Length: " + nal.length + ", PTS: " + presentationTimeUs);
                            }

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
                        } else {
                            Log.i(TAG, "H.264: All NALs sent, queuing EOS for input.");
                            decoder.queueInputBuffer(inIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEOS = true;
                        }
                    }
                }

                int outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                switch (outIndex) {
                    case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                        Log.i(TAG, "H.264: Output format changed to: " + decoder.getOutputFormat());
                        break;
                    case MediaCodec.INFO_TRY_AGAIN_LATER:
                        break;
                    case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                        Log.d(TAG, "H.264: Output buffers changed (deprecated).");
                        break;
                    default:
                        if (outIndex < 0) {
                            Log.w(TAG, "H.264: dequeueOutputBuffer returned unexpected negative index: " + outIndex);
                            break;
                        }
                        Log.d(TAG, "H.264: Output buffer available. Index: " + outIndex + ", Size: " + info.size + ", Flags: " + info.flags + ", PTS: " + info.presentationTimeUs);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            Log.i(TAG, "H.264: Codec config buffer received. Releasing.");
                            decoder.releaseOutputBuffer(outIndex, false);
                            break;
                        }
                        ByteBuffer outBuf = decoder.getOutputBuffer(outIndex);
                        if (info.size > 0) {
                            if (outBuf != null) {
                                byte[] yuvData = new byte[info.size];
                                outBuf.get(yuvData);
                                outputStream.write(yuvData); // Use the passed OutputStream
                                int currentFrameCount = frameCount.incrementAndGet();
                                Log.i(TAG, "H.264: Wrote frame " + currentFrameCount + ", " + info.size + " bytes of YUV data, PTS: " + info.presentationTimeUs);
                            } else {
                                Log.e(TAG, "H.264: Output buffer was null for index " + outIndex + " but info.size was " + info.size);
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.i(TAG, "H.264: Output EOS flag received.");
                            outputEOS = true;
                        }
                        break;
                }
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "H.264: MediaCodec configuration/operation failed (IllegalArgumentException): " + e.getMessage(), e);
            throw new IOException("H.264: MediaCodec configuration/operation failed (IllegalArgumentException): " + e.getMessage(), e);
        } catch (MediaCodec.CodecException e) {
            Log.e(TAG, "H.264: MediaCodec operation failed (CodecException): " + e.getMessage() + ", DiagnosticInfo: " + e.getDiagnosticInfo(), e);
            throw new IOException("H.264: MediaCodec operation failed (CodecException): " + e.getMessage(), e);
        } catch (IllegalStateException e) {
            Log.e(TAG, "H.264: MediaCodec operation failed (IllegalStateException): " + e.getMessage(), e);
            throw new IOException("H.264: MediaCodec operation failed (IllegalStateException): " + e.getMessage(), e);
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

    private static String selectHardwareCodecName(int width, int height) {
        MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (info.isEncoder()) continue;
            String name = info.getName();
            // Prefer non-Google HW decoders first
            if (!name.toLowerCase().contains("avc.decoder") || name.toLowerCase().startsWith("omx.google.")) continue;
            try {
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
                if (caps.getVideoCapabilities().isSizeSupported(width, height)) { // isSizeSupported is more direct
                    Log.d(TAG, "Selected HW H.264 decoder: " + name + " (supports " + width + "x" + height + ")");
                    return name;
                }
            } catch (Exception ignored) {}
        }
         // Fallback to any HW decoder by type if specific size match failed for non-Google
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (info.isEncoder()) continue;
            String name = info.getName();
            if (!name.toLowerCase().contains("avc.decoder") || name.toLowerCase().startsWith("omx.google.")) continue;
             Log.d(TAG, "Trying less specific HW H.264 decoder: " + name);
            return name; // Return first non-Google even if size not explicitly checked or supported
        }
        Log.w(TAG, "No specific non-Google HW H.264 decoder found for " + width + "x" + height + ". Fallback to MIME type (may pick SW or any HW).");
        return MediaFormat.MIMETYPE_VIDEO_AVC;
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
        Log.w(TAG, "OMX.google.h264.decoder not found, using default MIME type for SW fallback.");
        return MediaFormat.MIMETYPE_VIDEO_AVC;
    }

    // GitHub version of splitNalUnits
    private static List<byte[]> splitNalUnits(byte[] data) {
        List<byte[]> list = new ArrayList<>();
        if (data == null || data.length < 3) {
            return list;
        }
        int len = data.length;
        int firstStartCodePos = -1;

        for (int i = 0; i + 2 < len; i++) {
            if (data[i] == 0 && data[i+1] == 0) {
                if (i + 3 < len && data[i+2] == 0 && data[i+3] == 1) { // 00 00 00 01
                    firstStartCodePos = i;
                    break;
                } else if (data[i+2] == 1) { // 00 00 01
                    firstStartCodePos = i;
                    break;
                }
            }
        }

        if (firstStartCodePos == -1) {
            Log.w(TAG, "splitNalUnits: No start codes found in the entire data stream.");
            return list;
        }

        int offset = firstStartCodePos;
        while (offset < len) {
            int currentNalStart = offset;
            int currentPrefix = 0;
            if (currentNalStart + 3 < len && data[currentNalStart] == 0 && data[currentNalStart+1] == 0 && data[currentNalStart+2] == 0 && data[currentNalStart+3] == 1) {
                currentPrefix = 4;
            } else if (currentNalStart + 2 < len && data[currentNalStart] == 0 && data[currentNalStart+1] == 0 && data[currentNalStart+2] == 1) {
                currentPrefix = 3;
            } else {
                Log.e(TAG, "splitNalUnits: Expected start code at offset " + offset + " but not found. Stopping parse.");
                break;
            }

            int nextNalStart = -1;
            for (int i = currentNalStart + currentPrefix; i + 2 < len; i++) {
                if (data[i] == 0 && data[i+1] == 0) {
                    if (i + 3 < len && data[i+2] == 0 && data[i+3] == 1) {
                        nextNalStart = i;
                        break;
                    } else if (data[i+2] == 1) {
                        nextNalStart = i;
                        break;
                    }
                }
            }

            int endOfCurrentNal = (nextNalStart != -1) ? nextNalStart : len;
            byte[] nal = new byte[endOfCurrentNal - currentNalStart];
            System.arraycopy(data, currentNalStart, nal, 0, nal.length);
            list.add(nal);

            if (nextNalStart != -1) {
                offset = nextNalStart;
            } else {
                break;
            }
        }
        return list;
    }

    // GitHub version of findStartCode (utility)
    private static int findStartCode(byte[] data, int offset) {
        for (int i = offset; i + 3 < data.length; i++) { // Corrected loop condition
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                return i; // Found 001
            } else if (i + 4 <= data.length && data[i + 2] == 0 && data[i + 3] == 1) { // Found 0001 (ensure i+3 is a valid index)
                 //This was "i + 4 < data.length" for data[i+3], should be data.length to include i+3
                // Corrected: data[i] == 0 && data[i+1] == 0 && data[i+2] == 0 && data[i+3] == 1
                 if (data[i] == 0 && data[i + 1] == 0 && data[i+2] == 0 && data[i+3] == 1 ) return i;
            }
        }
        return -1;
    }

    // Local version of extractSpsAndPps (more robust with getStartCodeOffsetSafe)
    private static byte[][] extractSpsAndPps(List<byte[]> nals) {
        byte[] sps = null, pps = null;
        for (byte[] nal : nals) {
            if (nal == null || nal.length < 1) continue; // Basic check

            int offset = getStartCodeOffsetSafe(nal);
            if (offset == -1 || nal.length <= offset) {
                Log.w(TAG, "extractSpsAndPps: Invalid NAL unit or too short to determine type. Length: " + nal.length);
                continue;
            }

            int nalType = nal[offset] & 0x1F;
            if (nalType == 7) { // SPS
                sps = nal;
                Log.d(TAG, "SPS NAL unit found, length: " + nal.length);
            } else if (nalType == 8) { // PPS
                pps = nal;
                Log.d(TAG, "PPS NAL unit found, length: " + nal.length);
            }
            if (sps != null && pps != null) break;
        }
        if (sps == null) Log.w(TAG, "SPS NAL unit not found in the provided NAL units.");
        if (pps == null) Log.w(TAG, "PPS NAL unit not found in the provided NAL units.");
        return new byte[][]{sps, pps};
    }

    // Local version of getStartCodeOffsetSafe
    private static int getStartCodeOffsetSafe(byte[] data) {
        if (data == null) return -1;
        if (data.length >= 4 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1) {
            return 4;
        }
        if (data.length >= 3 && data[0] == 0 && data[1] == 0 && data[2] == 1) {
            return 3;
        }
        return -1;
    }
}
