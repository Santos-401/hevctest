package com.brison.hevctest;

import android.content.Context;
import android.util.Log; // Added for logging
import android.util.Pair;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

public class H264ResolutionExtractor {
    private static final String TAG = "H264ResExtractor"; // Added TAG for logging

    // Inner class to hold SPS information
    public static class H264SPSInfo {
        public final int width;
        public final int height;
        public final int profile_idc;
        public final int level_idc;
        public final int chroma_format_idc;


        public H264SPSInfo(int width, int height, int profile_idc, int level_idc, int chroma_format_idc) {
            this.width = width;
            this.height = height;
            this.profile_idc = profile_idc;
            this.level_idc = level_idc;
            this.chroma_format_idc = chroma_format_idc;
        }

        public Pair<Integer,Integer> getResolution() {
            return new Pair<>(width, height);
        }
    }

    public static void copyAllAssetsToInternalStorage(Context context) {
        try {
            copyAssetFolder(context, "", context.getFilesDir().getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy assets", e);
        }
    }

    private static boolean copyAssetFolder(Context context, String assetPath, String targetPath) throws IOException {
        String[] assets = context.getAssets().list(assetPath);

        if (assets == null || assets.length == 0) {
            return copyAssetFile(context, assetPath, targetPath);
        } else {
            File dir = new File(targetPath, assetPath);
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "Failed to create directory: " + dir.getAbsolutePath());
            }

            for (String asset : assets) {
                String newAssetPath = assetPath.isEmpty() ? asset : assetPath + File.separator + asset;
                String newTargetPath = targetPath + File.separator + assetPath; // targetPath should be the root for assets
                copyAssetFolder(context, newAssetPath, newTargetPath);
            }
            return true;
        }
    }

    private static boolean copyAssetFile(Context context, String assetPath, String targetRootPath) throws IOException {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = context.getAssets().open(assetPath);
            File outFile = new File(targetRootPath, assetPath); // assetPath includes subdirectories
            if (outFile.getParentFile() != null) {
                outFile.getParentFile().mkdirs();
            }
            out = new FileOutputStream(outFile);

            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return true;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
            if (out != null) try { out.close(); } catch (IOException ignored) {}
        }
    }


    public static H264SPSInfo extractSPSInfo(byte[] inputData) {
        try {
            return parseSPS(inputData);
        } catch (Exception e) {
            Log.e(TAG, "Exception in extractSPSInfo", e);
            return null;
        }
    }

    private static byte[] readAllBytes(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] temp = new byte[4096];
        int n;
        while ((n = input.read(temp)) != -1) {
            buffer.write(temp, 0, n);
        }
        return buffer.toByteArray();
    }

    private static H264SPSInfo parseSPS(byte[] data) {
        int offset = 0;
        while (offset < data.length - 4) { // Ensure at least 4 bytes for start code + 1 for NAL header
            if (data[offset] == 0x00 && data[offset + 1] == 0x00) {
                int nalUnitType = -1;
                byte[] spsPayload = null;
                if (offset + 3 < data.length && data[offset + 2] == 0x00 && data[offset + 3] == 0x01) { // 4-byte start code 00 00 00 01
                    if (offset + 4 < data.length) { // Check for NAL header byte
                        nalUnitType = data[offset + 4] & 0x1F;
                        if (nalUnitType == 7) { // SPS NAL unit type
                            spsPayload = Arrays.copyOfRange(data, offset + 5, data.length); // Payload after NAL header
                        }
                    }
                    offset += 4;
                } else if (data[offset + 2] == 0x01) { // 3-byte start code 00 00 01
                    if (offset + 3 < data.length) { // Check for NAL header byte
                        nalUnitType = data[offset + 3] & 0x1F;
                        if (nalUnitType == 7) { // SPS NAL unit type
                            spsPayload = Arrays.copyOfRange(data, offset + 4, data.length); // Payload after NAL header
                        }
                    }
                    offset += 3;
                } else {
                    offset++; // Not a full start code sequence, advance by one
                    continue;
                }

                if (spsPayload != null) {
                    return decodeSPSInternal(spsPayload);
                }
                // If not an SPS or no payload, continue searching from current offset
            } else {
                offset++;
            }
        }
        Log.w(TAG, "SPS NAL unit not found in the provided data.");
        return null;
    }


    private static H264SPSInfo decodeSPSInternal(byte[] spsNalPayload) {
        if (spsNalPayload == null || spsNalPayload.length == 0) {
            Log.w(TAG, "SPS NAL payload is null or empty.");
            return null;
        }
        BitReader br = new BitReader(removeEmulationPreventionBytes(spsNalPayload));

        int profile_idc = br.readBits(8);
        br.readBits(8); // constraint_set_flags / reserved_zero_bits (ignore for now)
        int level_idc = br.readBits(8);
        readUE(br);      // seq_parameter_set_id

        int chroma_format_idc = 1; // Default for baseline, or if not present
        if (profile_idc == 100 || profile_idc == 110 || profile_idc == 122 || profile_idc == 244 ||
                profile_idc == 44  || profile_idc == 83 || profile_idc == 86 || profile_idc == 118 ||
                profile_idc == 128 || profile_idc == 138 || profile_idc == 139 || profile_idc == 134 || profile_idc == 135 ) {
            chroma_format_idc = readUE(br);
            if (chroma_format_idc == 3) {
                br.readBit(); // separate_colour_plane_flag
            }
            readUE(br); // bit_depth_luma_minus8
            readUE(br); // bit_depth_chroma_minus8
            br.readBit(); // qpprime_y_zero_transform_bypass_flag
            int seq_scaling_matrix_present_flag = br.readBit();
            if (seq_scaling_matrix_present_flag == 1) {
                // Skipping scaling list parsing for simplicity, as it's complex and not needed for resolution.
                // This part needs to be implemented fully if scaling lists are essential.
                int limit = (chroma_format_idc != 3) ? 8 : 12;
                for (int i = 0; i < limit; i++) {
                    if (br.readBit() == 1) { // seq_scaling_list_present_flag[i]
                        int sizeOfScalingList = (i < 6) ? 16 : 64;
                        int lastScale = 8;
                        int nextScale = 8;
                        for (int j = 0; j < sizeOfScalingList; j++) {
                            if (nextScale != 0) {
                                int delta_scale = readSE(br);
                                nextScale = (lastScale + delta_scale + 256) % 256;
                            }
                            if (nextScale == 0) { // If nextScale is 0, lastScale is not updated.
                                // no explicit update, lastScale remains.
                            } else {
                                lastScale = nextScale;
                            }
                        }
                    }
                }
            }
        }

        readUE(br); // log2_max_frame_num_minus4
        int pic_order_cnt_type = readUE(br);
        if (pic_order_cnt_type == 0) {
            readUE(br); // log2_max_pic_order_cnt_lsb_minus4
        } else if (pic_order_cnt_type == 1) {
            br.readBit(); // delta_pic_order_always_zero_flag
            readSE(br);   // offset_for_non_ref_pic
            readSE(br);   // offset_for_top_to_bottom_field
            int num_ref_frames_in_pic_order_cnt_cycle = readUE(br);
            for (int i = 0; i < num_ref_frames_in_pic_order_cnt_cycle; i++) {
                readSE(br); // offset_for_ref_frame[i]
            }
        }

        readUE(br); // max_num_ref_frames
        br.readBit();   // gaps_in_frame_num_value_allowed_flag

        int pic_width_in_mbs_minus1 = readUE(br);
        int pic_height_in_map_units_minus1 = readUE(br);
        boolean frame_mbs_only_flag = (br.readBit() == 1);

        if (!frame_mbs_only_flag) {
            br.readBit(); // mb_adaptive_frame_field_flag
        }
        br.readBit(); // direct_8x8_inference_flag

        int frame_crop_left_offset = 0;
        int frame_crop_right_offset = 0;
        int frame_crop_top_offset = 0;
        int frame_crop_bottom_offset = 0;
        boolean frame_cropping_flag = (br.readBit() == 1);
        if (frame_cropping_flag) {
            frame_crop_left_offset = readUE(br);
            frame_crop_right_offset = readUE(br);
            frame_crop_top_offset = readUE(br);
            frame_crop_bottom_offset = readUE(br);
        }

        int width = (pic_width_in_mbs_minus1 + 1) * 16;
        int height = (pic_height_in_map_units_minus1 + 1) * 16 * (frame_mbs_only_flag ? 1 : 2);

        if (frame_cropping_flag) {
            int cropUnitX = 1;
            int cropUnitY = (frame_mbs_only_flag ? 1 : 2);

            if (chroma_format_idc == 1) { // 4:2:0
                cropUnitX = 2;
                cropUnitY *= 2; // Height crop unit is also affected by chroma subsampling for interlaced/field content
            } else if (chroma_format_idc == 2) { // 4:2:2
                cropUnitX = 2;
                // cropUnitY remains as (frame_mbs_only_flag ? 1 : 2)
            }
            // For chroma_format_idc 0 (monochrome) or 3 (4:4:4), cropUnitX/Y remain 1 (or 2 for Y if interlaced)

            width -= (frame_crop_left_offset + frame_crop_right_offset) * cropUnitX;
            height -= (frame_crop_top_offset + frame_crop_bottom_offset) * cropUnitY;
        }

        return new H264SPSInfo(width, height, profile_idc, level_idc, chroma_format_idc);
    }

    private static byte[] removeEmulationPreventionBytes(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < data.length; i++) {
            if (i + 2 < data.length && data[i] == 0x00 && data[i+1] == 0x00 && data[i+2] == 0x03) {
                out.write(data[i]);
                out.write(data[i+1]);
                i += 2; // Skip 0x03
            } else {
                out.write(data[i]);
            }
        }
        return out.toByteArray();
    }

    private static int readUE(BitReader br) {
        int zeroCount = 0;
        while (br.readBits(1) == 0 && zeroCount < 32) { // Added max zeroCount to prevent infinite loop
            zeroCount++;
        }
        if (zeroCount == 32) return 0; // Should indicate an error or very large number
        if (zeroCount == 0) return 0; // code_num = 0
        return (1 << zeroCount) - 1 + br.readBits(zeroCount);
    }

    private static int readSE(BitReader br) {
        int val = readUE(br);
        return ((val & 1) == 1) ? (val + 1) / 2 : -(val / 2); // More direct way to calculate signed Exp-Golomb
    }
}
