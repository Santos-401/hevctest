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

        Log.i(TAG, "decodeRawBitstream: Found " + nals.size() + " NAL units after splitting.");
        for (int i = 0; i < Math.min(nals.size(), 5); i++) {
            byte[] nal = nals.get(i);
            if (nal == null || nal.length < 4) { // Need at least 3 for start code + 1 for type
                Log.w(TAG, "decodeRawBitstream: NAL unit " + i + " is null or too short. Length: " + (nal == null ? "null" : nal.length));
                continue;
            }
            int nalType = -1;
            int nalHeaderOffset = -1;
            if (nal.length > 4 && nal[0] == 0 && nal[1] == 0 && nal[2] == 0 && nal[3] == 1) { // 00 00 00 01
                nalHeaderOffset = 4;
            } else if (nal[0] == 0 && nal[1] == 0 && nal[2] == 1) { // 00 00 01
                nalHeaderOffset = 3;
            }

            if (nalHeaderOffset != -1 && nal.length > nalHeaderOffset) {
                nalType = nal[nalHeaderOffset] & 0x1F;
                Log.i(TAG, "decodeRawBitstream: NAL unit " + i + " - Type: " + nalType + ", Length: " + nal.length);
            } else {
                // Log first few bytes if type cannot be determined to help debug splitNalUnits
                StringBuilder sb = new StringBuilder();
                for(int j=0; j < Math.min(nal.length, 10); j++) {
                    sb.append(String.format("%02X ", nal[j]));
                }
                Log.w(TAG, "decodeRawBitstream: NAL unit " + i + " - Could not determine type (headerOffset=" + nalHeaderOffset + "). Length: " + nal.length + ". First bytes: " + sb.toString());
            }
        }

        byte[][] csd = extractSpsAndPps(nals);
        if (csd[0] == null || csd[1] == null) {
            Log.e(TAG, "Failed to extract SPS or PPS");
            if (csd[0] == null) Log.e(TAG, "SPS is null");
            if (csd[1] == null) Log.e(TAG, "PPS is null");
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

        byte[] spsData = csd[0];
        byte[] ppsData = csd[1];

        // Determine offset for SPS (csd[0])
        int spsOffset = 0;
        if (spsData.length > 4 && spsData[0] == 0 && spsData[1] == 0 && spsData[2] == 0 && spsData[3] == 1) {
            spsOffset = 4;
        } else if (spsData.length > 3 && spsData[0] == 0 && spsData[1] == 0 && spsData[2] == 1) {
            spsOffset = 3;
        } else {
            throw new IOException("Invalid SPS start code format");
        }
        format.setByteBuffer("csd-0", ByteBuffer.wrap(spsData, spsOffset, spsData.length - spsOffset));
        Log.d(TAG, "Set csd-0 with offset: " + spsOffset + ", length: " + (spsData.length - spsOffset));

        // Determine offset for PPS (csd[1])
        int ppsOffset = 0;
        if (ppsData.length > 4 && ppsData[0] == 0 && ppsData[1] == 0 && ppsData[2] == 0 && ppsData[3] == 1) {
            ppsOffset = 4;
        } else if (ppsData.length > 3 && ppsData[0] == 0 && ppsData[1] == 0 && ppsData[2] == 1) {
            ppsOffset = 3;
        } else {
            throw new IOException("Invalid PPS start code format");
        }
        format.setByteBuffer("csd-1", ByteBuffer.wrap(ppsData, ppsOffset, ppsData.length - ppsOffset));
        Log.d(TAG, "Set csd-1 with offset: " + ppsOffset + ", length: " + (ppsData.length - ppsOffset));

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
        MediaCodec decoder = null;
        AtomicInteger frameCount = new AtomicInteger(0);

        Log.i(TAG, "H.264: Decoding with codec: " + codecName + ", Total NAL units to process: " + nals.size());

        try {
            decoder = MediaCodec.createByCodecName(codecName);
            decoder.configure(format, null, null, 0);
            decoder.start();

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean inputEOS = false;
                boolean outputEOS = false;
                int nalUnitIdx = 0;
                long presentationTimeUs = 0;

                while (!outputEOS) {
                    // Input Handling
                    if (!inputEOS) {
                        int inIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                        if (inIndex >= 0) {
                            if (nalUnitIdx < nals.size()) {
                                byte[] nal = nals.get(nalUnitIdx);
                                if (nal == null || nal.length == 0) {
                                    Log.w(TAG, "H.264: Skipping empty NAL unit at index " + nalUnitIdx);
                                    nalUnitIdx++;
                                    continue;
                                }

                                // Log NAL unit type
                                int nalType = -1;
                                int nalHeaderOffset = -1;
                                if (nal.length > 4 && nal[0] == 0 && nal[1] == 0 && nal[2] == 0 && nal[3] == 1) {
                                    nalHeaderOffset = 4;
                                } else if (nal.length > 3 && nal[0] == 0 && nal[1] == 0 && nal[2] == 1) {
                                    nalHeaderOffset = 3;
                                }

                                if (nalHeaderOffset != -1 && nal.length > nalHeaderOffset) {
                                    nalType = nal[nalHeaderOffset] & 0x1F; // Lower 5 bits for H.264 NAL type
                                    Log.d(TAG, "H.264: Queuing NAL unit " + nalUnitIdx + "/" + nals.size() + ", Type: " + nalType + ", Length: " + nal.length + ", PTS: " + presentationTimeUs);
                                } else {
                                    Log.w(TAG, "H.264: Queuing NAL unit " + nalUnitIdx + "/" + nals.size() + " with invalid start code or insufficient length. Length: " + nal.length + ", PTS: " + presentationTimeUs);
                                }

                                ByteBuffer ib = decoder.getInputBuffer(inIndex);
                                if (ib != null) {
                                    ib.clear();
                                    ib.put(nal);
                                    decoder.queueInputBuffer(inIndex, 0, nal.length, presentationTimeUs, 0);
                                    presentationTimeUs += 1000000L / 30; // 30 FPS assumption
                                } else {
                                    Log.e(TAG, "H.264: getInputBuffer returned null for index " + inIndex);
                                }
                                nalUnitIdx++;
                            } else { // All NALs have been queued
                                Log.i(TAG, "H.264: All NALs sent, queuing EOS for input.");
                                decoder.queueInputBuffer(inIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputEOS = true;
                            }
                        } else {
                            // Log.d(TAG, "H.264: Input buffer not available.");
                        }
                    }

                    // Output Handling
                    int outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                    switch (outIndex) {
                        case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                            Log.i(TAG, "H.264: Output format changed to: " + decoder.getOutputFormat());
                            break;
                        case MediaCodec.INFO_TRY_AGAIN_LATER:
                            // Log.d(TAG, "H.264: No output buffer available yet.");
                            break;
                        case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                            Log.d(TAG, "H.264: Output buffers changed (deprecated).");
                            break;
                        default:
                            if (outIndex < 0) {
                                Log.w(TAG, "H.264: dequeueOutputBuffer returned an unexpected index: " + outIndex);
                                break;
                            }
                            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                Log.d(TAG, "H.264: Skipping codec config buffer.");
                                decoder.releaseOutputBuffer(outIndex, false);
                                break;
                            }
                            ByteBuffer outBuf = decoder.getOutputBuffer(outIndex);
                            if (outBuf != null && info.size > 0) {
                                byte[] yuvData = new byte[info.size];
                                outBuf.get(yuvData);
                                fos.write(yuvData);
                                frameCount.incrementAndGet();
                            } else if (outBuf == null && info.size > 0){
                                Log.w(TAG, "H.264: Output buffer was null for index " + outIndex + " but info.size was " + info.size);
                            }
                            decoder.releaseOutputBuffer(outIndex, false);

                            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                Log.i(TAG, "H.264: Output EOS reached.");
                                outputEOS = true;
                            }
                            break;
                    }
                } // End of while(!outputEOS)
            } // fos is closed here by try-with-resources
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "H.264: MediaCodec configuration/operation failed (IllegalArgumentException): " + e.getMessage(), e);
            throw new IOException("H.264: MediaCodec configuration/operation failed (IllegalArgumentException): " + e.getMessage(), e);
        } catch (MediaCodec.CodecException e) {
            Log.e(TAG, "H.264: MediaCodec operation failed (CodecException): " + e.getMessage() + ", DiagnosticInfo: " + e.getDiagnosticInfo(), e);
            throw new IOException("H.264: MediaCodec operation failed (CodecException): " + e.getMessage(), e);
        } catch (IllegalStateException e) {
            Log.e(TAG, "H.264: MediaCodec operation failed (IllegalStateException): " + e.getMessage(), e);
            throw new IOException("H.264: MediaCodec operation failed (IllegalStateException): " + e.getMessage(), e);
        } catch (IOException e) { // To catch fos related IOExceptions
            Log.e(TAG, "H.264: IOException during decoding: " + e.getMessage(), e);
            throw e; // Re-throw original IOException
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (IllegalStateException e) {
                    Log.e(TAG, "H.264: IllegalStateException on codec.stop()", e);
                }
                decoder.release();
                Log.d(TAG, "H.264: MediaCodec stopped and released.");
            }
        }
        return frameCount.get();
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

    // Replaced with a more robust version adapted from HevcDecoder.splitAnnexB
    private static List<byte[]> splitNalUnits(byte[] data) {
        List<byte[]> list = new ArrayList<>();
        if (data == null || data.length < 3) { // Need at least 3 bytes for any start code
            return list;
        }
        int len = data.length;
        int initialOffset = 0;

        // Find the first start code in the entire stream
        int firstStartCodePos = -1;
        int firstPrefix = 0;
        for (int i = 0; i + 2 < len; i++) {
            if (data[i] == 0 && data[i+1] == 0) {
                if (i + 3 < len && data[i+2] == 0 && data[i+3] == 1) { // 00 00 00 01
                    firstStartCodePos = i;
                    firstPrefix = 4; // Length of this start code
                    break;
                } else if (data[i+2] == 1) { // 00 00 01
                    firstStartCodePos = i;
                    firstPrefix = 3; // Length of this start code
                    break;
                }
            }
        }

        if (firstStartCodePos == -1) {
            Log.w(TAG, "splitNalUnits: No start codes found in the entire data stream.");
            return list; // No start codes anywhere, so no NAL units
        }

        // Start processing from the first found start code
        int offset = firstStartCodePos;

        while (offset < len) {
            // The current NAL unit starts at 'offset'. Its start code length is 'firstPrefix' if it's the first NAL,
            // or needs to be re-determined for subsequent NALs if we only stored 'start' positions.
            // For simplicity, we assume 'offset' is always the beginning of a NAL unit including its start code.
            // The 'prefix' variable will be determined for the *current* NAL unit at 'offset'.
            int currentNalStart = offset;
            int currentPrefix = 0;
            if (currentNalStart + 3 < len && data[currentNalStart] == 0 && data[currentNalStart+1] == 0 && data[currentNalStart+2] == 0 && data[currentNalStart+3] == 1) {
                currentPrefix = 4;
            } else if (currentNalStart + 2 < len && data[currentNalStart] == 0 && data[currentNalStart+1] == 0 && data[currentNalStart+2] == 1) {
                currentPrefix = 3;
            } else {
                // Should not happen if offset is always at a start code.
                // This indicates a logic error or malformed stream after the first NAL.
                Log.e(TAG, "splitNalUnits: Expected start code at offset " + offset + " but not found. Stopping parse.");
                break;
            }

            // Find the start of the *next* NAL unit to determine the end of the current one
            int nextNalStart = -1;
            for (int i = currentNalStart + currentPrefix; i + 2 < len; i++) {
                if (data[i] == 0 && data[i+1] == 0) {
                    if (i + 3 < len && data[i+2] == 0 && data[i+3] == 1) { // 00 00 00 01
                        nextNalStart = i;
                        break;
                    } else if (data[i+2] == 1) { // 00 00 01
                        nextNalStart = i;
                        break;
                    }
                }
            }

            int endOfCurrentNal = (nextNalStart != -1) ? nextNalStart : len;
            byte[] nal = new byte[endOfCurrentNal - currentNalStart];
            System.arraycopy(data, currentNalStart, nal, 0, nal.length);
            list.add(nal);

            if (nextNalStart != -1) {
                offset = nextNalStart; // Move to the beginning of the next NAL unit
            } else {
                break; // No more NAL units found
            }
        }
        // This log might be redundant now if the firstStartCodePos check handles empty streams.
        // if (list.isEmpty() && data.length > 0 && firstStartCodePos != -1) {
        //     Log.w(TAG, "splitNalUnits: NAL units list is empty after processing, but start codes were initially found. Data length: " + data.length);
        // }
        return list;
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