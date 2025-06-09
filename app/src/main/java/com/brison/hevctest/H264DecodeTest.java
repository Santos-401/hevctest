package com.brison.hevctest;

import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class H264DecodeTest {
    private static final String TAG = "H264DecodeTest";
    private static final long TIMEOUT_US = 10000L;

    public static int decodeRawBitstream(String inputPath, String outputPath) throws IOException {
        MediaCodec decoder = null;
        ImageReader imageReader = null;
        HandlerThread imageReaderThread = null;
        final AtomicBoolean decodeComplete = new AtomicBoolean(false);

        try {
            // 解像度を事前に取得（この部分は元のロジックを使用）
            byte[] initialData = readFileToByteArray(inputPath);
            android.util.Pair<Integer, Integer> res = H264ResolutionExtractor.extractResolution(initialData);
            int width = (res != null) ? res.first : 352;
            int height = (res != null) ? res.second : 288;
            Log.i(TAG, "Detected resolution: " + width + "x" + height);

            // ImageReaderと、そのコールバック用のハンドラースレッドを準備
            imageReaderThread = new HandlerThread("ImageReaderThread");
            imageReaderThread.start();
            Handler imageReaderHandler = new Handler(imageReaderThread.getLooper());

            final String finalOutputPath = outputPath;
            imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                try (Image image = reader.acquireNextImage()) {
                    if (image != null) {
                        Log.i(TAG, "<<< Frame Available! Writing to " + finalOutputPath);
                        dumpYuvImage(image, finalOutputPath);
                        // 1フレームのみ出力して終了する例。連続したフレームを出力する場合はロジック変更が必要。
                        decodeComplete.set(true);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Failed to write YUV file.", e);
                }
            }, imageReaderHandler);

            // CSDは設定せず、デコーダに自動解析させる
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);

            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            // 出力先としてImageReaderのSurfaceを指定
            decoder.configure(format, imageReader.getSurface(), null, 0);
            decoder.start();
            Log.i(TAG, "Codec started. Decoding to Surface.");

            // ファイルをチャンクで読み込み、そのままデコーダに供給
            try (InputStream inputStream = new FileInputStream(inputPath)) {
                byte[] buffer = new byte[65536]; // 64KB chunk size
                boolean inputDone = false;

                while (!inputDone && !decodeComplete.get()) {
                    int inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inputBufIndex >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufIndex);
                        int chunkSize = inputStream.read(buffer);

                        if (chunkSize > 0) {
                            inputBuffer.clear();
                            inputBuffer.put(buffer, 0, chunkSize);
                            decoder.queueInputBuffer(inputBufIndex, 0, chunkSize, 0, 0);
                        } else {
                            // ファイル終端に到達
                            Log.i(TAG, "End of stream reached. Sending EOS flag.");
                            decoder.queueInputBuffer(inputBufIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        }
                    }
                }
            }

            // デコード完了を待つ（タイムアウト付き）
            long startTime = System.currentTimeMillis();
            while (!decodeComplete.get() && (System.currentTimeMillis() - startTime < 5000)) {
                // dequeueOutputBufferを呼び出してデコーダを進める必要がある
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int outIndex = decoder.dequeueOutputBuffer(info, 100);
                if(outIndex >= 0) {
                    decoder.releaseOutputBuffer(outIndex, false);
                }
                if((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
            if (!decodeComplete.get()) {
                Log.w(TAG, "Decoding timed out or did not produce a frame.");
                return 0; // フレームが1つも得られなかった
            }

        } finally {
            if (decoder != null) {
                decoder.stop();
                decoder.release();
                Log.i(TAG, "Codec released.");
            }
            if (imageReader != null) {
                imageReader.close();
                Log.i(TAG, "ImageReader closed.");
            }
            if (imageReaderThread != null) {
                imageReaderThread.quitSafely();
            }
        }
        Log.i(TAG, "--- Decoding finished successfully for " + outputPath + " ---");
        return 1; // 成功
    }

    private static void dumpYuvImage(Image image, String filePath) throws IOException {
        try (FileOutputStream output = new FileOutputStream(filePath, true)) { // 追記モードで開く
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer yBuffer = planes[0].getBuffer();
            ByteBuffer uBuffer = planes[1].getBuffer();
            ByteBuffer vBuffer = planes[2].getBuffer();

            byte[] yBytes = new byte[yBuffer.remaining()];
            yBuffer.get(yBytes);
            output.write(yBytes);

            byte[] uBytes = new byte[uBuffer.remaining()];
            uBuffer.get(uBytes);
            output.write(uBytes);

            byte[] vBytes = new byte[vBuffer.remaining()];
            vBuffer.get(vBytes);
            output.write(vBytes);
        }
    }

    private static byte[] readFileToByteArray(String path) throws IOException {
        try (InputStream is = new FileInputStream(path); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            return baos.toByteArray();
        }
    }

    // このメソッドはもう使用しませんが、H264ResolutionExtractorが依存しているため残します。
    private static List<byte[]> splitNalUnits(byte[] data) {
        List<byte[]> nalUnits = new ArrayList<>();
        int i = 0;
        while (i < data.length) {
            int nextStart = -1;
            for (int j = i + 3; j < data.length - 3; j++) {
                if (data[j] == 0x00 && data[j + 1] == 0x00) {
                    if (data[j + 2] == 0x01 || (j + 3 < data.length && data[j + 2] == 0x00 && data[j + 3] == 0x01)) {
                        nextStart = j;
                        break;
                    }
                }
            }
            int end = (nextStart == -1) ? data.length : nextStart;
            int startOffset = i;
            if (i + 3 < data.length && data[i] == 0x00 && data[i + 1] == 0x00) {
                if (data[i + 2] == 0x01) startOffset = i + 3;
                else if (data[i + 2] == 0x00 && data[i + 3] == 0x01) startOffset = i + 4;
            }
            if (startOffset < end) {
                nalUnits.add(Arrays.copyOfRange(data, startOffset, end));
            }
            if (nextStart == -1) break;
            i = nextStart;
        }
        return nalUnits;
    }
}
