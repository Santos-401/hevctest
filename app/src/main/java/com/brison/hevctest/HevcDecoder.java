package com.brison.hevctest;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
// Pair import might not be needed anymore if HEVCResolutionExtractor.SPSInfo is fully used
// import android.util.Pair;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class HevcDecoder {
    private Context context;
    private static final String TAG = "HevcDecoder";
    private static final String MIME = "video/hevc";
    private static final long TIMEOUT_US = 10000;

    public HevcDecoder() {
        // this.context = context;
    }

    /**
     * Annex-B 形式の HEVC ビットストリームをデコードし、YUV ファイルとして出力する
     * @param inputPath  raw Annex-B *.bit ファイルのパス
     * @param outputPath 出力先 YUV ファイルのパス
     */
    public void decodeToYuv(String inputPath, String outputPath) throws IOException {
        // 1) ビットストリーム全体を読み込む
        byte[] data;
        try (FileInputStream fis = new FileInputStream(inputPath)) {
            data = new byte[fis.available()];
            fis.read(data);
        }

        HEVCResolutionExtractor.SPSInfo spsInfo = HEVCResolutionExtractor.extractSPSInfo(data);
        if (spsInfo == null || spsInfo.width <= 0 || spsInfo.height <= 0) {
            throw new IOException("Could not extract valid SPS info (including resolution) from HEVC file.");
        }
        int actualWidth = spsInfo.width;
        int actualHeight = spsInfo.height;

        List<byte[]> nalUnits = splitAnnexB(data);
        Log.i(TAG, "NAL count: " + nalUnits.size());

        byte[] vps = null, spsByteArr = null, pps = null; // Renamed 'sps' to 'spsByteArr' to avoid conflict with spsInfo
        for (byte[] nal : nalUnits) {
            int offset = 0;
            if (nal.length > 4 && nal[0]==0 && nal[1]==0 && nal[2]==0 && nal[3]==1) {
                offset = 4;
            } else if (nal.length > 3 && nal[0]==0 && nal[1]==0 && nal[2]==1) {
                offset = 3;
            }
            // Ensure there's data after the start code prefix
            if (nal.length <= offset) {
                Log.w(TAG, "Skipping NAL unit with insufficient length after start code.");
                continue;
            }
            int header = nal[offset] & 0xFF;
            int type = (header & 0x7E) >> 1;
            if (type == 32) vps = nal;
            else if (type == 33) spsByteArr = nal;
            else if (type == 34) pps = nal;
            if (vps != null && spsByteArr != null && pps != null) break;
        }
        if (vps == null || spsByteArr == null || pps == null) {
            throw new IOException("Missing VPS/SPS/PPS in bitstream");
        }

        MediaFormat format = MediaFormat.createVideoFormat(MIME, actualWidth, actualHeight);
        format.setInteger(MediaFormat.KEY_PROFILE, spsInfo.profileIdc);
        format.setInteger(MediaFormat.KEY_LEVEL, spsInfo.levelIdc);

        format.setByteBuffer("csd-0", ByteBuffer.wrap(vps));
        format.setByteBuffer("csd-1", ByteBuffer.wrap(spsByteArr)); // Use spsByteArr
        format.setByteBuffer("csd-2", ByteBuffer.wrap(pps));

        File outputFile = new File(outputPath);
        File parentDir = outputFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        MediaCodec codec = MediaCodec.createDecoderByType(MIME);
        codec.configure(format, null, null, 0);
        codec.start();

        try (FileOutputStream fos = new FileOutputStream(outputPath)) {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEOS = false;
            boolean outputEOS = false;
            int nalUnitIndex = 0;
            long frameCountForPTS = 0;

            while (!outputEOS) {
                // Input Handling
                if (!inputEOS) {
                    int inIndex = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (inIndex >= 0) {
                        if (nalUnitIndex < nalUnits.size()) {
                            byte[] nal = nalUnits.get(nalUnitIndex++);
                            ByteBuffer ib = codec.getInputBuffer(inIndex); // Use the new API
                            if (ib != null) { // Check if buffer is not null
                                ib.clear();
                                ib.put(nal);
                                long pts = frameCountForPTS * 1000000L / 30; // Assuming 30fps for PTS
                                codec.queueInputBuffer(inIndex, 0, nal.length, pts, 0);
                                frameCountForPTS++;
                            } else {
                                Log.e(TAG, "getInputBuffer returned null for index " + inIndex);
                                // Potentially handle error or skip
                            }
                        } else {
                            Log.i(TAG, "All NAL units queued, sending EOS to input.");
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEOS = true;
                        }
                    } else {
                         Log.d(TAG, "Input buffer not available or timed out.");
                    }
                }

                // Output Handling
                int outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                switch (outIndex) {
                    case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                        Log.i(TAG, "Output format changed. New format: " + codec.getOutputFormat());
                        break;
                    case MediaCodec.INFO_TRY_AGAIN_LATER:
                        Log.d(TAG, "dequeueOutputBuffer timed out (INFO_TRY_AGAIN_LATER).");
                        break;
                    case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                        // This case is deprecated and should not be relied upon.
                        // Input and output buffers are available via getInputBuffer and getOutputBuffer.
                        Log.d(TAG, "Output buffers changed (deprecated behavior).");
                        break;
                    default: // outIndex >= 0, indicates a valid output buffer
                        if (outIndex < 0) {
                            // This should ideally not happen if INFO_TRY_AGAIN_LATER is handled.
                            Log.w(TAG, "dequeueOutputBuffer returned unexpected negative index: " + outIndex);
                            break;
                        }

                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            Log.d(TAG, "Skipping codec config buffer.");
                            codec.releaseOutputBuffer(outIndex, false);
                            break;
                        }

                        ByteBuffer ob = codec.getOutputBuffer(outIndex); // Use new API
                        if (ob != null && info.size > 0) {
                            byte[] yuv = new byte[info.size];
                            ob.get(yuv);
                            fos.write(yuv);
                            Log.d(TAG, "Wrote " + info.size + " bytes of YUV data, pts: " + info.presentationTimeUs);
                        } else if (ob == null) {
                            Log.w(TAG, "Output buffer was null for index " + outIndex);
                        }

                        codec.releaseOutputBuffer(outIndex, false);

                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.i(TAG, "Output EOS reached.");
                            outputEOS = true;
                        }
                        break;
                } // End of switch
            } // End of while (!outputEOS)

        } finally {
            // 7) クリーンアップ
            codec.stop();
            codec.release();
            // fos is closed by try-with-resources
        }
    }

    /**
     * Annex-B (0x000001 または 0x00000001) を含む
     * NAL ユニットをスタートコード込みで切り出す
     */
    private List<byte[]> splitAnnexB(byte[] data) {
        List<byte[]> list = new ArrayList<>();
        int len = data.length, offset = 0;
        while (offset < len) {
            int start = -1, prefix = 0;
            for (int i = offset; i + 3 < len; i++) {
                if (data[i]==0 && data[i+1]==0 && data[i+2]==0 && data[i+3]==1) {
                    start = i; prefix = 4; break;
                } else if (data[i]==0 && data[i+1]==0 && data[i+2]==1) {
                    start = i; prefix = 3; break;
                }
            }
            if (start < 0) break; // No more start codes found
            int next = -1;
            for (int i = start + prefix; i + 3 < len; i++) { // Search for the next start code
                if ((data[i]==0 && data[i+1]==0 && data[i+2]==0 && data[i+3]==1)
                        || (data[i]==0 && data[i+1]==0 && data[i+2]==1)) {
                    next = i; break;
                }
            }
            int end = (next > start) ? next : len;
            byte[] nal = new byte[end - start];
            System.arraycopy(data, start, nal, 0, nal.length);
            list.add(nal);
            offset = end;
        }
        return list;
    }
}
