package com.brison.hevctest;

import android.util.Pair;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * HEVC (H.265) のビットストリームから最初に現れる SPS を抽出し、
 * RBSP を Exp-Golomb 解析して幅×高さを返すクラス。
 * ※H.265 でも 3バイト長／4バイト長スタートコードの混在に対応。
 */
public class HEVCResolutionExtractor {

    public static Pair<Integer, Integer> extractResolution(byte[] data) {
        int offset = findNalUnit(data, (byte)33); // nal_unit_type == 33 が SPS
        if (offset < 0) return null;

        // スタートコード (3b/4b) を除去し、RBSP データ (Emulation Bytes含む) を取り出す
        byte[] sps = removeEmulationPreventionBytes(
                Arrays.copyOfRange(data, offset + 4, data.length)
        );
        BitReader br = new BitReader(sps);

        // NAL ヘッダ（16bit）を読み飛ばし
        br.readBits(16);

        // profile_tier_level の解析
        br.readBits(4);      // general_profile_space + general_tier_flag + general_profile_idc
        br.readBits(32);     // general_profile_compatibility_flags
        br.readBits(48);     // general_constraint_indicator_flags
        br.readBits(8);      // general_level_idc

        int maxSubLayersMinus1 = br.readBits(3);
        br.readBit();        // general_tier_flag の次の reserved_bit
        parseProfileTierLevel(br, maxSubLayersMinus1);

        br.readUE(); // sps_seq_parameter_set_id

        int chromaFormatIdc = br.readUE();
        if (chromaFormatIdc == 3) {
            br.readBit(); // separate_colour_plane_flag
        }

        int picWidth = br.readUE();  // pic_width_in_luma_samples_div2
        int picHeight = br.readUE(); // pic_height_in_luma_samples_div2

        if (br.readBit() == 1) { // conformance_window_flag
            br.readUE(); // conf_win_left_offset
            br.readUE(); // conf_win_right_offset
            br.readUE(); // conf_win_top_offset
            br.readUE(); // conf_win_bottom_offset
        }

        return new Pair<>(picWidth, picHeight);
    }

    /**
     * profile_tier_level の一部を読み飛ばす
     */
    private static void parseProfileTierLevel(BitReader br, int maxSubLayersMinus1) {
        // sub_layer_profile_present_flag や sub_layer_level_present_flag をチェックして
        // 必要に応じて 88+8 ビット程度を読み飛ばす実装
        for (int i = 0; i < maxSubLayersMinus1; i++) {
            if (br.readBit() == 1) {
                br.readBits(88);
                br.readBits(8);
            }
        }
    }

    /**
     * 3バイト長 OR 4バイト長スタートコードを検出し、
     * その後の NAL ヘッダバイトを読み取り、nal_unit_type を返す。
     */
    private static int findNalUnit(byte[] data, byte nalType) {
        for (int i = 0; i + 4 < data.length; i++) {
            int header;
            // 4バイト長スタートコード
            if (data[i] == 0x00 && data[i+1] == 0x00 && data[i+2] == 0x00 && data[i+3] == 0x01) {
                header = data[i + 4] & 0xFF;
            }
            // 3バイト長スタートコード
            else if (i + 2 < data.length
                    && data[i] == 0x00 && data[i+1] == 0x00 && data[i+2] == 0x01) {
                header = data[i + 3] & 0xFF;
            }
            else {
                continue;
            }
            int type = (header >> 1) & 0x3F; // ヘッダビットから nal_unit_type を取得
            if (type == (nalType & 0x3F)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Emulation Prevention Bytes (0x00 00 03) を除去する
     */
    private static byte[] removeEmulationPreventionBytes(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < data.length; i++) {
            if (i + 2 < data.length
                    && data[i] == 0x00 && data[i+1] == 0x00 && data[i+2] == 0x03) {
                out.write(0x00);
                out.write(0x00);
                i += 2; // 0x03 をスキップ
            } else {
                out.write(data[i]);
            }
        }
        return out.toByteArray();
    }
}
