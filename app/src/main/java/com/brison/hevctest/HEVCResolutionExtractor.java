package com.brison.hevctest;

// HEVCResolutionExtractor.java
// → 新規追加クラス。H.265 SPS（NAL unit type 33）の pic_width_in_luma_samples,
//    pic_height_in_luma_samples をパースします。

import android.util.Pair;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public class HEVCResolutionExtractor {

    public static Pair<Integer, Integer> extractResolution(byte[] data) {
        int offset = findNalUnit(data, (byte)33);  // nal_unit_type == 33 は SPS
        if (offset < 0) return null;

        // スタートコード（0x00000001）以降のバイト列を取り出し、エミュレーション防止バイト除去
        byte[] sps = removeEmulationPreventionBytes(
                Arrays.copyOfRange(data, offset + 4, data.length)
        );
        BitReader br = new BitReader(sps);

        // HEVC NAL ヘッダ（2 バイト）を読み飛ばし
        br.readBits(16);

        // vps_id(4), max_sub_layers_minus1(3), temporal_id_nesting_flag(1)
        br.readBits(4);
        int maxSubLayers = br.readBits(3);
        br.readBit();

        // profile_tier_level() をパースしてビットを消費
        parseProfileTierLevel(br, maxSubLayers);

        // sps_seq_parameter_set_id
        br.readUE();

        // chroma_format_idc
        int chroma = br.readUE();
        if (chroma == 3) {
            br.readBit(); // separate_colour_plane_flag
        }

        // ここで幅・高さを取得
        int picWidth  = (int)br.readUE();
        int picHeight = (int)br.readUE();

        // conformance_window_flag が立っていれば、クロップ値を飛ばす
        if (br.readBit() == 0) {
            br.readUE();  // left_offset
            br.readUE();  // right_offset
            br.readUE();  // top_offset
            br.readUE();  // bottom_offset
        }

        return new Pair<>(picWidth, picHeight);
    }

    private static void parseProfileTierLevel(BitReader br, int maxSubLayersMinus1) {
        // profile_space(2), tier_flag(1), profile_idc(5)
        br.readBits(8);
        // profile_compatibility_flags(32) + constraint_flags(48) + level_idc(8)
        br.readBits(32);
        br.readBits(48);
        br.readBits(8);
        // sub_layer_profile_present_flag と sub_layer_level_present_flag
        for (int i = 0; i < maxSubLayersMinus1; i++) {

            if (br.readBit() == 1) {
                // 同様にビットを飛ばす
                br.readBits(88);  // sub_layer_profile_space〜constraint_flags 合計
                br.readBits(8);   // sub_layer_level_idc
            }
        }
    }

    private static int findNalUnit(byte[] data, byte nalType) {
        for (int i = 0; i + 4 < data.length; i++) {
            if (data[i] == 0x00 && data[i+1] == 0x00 && data[i+2] == 0x00 && data[i+3] == 0x01) {
                // 次のバイトから nal header を読み、nal_unit_type をチェック
                int header = data[i+4] & 0xFF;
                int type = (header >> 1) & 0x3F;
                if (type == (nalType & 0x3F)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static byte[] removeEmulationPreventionBytes(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < data.length; i++) {
            // 0x000003 パターンを検出したら 0x03 をスキップ
            if (i+2 < data.length && data[i]==0x00 && data[i+1]==0x00 && data[i+2]==0x03) {
                out.write(0);
                out.write(0);
                i += 2;
            } else {
                out.write(data[i]);
            }
        }
        return out.toByteArray();
    }
}
