package com.brison.hevctest;

// Updated comment
// HEVCResolutionExtractor.java
// Parses H.265 SPS to extract width, height, profile, level, tier, and chroma format IDC.

import android.util.Log;
import android.util.Pair; // Retained if any internal methods might use it, though parseProfileTierLevel changed

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public class HEVCResolutionExtractor {
    private static final String TAG = "HEVCResExtractor";

    public static class SPSInfo {
        public final int width;
        public final int height;
        public final int profileIdc;
        public final int levelIdc;
        public final int chromaFormatIdc;
        public final boolean tierFlag; // true for High Tier, false for Main Tier

        public SPSInfo(int width, int height, int profileIdc, int levelIdc, int chromaFormatIdc, boolean tierFlag) {
            this.width = width;
            this.height = height;
            this.profileIdc = profileIdc;
            this.levelIdc = levelIdc;
            this.chromaFormatIdc = chromaFormatIdc;
            this.tierFlag = tierFlag;
        }
    }

    private static class ProfileTierLevelInfo {
        final int profileIdc;
        final int levelIdc;
        final boolean tierFlag;

        ProfileTierLevelInfo(int profileIdc, int levelIdc, boolean tierFlag) {
            this.profileIdc = profileIdc;
            this.levelIdc = levelIdc;
            this.tierFlag = tierFlag;
        }
    }

    private static class NalUnitPosition {
        final int offset;
        final int startCodeLength;
        NalUnitPosition(int offset, int startCodeLength) {
            this.offset = offset;
            this.startCodeLength = startCodeLength;
        }
    }

    private static NalUnitPosition findNalUnitPos(byte[] data, byte nalTypeToFind) {
        for (int i = 0; i + 2 < data.length; i++) {
            if (data[i] == 0x00 && data[i+1] == 0x00) {
                int nalUnitHeaderByteOffset = -1;
                int currentStartCodeLength = 0;

                if (data[i+2] == 0x01) {
                    nalUnitHeaderByteOffset = i + 3;
                    currentStartCodeLength = 3;
                } else if (i + 3 < data.length && data[i+2] == 0x00 && data[i+3] == 0x01) {
                    nalUnitHeaderByteOffset = i + 4;
                    currentStartCodeLength = 4;
                }

                if (nalUnitHeaderByteOffset != -1 && nalUnitHeaderByteOffset < data.length) {
                    int nalUnitHeaderByte1 = data[nalUnitHeaderByteOffset] & 0xFF;
                    int currentNalType = (nalUnitHeaderByte1 >> 1) & 0x3F;
                    if (currentNalType == nalTypeToFind) {
                        return new NalUnitPosition(i, currentStartCodeLength);
                    }
                }
            }
        }
        return null;
    }

    public static SPSInfo extractSPSInfo(byte[] data) {
        NalUnitPosition spsPosition = findNalUnitPos(data, (byte)33);
        if (spsPosition == null) {
            Log.w(TAG, "SPS NAL unit (type 33) not found.");
            return null;
        }

        int nalHeaderStartOffset = spsPosition.offset + spsPosition.startCodeLength;
        int spsPayloadOffset = nalHeaderStartOffset + 2;

        if (spsPayloadOffset >= data.length) {
            Log.w(TAG, "SPS payload offset is out of bounds or NAL unit is too short.");
            return null;
        }

        int spsNalEndOffset = data.length;
        int searchNextNalOffset = nalHeaderStartOffset + 2; // Start search after current NAL header

        for (int j = searchNextNalOffset; j + 2 < data.length; j++) {
            if (data[j] == 0x00 && data[j+1] == 0x00) {
                if (data[j+2] == 0x01 || (j + 3 < data.length && data[j+2] == 0x00 && data[j+3] == 0x01)) {
                    spsNalEndOffset = j;
                    break;
                }
            }
        }

        if (spsPayloadOffset >= spsNalEndOffset) {
             Log.w(TAG, "SPS payload appears empty or invalid end offset found (payload offset: " + spsPayloadOffset + ", end offset: " + spsNalEndOffset + ").");
             return null;
        }

        byte[] spsRbsp = removeEmulationPreventionBytes(
                Arrays.copyOfRange(data, spsPayloadOffset, spsNalEndOffset)
        );

        if (spsRbsp.length == 0) {
            Log.w(TAG, "SPS RBSP is empty after extraction and emulation prevention byte removal.");
            return null;
        }
        BitReader br = new BitReader(spsRbsp);

        br.readBits(4);
        int maxSubLayersMinus1 = br.readBits(3);
        br.readBit();

        ProfileTierLevelInfo ptlInfo = parseProfileTierLevel(br, maxSubLayersMinus1);
        if (ptlInfo == null) {
            Log.w(TAG, "Failed to parse ProfileTierLevel info from SPS.");
            return null;
        }
        int profileIdc = ptlInfo.profileIdc;
        int levelIdc = ptlInfo.levelIdc;
        boolean tierFlag = ptlInfo.tierFlag;

        readUE(br);

        int chromaFormatIdc = (int) readUE(br);
        if (chromaFormatIdc == 3) {
            br.readBit();
        }

        int picWidth = (int) readUE(br);
        int picHeight = (int) readUE(br);

        if (br.readBit() == 1) {
            readUE(br);
            readUE(br);
            readUE(br);
            readUE(br);
        }

        return new SPSInfo(picWidth, picHeight, profileIdc, levelIdc, chromaFormatIdc, tierFlag);
    }

    private static ProfileTierLevelInfo parseProfileTierLevel(BitReader br, int maxSubLayersMinus1) {
        br.readBits(2);
        boolean general_tier_flag = (br.readBit() == 1);
        int general_profile_idc = br.readBits(5);
        br.readBits(32);
        br.readBits(48);
        int general_level_idc = br.readBits(8);

        if (maxSubLayersMinus1 < 0 || maxSubLayersMinus1 > 6) { // Max 7 sublayers (0-6 for minus1)
             // Invalid maxSubLayersMinus1, cannot proceed with sub-layer parsing.
             // Log this or handle as an error. For now, proceed with general PTL info.
             Log.w(TAG, "Invalid sps_max_sub_layers_minus1: " + maxSubLayersMinus1);
             // Return general PTL, sub-layer parsing will be skipped or might error in BitReader
             return new ProfileTierLevelInfo(general_profile_idc, general_level_idc, general_tier_flag);
        }

        boolean[] sub_layer_profile_present_flag = new boolean[maxSubLayersMinus1];
        boolean[] sub_layer_level_present_flag = new boolean[maxSubLayersMinus1];

        for (int i = 0; i < maxSubLayersMinus1; i++) {
            sub_layer_profile_present_flag[i] = (br.readBit() == 1);
            sub_layer_level_present_flag[i] = (br.readBit() == 1);
        }

        if (maxSubLayersMinus1 > 0) {
            for (int i = maxSubLayersMinus1; i < 8; i++) {
                br.readBits(2);
            }
        }

        for (int i = 0; i < maxSubLayersMinus1; i++) {
            if (sub_layer_profile_present_flag[i]) {
                br.readBits(2);
                br.readBit();
                br.readBits(5);
                br.readBits(32);
                br.readBits(48);
                br.readBits(8);
            } else if (sub_layer_level_present_flag[i]) {
                br.readBits(8);
            }
        }
        return new ProfileTierLevelInfo(general_profile_idc, general_level_idc, general_tier_flag);
    }

    private static byte[] removeEmulationPreventionBytes(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < data.length; i++) {
            if (i + 2 < data.length && data[i] == 0x00 && data[i+1] == 0x00 && data[i+2] == 0x03) {
                out.write(data[i]);
                out.write(data[i+1]);
                i += 2;
            } else {
                out.write(data[i]);
            }
        }
        return out.toByteArray();
    }

    private static long readUE(BitReader br) {
        int zeros = 0;
        while (br.readBit() == 0 && zeros < 32) {
            zeros++;
        }
        if (zeros == 0) return 0;
        if (zeros >= 32) {
            Log.w(TAG, "Too many leading zeros in UE Golomb code (" + zeros + ")");
            return Long.MAX_VALUE;
        }
        long valueSuffix;
        try {
            valueSuffix = br.readBits(zeros);
        } catch (IllegalArgumentException e) { // BitReader might throw if trying to read too many bits
            Log.e(TAG, "Error reading suffix for UE Golomb code, zeros: " + zeros, e);
            return Long.MAX_VALUE;
        }
        long valueBase = (1L << zeros) - 1;
        return valueBase + valueSuffix;
    }
}
