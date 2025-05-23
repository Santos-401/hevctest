package com.brison.hevctest;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.util.Range;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import android.util.Pair;

public class H264DecodeTest {
    private static final long TIMEOUT_US = 10000;
    private static final String TAG = "com.brison.example";

    public static void decodeRawBitstream(String inputPath, String outputPath) throws IOException {
        boolean isHevc = inputPath.toLowerCase().endsWith(".bit");
        String mime = isHevc
                ? MediaFormat.MIMETYPE_VIDEO_HEVC
                : MediaFormat.MIMETYPE_VIDEO_AVC;

        // ファイル丸ごと読み込み
        byte[] raw = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            raw = new FileInputStream(inputPath).readAllBytes();
        }

        // 解像度抽出
        Pair<Integer, Integer> res = isHevc
                ? HEVCResolutionExtractor.extractResolution(raw)
                : H264ResolutionExtractor.extractResolution(raw);
        if (res == null) {
            Log.w(TAG, "解像度抽出失敗、デフォルト 352x288 を使用");
            res = new Pair<>(352, 288);
        }
        int width  = res.first;
        int height = res.second;
        Log.i(TAG, "解像度: " + width + "x" + height);

        // MediaCodec 初期化
        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
        codec.configure(format, null, null, 0);
        codec.start();

        // NAL ユニットに分割
        List<byte[]> nalUnits = splitNalUnits(raw);

        FileOutputStream fos = new FileOutputStream(outputPath);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        int nalIndex = 0;
        boolean inputDone  = false;
        boolean outputDone = false;

        while (!outputDone) {
            // --- 入力バッファ側 ---
            if (!inputDone) {
                int inIndex = codec.dequeueInputBuffer(TIMEOUT_US);
                if (inIndex >= 0) {
                    ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                    inBuf.clear();

                    if (nalIndex < nalUnits.size()) {
                        byte[] unit = nalUnits.get(nalIndex++);
                        inBuf.put(unit);
                        int flags = (nalIndex == nalUnits.size())
                                ? MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                : 0;
                        codec.queueInputBuffer(inIndex, 0, unit.length, 0, flags);
                    } else {
                        // NAL が尽きたら改めて EOS 投入
                        codec.queueInputBuffer(inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    }
                }
            }

            // --- 出力バッファ側 ---
            int outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US);
            if (outIndex >= 0) {
                // 正常なバッファ
                ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                if (outBuf != null && info.size > 0) {
                    byte[] yuv = new byte[info.size];
                    outBuf.get(yuv);
                    fos.write(yuv);
                }
                codec.releaseOutputBuffer(outIndex, false);

                // EOS フラグが返ってきたらループを抜ける
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }

            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // 出力フォーマットが変わった場合（1 度だけ呼ばれる）
                MediaFormat newFmt = codec.getOutputFormat();
                Log.i(TAG, "出力フォーマット変更: " + newFmt);
            } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // 今は出力無し
            }
        }

        fos.close();
        codec.stop();
        codec.release();
        Log.i(TAG, "デコード完了: " + outputPath);
    }

    private static String selectCodecName(int width, int height) {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);

        for (MediaCodecInfo info : codecList.getCodecInfos()) {
            if (info.isEncoder()) continue;
            String name = info.getName();
            if (!name.toLowerCase().contains("avc.decoder")) continue;
            try {
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
                MediaCodecInfo.VideoCapabilities videoCaps = caps.getVideoCapabilities();
                Range<Integer> wRange = videoCaps.getSupportedWidths();
                Range<Integer> hRange = videoCaps.getSupportedHeights();
                if (wRange.contains(width) && hRange.contains(height)) {
                    Log.i(TAG, String.format("選択したデコーダ: %s (対応: %dx%d〜%dx%d)",
                            name,
                            wRange.getLower(), hRange.getLower(),
                            wRange.getUpper(), hRange.getUpper()));
                    return name;
                }
            } catch (Exception e) {
                Log.w(TAG, "Capabilities check failed for " + name, e);
            }
        }
        Log.w(TAG, String.format("対応するハードウェアデコーダが見つかりませんでした (%dx%d)。ソフトウェアにフォールバックします。", width, height));
        return "OMX.google.h264.decoder";

    }

    public static int decodeRaw264(Context context,
                                   int width,
                                   int height,
                                   String assetName,
                                   Uri outputDirUri) throws IOException {
        // Read input raw H264 file
        File inputFile = new File(context.getFilesDir(), assetName);
        if (!inputFile.exists()) {
            Log.e(TAG, "Input file not found: " + inputFile.getAbsolutePath());
            return 0;
        }
        byte[] rawData = new byte[(int) inputFile.length()];
        try (FileInputStream fis = new FileInputStream(inputFile)) {
            fis.read(rawData);
        }


        // Split NAL units and extract SPS/PPS
        List<byte[]> nals = splitNalUnits(rawData);
        byte[][] csd = extractSpsAndPps(nals);
        if (csd[0] == null || csd[1] == null) {
            Log.e(TAG, "Failed to extract SPS or PPS");
            return 0;
        }


        // Configure MediaFormat
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd[0], 1, csd[0].length - 1));
        format.setByteBuffer("csd-1", ByteBuffer.wrap(csd[1], 1, csd[1].length - 1));

        // Create and start decoder
        String codecName = selectCodecName(width, height);
        MediaCodec decoder;
        try {
            decoder = MediaCodec.createByCodecName(codecName);
        } catch (IOException e) {
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        }
        decoder.configure(format, null, null, 0);
        decoder.start();

        // Prepare single-output YUV file
        File outDir = new File(outputDirUri.getPath());

        if (!outDir.exists()) outDir.mkdirs();
