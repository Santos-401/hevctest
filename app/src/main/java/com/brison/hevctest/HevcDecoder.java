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
    private static final long TIMEOUT_US = 10000; // 10ms
    private static final long STUCK_TIMEOUT_MS = 10000L; // 10 seconds for stuck detection
    private static final long TRY_AGAIN_LOG_INTERVAL_MS = 2000; // Log INFO_TRY_AGAIN every 2 seconds


    private static String bytesToHex(byte[] bytes, int maxLength) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        int lengthToLog = Math.min(bytes.length, maxLength);
        if (lengthToLog == 0 && bytes.length > 0) {
            lengthToLog = Math.min(bytes.length, 8);
        }
        for (int i = 0; i < lengthToLog; i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        if (bytes.length > lengthToLog) {
            sb.append("...");
        } else if (bytes.length == 0) {
            sb.append("(empty)");
        }
        return sb.toString().trim();
    }

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
            for (int j = i + 1; j <= data.length - 3; j++) {
                if (data[j] == 0x00 && data[j + 1] == 0x00) {
                    if (data[j + 2] == 0x01) {
                        nextStartCode = j;
                        break;
                    } else if (j + 3 < data.length && data[j + 2] == 0x00 && data[j + 3] == 0x01) {
                        nextStartCode = j;
                        break;
                    }
                }
            }

            byte[] nalUnit;
            if (nextStartCode == -1) {
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
        Log.d(TAG, logTagPrefix + "Creating MediaFormat for " + spsInfo.width + "x" + spsInfo.height);

        if (vps != null) format.setByteBuffer("csd-0", ByteBuffer.wrap(vps));
        if (spsByteArr != null) format.setByteBuffer("csd-1", ByteBuffer.wrap(spsByteArr));
        if (pps != null) format.setByteBuffer("csd-2", ByteBuffer.wrap(pps));

        int profileIdc = spsInfo.profileIdc;
        int androidProfile = -1;
        switch (profileIdc) {
            case 1: androidProfile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain; break;
            case 2: androidProfile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10; break;
            default: Log.w(TAG, logTagPrefix + "Unmapped HEVC profile_idc: " + profileIdc); break;
        }
        if (androidProfile != -1) {
            format.setInteger(MediaFormat.KEY_PROFILE, androidProfile);
            Log.d(TAG, logTagPrefix + "Set KEY_PROFILE to: " + androidProfile + " (from profileIdc: " + profileIdc + ")");
        } else {
            Log.w(TAG, logTagPrefix + "KEY_PROFILE will not be set for profile_idc " + profileIdc);
        }

        int levelIdc = spsInfo.levelIdc;
        int androidLevel = -1;
        if (levelIdc <= 30) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel1;
        else if (levelIdc <= 60 && levelIdc % 3 == 0) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel2;
        else if (levelIdc <= 63 && levelIdc % 3 == 0) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel21;
        else if (levelIdc <= 90 && levelIdc % 3 == 0) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel3;
        else if (levelIdc <= 93 && levelIdc % 3 == 0) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31;
        else if (levelIdc <= 120 && levelIdc % 3 == 0) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4;
        else if (levelIdc <= 123 && levelIdc % 3 == 0) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41;
        else if (levelIdc <= 150 && levelIdc % 3 == 0) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5;
        else if (levelIdc <= 153 && levelIdc % 3 == 0) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51;
        else if (levelIdc <= 156 && levelIdc % 3 == 0) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel52;
        else if (levelIdc <= 180 && levelIdc % 3 == 0) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel6;
        else if (levelIdc <= 183 && levelIdc % 3 == 0) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel61;
        else if (levelIdc <= 186 && levelIdc % 3 == 0) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel62;
        else { Log.w(TAG, logTagPrefix + "Unmapped HEVC level_idc: " + levelIdc + " to a MediaCodecInfo.CodecProfileLevel constant."); }

        if (androidLevel != -1) {
            format.setInteger(MediaFormat.KEY_LEVEL, androidLevel);
            Log.d(TAG, logTagPrefix + "Set KEY_LEVEL to: " + androidLevel + " (from levelIdc: " + levelIdc + ")");
        } else {
            Log.w(TAG, logTagPrefix + "KEY_LEVEL will not be set for level_idc " + levelIdc);
        }
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        Log.i(TAG, logTagPrefix + "Final MediaFormat: " + format.toString());
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
            boolean isHwAttempt = (currentDecoderName == null || currentDecoderName.isEmpty() || !currentDecoderName.toLowerCase().contains("software"));
            String logDecoderName = (currentDecoderName == null || currentDecoderName.isEmpty()) ? "Default HW" : currentDecoderName;

            Log.i(TAG, logTagPrefix + "Attempting to decode with " + logDecoderName + " decoder.");

            // Variables for INFO_TRY_AGAIN_LATER logging
            long lastTryAgainLogTime = System.currentTimeMillis();
            int tryAgainContinuousCount = 0;

            try {
                codec = (currentDecoderName == null || currentDecoderName.isEmpty()) ?
                        MediaCodec.createDecoderByType(MIME) :
                        MediaCodec.createByCodecName(currentDecoderName);

                MediaCodecInfo codecInfo = codec.getCodecInfo();
                Log.i(TAG, logTagPrefix + logDecoderName + " selected: " + codecInfo.getName());
                String[] supportedTypes = codecInfo.getSupportedTypes();
                boolean mimeSupportedByInstance = false;
                for(String type : supportedTypes) {
                    if (type.equalsIgnoreCase(MIME)) {
                        mimeSupportedByInstance = true;
                        break;
                    }
                }
                Log.d(TAG, logTagPrefix + logDecoderName + " (" + codecInfo.getName() + ") supports MIME types: " + Arrays.toString(supportedTypes) + ". Target MIME " + MIME + " directly supported by instance: " + mimeSupportedByInstance);
                MediaCodecInfo.CodecCapabilities caps = null;
                try {
                    caps = codecInfo.getCapabilitiesForType(MIME);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, logTagPrefix + logDecoderName + " (" + codecInfo.getName() + ") threw IllegalArgumentException when getting capabilities for MIME type " + MIME, e);
                }
                if (caps != null) {
                    int configuredProfile = format.getInteger(MediaFormat.KEY_PROFILE, -1);
                    int configuredLevel = format.getInteger(MediaFormat.KEY_LEVEL, -1);
                    Log.d(TAG, logTagPrefix + logDecoderName + " (" + codecInfo.getName() + ") Reported HEVC Capabilities: " + Arrays.toString(caps.profileLevels));
                    boolean profileSupported = false;
                    boolean levelSupportedEnough = false;
                    for (MediaCodecInfo.CodecProfileLevel capProfileLevel : caps.profileLevels) {
                        if (capProfileLevel.profile == configuredProfile) {
                            profileSupported = true;
                            if (capProfileLevel.level >= configuredLevel) {
                                levelSupportedEnough = true;
                                break;
                            }
                        }
                    }
                    Log.i(TAG, logTagPrefix + logDecoderName + " (" + codecInfo.getName() + ") - Configured Profile: " + configuredProfile + ", Configured Level: " + configuredLevel);
                    Log.i(TAG, logTagPrefix + logDecoderName + " (" + codecInfo.getName() + ") - Reported Profile Support by Capabilities: " + profileSupported);
                    Log.i(TAG, logTagPrefix + logDecoderName + " (" + codecInfo.getName() + ") - Reported Level Sufficient by Capabilities: " + levelSupportedEnough);
                    if (!profileSupported) {
                        Log.w(TAG, logTagPrefix + logDecoderName + " (" + codecInfo.getName() + "): Configured profile " + configuredProfile + " (expecting Main10=" + MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 + ") is NOT listed in its capabilities for HEVC.");
                    }
                    if (profileSupported && !levelSupportedEnough) {
                        Log.w(TAG, logTagPrefix + logDecoderName + " (" + codecInfo.getName() + "): Configured profile " + configuredProfile + " is supported, but configured level " + configuredLevel + " might be HIGHER than any reported level in capabilities for this profile.");
                    }
                    if (profileSupported && levelSupportedEnough) {
                        Log.i(TAG, logTagPrefix + logDecoderName + " (" + codecInfo.getName() + "): Capabilities seem to support the configured HEVC profile (" + configuredProfile + ") and level (" + configuredLevel + ").");
                    }
                } else {
                    Log.w(TAG, logTagPrefix + logDecoderName + " (" + codecInfo.getName() + "): Could not get CodecCapabilities for HEVC MIME type (" + MIME + "). This is unexpected if the codec was selected for this type.");
                }

                Log.i(TAG, logTagPrefix + "Configuring " + logDecoderName + " codec with format: " + format);
                codec.configure(format, null, null, 0);
                codec.start();

                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean inputDone = false;
                boolean outputDone = false;
                boolean actualOutputEOSReceived = false;
                int nalUnitIndex = 0;
                long frameCountForPTS = 0;
                long lastSuccessfulDequeueTime = System.currentTimeMillis(); // Used for stuck detection and TRY_AGAIN logging

                while (!outputDone) {
                    if (Thread.interrupted()) {
                        Log.w(TAG, logTagPrefix + logDecoderName + " decode thread interrupted. Aborting.");
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
                                    long pts = frameCountForPTS * 1000000L / 30;
                                    codec.queueInputBuffer(inIndex, 0, nal.length, pts, 0);
                                    frameCountForPTS++;
                                } else { Log.e(TAG, logTagPrefix + logDecoderName + " getInputBuffer returned null for index " + inIndex); }
                            } else {
                                Log.i(TAG, logTagPrefix + "All NAL units ("+nalUnits.size()+") queued for " + logDecoderName + ", sending EOS to input.");
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            }
                            lastSuccessfulDequeueTime = System.currentTimeMillis(); // Reset stuck timer on successful input queue
                            tryAgainContinuousCount = 0; // Reset tryAgain counter on successful input queue
                        }
                    }

                    int outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                    switch (outIndex) {
                        case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                            Log.i(TAG, logTagPrefix + logDecoderName + " output format changed. New format: " + codec.getOutputFormat());
                            lastSuccessfulDequeueTime = System.currentTimeMillis();
                            tryAgainContinuousCount = 0; // Reset counter
                            break;
                        case MediaCodec.INFO_TRY_AGAIN_LATER:
                            tryAgainContinuousCount++;
                            long currentTime = System.currentTimeMillis();
                            if (tryAgainContinuousCount <= 3 || (currentTime - lastTryAgainLogTime) >= TRY_AGAIN_LOG_INTERVAL_MS) {
                                Log.d(TAG, logTagPrefix + logDecoderName +
                                        " INFO_TRY_AGAIN_LATER. Continuous count: " + tryAgainContinuousCount +
                                        ". Time since last successful dequeue/input: " + (currentTime - lastSuccessfulDequeueTime) + "ms. " +
                                        "InputDone: " + inputDone);
                                lastTryAgainLogTime = currentTime;
                            }
                            break;
                        case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                            Log.d(TAG, logTagPrefix + logDecoderName + " output buffers changed (deprecated behavior).");
                            lastSuccessfulDequeueTime = System.currentTimeMillis();
                            tryAgainContinuousCount = 0; // Reset counter
                            break;
                        default:
                            if (outIndex >= 0) {
                                lastSuccessfulDequeueTime = System.currentTimeMillis();
                                tryAgainContinuousCount = 0; // Reset counter

                                ByteBuffer ob = codec.getOutputBuffer(outIndex);
                                if (ob != null) {
                                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                        Log.d(TAG, logTagPrefix + logDecoderName + " skipping codec config buffer.");
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
                                        Log.i(TAG, logTagPrefix + logDecoderName + " output EOS reached.");
                                        outputDone = true;
                                        actualOutputEOSReceived = true;
                                    }
                                } else { Log.w(TAG, logTagPrefix + logDecoderName + " getOutputBuffer returned null for index " + outIndex); }
                            } else { Log.w(TAG, logTagPrefix + logDecoderName + " dequeueOutputBuffer returned unhandled " + outIndex); }
                            break;
                    }
                    if (!outputDone && (System.currentTimeMillis() - lastSuccessfulDequeueTime) > STUCK_TIMEOUT_MS) {
                        Log.e(TAG, logTagPrefix + logDecoderName +
                                " decoder seems to be stuck. Aborting after " + STUCK_TIMEOUT_MS + "ms. " +
                                "InputDone: " + inputDone + ", OutputDone: " + outputDone +
                                ", Last BufferInfo Flags (may be stale): " + info.flags +
                                ", Continuous INFO_TRY_AGAIN_LATER count at timeout: " + tryAgainContinuousCount);
                        lastException = new IOException(logTagPrefix + logDecoderName + " decoder stuck after " + STUCK_TIMEOUT_MS + "ms.");
                        outputDone = true;
                    }
                }

                if (lastException == null && actualOutputEOSReceived) {
                    decodeSuccess = true;
                    Log.i(TAG, logTagPrefix + "Successfully decoded with " + logDecoderName);
                } else if (lastException != null) {
                    Log.w(TAG, logTagPrefix + "Attempt with " + logDecoderName + " failed or was aborted.", lastException);
                } else if (!actualOutputEOSReceived) {
                    Log.w(TAG, logTagPrefix + logDecoderName + " loop ended but actual EOS flag was not received. OutputDone: "+outputDone + ", Last BufferInfo flags: " + info.flags);
                    if(lastException == null) { // Should ideally be covered by the stuck detection exception
                        lastException = new IOException(logTagPrefix + logDecoderName + " decoding loop ended without receiving EOS or a specific error.");
                    }
                }

            } catch (MediaCodec.CodecException ce) {
                Log.e(TAG, logTagPrefix + logDecoderName + " CodecException. Diag: " + ce.getDiagnosticInfo(), ce);
                lastException = new IOException(logTagPrefix + logDecoderName + " CodecException: " + ce.getMessage(), ce);
            } catch (Exception e) {
                Log.e(TAG, logTagPrefix + "Decoder failed for " + logDecoderName, e);
                lastException = e;
            } finally {
                if (codec != null) {
                    try { codec.stop(); } catch (IllegalStateException e) { Log.e(TAG, logTagPrefix + "Error stopping codec for " + logDecoderName, e); }
                    codec.release();
                    codec = null;
                }
            }
            if (decodeSuccess) break;
            if (currentDecoderName == initialDecoderName && decoderNamesToTry.length > 1) {
                Log.i(TAG, logTagPrefix + "Attempt with " + logDecoderName + " failed. Trying fallback: " + decoderNamesToTry[1]);
            }
        }

        if (!decodeSuccess) {
            String msg = logTagPrefix + "All decode attempts failed.";
            if (lastException != null) {
                Log.e(TAG, msg, lastException);
                throw new IOException(msg + " Last error: " + lastException.getMessage(), lastException);
            } else {
                Log.e(TAG, msg + " No specific exception recorded.");
                throw new IOException(msg + ".");
            }
        }
    }


    public void decodeUriToUri(Context context, Uri inputUri, Uri outputUri) throws IOException {
        String logPrefix = "decodeUriToUri (" + inputUri.getLastPathSegment() + "): ";
        Log.i(TAG, logPrefix + "Starting decode from " + inputUri + " to " + outputUri);
        byte[] data;
        try (InputStream inputStream = context.getContentResolver().openInputStream(inputUri)) {
            if (inputStream == null) throw new IOException(logPrefix + "Unable to open input stream from URI.");
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024 * 8];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) byteStream.write(buffer, 0, bytesRead);
            data = byteStream.toByteArray();
        }
        if (data.length == 0) throw new IOException(logPrefix + "Input data from URI is empty.");

        HEVCResolutionExtractor.SPSInfo spsInfo = HEVCResolutionExtractor.extractSPSInfo(data);
        if (spsInfo == null || spsInfo.width <= 0 || spsInfo.height <= 0) {
            throw new IOException(logPrefix + "Could not extract valid SPS info.");
        }
        Log.i(TAG, logPrefix + "Extracted SPS Info: " + spsInfo);

        List<byte[]> nalUnits = splitAnnexB(data);
        if (nalUnits.isEmpty()) throw new IOException(logPrefix + "No NAL units found.");
        Log.i(TAG, logPrefix + "Split into " + nalUnits.size() + " NAL units (with start codes).");

        byte[] vps = null, spsByteArr = null, pps = null;
        for (byte[] nal : nalUnits) {
            if (nal.length < 4) continue;
            int offset = (nal[2] == 1) ? 3 : ((nal[2] == 0 && nal[3] == 1) ? 4 : 0);
            if (offset == 0 || nal.length <= offset) continue;
            int type = (nal[offset] & 0x7E) >> 1;
            if (type == 32 && vps == null) { vps = nal; Log.d(TAG, logPrefix + "Found VPS, length " + nal.length); }
            else if (type == 33 && spsByteArr == null) { spsByteArr = nal; Log.d(TAG, logPrefix + "Found SPS, length " + nal.length); }
            else if (type == 34 && pps == null) { pps = nal; Log.d(TAG, logPrefix + "Found PPS, length " + nal.length); }
            if (vps != null && spsByteArr != null && pps != null) break;
        }
        if (vps == null || spsByteArr == null || pps == null) {
            String missing = (vps == null ? "VPS " : "") + (spsByteArr == null ? "SPS " : "") + (pps == null ? "PPS " : "");
            throw new IOException(logPrefix + "Missing CSD NAL units: " + missing.trim());
        }

        MediaFormat format = createMediaFormat(vps, spsByteArr, pps, spsInfo, logPrefix);
        String softwareDecoderName = findSoftwareDecoder(MIME);

        try (OutputStream fos = context.getContentResolver().openOutputStream(outputUri)) {
            if (fos == null) throw new IOException(logPrefix + "Failed to open OutputStream for output URI.");
            decodeInternal(nalUnits, format, fos, null, softwareDecoderName, logPrefix);
        }
    }

    public void decodeToYuv(String inputPath, String outputPath) throws IOException {
        String logPrefix = "decodeToYuv (" + new File(inputPath).getName() + "): ";
        Log.i(TAG, logPrefix + "Starting decode from file " + inputPath + " to " + outputPath);
        byte[] data;
        try (FileInputStream fis = new FileInputStream(inputPath)) {
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024 * 8];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) byteStream.write(buffer, 0, bytesRead);
            data = byteStream.toByteArray();
        }
        if (data.length == 0) throw new IOException(logPrefix + "Input file data is empty.");

        HEVCResolutionExtractor.SPSInfo spsInfo = HEVCResolutionExtractor.extractSPSInfo(data);
        if (spsInfo == null || spsInfo.width <= 0 || spsInfo.height <= 0) {
            throw new IOException(logPrefix + "Could not extract valid SPS info from file.");
        }
        Log.i(TAG, logPrefix + "Extracted SPS Info: " + spsInfo);

        List<byte[]> nalUnits = splitAnnexB(data);
        if (nalUnits.isEmpty()) throw new IOException(logPrefix + "No NAL units found in file.");
        Log.i(TAG, logPrefix + "Split into " + nalUnits.size() + " NAL units (with start codes).");

        byte[] vps = null, spsByteArr = null, pps = null;
        for (byte[] nal : nalUnits) {
            if (nal.length < 4) continue;
            int offset = (nal[2] == 1) ? 3 : ((nal[2] == 0 && nal[3] == 1) ? 4 : 0);
            if (offset == 0 || nal.length <= offset) continue;
            int type = (nal[offset] & 0x7E) >> 1;
            if (type == 32 && vps == null) { vps = nal; Log.d(TAG, logPrefix + "Found VPS, length " + nal.length); }
            else if (type == 33 && spsByteArr == null) { spsByteArr = nal; Log.d(TAG, logPrefix + "Found SPS, length " + nal.length); }
            else if (type == 34 && pps == null) { pps = nal; Log.d(TAG, logPrefix + "Found PPS, length " + nal.length); }
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
            if (!parentDir.mkdirs()) Log.w(TAG, logPrefix + "Failed to create output parent directory: " + parentDir.getAbsolutePath());
        }

        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            decodeInternal(nalUnits, format, fos, null, softwareDecoderName, logPrefix);
        }
    }
}