package com.brison.hevctest;

import android.content.Context;
import android.util.Pair;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

public class H264ResolutionExtractor {
    public static void copyAllAssetsToInternalStorage(Context context) {
        try {
            copyAssetFolder(context, "", context.getFilesDir().getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static boolean copyAssetFolder(Context context, String assetPath, String targetPath) throws IOException {
        String[] assets = context.getAssets().list(assetPath);

        if (assets == null || assets.length == 0) {
            // It's a file
            return copyAssetFile(context, assetPath, targetPath);
        } else {
            // It's a folder
            File dir = new File(targetPath, assetPath);
            if (!dir.exists()) dir.mkdirs();

            for (String asset : assets) {
                String newAssetPath = assetPath.isEmpty() ? asset : assetPath + "/" + asset;
                copyAssetFolder(context, newAssetPath, targetPath);
            }
            return true;
        }
    }

    private static boolean copyAssetFile(Context context, String assetPath, String targetRootPath) throws IOException {
        InputStream in = context.getAssets().open(assetPath);
        File outFile = new File(targetRootPath, assetPath);
        outFile.getParentFile().mkdirs(); // Ensure directory exists
        OutputStream out = new FileOutputStream(outFile);

        byte[] buffer = new byte[1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }

        in.close();
        out.flush();
        out.close();
        return true;
    }

    public static Pair<Integer, Integer> extractResolution(byte[]  input) {
        try {
//            byte[] data = readAllBytes(input);
            return parseSPSResolution(input);
        } catch (Exception e) {
            e.printStackTrace();
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

    private static Pair<Integer, Integer> parseSPSResolution(byte[] data) {
        int offset = 0;
        while (offset < data.length - 4) {
            if (data[offset] == 0x00 && data[offset + 1] == 0x00 &&
                    data[offset + 2] == 0x00 && data[offset + 3] == 0x01) {

                int nalType = data[offset + 4] & 0x1F;
                if (nalType == 7) {
                    byte[] sps = Arrays.copyOfRange(data, offset + 5, data.length);
                    return decodeSPS(sps);
                }
                offset += 4;
            } else {
                offset++;
            }
        }
        return null;
    }

    private static Pair<Integer, Integer> decodeSPS(byte[] sps) {
        BitReader br = new BitReader(removeEmulationPreventionBytes(sps));
        br.readBits(8);  // profile_idc
        br.readBits(8);  // constraint flags + level_idc
        br.readBits(8);  // level_idc
        readUE(br);      // seq_parameter_set_id

        int chroma_format_idc = 1;
        if (sps[0] == 100 || sps[0] == 110 || sps[0] == 122 || sps[0] == 244 ||
                sps[0] == 44 || sps[0] == 83 || sps[0] == 86 || sps[0] == 118 || sps[0] == 128) {
            chroma_format_idc = readUE(br);
            if (chroma_format_idc == 3) br.readBits(1);
            readUE(br);
            readUE(br);
            br.readBits(1);
            if (br.readBits(1) == 1) {
                for (int i = 0; i < 8; i++) {
                    if (br.readBits(1) == 1) {}
                }
            }
        }

        readUE(br);
        int pic_order_cnt_type = readUE(br);
        if (pic_order_cnt_type == 0) {
            readUE(br);
        } else if (pic_order_cnt_type == 1) {
            br.readBits(1);
            readSE(br);
            readSE(br);
            int n = readUE(br);
            for (int i = 0; i < n; i++) readSE(br);
        }

        readUE(br);
        br.readBits(1);

        int widthMbs = readUE(br) + 1;
        int heightMbs = readUE(br) + 1;
        boolean frameMbsOnly = br.readBits(1) == 1;
        if (!frameMbsOnly) br.readBits(1);
        br.readBits(1);

        boolean cropFlag = br.readBits(1) == 1;
        int cropLeft = 0, cropRight = 0, cropTop = 0, cropBottom = 0;
        if (cropFlag) {
            cropLeft = readUE(br);
            cropRight = readUE(br);
            cropTop = readUE(br);
            cropBottom = readUE(br);
        }

        int width = widthMbs * 16 - (cropLeft + cropRight) * 2;
        int height = heightMbs * 16 * (frameMbsOnly ? 1 : 2) - (cropTop + cropBottom) * 2;
        return new Pair<>(width, height);

    }

    private static byte[] removeEmulationPreventionBytes(byte[] sps) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < sps.length; i++) {
            if (i + 2 < sps.length && sps[i] == 0 && sps[i+1] == 0 && sps[i+2] == 3) {
                out.write(0);
                out.write(0);
                i += 2;
            } else {
                out.write(sps[i]);
            }
        }
        return out.toByteArray();
    }

    private static int readUE(BitReader br) {
        int zeroCount = 0;
        while (br.readBits(1) == 0) zeroCount++;
        if (zeroCount == 0) return 0;
        return (1 << zeroCount) - 1 + br.readBits(zeroCount);
    }

    private static int readSE(BitReader br) {
        int val = readUE(br);
        return ((val + 1) / 2) * ((val % 2 == 0) ? -1 : 1);
    }
}