//    File outFile = new File(outDir, "all_frames.yuv");
        String baseName = assetName.contains(".")
                ? assetName.substring(0, assetName.lastIndexOf('.'))
                : assetName;

        File outFile = new File(outDir, baseName + ".yuv");
        FileOutputStream fosAll = new FileOutputStream(outFile);

        AtomicInteger frameCount = new AtomicInteger(0);
        boolean outputDone = false;
        int nalIndex = 0;
        long ptsUs = 0;

        while (!outputDone) {
            // Queue next NAL
            if (nalIndex < nals.size()) {
                int inIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                if (inIndex >= 0) {
                    ByteBuffer inBuf = decoder.getInputBuffer(inIndex);
                    inBuf.clear();
                    byte[] nal = nals.get(nalIndex);
                    inBuf.put(nal);
                    int flags = (nalIndex == nals.size() - 1) ?
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0;
                    decoder.queueInputBuffer(inIndex, 0, nal.length, ptsUs, flags);
                    ptsUs += 1000000L / 30;
                    nalIndex++;
                }
            }

            // Retrieve decoded output
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
            if (outIndex >= 0) {
                ByteBuffer outBuf = decoder.getOutputBuffer(outIndex);
                if (outBuf != null && info.size > 0) {
                    byte[] yuv = new byte[info.size];
                    outBuf.get(yuv);
                    fosAll.write(yuv);
                    frameCount.incrementAndGet();
                }
                decoder.releaseOutputBuffer(outIndex, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.i(TAG, "Output format changed: " + decoder.getOutputFormat());
            }
        }

        // Cleanup
        fosAll.close();
        decoder.stop();
        decoder.release();
        return frameCount.get();
    }

    // --- SPS/PPS 抽出用ヘルパーメソッド ---
//    private static List<byte[]> splitNalUnits(byte[] data) {
//        List<byte[]> nals = new ArrayList<>();
//        int offset = 0;
//        while (offset < data.length) {
//            int startCode = findStartCode(data, offset);
//            if (startCode < 0) break;
//            int scSize = (data[startCode] == 0 && data[startCode + 1] == 0 && data[startCode + 2] == 1) ? 3 : 4;
//            int nalStart = startCode + scSize;
//            int nextStart = findStartCode(data, nalStart);
//            int nalEnd = (nextStart > 0) ? nextStart : data.length;
//            nals.add(Arrays.copyOfRange(data, startCode, nalEnd));
//            offset = nalEnd;
//        }
//        return nals;
//    }
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