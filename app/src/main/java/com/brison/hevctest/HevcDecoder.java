package com.brison.hevctest;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class HevcDecoder {
    private Context context;
    private static final String TAG = "HevcDecoder";
    private static final String MIME = "video/hevc";
    private static final long TIMEOUT_US = 10000;

    public HevcDecoder() {
        // this.context = context;
    }

    public void decodeToYuv(String inputPath, String outputPath) throws IOException {
        byte[] data;
        try (FileInputStream fis = new FileInputStream(inputPath)) {
            data = new byte[fis.available()];
            fis.read(data);
        }

        HEVCResolutionExtractor.SPSInfo spsInfo = HEVCResolutionExtractor.extractSPSInfo(data);
        if (spsInfo == null || spsInfo.width <= 0 || spsInfo.height <= 0) {
            throw new IOException("Could not extract valid SPS info (including resolution) from HEVC file.");
        }
        int actualWidth = spsInfo.width;
        int actualHeight = spsInfo.height;

        List<byte[]> nalUnits = splitAnnexB(data);
        Log.i(TAG, "NAL count: " + nalUnits.size());

        byte[] vps = null, spsByteArr = null, pps = null;
        for (byte[] nal : nalUnits) {
            int offset = 0;
            if (nal.length > 4 && nal[0]==0 && nal[1]==0 && nal[2]==0 && nal[3]==1) {
                offset = 4;
            } else if (nal.length > 3 && nal[0]==0 && nal[1]==0 && nal[2]==1) {
                offset = 3;
            }
            if (nal.length <= offset) {
                Log.w(TAG, "Skipping NAL unit with insufficient length after start code.");
                continue;
            }
            int header = nal[offset] & 0xFF;
            int type = (header & 0x7E) >> 1;
            if (type == 32) vps = nal;
            else if (type == 33) spsByteArr = nal;
            else if (type == 34) pps = nal;
            if (vps != null && spsByteArr != null && pps != null) break;
        }
        if (vps == null || spsByteArr == null || pps == null) {
            throw new IOException("Missing VPS/SPS/PPS in bitstream");
        }

        File outputFile = new File(outputPath);
        File parentDir = outputFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                Log.w(TAG, "Failed to create output parent directory: " + parentDir.getAbsolutePath());
                // Depending on strictness, could throw IOException here
            }
        }

        MediaCodec codec = null;
        try (FileOutputStream fos = new FileOutputStream(outputFile)) { // Use outputFile directly
            MediaFormat format = MediaFormat.createVideoFormat(MIME, actualWidth, actualHeight);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(vps));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(spsByteArr));
            format.setByteBuffer("csd-2", ByteBuffer.wrap(pps));

            int profileIdc = spsInfo.profileIdc;
            int androidProfile = -1;
            switch (profileIdc) {
                case 1: androidProfile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain; break;
                case 2: androidProfile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10; break;
                default: Log.w(TAG, "Unmapped HEVC profile_idc: " + profileIdc + " for KEY_PROFILE"); break;
            }
            if (androidProfile != -1) {
                format.setInteger(MediaFormat.KEY_PROFILE, androidProfile);
                Log.i(TAG, "Set KEY_PROFILE to " + androidProfile + " (from profile_idc " + profileIdc + ")");
            }

            int levelIdc = spsInfo.levelIdc;
            boolean tierFlag = spsInfo.tierFlag; // Get the tier flag
            int androidLevel = -1;

            if (tierFlag) { // High Tier
                Log.i(TAG, "Attempting to set High Tier level for level_idc: " + levelIdc);
                if (levelIdc <= 30) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel1;
                else if (levelIdc <= 60) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel2;
                else if (levelIdc <= 63) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel21;
                else if (levelIdc <= 90) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel3;
                else if (levelIdc <= 93) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel31;
                else if (levelIdc <= 120) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel4;
                else if (levelIdc <= 123) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel41;
                else if (levelIdc <= 150) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel5;
                else if (levelIdc <= 153) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51;
                else if (levelIdc <= 156) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel52;
                else if (levelIdc <= 180) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel6;
                else if (levelIdc <= 183) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel61;
                else if (levelIdc <= 186) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel62;
                else { Log.w(TAG, "Unmapped HEVC High Tier level_idc: " + levelIdc + " for KEY_LEVEL"); }
            } else { // Main Tier
                Log.i(TAG, "Attempting to set Main Tier level for level_idc: " + levelIdc);
                if (levelIdc <= 30) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel1;
                else if (levelIdc <= 60) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel2;
                else if (levelIdc <= 63) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel21;
                else if (levelIdc <= 90) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel3;
                else if (levelIdc <= 93) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31;
                else if (levelIdc <= 120) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4;
                else if (levelIdc <= 123) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41;
                else if (levelIdc <= 150) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5;
                else if (levelIdc <= 153) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51;
                else if (levelIdc <= 156) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel52;
                else if (levelIdc <= 180) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel6;
                else if (levelIdc <= 183) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel61;
                else if (levelIdc <= 186) androidLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel62;
                else { Log.w(TAG, "Unmapped HEVC Main Tier level_idc: " + levelIdc + " for KEY_LEVEL"); }
            }

            if (androidLevel != -1) {
                format.setInteger(MediaFormat.KEY_LEVEL, androidLevel);
                Log.i(TAG, "Set KEY_LEVEL to " + androidLevel + " (from level_idc " + levelIdc + ", tier: " + (tierFlag ? "High" : "Main") + ")");
            } else {
                Log.w(TAG, "No Android KEY_LEVEL mapping found for HEVC level_idc: " + levelIdc + " and tier: " + (tierFlag ? "High" : "Main"));
            }

            Log.i(TAG, "Final MediaFormat configuration: CSDs, Width, Height, Mapped Profile & Level. SPS ChromaFormatIDC: " + spsInfo.chromaFormatIdc + ", TierFlag: " + spsInfo.tierFlag);
            Log.d(TAG, "MediaFormat: " + format.toString());


            try {
                codec = MediaCodec.createDecoderByType(MIME);
                Log.i(TAG, "Configuring codec with format: " + format);
                codec.configure(format, null, null, 0);
                codec.start();

                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean inputEOS = false;
                boolean outputEOS = false;
                int nalUnitIndex = 0;
                long frameCountForPTS = 0;

                while (!outputEOS) {
                    if (!inputEOS) {
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
                                } else {
                                    Log.e(TAG, "getInputBuffer returned null for index " + inIndex);
                                }
                            } else {
                                Log.i(TAG, "All NAL units queued, sending EOS to input.");
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputEOS = true;
                            }
                        } else {
                            Log.d(TAG, "Input buffer not available or timed out during NAL unit processing.");
                        }
                    }

                    int outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                    switch (outIndex) {
                        case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                            Log.i(TAG, "Output format changed. New format: " + codec.getOutputFormat());
                            break;
                        case MediaCodec.INFO_TRY_AGAIN_LATER:
                            Log.d(TAG, "dequeueOutputBuffer timed out (INFO_TRY_AGAIN_LATER).");
                            break;
                        case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                            Log.d(TAG, "Output buffers changed (deprecated behavior).");
                            break;
                        default:
                            if (outIndex < 0) {
                                Log.w(TAG, "dequeueOutputBuffer returned unexpected negative index: " + outIndex);
                                break;
                            }
                            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                Log.d(TAG, "Skipping codec config buffer.");
                                codec.releaseOutputBuffer(outIndex, false);
                                break;
                            }
                            ByteBuffer ob = codec.getOutputBuffer(outIndex);
                            if (ob != null && info.size > 0) {
                                byte[] yuv = new byte[info.size];
                                ob.get(yuv);
                                fos.write(yuv);
                                Log.d(TAG, "Wrote " + info.size + " bytes of YUV data, pts: " + info.presentationTimeUs);
                            } else if (ob == null && info.size > 0) { // Check if ob is null but size > 0
                                Log.w(TAG, "Output buffer was null for index " + outIndex + " but info.size was " + info.size);
                            }
                            codec.releaseOutputBuffer(outIndex, false);
                            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                Log.i(TAG, "Output EOS reached.");
                                outputEOS = true;
                            }
                            break;
                    }
                }
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "MediaCodec IllegalArgumentException: " + e.getMessage(), e);
                throw new IOException("MediaCodec configuration/operation failed (IllegalArgumentException): " + e.getMessage(), e);
            } catch (MediaCodec.CodecException e) {
                Log.e(TAG, "MediaCodec CodecException: " + e.getMessage() + ", DiagnosticInfo: " + e.getDiagnosticInfo(), e);
                throw new IOException("MediaCodec operation failed (CodecException): " + e.getMessage(), e);
            } catch (IllegalStateException e) {
                Log.e(TAG, "MediaCodec IllegalStateException: " + e.getMessage(), e);
                throw new IOException("MediaCodec operation failed (IllegalStateException): " + e.getMessage(), e);
            } finally {
                if (codec != null) {
                    try {
                        codec.stop();
                        Log.d(TAG, "MediaCodec stopped.");
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "IllegalStateException on codec.stop() - may already be stopped or in error state.", e);
                    }
                    codec.release();
                    Log.d(TAG, "MediaCodec released.");
                }
            }
        } // fos is closed automatically by try-with-resources
    }

    private List<byte[]> splitAnnexB(byte[] data) {
        List<byte[]> list = new ArrayList<>();
        int len = data.length, offset = 0;
        while (offset < len) {
            int start = -1, prefix = 0;
            for (int i = offset; i + 3 < len; i++) {
                if (data[i]==0 && data[i+1]==0 && data[i+2]==0 && data[i+3]==1) {
                    start = i; prefix = 4; break;
                } else if (data[i]==0 && data[i+1]==0 && data[i+2]==1) {
                    start = i; prefix = 3; break;
                }
            }
            if (start < 0) break;
            int next = -1;
            for (int i = start + prefix; i + 3 < len; i++) {
                if ((data[i]==0 && data[i+1]==0 && data[i+2]==0 && data[i+3]==1)
                        || (data[i]==0 && data[i+1]==0 && data[i+2]==1)) {
                    next = i; break;
                }
            }
            int end = (next > start) ? next : len;
            byte[] nal = new byte[end - start];
            System.arraycopy(data, start, nal, 0, nal.length);
            list.add(nal);
            offset = end;
        }
        return list;
    }
}
