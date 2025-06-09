package com.brison.hevctest;

import android.util.Pair;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * H.264 のビットストリームから最初に現れる SPS を抽出し、
 * Exp-Golomb 解析を行って幅×高さを返すクラス。
 */
public class H264ResolutionExtractor {

    /**
     * 全ビットストリームを NAL単位で分割し、最初に見つかった SPS (nal_type=7) の
     * RBSP を decodeSPS() に渡し、(width, height) を返す。
     */
    public static Pair<Integer, Integer> extractResolution(byte[] data) {
        List<Integer> starts = new ArrayList<>();
        int len = data.length;
        int i = 0;
        // 4バイト長 OR 3バイト長スタートコードをリストに追加
        while (i < len - 3) {
            if (i + 3 < len
                    && data[i] == 0x00 && data[i+1] == 0x00
                    && data[i+2] == 0x00 && data[i+3] == 0x01) {
                // 0x00 00 00 01
                starts.add(i);
                i += 4;
            }
            else if (i + 2 < len
                    && data[i] == 0x00 && data[i+1] == 0x00
                    && data[i+2] == 0x01) {
                // 0x00 00 01
                starts.add(i);
                i += 3;
            } else {
                i++;
            }
        }
        // 最後の NAL を取りこぼさないよう末尾を追加
        starts.add(len);

        // 各 NAL を取り出し、最初に見つかる SPS を decodeSPS() で解析
        for (int idx = 0; idx < starts.size() - 1; idx++) {
            int startOffset = starts.get(idx);
            int nextOffset = starts.get(idx + 1);
            byte[] unit = Arrays.copyOfRange(data, startOffset, nextOffset);

            // スタートコード長を判定し、NALヘッダ位置を求める
            int headerOffset;
            if (unit.length >= 3 && unit[2] == 0x01) {
                headerOffset = 3; // 3バイト長
            } else {
                headerOffset = 4; // 4バイト長
            }
            if (headerOffset >= unit.length) continue;

            int nalType = unit[headerOffset] & 0x1F;
            if (nalType == 7) {
                // SPS の RBSP 部分を抽出 (ヘッダバイトを除いた部分)
                byte[] spsRbsp = removeEmulationPreventionBytes(
                        Arrays.copyOfRange(unit, headerOffset + 1, unit.length)
                );
                return decodeSPS(spsRbsp);
            }
        }
        return null; // SPS が見つからなかった場合
    }

    /**
     * SPS の RBSP を Exp-Golomb 解析して「実画素幅×高さ」を計算して返す。
     * Emulation Prevention Bytes (0x00 00 03) は既に除去済みのデータを引数に取る前提。
     */
    private static Pair<Integer, Integer> decodeSPS(byte[] sps) {
        BitReader br = new BitReader(sps);

        // profile_idc + constraint_flags + level_idc を読み飛ばし
        br.readBits(8);
        br.readBits(8);
        br.readBits(8);
        readUE(br); // seq_parameter_set_id

        int profile = (sps[0] & 0xFF);
        int chroma_format_idc = 1;
        if (profile == 100 || profile == 110 || profile == 122 ||
                profile == 244 || profile == 44 || profile == 83 ||
                profile == 86 || profile == 118 || profile == 128) {
            chroma_format_idc = readUE(br);
            if (chroma_format_idc == 3) {
                br.readBits(1); // separate_colour_plane_flag
            }
            readUE(br); // bit_depth_luma_minus8
            readUE(br); // bit_depth_chroma_minus8
            br.readBits(1); // qpprime_y_zero_transform_bypass_flag
            if (br.readBits(1) == 1) { // seq_scaling_matrix_present_flag
                skipScalingLists(br);
            }
        }

        readUE(br); // log2_max_frame_num_minus4
        int pic_order_cnt_type = readUE(br);
        if (pic_order_cnt_type == 0) {
            readUE(br); // log2_max_pic_order_cnt_lsb_minus4
        } else if (pic_order_cnt_type == 1) {
            br.readBits(1); // delta_pic_order_always_zero_flag
            readSE(br);     // offset_for_non_ref_pic
            readSE(br);     // offset_for_top_to_bottom_field
            int numRefFrames = readUE(br);
            for (int i = 0; i < numRefFrames; i++) {
                readSE(br); // offset_for_ref_frame[i]
            }
        }
        readUE(br); // num_ref_frames
        br.readBits(1); // gaps_in_frame_num_value_allowed_flag

        // pic_width_in_mbs_minus1 + 1
        int widthMbs = readUE(br) + 1;
        // pic_height_in_map_units_minus1 + 1
        int heightMbs = readUE(br) + 1;
        boolean frameMbsOnly = (br.readBits(1) == 1);
        if (!frameMbsOnly) {
            br.readBits(1); // mb_adaptive_frame_field_flag
        }
        br.readBits(1); // direct_8x8_inference_flag

        // conformance_window_flag のチェック
        boolean cropFlag = (br.readBits(1) == 1);
        int cropLeft = 0, cropRight = 0, cropTop = 0, cropBottom = 0;
        if (cropFlag) {
            cropLeft   = readUE(br);
            cropRight  = readUE(br);
            cropTop    = readUE(br);
            cropBottom = readUE(br);
        }

        // 実ピクセル幅・高さを計算
        int width  = widthMbs * 16 - (cropLeft + cropRight) * 2;
        int height = heightMbs * 16 * (frameMbsOnly ? 1 : 2) - (cropTop + cropBottom) * 2;
        return new Pair<>(width, height);
    }

    /**
     * Emulation Prevention Bytes (0x00 00 03) を除去するユーティリティ
     */
    private static byte[] removeEmulationPreventionBytes(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < data.length; i++) {
            if (i + 2 < data.length
                    && data[i] == 0x00 && data[i+1] == 0x00 && data[i+2] == 0x03) {
                // 0x00 00 03 → 0x00 00 に変換
                out.write(0x00);
                out.write(0x00);
                i += 2; // 0x03 をスキップ
            } else {
                out.write(data[i]);
            }
        }
        return out.toByteArray();
    }

    /**
     * Unsigned Exp-Golomb (UE) を読み出す
     * ─── 先に hasMore() をチェックし、readBit()→readBits() の順序を守る ───
     */
    private static int readUE(BitReader br) {
        int zeros = 0;
        while (true) {
            if (!br.hasMore()) {
                break;
            }
            int bit = br.readBit();
            if (bit == 1) {
                break;
            } else if (bit == 0) {
                zeros++;
            } else {
                // readBit() が -1 を返した場合はデータ末尾とみなし終了
                break;
            }
        }
        if (zeros == 0) {
            return 0;
        }
        // 'zeros' ビットだけ続く後のビットを読み取り
        int codeNum = (1 << zeros) - 1;
        int trailing = br.readBits(zeros);
        if (trailing < 0) {
            return codeNum;
        }
        return codeNum + trailing;
    }

    /**
     * Signed Exp-Golomb (SE) を読み出す
     */
    private static int readSE(BitReader br) {
        int ueVal = readUE(br);
        int sign = ((ueVal & 0x01) == 0) ? -1 : 1;
        return sign * ((ueVal + 1) / 2);
    }

    /**
     * seq_scaling_matrix_present_flag = 1 のときにスケーリングリストを飛ばすための簡易実装
     */
    private static void skipScalingLists(BitReader br) {
        // 最大 88 ビット程度読み飛ばす
        for (int n = 0; n < 88; n++) {
            if (!br.hasMore()) break;
            br.readBits(1);
        }
    }
}
