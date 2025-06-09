package com.brison.hevctest;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;
import android.util.Pair;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class H264DecodeTest {
    private static final String TAG = "H264DecodeTest";
    private static final long TIMEOUT_US = 10000;

    /**
     * 生のH.264ビットストリームを読み込み、デコードしてYUV出力する
     * @param inputPath 入力ファイルの絶対パス
     * @param outputDirPath 出力ディレクトリのパス
     * @return デコードしたフレーム数
     */
    public static int decodeRawBitstream(String inputPath, String outputDirPath) throws IOException {
        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            Log.e(TAG, "Input file not found: " + inputPath);
            return 0;
        }
        // 1. ファイル読み込み
        byte[] rawData = new byte[(int) inputFile.length()];
        try (FileInputStream fis = new FileInputStream(inputFile)) {
            fis.read(rawData);
        }
        // 2. NAL分割 & SPS/PPS抽出
        List<byte[]> nals = splitNalUnits(rawData);
        byte[][] csd = extractSpsAndPps(nals);
        if (csd[0] == null || csd[1] == null) {
            Log.e(TAG, "Failed to extract SPS or PPS");
            return 0;
        }
        // 3. 解像度取得 (H264ResolutionExtractor を利用)
        Pair<Integer, Integer> resPair = H264ResolutionExtractor.extractResolution(rawData);
        int width;
        int height;
        if (resPair != null) {
            width  = resPair.first;
            height = resPair.second;
        } else {
            Log.w(TAG, "解像度抽出失敗、デフォルト 352x288 を使用");
            width  = 352;
            height = 288;
        }
        Log.i(TAG, "Resolution: " + width + "x" + height);
        // 4. MediaFormat作成
        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd[0], 1, csd[0].length - 1));
        format.setByteBuffer("csd-1", ByteBuffer.wrap(csd[1], 1, csd[1].length - 1));

        File outDir = new File(outputDirPath);
        if (!outDir.exists()) outDir.mkdirs();
        File hwOut = new File(outDir, inputFile.getName() + ".yuv");

        // 5. ハードウェアデコーダでトライ
        String hwCodec = selectHardwareCodecName(width, height);
        try {
            return decodeWithCodec(nals, format, hwCodec, hwOut);
        } catch (Exception e) {
            Log.w(TAG, "Hardware decode failed, fallback to software: " + hwCodec, e);
            // フォールバック
            String swCodec = selectSoftwareCodecName();
            File swOut = new File(outDir, inputFile.getName() + "_sw.yuv");
            return decodeWithCodec(nals, format, swCodec, swOut);
        }
    }

    /**
     * 指定コーデックでデコード処理を実行し、YUVをファイル出力
     */
    private static int decodeWithCodec(
            List<byte[]> nals,
            MediaFormat format,
            String codecName,
            File outputFile
    ) throws IOException {
        MediaCodec decoder = MediaCodec.createByCodecName(codecName);
        decoder.configure(format, null, null, 0);
        decoder.start();

        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            AtomicInteger frameCount = new AtomicInteger(0);
            int nalIndex = 0;
            long ptsUs = 0;
            boolean outputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (!outputDone) {
                // 入力バッファ
                int inIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                if (inIndex >= 0 && nalIndex < nals.size()) {
                    ByteBuffer inBuf = decoder.getInputBuffer(inIndex);
                    inBuf.clear();
                    byte[] nal = nals.get(nalIndex);
                    inBuf.put(nal);
                    int flags = (nalIndex == nals.size() - 1)
                            ? MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            : 0;
                    decoder.queueInputBuffer(inIndex, 0, nal.length, ptsUs, flags);
                    ptsUs += 1000000L / 30;
                    nalIndex++;
                }
                // 出力バッファ
                int outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                if (outIndex >= 0) {
                    ByteBuffer outBuf = decoder.getOutputBuffer(outIndex);
                    if (outBuf != null && info.size > 0) {
                        byte[] yuv = new byte[info.size];
                        outBuf.get(yuv);
                        fos.write(yuv);
                        frameCount.incrementAndGet();
                    }
                    decoder.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
            decoder.stop();
            decoder.release();
            return frameCount.get();
        }
    }

    // ——— ハードウェアデコーダのみ選ぶユーティリティ ———
    private static String selectHardwareCodecName(int width, int height) {
        MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (info.isEncoder()) continue;
            String name = info.getName();
            if (!name.toLowerCase().contains("avc.decoder")) continue;
            try {
                MediaCodecInfo.CodecCapabilities caps =
                        info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
                if (caps.getVideoCapabilities()
                        .getSupportedWidths().contains(width)
                        && caps.getVideoCapabilities()
                        .getSupportedHeights().contains(height)) {
                    return name;
                }
            } catch (Exception ignored) {}
        }
        // 見つからなければデフォルト
        return MediaFormat.MIMETYPE_VIDEO_AVC;
    }

    // ——— ソフトウェアデコーダ（Google 実装）を選ぶ ———
    private static String selectSoftwareCodecName() {
        MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (info.isEncoder()) continue;
            String name = info.getName();
            if (name.equalsIgnoreCase("OMX.google.h264.decoder")) {
                return name;
            }
        }
        // 見つからなければタイプ指定
        return MediaFormat.MIMETYPE_VIDEO_AVC;
    }
    private static List<byte[]> splitNalUnits(byte[] data) {
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i + 4 < data.length; i++) {
            if (data[i]==0 && data[i+1]==0 && data[i+2]==0 && data[i+3]==1) {
                starts.add(i);
            }
        }
        List<byte[]> units = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int s = starts.get(i);
            int e = (i + 1 < starts.size()) ? starts.get(i+1) : data.length;
            int len = e - s;
            byte[] unit = new byte[len];
            System.arraycopy(data, s, unit, 0, len);
            units.add(unit);
        }
        return units;
    }
    private static int findStartCode(byte[] data, int offset) {
        for (int i = offset; i + 3 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                return i;
            } else if (i + 4 < data.length && data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1) {
                return i;
            }
        }
        return -1;
    }

    private static byte[][] extractSpsAndPps(List<byte[]> nals) {
        byte[] sps = null, pps = null;
        for (byte[] nal : nals) {
            int headerOffset = (nal[2] == 1) ? 3 : 4;
            int nalType = nal[headerOffset] & 0x1F;
            if (nalType == 7) {
                sps = nal;
            } else if (nalType == 8) {
                pps = nal;
            }
            if (sps != null && pps != null) break;
        }
        return new byte[][]{sps, pps};
    }
}