package com.brison.hevctest;

import android.content.Context;
import android.util.Log;
import android.util.Pair;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

public class H264ResolutionExtractor {
    private static final String TAG = "H264ResExtractor";

    // Asset copying methods - not directly used by core extraction logic from byte array
    // but kept if they are used elsewhere or for future use.
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
            // It's a file. Construct the full path within assets.
            return copyAssetFile(context, assetPath, targetPath);
        } else {
            // It's a folder
            File dir = new File(targetPath, assetPath); // assetPath is relative path of folder in assets
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "Failed to create directory: " + dir.getAbsolutePath());
            }
            for (String asset : assets) {
                String newAssetPath = assetPath.isEmpty() ? asset : assetPath + File.separator + asset;
                // Target path for items within this folder should be 'targetPath' itself if assetPath was already specific,
                // or targetPath + File.separator + assetPath if assetPath is a part of the structure being created.
                // The original logic for targetPath in recursion might need review if deeply nested asset folders are used.
                // For simplicity, assuming assetPath is the current segment being processed.
                copyAssetFolder(context, newAssetPath, targetPath);
            }
            return true;
        }
    }

    private static boolean copyAssetFile(Context context, String assetPath, String targetRootPath) throws IOException {
        // assetPath is the full path relative to the assets root.
        // targetRootPath is the base directory on internal storage.
        // The file should be placed in targetRootPath maintaining its relative path from assets.
        File outFile = new File(targetRootPath, assetPath);
        File parentDir = outFile.getParentFile();
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
            Log.w(TAG, "Failed to create parent directory: " + parentDir.getAbsolutePath());
        }

        try (InputStream in = context.getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        return true;
    }

    private static class NalUnitPosition {
        final int offset;
        final int startCodeLength;
        NalUnitPosition(int offset, int startCodeLength) {
            this.offset = offset;
            this.startCodeLength = startCodeLength;
        }
    }

    private static NalUnitPosition findNalUnitPosH264(byte[] data, int nalTypeToFind, int searchOffset) {
        for (int i = searchOffset; i + 2 < data.length; i++) {
            if (data[i] == 0x00 && data[i+1] == 0x00) {
                int nalUnitHeaderByteOffset = -1;
                int currentStartCodeLength = 0;

                if (data[i+2] == 0x01) { // Found 000001
                    nalUnitHeaderByteOffset = i + 3;
                    currentStartCodeLength = 3;
                } else if (i + 3 < data.length && data[i+2] == 0x00 && data[i+3] == 0x01) { // Found 00000001
                    nalUnitHeaderByteOffset = i + 4;
                    currentStartCodeLength = 4;
                }

                if (nalUnitHeaderByteOffset != -1 && nalUnitHeaderByteOffset < data.length) {
                    int currentNalType = data[nalUnitHeaderByteOffset] & 0x1F;
                    if (currentNalType == nalTypeToFind) {
                        return new NalUnitPosition(i, currentStartCodeLength);
                    }
                }
            }
        }
        return null;
    }

    public static Pair<Integer, Integer> extractResolution(byte[] inputData) {
        try {
            return parseSPSResolution(inputData);
        } catch (Exception e) {
            Log.e(TAG, "Exception in extractResolution", e);
            return null;
        }
    }

    private static Pair<Integer, Integer> parseSPSResolution(byte[] data) {
        NalUnitPosition spsPosition = findNalUnitPosH264(data, (byte)7, 0); // SPS NAL type is 7, start search from offset 0
        if (spsPosition == null) {
            Log.w(TAG, "SPS NAL unit not found.");
            return null;
        }

        int spsPayloadOffset = spsPosition.offset + spsPosition.startCodeLength + 1;

        if (spsPayloadOffset >= data.length) {
            Log.w(TAG, "SPS payload offset is out of bounds.");
            return null;
        }

        int spsNalEndOffset = data.length;
        // Search for the next NAL unit to determine the end of the current SPS NAL unit.
        // Start searching right after the current SPS's NAL header.
        int searchStartForNextNal = spsPosition.offset + spsPosition.startCodeLength;

        for (int j = searchStartForNextNal; j + 2 < data.length; j++) {
            if (data[j] == 0x00 && data[j+1] == 0x00) {
                if (data[j+2] == 0x01 || (j + 3 < data.length && data[j+2] == 0x00 && data[j+3] == 0x01)) {
                    spsNalEndOffset = j;
                    break;
                }
            }
        }

        if (spsPayloadOffset >= spsNalEndOffset) {
             Log.w(TAG, "SPS payload appears empty or invalid end offset.");
             return null;
        }

        byte[] spsPayload = Arrays.copyOfRange(data, spsPayloadOffset, spsNalEndOffset);
        return decodeSPS(spsPayload);
    }

    private static Pair<Integer, Integer> decodeSPS(byte[] spsRbsp) {
        BitReader br = new BitReader(removeEmulationPreventionBytes(spsRbsp));

        int profile_idc = br.readBits(8);
        br.readBits(8); // constraint_set_flags and reserved_zero_2bits
        int level_idc = br.readBits(8);
        readUE(br);      // seq_parameter_set_id

        int chroma_format_idc = 1;
        if (profile_idc == 100 || profile_idc == 110 || profile_idc == 122 || profile_idc == 244 ||
            profile_idc == 44  || profile_idc == 83  || profile_idc == 86  || profile_idc == 118 ||
            profile_idc == 128 || profile_idc == 138 || profile_idc == 139 || profile_idc == 134 || profile_idc == 135) {
            chroma_format_idc = readUE(br);
            if (chroma_format_idc == 3) {
                br.readBits(1); // separate_colour_plane_flag
            }
            readUE(br); // bit_depth_luma_minus8
            readUE(br); // bit_depth_chroma_minus8
            br.readBits(1); // qpprime_y_zero_transform_bypass_flag
            int seq_scaling_matrix_present_flag = br.readBits(1);
            if (seq_scaling_matrix_present_flag == 1) {
                int numScalingLists = (chroma_format_idc != 3) ? 8 : 12;
                for (int i = 0; i < numScalingLists; i++) {
                    int seq_scaling_list_present_flag_i = br.readBits(1);
                    if (seq_scaling_list_present_flag_i == 1) {
                        int sizeOfScalingList = (i < 6) ? 16 : 64;
                        int lastScale = 8;
                        int nextScale = 8;
                        for (int j = 0; j < sizeOfScalingList; j++) {
                            if (nextScale != 0) {
                                int delta_scale = readSE(br);
                                nextScale = (lastScale + delta_scale + 256) % 256;
                            }
                            lastScale = (nextScale == 0) ? lastScale : nextScale;
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
            br.readBits(1); // delta_pic_order_always_zero_flag
            readSE(br); // offset_for_non_ref_pic
            readSE(br); // offset_for_top_to_bottom_field
            int num_ref_frames_in_pic_order_cnt_cycle = readUE(br);
            for (int i = 0; i < num_ref_frames_in_pic_order_cnt_cycle; i++) {
                readSE(br); // offset_for_ref_frame[i]
            }
        }

        readUE(br); // max_num_ref_frames
        br.readBits(1); // gaps_in_frame_num_value_allowed_flag

        int pic_width_in_mbs_minus1 = readUE(br);
        int pic_height_in_map_units_minus1 = readUE(br);
        boolean frame_mbs_only_flag = (br.readBits(1) == 1);
        if (!frame_mbs_only_flag) {
            br.readBits(1); // mb_adaptive_frame_field_flag
        }
        br.readBits(1); // direct_8x8_inference_flag

        boolean frame_cropping_flag = (br.readBits(1) == 1);
        int frame_crop_left_offset = 0, frame_crop_right_offset = 0;
        int frame_crop_top_offset = 0, frame_crop_bottom_offset = 0;
        if (frame_cropping_flag) {
            frame_crop_left_offset = readUE(br);
            frame_crop_right_offset = readUE(br);
            frame_crop_top_offset = readUE(br);
            frame_crop_bottom_offset = readUE(br);
        }

        int width = (pic_width_in_mbs_minus1 + 1) * 16;
        // Correct FrameHeightInSamples calculation according to H.264 spec (7-14)
        // PicHeightInMapUnits = pic_height_in_map_units_minus1 + 1
        // FrameHeightInMbs = ( 2 - frame_mbs_only_flag ) * PicHeightInMapUnits
        // height = FrameHeightInMbs * 16
        int height = (2 - (frame_mbs_only_flag ? 1 : 0)) * (pic_height_in_map_units_minus1 + 1) * 16;

        if (frame_cropping_flag) {
            int cropUnitX, cropUnitY;
            if (chroma_format_idc == 0 || chroma_format_idc == 3) { // Monochrome or 4:4:4
                 cropUnitX = 1;
                 cropUnitY = (2 - (frame_mbs_only_flag ? 1: 0));
            } else if (chroma_format_idc == 1) { // 4:2:0
                 cropUnitX = 2;
                 cropUnitY = 2 * (2 - (frame_mbs_only_flag ? 1: 0));
            } else { // 4:2:2 (chroma_format_idc == 2)
                 cropUnitX = 2;
                 cropUnitY = (2 - (frame_mbs_only_flag ? 1: 0));
            }
            width  -= (frame_crop_left_offset + frame_crop_right_offset) * cropUnitX;
            height -= (frame_crop_top_offset + frame_crop_bottom_offset) * cropUnitY;
        }
        return new Pair<>(width, height);
    }

    private static byte[] removeEmulationPreventionBytes(byte[] sppsRbsp) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < sppsRbsp.length; i++) {
            if (i + 2 < sppsRbsp.length && sppsRbsp[i] == 0 && sppsRbsp[i+1] == 0 && sppsRbsp[i+2] == 3) {
                out.write(0);
                out.write(0);
                i += 2;
            } else {
                out.write(sppsRbsp[i]);
            }
        }
        return out.toByteArray();
    }

    private static int readUE(BitReader br) {
        int zeroCount = 0;
        while (br.readBits(1) == 0 && zeroCount < 32) {
            zeroCount++;
        }
        if (zeroCount == 0) return 0; // Value is 0
        if (zeroCount > 31) {
             Log.w(TAG, "Too many leading zeros in UE Golomb code, possible error or large value.");
             return Integer.MAX_VALUE;
        }
        // Calculate value: (2^zeroCount - 1) + readBits(zeroCount)
        int valueBase = (1 << zeroCount) - 1;
        int valueSuffix = br.readBits(zeroCount);
        // Check for potential overflow if zeroCount is large, though readBits should handle its own limits.
        // The number of bits to read (zeroCount) is already determined.
        return valueBase + valueSuffix;
    }

    private static int readSE(BitReader br) {
        int val = readUE(br);
        if (val == Integer.MAX_VALUE) return Integer.MAX_VALUE;

        // Formula for SE: (-1)^(k+1) * Ceil(k/2) where k is the code_num (val)
        // Equivalent to: if val is even, -(val/2). if val is odd, (val+1)/2.
        if (val % 2 == 0) { // Even
            return - (val / 2);
        } else { // Odd
            return (val + 1) / 2;
        }
    }
}
