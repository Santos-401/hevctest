package com.brison.hevctest;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HevcDecoder {
    private static final String TAG = "HevcDecoder";
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_HEVC;
    private static final long TIMEOUT_US = 10000; // 10ms for dequeue operations
    private static final long STUCK_TIMEOUT_MS = 3000L; // 3 seconds for stuck detection

    public String findSoftwareDecoder(String mimeType) {
        try {
            MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo codecInfo : codecList.getCodecInfos()) {
                if (codecInfo.isEncoder()) {
                    continue;
                }
                boolean isSoftware = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    isSoftware = codecInfo.isSoftwareOnly();
                } else {
                    String name = codecInfo.getName().toLowerCase();
                    if (name.startsWith("omx.google.") || name.startsWith("c2.android.")) {
                        isSoftware = true;
                    } else if (name.contains(".sw.") || name.contains("software")) {
                        isSoftware = true;
                    } else if (name.startsWith("omx.") && !name.contains(".hw") && !name.contains("hardware") &&
                            !name.contains(".qcom.") && !name.contains(".qti.") &&
                            !name.contains(".mediatek.") && !name.contains(".img.") &&
                            !name.contains(".intel.") && !name.contains(".nvidia.") &&
                            !name.contains(".brcm.")) {
                        isSoftware = true;
                    }
                }
                if (!isSoftware) continue;

                String[] types = codecInfo.getSupportedTypes();
                for (String type : types) {
                    if (type.equalsIgnoreCase(mimeType)) {
                        Log.i(TAG, "Found potential software HEVC decoder: " + codecInfo.getName());
                        return codecInfo.getName();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error querying MediaCodecList for software HEVC decoder", e);
        }
        Log.w(TAG, "No suitable software HEVC decoder found for " + mimeType + ". Will rely on createDecoderByType if HW fails.");
        return "";
    }

    private List<byte[]> splitAnnexB(byte[] data) {
        List<byte[]> nalUnits = new ArrayList<>();
        if (data == null || data.length == 0) {
            return nalUnits;
        }
        int i = 0;
        while (i < data.length) {
            int nextStartCode = -1;
            // Find next start code (00 00 01 or 00 00 00 01)
            for (int j = i + 1; j <= data.length - 3; j++) { // Ensure at least 3 bytes remaining
                if (data[j] == 0x00 && data[j + 1] == 0x00) {
                    if (data[j + 2] == 0x01) {
                        nextStartCode = j;
                        break;
                    } else if (j + 3 < data.length && data[j + 2] == 0x00 && data[j + 3] == 0x01) {
                        nextStartCode = j; // This is a 4-byte start code, start of next NAL is at j
                        break;
                    }
                }
            }

            byte[] nalUnit;
            if (nextStartCode == -1) {
                // Last NAL unit
                nalUnit = Arrays.copyOfRange(data, i, data.length);
                nalUnits.add(nalUnit);
                break;
            } else {
                nalUnit = Arrays.copyOfRange(data, i, nextStartCode);
                nalUnits.add(nalUnit);
                i = nextStartCode;
            }
        }
        return nalUnits;
    }

    private MediaFormat createMediaFormat(byte[] vps, byte[] spsByteArr, byte[] pps, HEVCResolutionExtractor.SPSInfo spsInfo, String logTagPrefix) {
        MediaFormat format = MediaFormat.createVideoFormat(MIME, spsInfo.width, spsInfo.height);

        // It's generally recommended to provide CSD during configure for initial setup.
        // We will also send them as flagged input buffers if they are part of the NAL unit list.
        if (vps != null) format.setByteBuffer("csd-0", ByteBuffer.wrap(vps));
        if (spsByteArr != null) format.setByteBuffer("csd-1", ByteBuffer.wrap(spsByteArr));
        if (pps != null) format.setByteBuffer("csd-2", ByteBuffer.wrap(pps));

        int profileIdc = spsInfo.profileIdc;
        int androidProfile = -1;
        switch (profileIdc) {
            case 1: androidProfile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain; break;
            case 2: androidProfile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10; break;
            // Add other profile mappings if needed
            default: Log.w(TAG, logTagPrefix + "Unmapped HEVC profile_idc: " + profileIdc); break;
        }
        if (androidProfile != -1) {
            format.setInteger(MediaFormat.KEY_PROFILE, androidProfile);
        }

        int levelIdc = spsInfo.levelIdc;
        int androidLevel = -1;
        // Simplified level mapping logic based on common tiers; adjust if more precise mapping is needed
        if (levelIdc <= 30) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel1;
        else if (levelIdc <= 63) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel21; // Covers L2 and L2.1
        else if (levelIdc <= 93) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31; // Covers L3 and L3.1
        else if (levelIdc <= 123) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41; // Covers L4 and L4.1
        else if (levelIdc <= 156) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel52; // Covers L5, L5.1, L5.2
        else if (levelIdc <= 186) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel62; // Covers L6, L6.1, L6.2
        else { Log.w(TAG, logTagPrefix + "Unmapped HEVC level_idc: " + levelIdc); }

        if (androidLevel != -1) {
            format.setInteger(MediaFormat.KEY_LEVEL, androidLevel);
        }
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30); // Assuming 30fps, adjust if dynamic

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                format.setInteger(MediaFormat.KEY_OPERATING_RATE, Integer.MAX_VALUE);
            } catch (Exception e) {
                Log.w(TAG, logTagPrefix + "Failed to set KEY_OPERATING_RATE", e);
            }
        }
        // Log.i(TAG, logTagPrefix + "Final MediaFormat: " + format.toString()); // Keep if essential
        return format;
    }

    private void decodeInternal(List<byte[]> nalUnits, MediaFormat format, OutputStream fos,
                                String initialDecoderName, String fallbackDecoderName, String logTagPrefix) throws IOException {
        MediaCodec codec = null;
        boolean decodeSuccess = false;
        Exception lastException = null;

        String[] decoderNamesToTry = fallbackDecoderName != null && !fallbackDecoderName.isEmpty() ?
                new String[]{initialDecoderName, fallbackDecoderName} :
                new String[]{initialDecoderName};

        for (String currentDecoderName : decoderNamesToTry) {
            String logDecoderName = (currentDecoderName == null || currentDecoderName.isEmpty()) ? "Default" : currentDecoderName;
            Log.i(TAG, logTagPrefix + "Attempting to decode with " + logDecoderName + " decoder.");

            long lastSuccessfulDequeueTime = System.currentTimeMillis();
            int tryAgainContinuousCount = 0;

            try {
                codec = (currentDecoderName == null || currentDecoderName.isEmpty()) ?
                        MediaCodec.createDecoderByType(MIME) :
                        MediaCodec.createByCodecName(currentDecoderName);

                Log.i(TAG, logTagPrefix + "Selected decoder: " + codec.getCodecInfo().getName());
                // Minimal capability check logging (optional, can be removed if too verbose)
                // MediaCodecInfo.CodecCapabilities caps = codec.getCodecInfo().getCapabilitiesForType(MIME);
                // if (caps != null) {
                //     Log.d(TAG, logTagPrefix + "Decoder capabilities: " + Arrays.toString(caps.profileLevels));
                // }

                codec.configure(format, null, null, 0);
                codec.start();

                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean inputDone = false;
                boolean outputDone = false;
                boolean actualOutputEOSReceived = false;
                int nalUnitIndex = 0;
                long frameCountForPTS = 0; // Assuming PTS generation is needed.

                while (!outputDone) {
                    if (Thread.interrupted()) {
                        Log.w(TAG, logTagPrefix + logDecoderName + " decode thread interrupted.");
                        throw new IOException(logTagPrefix + logDecoderName + " decode thread interrupted.");
                    }

                    if (!inputDone) {
                        int inIndex = codec.dequeueInputBuffer(TIMEOUT_US);
                        if (inIndex >= 0) {
                            if (nalUnitIndex < nalUnits.size()) {
                                byte[] nal = nalUnits.get(nalUnitIndex++);
                                ByteBuffer ib = codec.getInputBuffer(inIndex);
                                if (ib != null) {
                                    ib.clear();
                                    ib.put(nal);
                                    long pts = frameCountForPTS * 1000000L / 30; // Assumes 30 FPS
                                    int flags = 0;
                                    int nalType = getHevcNalUnitType(nal);

                                    if (nalType == 32 || nalType == 33 || nalType == 34) { // VPS, SPS, PPS
                                        flags |= MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
                                        Log.i(TAG, logTagPrefix + logDecoderName + " Queuing NAL Type " + nalType + " with BUFFER_FLAG_CODEC_CONFIG");
                                    }
                                    codec.queueInputBuffer(inIndex, 0, nal.length, pts, flags);
                                    if ((flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                        // Only increment frame/PTS count for non-config frames to avoid issues with some decoders
                                        frameCountForPTS++;
                                    }
                                } else {
                                    Log.e(TAG, logTagPrefix + logDecoderName + " getInputBuffer returned null for index " + inIndex);
                                }
                            } else {
                                Log.i(TAG, logTagPrefix + logDecoderName + " All NAL units queued, sending EOS to input.");
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            }
                            lastSuccessfulDequeueTime = System.currentTimeMillis();
                            tryAgainContinuousCount = 0;
                        }
                    }

                    int outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                    switch (outIndex) {
                        case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                            Log.i(TAG, logTagPrefix + logDecoderName + " Output format changed: " + codec.getOutputFormat());
                            lastSuccessfulDequeueTime = System.currentTimeMillis();
                            tryAgainContinuousCount = 0;
                            break;
                        case MediaCodec.INFO_TRY_AGAIN_LATER:
                            tryAgainContinuousCount++;
                            // Removed verbose logging for INFO_TRY_AGAIN_LATER
                            break;
                        case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                            // Log.d(TAG, logTagPrefix + "Output buffers changed (deprecated).");
                            lastSuccessfulDequeueTime = System.currentTimeMillis();
                            tryAgainContinuousCount = 0;
                            break;
                        default:
                            if (outIndex >= 0) {
                                lastSuccessfulDequeueTime = System.currentTimeMillis();
                                tryAgainContinuousCount = 0;

                                ByteBuffer ob = codec.getOutputBuffer(outIndex);
                                if (ob != null) {
                                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                        // Log.d(TAG, logTagPrefix + "Skipping codec config buffer.");
                                        codec.releaseOutputBuffer(outIndex, false);
                                        continue;
                                    }
                                    if (info.size > 0) {
                                        byte[] yuv = new byte[info.size];
                                        ob.position(info.offset);
                                        ob.limit(info.offset + info.size);
                                        ob.get(yuv);
                                        fos.write(yuv);
                                    }
                                    codec.releaseOutputBuffer(outIndex, false);
                                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                        // Log.i(TAG, logTagPrefix + "Output EOS reached.");
                                        outputDone = true;
                                        actualOutputEOSReceived = true;
                                    }
                                } else {
                                    Log.w(TAG, logTagPrefix + logDecoderName + " getOutputBuffer returned null for index " + outIndex);
                                }
                            } else {
                                // Log.w(TAG, logTagPrefix + "Unhandled dequeueOutputBuffer result: " + outIndex);
                            }
                            break;
                    }

                    if (!outputDone && (System.currentTimeMillis() - lastSuccessfulDequeueTime) > STUCK_TIMEOUT_MS) {
                        Log.e(TAG, logTagPrefix + logDecoderName + " decoder stuck. Aborting. " +
                                "InputDone: " + inputDone + ", OutputDone: " + outputDone +
                                ", Last BufferInfo Flags: " + info.flags +
                                ", Continuous TRY_AGAIN count: " + tryAgainContinuousCount);
                        lastException = new IOException(logTagPrefix + logDecoderName + " decoder stuck after " + STUCK_TIMEOUT_MS + "ms.");
                        outputDone = true; // Force exit loop
                    }
                }

                if (lastException == null && actualOutputEOSReceived) {
                    decodeSuccess = true;
                    Log.i(TAG, logTagPrefix + "Successfully decoded with " + logDecoderName);
                } else if (lastException != null) {
                    Log.w(TAG, logTagPrefix + "Attempt with " + logDecoderName + " failed or aborted.", lastException);
                } else if (!actualOutputEOSReceived) { // Loop ended, no exception, but no EOS
                    Log.w(TAG, logTagPrefix + logDecoderName + " loop ended (OutputDone=" + outputDone + ") but EOS flag not received. Last BufferInfo flags: " + info.flags);
                    if (lastException == null) {
                        lastException = new IOException(logTagPrefix + logDecoderName + " decoding loop ended without receiving EOS or a specific error.");
                    }
                }

            } catch (MediaCodec.CodecException ce) {
                Log.e(TAG, logTagPrefix + logDecoderName + " CodecException. Diag: " + ce.getDiagnosticInfo(), ce);
                lastException = new IOException(logTagPrefix + logDecoderName + " CodecException: " + ce.getMessage(), ce);
            } catch (Exception e) {
                Log.e(TAG, logTagPrefix + "Decoder failed for " + logDecoderName, e);
                lastException = e; // Keep original exception if it's not IOException
            } finally {
                if (codec != null) {
                    try { codec.stop(); } catch (IllegalStateException e) { Log.e(TAG, logTagPrefix + "Error stopping codec for " + logDecoderName, e); }
                    codec.release();
                    // codec = null; // Not strictly necessary here
                }
            }
            if (decodeSuccess) break; // Successfully decoded with current decoder

            if (currentDecoderName == initialDecoderName && decoderNamesToTry.length > 1) {
                Log.i(TAG, logTagPrefix + "Attempt with " + initialDecoderName + " failed. Trying fallback: " + decoderNamesToTry[1]);
            }
        }

        if (!decodeSuccess) {
            String msg = logTagPrefix + "All decode attempts failed.";
            if (lastException != null) {
                Log.e(TAG, msg, lastException);
                // Re-throw the last specific exception encountered
                if (lastException instanceof IOException) throw (IOException) lastException;
                throw new IOException(msg + " Last error: " + lastException.getMessage(), lastException);
            } else {
                Log.e(TAG, msg + " No specific exception recorded.");
                throw new IOException(msg + ".");
            }
        }
    }

    public void decodeUriToUri(Context context, Uri inputUri, Uri outputUri) throws IOException {
        String logPrefix = "decodeUriToUri (" + (inputUri != null ? inputUri.getLastPathSegment() : "null_input") + "): ";
        Log.i(TAG, logPrefix + "Starting decode from " + inputUri + " to " + outputUri);
        byte[] data;
        try (InputStream inputStream = context.getContentResolver().openInputStream(inputUri)) {
            if (inputStream == null) throw new IOException(logPrefix + "Unable to open input stream from URI.");
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024 * 8]; // 8KB buffer
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                byteStream.write(buffer, 0, bytesRead);
            }
            data = byteStream.toByteArray();
        }
        if (data.length == 0) throw new IOException(logPrefix + "Input data from URI is empty.");

        HEVCResolutionExtractor.SPSInfo spsInfo = HEVCResolutionExtractor.extractSPSInfo(data);
        if (spsInfo == null || spsInfo.width <= 0 || spsInfo.height <= 0) {
            throw new IOException(logPrefix + "Could not extract valid SPS info.");
        }
        // Log.i(TAG, logPrefix + "Extracted SPS Info: " + spsInfo);

        List<byte[]> nalUnits = splitAnnexB(data);
        if (nalUnits.isEmpty()) throw new IOException(logPrefix + "No NAL units found.");
        // Log.i(TAG, logPrefix + "Split into " + nalUnits.size() + " NAL units.");

        byte[] vps = null, spsByteArr = null, pps = null;
        for (byte[] nal : nalUnits) {
            if (nal.length < 4) continue; // Basic check for NAL header
            int offset = (nal[0] == 0 && nal[1] == 0 && nal[2] == 1) ? 3 :
                    (nal[0] == 0 && nal[1] == 0 && nal[2] == 0 && nal[3] == 1) ? 4 : 0;
            if (offset == 0 || nal.length <= offset) continue; // Not a valid start code or too short

            int type = (nal[offset] & 0x7E) >> 1; // Extract NAL unit type
            if (type == 32 && vps == null) vps = nal;
            else if (type == 33 && spsByteArr == null) spsByteArr = nal;
            else if (type == 34 && pps == null) pps = nal;
            if (vps != null && spsByteArr != null && pps != null) break;
        }

        if (vps == null || spsByteArr == null || pps == null) {
            String missing = (vps == null ? "VPS " : "") + (spsByteArr == null ? "SPS " : "") + (pps == null ? "PPS " : "");
            throw new IOException(logPrefix + "Missing CSD NAL units: " + missing.trim());
        }

        MediaFormat format = createMediaFormat(vps, spsByteArr, pps, spsInfo, logPrefix);
        String softwareDecoderName = findSoftwareDecoder(MIME); // Find only if needed or as fallback

        try (OutputStream fos = context.getContentResolver().openOutputStream(outputUri)) {
            if (fos == null) throw new IOException(logPrefix + "Failed to open OutputStream for output URI.");
            decodeInternal(nalUnits, format, fos, null, softwareDecoderName, logPrefix); // Try HW first by default (null initialDecoderName)
        }
        Log.i(TAG, logPrefix + "Finished decode from " + inputUri + " to " + outputUri);
    }

    public void decodeToYuv(String inputPath, String outputPath) throws IOException {
        String logPrefix = "decodeToYuv (" + (inputPath != null ? new File(inputPath).getName() : "null_input") + "): ";
        Log.i(TAG, logPrefix + "Starting decode from file " + inputPath + " to " + outputPath);
        byte[] data;
        try (FileInputStream fis = new FileInputStream(inputPath)) {
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024 * 8]; // 8KB buffer
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                byteStream.write(buffer, 0, bytesRead);
            }
            data = byteStream.toByteArray();
        }
        if (data.length == 0) throw new IOException(logPrefix + "Input file data is empty.");

        HEVCResolutionExtractor.SPSInfo spsInfo = HEVCResolutionExtractor.extractSPSInfo(data);
        if (spsInfo == null || spsInfo.width <= 0 || spsInfo.height <= 0) {
            throw new IOException(logPrefix + "Could not extract valid SPS info from file.");
        }
        // Log.i(TAG, logPrefix + "Extracted SPS Info: " + spsInfo);

        List<byte[]> nalUnits = splitAnnexB(data);
        if (nalUnits.isEmpty()) throw new IOException(logPrefix + "No NAL units found in file.");
        // Log.i(TAG, logPrefix + "Split into " + nalUnits.size() + " NAL units.");

        byte[] vps = null, spsByteArr = null, pps = null;
        for (byte[] nal : nalUnits) {
            if (nal.length < 4) continue;
            int offset = (nal[0] == 0 && nal[1] == 0 && nal[2] == 1) ? 3 :
                    (nal[0] == 0 && nal[1] == 0 && nal[2] == 0 && nal[3] == 1) ? 4 : 0;
            if (offset == 0 || nal.length <= offset) continue;

            int type = (nal[offset] & 0x7E) >> 1;
            if (type == 32 && vps == null) vps = nal;
            else if (type == 33 && spsByteArr == null) spsByteArr = nal;
            else if (type == 34 && pps == null) pps = nal;
            if (vps != null && spsByteArr != null && pps != null) break;
        }

        if (vps == null || spsByteArr == null || pps == null) {
            String missing = (vps == null ? "VPS " : "") + (spsByteArr == null ? "SPS " : "") + (pps == null ? "PPS " : "");
            throw new IOException(logPrefix + "Missing CSD NAL units from file: " + missing.trim());
        }

        MediaFormat format = createMediaFormat(vps, spsByteArr, pps, spsInfo, logPrefix);
        String softwareDecoderName = findSoftwareDecoder(MIME);

        File outputFile = new File(outputPath);
        File parentDir = outputFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                Log.w(TAG, logPrefix + "Failed to create output parent directory: " + parentDir.getAbsolutePath());
                // Continue attempt to write, FileOutputStream will throw error if dir still not creatable
            }
        }

        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            decodeInternal(nalUnits, format, fos, null, softwareDecoderName, logPrefix); // Try HW first
        }
        Log.i(TAG, logPrefix + "Finished decode from " + inputPath + " to " + outputPath);
    }

    private int getHevcNalUnitType(byte[] nalUnit) {
        if (nalUnit == null || nalUnit.length < 3) { // Minimum for a start code + NAL header byte
            return -1; // Invalid or too short
        }

        int offset = 0;
        // Find start of NAL unit payload (skip start code)
        if (nalUnit[0] == 0x00 && nalUnit[1] == 0x00) {
            if (nalUnit[2] == 0x01) { // 00 00 01
                offset = 3;
            } else if (nalUnit.length >= 4 && nalUnit[2] == 0x00 && nalUnit[3] == 0x01) { // 00 00 00 01
                offset = 4;
            } else {
                return -2; // Start code not found or malformed
            }
        } else {
            return -3; // Does not start with 00 00
        }

        if (nalUnit.length <= offset) {
            return -4; // No data after start code
        }

        // The NAL unit header is 2 bytes for HEVC.
        // forbidden_zero_bit (1 bit)
        // nal_unit_type (6 bits)
        // nuh_layer_id (6 bits)
        // nuh_temporal_id_plus1 (3 bits)
        // We need the nal_unit_type from the first byte of the NAL unit header.
        return (nalUnit[offset] & 0x7E) >> 1;
    }
}
