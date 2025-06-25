package com.brison.hevctest;

// HEVCResolutionExtractor.java
// → 新規追加クラス。H.265 SPS（NAL unit type 33）の pic_width_in_luma_samples,
//    pic_height_in_luma_samples をパースします。
//    Enhanced to extract profile, level, and chroma format IDC.

import android.util.Pair; // Needed for parseProfileTierLevel's return type

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public class HEVCResolutionExtractor {

    public static class SPSInfo {
        public final int width;
        public final int height;
        public final int profileIdc;
        public final int levelIdc;
        public final int chromaFormatIdc;
        public final boolean generalTierFlag; // Added generalTierFlag
        // Consider adding bitDepthLuma, bitDepthChroma if needed later

        public SPSInfo(int width, int height, int profileIdc, int levelIdc, int chromaFormatIdc, boolean generalTierFlag) {
            this.width = width;
            this.height = height;
            this.profileIdc = profileIdc;
            this.levelIdc = levelIdc;
            this.chromaFormatIdc = chromaFormatIdc;
            this.generalTierFlag = generalTierFlag;
        }
    }

    public static SPSInfo extractSPSInfo(byte[] data) {
        int offset = findNalUnit(data, (byte)33);  // nal_unit_type == 33 は SPS
        if (offset < 0) return null;

        // スタートコード（0x00000001）以降のバイト列を取り出し、エミュレーション防止バイト除去
        // Skip NAL unit header (2 bytes: forbidden_zero_bit (1), nal_unit_type (6), nuh_layer_id (6), nuh_temporal_id_plus1 (3))
        // The NAL unit header is 2 bytes for SPS.
        byte[] sps = removeEmulationPreventionBytes(
                Arrays.copyOfRange(data, offset + 4, data.length) // +4 for start code
        );
        BitReader br = new BitReader(sps);

        // HEVC NAL ヘッダ（2 バイト）を読み飛ばし
        br.readBits(16); // forbidden_zero_bit (1) + nal_unit_type (6) + nuh_layer_id (6) + nuh_temporal_id_plus1 (3)

        // sps_video_parameter_set_id (4 bits)
        br.readBits(4);
        // sps_max_sub_layers_minus1 (3 bits)
        int maxSubLayersMinus1 = br.readBits(3);
        // sps_temporal_id_nesting_flag (1 bit)
        br.readBit();

        // profile_tier_level() をパースして IDC 値を取得
        // The PTL now returns a triple: profile, level, tier flag
        PTLInfo ptlInfo = parseProfileTierLevel(br, maxSubLayersMinus1);
        if (ptlInfo == null) { // Should ideally not happen if PTL is mandatory and correctly parsed
            return null;
        }
        int profileIdc = ptlInfo.profileIdc;
        int levelIdc = ptlInfo.levelIdc;
        boolean tierFlag = ptlInfo.tierFlag;

        // sps_seq_parameter_set_id (ue(v))
        br.readUE();

        // chroma_format_idc (ue(v))
        int chromaFormatIdc = (int) br.readUE();
        if (chromaFormatIdc == 3) {
            br.readBit(); // separate_colour_plane_flag (1 bit)
        }

        // pic_width_in_luma_samples (ue(v))
        int picWidth = (int) br.readUE();
        // pic_height_in_luma_samples (ue(v))
        int picHeight = (int) br.readUE();

        // conformance_window_flag (1 bit)
        if (br.readBit() == 1) { // If conformance_window_flag is 1
            br.readUE();  // conf_win_left_offset (ue(v))
            br.readUE();  // conf_win_right_offset (ue(v))
            br.readUE();  // conf_win_top_offset (ue(v))
            br.readUE();  // conf_win_bottom_offset (ue(v))
        }

        // Other SPS fields like bit_depth_luma_minus8, bit_depth_chroma_minus8, etc.,
        // can be parsed here if needed for SPSInfo. For now, focusing on requested fields.

        return new SPSInfo(picWidth, picHeight, profileIdc, levelIdc, chromaFormatIdc, tierFlag);
    }

    // Helper class for PTL info
    private static class PTLInfo {
        final int profileIdc;
        final int levelIdc;
        final boolean tierFlag;

        PTLInfo(int profile, int level, boolean tier) {
            this.profileIdc = profile;
            this.levelIdc = level;
            this.tierFlag = tier;
        }
    }

    private static PTLInfo parseProfileTierLevel(BitReader br, int maxSubLayersMinus1) {
        // general_profile_space (2 bits)
        br.readBits(2);
        // general_tier_flag (1 bit)
        boolean general_tier_flag = (br.readBit() == 1);
        // general_profile_idc (5 bits)
        int general_profile_idc = (int) br.readBits(5);
        // general_profile_compatibility_flags (32 bits)
        br.readBits(32);
        // general_constraint_indicator_flags (48 bits) - previously referred to as general_progressive_source_flag etc.
        br.readBits(48); // Corrected to 48 bits as per HEVC spec for these flags
        // general_level_idc (8 bits)
        int general_level_idc = (int) br.readBits(8);

        // Arrays to store sub-layer presence flags
        boolean[] sub_layer_profile_present_flag = new boolean[maxSubLayersMinus1];
        boolean[] sub_layer_level_present_flag = new boolean[maxSubLayersMinus1];

        for (int i = 0; i < maxSubLayersMinus1; i++) {
            sub_layer_profile_present_flag[i] = (br.readBit() == 1); // u(1)
            sub_layer_level_present_flag[i] = (br.readBit() == 1);   // u(1)
        }

        if (maxSubLayersMinus1 > 0) {
            for (int i = maxSubLayersMinus1; i < 8; i++) {
                br.readBits(2); // reserved_zero_2bits
            }
        }

        for (int i = 0; i < maxSubLayersMinus1; i++) {
            if (sub_layer_profile_present_flag[i]) {
                br.readBits(2);    // sub_layer_profile_space[i]
                br.readBit();      // sub_layer_tier_flag[i]
                br.readBits(5);    // sub_layer_profile_idc[i]
                br.readBits(32);   // sub_layer_profile_compatibility_flag[i]
                br.readBits(48);   // sub_layer_constraint_indicator_flags[i] (Corrected to 48 bits)
                br.readBits(8);    // sub_layer_level_idc[i]
            }
            // If sub_layer_profile_present_flag[i] is 0, but sub_layer_level_present_flag[i] is 1,
            // then only sub_layer_level_idc[i] is present for that sub-layer.
            else if (sub_layer_level_present_flag[i]) {
                br.readBits(8);    // sub_layer_level_idc[i]
            }
        }
        return new PTLInfo(general_profile_idc, general_level_idc, general_tier_flag);
    }

    private static int findNalUnit(byte[] data, byte nalType) {
        for (int i = 0; i + 4 < data.length; i++) {
            if (data[i] == 0x00 && data[i+1] == 0x00 && data[i+2] == 0x00 && data[i+3] == 0x01) {
                // Check NAL unit type from the NAL unit header (first byte after start code)
                // NAL unit header: forbidden_zero_bit (1), nal_unit_type (6), nuh_layer_id (6), nuh_temporal_id_plus1 (3)
                // We are interested in nal_unit_type which is bits 1-6 of the first byte of NAL unit header.
                int nalUnitHeaderByte1 = data[i+4] & 0xFF;
                int currentNalType = (nalUnitHeaderByte1 >> 1) & 0x3F; // Extract bits 1-6

                if (currentNalType == nalType) {
                    return i; // Return offset of start code
                }
            }
        }
        return -1;
    }

    private static byte[] removeEmulationPreventionBytes(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < data.length; i++) {
            // Check for 0x000003 pattern
            if (i + 2 < data.length && data[i] == 0x00 && data[i+1] == 0x00 && data[i+2] == 0x03) {
                out.write(data[i]);   // Write 0x00
                out.write(data[i+1]); // Write 0x00
                i += 2; // Skip 0x03, next loop iteration will start after 0x03
            } else {
                out.write(data[i]);
            }
        }
        return out.toByteArray();
    }
}
