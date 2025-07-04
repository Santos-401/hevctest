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
import java.io.OutputStream; // Import OutputStream
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HevcDecoder {
    private static final String TAG = "HevcDecoder";
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_HEVC;
    private static final long TIMEOUT_US = 10000; // 10ms
    private static final long STUCK_TIMEOUT_MS = 10000L; // 10 seconds

    // Helper method to convert byte array to Hex String for logging
    private static String bytesToHex(byte[] bytes, int maxLength) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        int lengthToLog = Math.min(bytes.length, maxLength);
        if (lengthToLog == 0 && bytes.length > 0) { // If maxLength is 0 but bytes exist, show at least indication of non-empty
            lengthToLog = Math.min(bytes.length, 8); // Show a few bytes if maxLength was 0
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
        // Fallback for C2 components that don't correctly report isSoftwareOnly() or for older OS versions.
        try {
            MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS); // Or ALL_CODECS
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
                        Log.i(TAG, "Found potential software decoder: " + codecInfo.getName());
                        return codecInfo.getName();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error querying MediaCodecList for software decoder", e);
        }
        Log.w(TAG, "No suitable software decoder found for " + mimeType);
        return "";
    }


    private List<byte[]> splitAnnexB(byte[] data) {
        List<byte[]> nalUnits = new ArrayList<>();
        int i = 0;
        while (i < data.length) {
            int nextStartCode = -1;
            // Find next start code (00 00 01 or 00 00 00 01)
            for (int j = i + 3; j < data.length - 3; j++) { // Ensure there's room for a 3 or 4 byte start code
                if (data[j] == 0x00 && data[j + 1] == 0x00) {
                    if (data[j + 2] == 0x01) {
                        nextStartCode = j;
                        break;
                    } else if (data[j + 2] == 0x00 && j + 3 < data.length && data[j + 3] == 0x01) {
                        nextStartCode = j;
                        break;
                    }
                }
            }

            int currentUnitStart = i;
            // Skip leading start code bytes for the NAL unit content itself
            if (data[i] == 0x00 && data[i+1] == 0x00) {
                if (data[i+2] == 0x01) {
                    currentUnitStart = i + 3;
                } else if (data[i+2] == 0x00 && i + 3 < data.length && data[i+3] == 0x01) {
                    currentUnitStart = i + 4;
                }
            }


            byte[] nalUnit;
            if (nextStartCode == -1) { // Last NAL unit
                nalUnit = Arrays.copyOfRange(data, currentUnitStart, data.length);
                nalUnits.add(nalUnit);
                break;
            } else {
                nalUnit = Arrays.copyOfRange(data, currentUnitStart, nextStartCode);
                nalUnits.add(nalUnit);
                i = nextStartCode; // Move to the beginning of the next start code
            }
        }
        return nalUnits;
    }

    public void decodeUriToUri(Context context, Uri inputUri, Uri outputUri) throws IOException {
        byte[] data;
        try (InputStream inputStream = context.getContentResolver().openInputStream(inputUri)) {
            if (inputStream == null) {
                throw new IOException("Unable to open input stream from URI: " + inputUri);
            }
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024 * 4];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                byteStream.write(buffer, 0, bytesRead);
            }
            data = byteStream.toByteArray();
        }

        HEVCResolutionExtractor.SPSInfo spsInfo = HEVCResolutionExtractor.extractSPSInfo(data);
        if (spsInfo == null || spsInfo.width <= 0 || spsInfo.height <= 0) {
            throw new IOException("Could not extract valid SPS info (including resolution) from HEVC stream for URI: " + inputUri);
        }
        int actualWidth = spsInfo.width;
        int actualHeight = spsInfo.height;

        final int MAX_REASONABLE_DIMENSION = 8192;
        final long MAX_REASONABLE_PIXELS = (long)MAX_REASONABLE_DIMENSION * MAX_REASONABLE_DIMENSION;
        if (actualWidth > MAX_REASONABLE_DIMENSION || actualHeight > MAX_REASONABLE_DIMENSION ||
                (long)actualWidth * actualHeight > MAX_REASONABLE_PIXELS) {
            String resolutionMsg = "Video resolution (" + actualWidth + "x" + actualHeight + ")";
            Log.w(TAG, resolutionMsg + " exceeds reasonable limits for URI: " + inputUri + ". Decode attempt will be skipped.");
            throw new IOException(resolutionMsg + " is not supported or exceeds practical limits.");
        }

        List<byte[]> nalUnitsWithStartCodes = new ArrayList<>(); // To find VPS/SPS/PPS with start codes
        int tempOffset = 0;
        while(tempOffset < data.length) {
            int nextSC = -1;
            for (int j = tempOffset + 3; j < data.length - 3; j++) {
                if (data[j] == 0 && data[j+1] == 0 && (data[j+2] == 1 || (data[j+2] == 0 && j+3 < data.length && data[j+3] == 1))) {
                    nextSC = j;
                    break;
                }
            }
            if (nextSC == -1) {
                nalUnitsWithStartCodes.add(Arrays.copyOfRange(data, tempOffset, data.length));
                break;
            } else {
                nalUnitsWithStartCodes.add(Arrays.copyOfRange(data, tempOffset, nextSC));
                tempOffset = nextSC;
            }
        }
        Log.i(TAG, "NAL count (with start codes, for CSD extraction) from URI " + inputUri + ": " + nalUnitsWithStartCodes.size());


        byte[] vps = null, spsByteArr = null, pps = null;
        for (byte[] nal : nalUnitsWithStartCodes) { // Use original NALs with start codes for CSD
            int offset = 0;
            if (nal.length > 4 && nal[0]==0 && nal[1]==0 && nal[2]==0 && nal[3]==1) {
                offset = 4;
            } else if (nal.length > 3 && nal[0]==0 && nal[1]==0 && nal[2]==1) {
                offset = 3;
            }
            if (nal.length <= offset) continue;

            int header = nal[offset] & 0xFF;
            int type = (header & 0x7E) >> 1;
            if (type == 32) vps = nal; // VPS NAL unit (includes start code)
            else if (type == 33) spsByteArr = nal; // SPS NAL unit (includes start code)
            else if (type == 34) pps = nal; // PPS NAL unit (includes start code)
            if (vps != null && spsByteArr != null && pps != null) break;
        }

        List<byte[]> nalUnitsForDecoding = splitAnnexB(data); // NAL units without start codes for queueing
        Log.i(TAG, "NAL count (for decoding, without start codes) from URI " + inputUri + ": " + nalUnitsForDecoding.size());

        String softwareDecoderName = findSoftwareDecoder(MIME);
        List<String> decoderNamesToTry = new ArrayList<>();
        decoderNamesToTry.add(null); // Try hardware decoder first by default
        if (softwareDecoderName != null && !softwareDecoderName.isEmpty()) {
            decoderNamesToTry.add(softwareDecoderName);
        }

        MediaCodec codec = null;
        boolean decodeSuccess = false;
        Exception lastException = null;
        boolean isHwAttempt = false; // Define here to use in the final log message

        for (String currentDecoderName : decoderNamesToTry) {
            isHwAttempt = (currentDecoderName == null);
            String logDecoderName = isHwAttempt ? "Default HW" : currentDecoderName;
            Log.i(TAG, "Attempting to decode URI " + inputUri + " with " + logDecoderName + " decoder.");

            try (OutputStream fos = context.getContentResolver().openOutputStream(outputUri)) {
                if (fos == null) {
                    throw new IOException("Failed to open OutputStream for output URI: " + outputUri);
                }

                Log.d(TAG, "CSD Info for URI: " + inputUri + " (for " + logDecoderName + ")");
                if (vps != null) {
                    Log.d(TAG, "  VPS length: " + vps.length + " bytes. First 8 bytes: " + bytesToHex(vps, 8));
                } else {
                    Log.d(TAG, "  VPS is null or not found for this stream.");
                }
                if (spsByteArr != null) {
                    Log.d(TAG, "  SPS length: " + spsByteArr.length + " bytes. First 8 bytes: " + bytesToHex(spsByteArr, 8));
                } else {
                    Log.d(TAG, "  SPS is null or not found for this stream.");
                }
                if (pps != null) {
                    Log.d(TAG, "  PPS length: " + pps.length + " bytes. First 8 bytes: " + bytesToHex(pps, 8));
                } else {
                    Log.d(TAG, "  PPS is null or not found for this stream.");
                }

                if (spsInfo == null) { 
                    Log.e(TAG, "  spsInfo is unexpectedly null here before format creation!");
                    throw new IOException("SPSInfo is null, cannot configure decoder.");
                }
                 Log.d(TAG, "  SPS Extracted Info: width=" + spsInfo.width + ", height=" + spsInfo.height +
                            ", profileIdc=" + spsInfo.profileIdc + ", levelIdc=" + spsInfo.levelIdc +
                            ", chromaFormatIdc=" + spsInfo.chromaFormatIdc);
                
                MediaFormat format = MediaFormat.createVideoFormat(MIME, actualWidth, actualHeight);
                if (vps != null) format.setByteBuffer("csd-0", ByteBuffer.wrap(vps));
                if (spsByteArr != null) format.setByteBuffer("csd-1", ByteBuffer.wrap(spsByteArr));
                if (pps != null) format.setByteBuffer("csd-2", ByteBuffer.wrap(pps));

                int profileIdc = spsInfo.profileIdc;
                int androidProfile = -1;
                switch (profileIdc) {
                    case 1: androidProfile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain; break;
                    case 2: androidProfile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10; break;
                    default: Log.w(TAG, "Unmapped HEVC profile_idc: " + profileIdc + " for KEY_PROFILE"); break;
                }
                if (androidProfile != -1) {
                    format.setInteger(MediaFormat.KEY_PROFILE, androidProfile);
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
                else { Log.w(TAG, "Unmapped HEVC level_idc: " + levelIdc + " for KEY_LEVEL"); }

                if (androidLevel != -1) {
                    format.setInteger(MediaFormat.KEY_LEVEL, androidLevel);
                }
                
                Log.i(TAG, "Final MediaFormat for " + logDecoderName + ": " + format.toString());

                if (isHwAttempt) {
                    codec = MediaCodec.createDecoderByType(MIME);
                } else {
                    codec = MediaCodec.createByCodecName(currentDecoderName);
                }
                Log.i(TAG, "Configuring " + logDecoderName + " codec with format: " + format);
                codec.configure(format, null, null, 0);
                codec.start();

                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean inputDone = false; 
                boolean outputDone = false; 
                int nalUnitIndex = 0;
                long frameCountForPTS = 0;
                long lastSuccessfulDequeueTime = System.currentTimeMillis();
                boolean actualOutputEOSReceived = false;

                while (!outputDone) {
                    if (!inputDone) {
                        int inIndex = codec.dequeueInputBuffer(TIMEOUT_US);
                        if (inIndex >= 0) {
                            if (nalUnitIndex < nalUnitsForDecoding.size()) {
                                byte[] nal = nalUnitsForDecoding.get(nalUnitIndex++);
                                ByteBuffer ib = codec.getInputBuffer(inIndex);
                                if (ib != null) {
                                    ib.clear();
                                    ib.put(nal);
                                    long pts = frameCountForPTS * 1000000L / 30; 
                                    codec.queueInputBuffer(inIndex, 0, nal.length, pts, 0);
                                    frameCountForPTS++;
                                }
                            } else {
                                Log.i(TAG, "All NAL units queued for " + logDecoderName + ", sending EOS to input.");
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            }
                        }
                    }

                    int outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                    switch (outIndex) {
                        case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                            Log.i(TAG, logDecoderName + " output format changed. New format: " + codec.getOutputFormat());
                            lastSuccessfulDequeueTime = System.currentTimeMillis();
                            break;
                        case MediaCodec.INFO_TRY_AGAIN_LATER:
                            break;
                        case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                            Log.d(TAG, logDecoderName + " output buffers changed (deprecated behavior).");
                            lastSuccessfulDequeueTime = System.currentTimeMillis();
                            break;
                        default:
                            if (outIndex >= 0) {
                                lastSuccessfulDequeueTime = System.currentTimeMillis(); 
                                ByteBuffer ob = codec.getOutputBuffer(outIndex);
                                Log.d(TAG, logDecoderName + " output buffer index: " + outIndex + ", size: " + info.size + ", flags: " + info.flags + ", offset: " + info.offset + ", presentationTimeUs: " + info.presentationTimeUs);

                                if (ob != null) {
                                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                        Log.d(TAG, logDecoderName + " skipping codec config buffer (flags: " + info.flags + ").");
                                        codec.releaseOutputBuffer(outIndex, false);
                                        continue; 
                                    }

                                    if (info.size > 0) {
                                        byte[] yuv = new byte[info.size];
                                        ob.position(info.offset);
                                        ob.limit(info.offset + info.size);
                                        ob.get(yuv);

                                        Log.d(TAG, logDecoderName + " attempting to write " + yuv.length + " bytes to OutputStream.");
                                        fos.write(yuv);
                                        Log.d(TAG, logDecoderName + " successfully wrote " + yuv.length + " bytes to OutputStream.");
                                    }
                                    
                                    codec.releaseOutputBuffer(outIndex, false); 

                                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                        Log.i(TAG, logDecoderName + " output EOS reached.");
                                        outputDone = true;
                                        actualOutputEOSReceived = true;
                                    }
                                } else {
                                    Log.w(TAG, logDecoderName + " codec.getOutputBuffer(" + outIndex + ") returned null for index " + outIndex);
                                }
                            }
                            break;
                    }

                    if (!outputDone && (System.currentTimeMillis() - lastSuccessfulDequeueTime) > STUCK_TIMEOUT_MS) {
                        Log.e(TAG, logDecoderName + " decoder seems to be stuck. Aborting after " + STUCK_TIMEOUT_MS + "ms");
                        lastException = new IOException(logDecoderName + " decoder stuck after " + STUCK_TIMEOUT_MS + "ms without output.");
                        outputDone = true; 
                    }
                } 

                if (lastException == null && actualOutputEOSReceived ) { 
                    decodeSuccess = true;
                    Log.i(TAG, "Successfully decoded URI " + inputUri + " with " + logDecoderName);
                } else if (lastException != null) {
                     Log.w(TAG, "Attempt with " + logDecoderName + " failed or was aborted.", lastException);
                } else if (outputDone && !actualOutputEOSReceived) {
                    Log.w(TAG, "Loop for " + logDecoderName + " ended (likely due to stuck detection) but EOS flag not set. OutputDone: " + outputDone);
                }


            } catch (Exception e) {
                Log.w(TAG, "Decoder configuration/operation failed for " + logDecoderName + " decoder. URI: " + inputUri, e);
                lastException = e; 
            } finally {
                if (codec != null) {
                    try {
                        codec.stop();
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "Error stopping codec for " + logDecoderName, e);
                    }
                    codec.release();
                    codec = null;
                }
            }

            if (decodeSuccess) { 
                break;
            }
        } 

        if (!decodeSuccess) {
            String msg = "Decoding failed for URI: " + inputUri;
            if (lastException != null) {
                // isHwAttempt here will reflect the last attempted decoder type
                Log.e(TAG, msg + ". Last error from " + (isHwAttempt ? "hardware" : "software") + " attempt: " + lastException.getMessage(), lastException);
                throw new IOException(msg + ". Last error: " + lastException.getMessage(), lastException);
            } else {
                Log.e(TAG, msg + " with no specific exception recorded (possibly all attempts failed cleanly or EOS not reached).");
                throw new IOException(msg + ".");
            }
        }
    }


    // Original method for path-based decoding - keep for now if needed, or remove if fully migrated to URI
    public void decodeToYuv(String inputPath, String outputPath) throws IOException {
        byte[] data;
        try (FileInputStream fis = new FileInputStream(inputPath)) {
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024*4];
            int bytesRead;
            while((bytesRead = fis.read(buffer)) != -1) {
                byteStream.write(buffer, 0, bytesRead);
            }
            data = byteStream.toByteArray();
        }

        HEVCResolutionExtractor.SPSInfo spsInfo = HEVCResolutionExtractor.extractSPSInfo(data);
        if (spsInfo == null || spsInfo.width <= 0 || spsInfo.height <= 0) {
            throw new IOException("Could not extract valid SPS info (including resolution) from HEVC file.");
        }
        int actualWidth = spsInfo.width;
        int actualHeight = spsInfo.height;

        Log.w(TAG, "decodeToYuv(String, String) is using a simplified decoder setup. Consider aligning with decodeUriToUri for full features.");

        List<byte[]> nalUnitsWithStartCodes = new ArrayList<>();
        int tempOffset = 0;
        while(tempOffset < data.length) {
            int nextSC = -1;
            for (int j = tempOffset + 3; j < data.length - 3; j++) {
                if (data[j] == 0 && data[j+1] == 0 && (data[j+2] == 1 || (data[j+2] == 0 && j+3 < data.length && data[j+3] == 1))) {
                    nextSC = j;
                    break;
                }
            }
            if (nextSC == -1) {
                nalUnitsWithStartCodes.add(Arrays.copyOfRange(data, tempOffset, data.length));
                break;
            } else {
                nalUnitsWithStartCodes.add(Arrays.copyOfRange(data, tempOffset, nextSC));
                tempOffset = nextSC;
            }
        }

        byte[] vps = null, spsByteArr = null, pps = null;
        for (byte[] nal : nalUnitsWithStartCodes) {
            int offset = 0;
            if (nal.length > 4 && nal[0]==0 && nal[1]==0 && nal[2]==0 && nal[3]==1) offset = 4;
            else if (nal.length > 3 && nal[0]==0 && nal[1]==0 && nal[2]==1) offset = 3;
            if (nal.length <= offset) continue;
            int type = (nal[offset] & 0x7E) >> 1;
            if (type == 32) vps = nal; else if (type == 33) spsByteArr = nal; else if (type == 34) pps = nal;
            if (vps != null && spsByteArr != null && pps != null) break;
        }
        if (vps == null || spsByteArr == null || pps == null) {
            throw new IOException("Missing VPS/SPS/PPS in bitstream: " + inputPath);
        }

        List<byte[]> nalUnitsForDecoding = splitAnnexB(data); 

        File outputFile = new File(outputPath);
         File parentDir = outputFile.getParentFile();
         if (parentDir != null && !parentDir.exists()) {
             if (!parentDir.mkdirs()) {
                 Log.w(TAG, "Failed to create output parent directory for path-based decode: " + parentDir.getAbsolutePath());
             }
         }


        MediaCodec codec = null;
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            MediaFormat format = MediaFormat.createVideoFormat(MIME, actualWidth, actualHeight);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(vps));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(spsByteArr));
            format.setByteBuffer("csd-2", ByteBuffer.wrap(pps));

            if (spsInfo.profileIdc == 1) format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain);
            // Example: if (spsInfo.levelIdc == 93) format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31); 
            // Proper level mapping should be here if this method is to be robust


            Log.i(TAG, "Configuring codec for path-based decode with format: " + format);
            codec = MediaCodec.createDecoderByType(MIME); 
            codec.configure(format, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false; 
            boolean outputDone = false; 
            int nalUnitIndex = 0;
            long frameCountForPTS = 0;
            // No STUCK_TIMEOUT in this simplified legacy method

            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (inIndex >= 0) {
                        if (nalUnitIndex < nalUnitsForDecoding.size()) {
                            byte[] nal = nalUnitsForDecoding.get(nalUnitIndex++);
                            ByteBuffer ib = codec.getInputBuffer(inIndex);
                            if (ib != null) {
                                ib.clear();
                                ib.put(nal);
                                long pts = frameCountForPTS * 1000000L / 30; 
                                codec.queueInputBuffer(inIndex, 0, nal.length, pts, 0);
                                frameCountForPTS++;
                            }
                        } else {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        }
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                switch (outIndex) {
                    case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED: 
                        Log.i(TAG, "Path decode: format changed: " + codec.getOutputFormat()); 
                        break;
                    case MediaCodec.INFO_TRY_AGAIN_LATER: 
                        break; 
                    default:
                        if (outIndex >= 0) {
                            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                Log.d(TAG, "Path decode: skipping CSD");
                                codec.releaseOutputBuffer(outIndex, false);
                                continue; 
                            }
                            
                            ByteBuffer ob = codec.getOutputBuffer(outIndex);
                            if (ob != null && info.size > 0) {
                                byte[] yuvData = new byte[info.size];
                                ob.position(info.offset); 
                                ob.limit(info.offset + info.size); 
                                ob.get(yuvData);
                                fos.write(yuvData);
                            }
                            codec.releaseOutputBuffer(outIndex, false);
                            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true;
                            }
                        }
                        break;
                }
            } 
            Log.i(TAG, "Path-based decoding finished for: " + inputPath);

        } catch (Exception e) {
            Log.e(TAG, "Error during path-based decoding of " + inputPath, e);
            throw new IOException("Failed to decode " + inputPath, e);
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception e) {/*ignore*/}
                codec.release();
            }
        }
    }
}
