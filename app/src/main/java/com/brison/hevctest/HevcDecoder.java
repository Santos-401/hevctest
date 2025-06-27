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

            // Strip start codes for CSD data as per MediaFormat documentation
            int vpsOffset = getStartCodeOffset(vps);
            int spsOffset = getStartCodeOffset(spsByteArr);
            int ppsOffset = getStartCodeOffset(pps);

            if (vpsOffset == -1 || spsOffset == -1 || ppsOffset == -1) {
                throw new IOException("Invalid or missing start code in VPS/SPS/PPS NAL units.");
            }

            format.setByteBuffer("csd-0", ByteBuffer.wrap(vps, vpsOffset, vps.length - vpsOffset));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(spsByteArr, spsOffset, spsByteArr.length - spsOffset));
            format.setByteBuffer("csd-2", ByteBuffer.wrap(pps, ppsOffset, pps.length - ppsOffset));
            Log.i(TAG, "Set csd-0 (VPS) with offset: " + vpsOffset + ", length: " + (vps.length - vpsOffset));
            Log.i(TAG, "Set csd-1 (SPS) with offset: " + spsOffset + ", length: " + (spsByteArr.length - spsOffset));
            Log.i(TAG, "Set csd-2 (PPS) with offset: " + ppsOffset + ", length: " + (pps.length - ppsOffset));

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
            boolean isHighTier = spsInfo.generalTierFlag;
            int androidLevel = -1;

            if (levelIdc <= 30) androidLevel = isHighTier ? MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel1 : MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel1; // Level 1 (30*3 = 90)
            else if (levelIdc <= 60) androidLevel = isHighTier ? MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel2 : MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel2; // Level 2 (60*3 = 180)
            else if (levelIdc <= 63) androidLevel = isHighTier ? MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel21 : MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel21; // Level 2.1 (63*3 = 189)
            else if (levelIdc <= 90) androidLevel = isHighTier ? MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel3 : MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel3; // Level 3 (90*3 = 270)
            else if (levelIdc <= 93) androidLevel = isHighTier ? MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel31 : MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31; // Level 3.1 (93*3 = 279)
            else if (levelIdc <= 120) androidLevel = isHighTier ? MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel4 : MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4; // Level 4 (120*3 = 360)
            else if (levelIdc <= 123) androidLevel = isHighTier ? MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel41 : MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41; // Level 4.1 (123*3 = 369)
            else if (levelIdc <= 150) androidLevel = isHighTier ? MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel5 : MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5; // Level 5 (150*3 = 450)
            else if (levelIdc <= 153) androidLevel = isHighTier ? MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51 : MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51; // Level 5.1 (153*3 = 459)
            else if (levelIdc <= 156) androidLevel = isHighTier ? MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel52 : MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel52; // Level 5.2 (156*3 = 468)
            else if (levelIdc <= 180) androidLevel = isHighTier ? MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel6 : MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel6; // Level 6 (180*3 = 540)
            else if (levelIdc <= 183) androidLevel = isHighTier ? MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel61 : MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel61; // Level 6.1 (183*3 = 549)
            else if (levelIdc <= 186) androidLevel = isHighTier ? MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel62 : MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel62; // Level 6.2 (186*3 = 558)
            else { Log.w(TAG, "Unmapped HEVC level_idc: " + levelIdc + " (Tier: " + (isHighTier ? "High" : "Main") + ") for KEY_LEVEL"); }

            if (androidLevel != -1) {
                format.setInteger(MediaFormat.KEY_LEVEL, androidLevel);
                Log.i(TAG, "Set KEY_LEVEL to " + androidLevel + " (from level_idc " + levelIdc + ", Tier: " + (isHighTier ? "High" : "Main") + ")");
            }

            if (spsInfo.chromaFormatIdc == 1) { // YUV 4:2:0
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
                Log.i(TAG, "Set KEY_COLOR_FORMAT to COLOR_FormatYUV420Flexible for chroma_format_idc = 1");
            } else {
                Log.w(TAG, "ChromaFormatIDC is " + spsInfo.chromaFormatIdc + " (not 1 for YUV420). KEY_COLOR_FORMAT not explicitly set, relying on decoder default.");
            }

            Log.i(TAG, "Final MediaFormat configuration: CSDs, Width, Height, Mapped Profile & Level. SPS ChromaFormatIDC: " + spsInfo.chromaFormatIdc + ", TierFlag: " + isHighTier);
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
        List<byte[]> nals = new ArrayList<>();
        if (data == null || data.length < 3) {
            Log.w(TAG, "splitAnnexB: Data is null or too short to contain NAL units.");
            return nals;
        }

        int len = data.length;
        int currentNalStartIdx = -1;
        int currentNalPrefixLen = 0;

        // Find the first NAL unit start
        for (int i = 0; i + 2 < len; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (i + 3 < len && data[i + 2] == 0 && data[i + 3] == 1) { // 00 00 00 01
                    currentNalStartIdx = i;
                    currentNalPrefixLen = 4;
                    break;
                } else if (data[i + 2] == 1) { // 00 00 01
                    currentNalStartIdx = i;
                    currentNalPrefixLen = 3;
                    break;
                }
            }
        }

        if (currentNalStartIdx == -1) {
            Log.w(TAG, "splitAnnexB: No NAL unit start codes found in the data.");
            return nals;
        }

        while (currentNalStartIdx < len) {
            int nextNalStartIdx = -1;
            int nextNalPrefixLen = 0;

            // Find the next NAL unit start code
            // Start search from after the current NAL's start code.
            for (int i = currentNalStartIdx + currentNalPrefixLen; i + 2 < len; i++) {
                if (data[i] == 0 && data[i + 1] == 0) {
                    if (i + 3 < len && data[i + 2] == 0 && data[i + 3] == 1) { // 00 00 00 01
                        nextNalStartIdx = i;
                        nextNalPrefixLen = 4;
                        break;
                    } else if (data[i + 2] == 1) { // 00 00 01
                        nextNalStartIdx = i;
                        nextNalPrefixLen = 3;
                        break;
                    }
                }
            }

            int currentNalEndIdx;
            if (nextNalStartIdx != -1) {
                currentNalEndIdx = nextNalStartIdx;
            } else {
                currentNalEndIdx = len;
            }

            // Extract the NAL unit (including its start code)
            int nalLength = currentNalEndIdx - currentNalStartIdx;
            if (nalLength > 0) {
                byte[] nal = new byte[nalLength];
                System.arraycopy(data, currentNalStartIdx, nal, 0, nalLength);
                nals.add(nal);
            } else {
                // This case should ideally not happen if start codes are found correctly
                // and currentNalStartIdx is always less than len.
                // Adding a log for safety.
                Log.w(TAG, "splitAnnexB: Found a NAL unit with zero or negative length at offset "
                        + currentNalStartIdx + " to " + currentNalEndIdx + ". Skipping.");
            }


            if (nextNalStartIdx != -1) {
                currentNalStartIdx = nextNalStartIdx;
                currentNalPrefixLen = nextNalPrefixLen;
            } else {
                // No more NAL units
                break;
            }
        }
        Log.i(TAG, "splitAnnexB: Split into " + nals.size() + " NAL units.");
        return nals;
    }

    private int getStartCodeOffset(byte[] nalUnit) {
        if (nalUnit == null || nalUnit.length < 3) {
            return -1; // Not enough data for a start code
        }
        if (nalUnit.length >= 4 && nalUnit[0] == 0 && nalUnit[1] == 0 && nalUnit[2] == 0 && nalUnit[3] == 1) {
            return 4; // 00 00 00 01
        }
        if (nalUnit[0] == 0 && nalUnit[1] == 0 && nalUnit[2] == 1) {
            return 3; // 00 00 01
        }
        return -1; // No recognized start code at the beginning
    }
}
