package com.brison.hevctest;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class HevcDecoder {
    private static final String TAG = "HevcDecoder";
    private static final String MIME = "video/hevc";
    private static final long TIMEOUT_US = 10000;

    private final int width;
    private final int height;

    public HevcDecoder(int width, int height) {
        this.width = width;
        this.height = height;
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

        // 2) Annex-B スタートコード込みで NAL 単位に分割
        List<byte[]> nalUnits = splitAnnexB(data);
        Log.i(TAG, "NAL count: " + nalUnits.size());

        // 3) VPS/SPS/PPS を抽出して MediaFormat にセット
        byte[] vps = null, sps = null, pps = null;
        for (byte[] nal : nalUnits) {
            int offset = 0;
            if (nal.length > 4 && nal[0]==0 && nal[1]==0 && nal[2]==0 && nal[3]==1) {
                offset = 4;
            } else if (nal.length > 3 && nal[0]==0 && nal[1]==0 && nal[2]==1) {
                offset = 3;
            }
            int header = nal[offset] & 0xFF;
            int type = (header & 0x7E) >> 1;  // HEVC NALU type: bits1-6
            if (type == 32) vps = nal;
            else if (type == 33) sps = nal;
            else if (type == 34) pps = nal;
            if (vps != null && sps != null && pps != null) break;
        }
        if (vps == null || sps == null || pps == null) {
            throw new IOException("Missing VPS/SPS/PPS in bitstream");
        }

        MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(vps));
        format.setByteBuffer("csd-1", ByteBuffer.wrap(sps));
        format.setByteBuffer("csd-2", ByteBuffer.wrap(pps));

        // 4) MediaCodec の初期化
        MediaCodec codec = MediaCodec.createDecoderByType(MIME);
        codec.configure(format, null, null, 0);
        codec.start();

        // 5) YUV 出力先ファイル
        try (FileOutputStream fos = new FileOutputStream(outputPath)) {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int frameCount = 0;

            // 入力ループ
            for (byte[] nal : nalUnits) {
                int inIndex = codec.dequeueInputBuffer(TIMEOUT_US);
                if (inIndex >= 0) {
                    ByteBuffer ib = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                            ? codec.getInputBuffer(inIndex)
                            : ByteBuffer.wrap(codec.getInputBuffers()[inIndex].array());
                    ib.clear();
                    ib.put(nal);
                    long pts = frameCount * 1000000L / 30; // 30fps 想定
                    codec.queueInputBuffer(inIndex, 0, nal.length, pts, 0);
                    frameCount++;
                }

                int outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                while (outIndex >= 0) {
                    ByteBuffer ob = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                            ? codec.getOutputBuffer(outIndex)
                            : ByteBuffer.wrap(codec.getOutputBuffers()[outIndex].array());
                    if (ob != null && info.size > 0) {
                        byte[] yuv = new byte[info.size];
                        ob.get(yuv);
                        fos.write(yuv);
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                }
            }

            // 6) EOS を送信
            int inIndex = codec.dequeueInputBuffer(TIMEOUT_US);
            if (inIndex >= 0) {
                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
            // 残りの出力を取り出し
            int outIndex = codec.dequeueOutputBuffer(new MediaCodec.BufferInfo(), TIMEOUT_US);
            while (outIndex >= 0) {
                codec.releaseOutputBuffer(outIndex, false);
                outIndex = codec.dequeueOutputBuffer(new MediaCodec.BufferInfo(), TIMEOUT_US);
            }
        } finally {
            // 7) クリーンアップ
            codec.stop();
            codec.release();
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
            if (start < 0) break;
            int next = -1;
            for (int i = start + prefix; i + 3 < len; i++) {
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
