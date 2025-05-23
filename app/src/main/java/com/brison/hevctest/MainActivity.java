package com.brison.hevctest;


import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "com.brison.example";
    private Button buttonListLocalFiles; // ★ ID変更反映
    private ListView listViewLocalFiles; // ★ ID変更反映
    private ExecutorService executorService;
    private Handler mainHandler; // UI更新用
    private ArrayAdapter<String> localFileListAdapter; // ★ 名前変更
    private List<File> internalFiles;
    private Button buttonStartDecord;


    // 内部ストレージのファイル一覧を取得・表示
    private void listInternalFiles() {
        Log.d(TAG, "listInternalFiles called");
        File internalDir = getFilesDir(); // Use getFilesDir() - /data/data/package/files
        if (internalDir == null || !internalDir.exists()) {
            updateStatus("内部ストレージ(/files)が見つかりません");
            Log.w(TAG, "Internal storage dir not found: " + (internalDir != null ? internalDir.getAbsolutePath() : "null"));
            return;
        }
        updateStatus("スマホ内ファイル検索中: " + internalDir.getAbsolutePath());
        File[] files = internalDir.listFiles();

        internalFiles.clear();
        localFileListAdapter.clear(); // ★ Adapter名変更

        if (files != null && files.length > 0) {
            Arrays.sort(files);
            List<String> displayNames = new ArrayList<>();
            for (File file : files) {
                if (file.isFile()) {
                    internalFiles.add(file);
                    displayNames.add(file.getName() + " (" + formatFileSize(file.length()) + ")");
                }
            }
            localFileListAdapter.addAll(displayNames); // ★ Adapter名変更
            localFileListAdapter.notifyDataSetChanged(); // ★ Adapter名変更
            updateStatus(internalFiles.size() + " 個のローカルファイル");
            Log.i(TAG, internalFiles.size() + " local files found.");
//            startDecodingProcess();

        } else {
            updateStatus("スマホ内に送信可能なファイルが見つかりません");
            Log.i(TAG, "No local files found in " + internalDir.getAbsolutePath());
        }
    }

    // ファイルサイズを読みやすい形式にフォーマット
    private String formatFileSize(long size) {
        if (size <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return String.format("%.1f %s", size / Math.pow(1024, digitGroups), units[digitGroups]);
    }


    // ステータスTextViewを更新 (UIスレッドから呼び出す)
    private void updateStatus(String message) {
        Log.i(TAG, "updateStatus Status: " + message);
    }

    // ステータスTextViewを更新 (どのスレッドからでも呼び出し可能)
    private void updateStatusOnUiThread(final String message) {
        mainHandler.post(() -> updateStatus(message));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.d(TAG, "onCreate started");

        // --- Initialize UI Elements ---
        buttonListLocalFiles = findViewById(R.id.buttonListLocalFiles);
        listViewLocalFiles = findViewById(R.id.listViewLocalFiles);
        buttonStartDecord = findViewById(R.id.startButton);


        // --- Initialize Executor and Handler ---
        executorService = Executors.newSingleThreadExecutor(); // Single thread for sequential network ops
        mainHandler = new Handler(Looper.getMainLooper());

        // --- Setup Local File List ---
        internalFiles = new ArrayList<>();
        localFileListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>()); // simple_list_item_1に変更
        listViewLocalFiles.setAdapter(localFileListAdapter);
        // listViewLocalFiles.setChoiceMode(ListView.CHOICE_MODE_SINGLE); // No selection needed for local list now

        // --- Set Button Listeners ---
        buttonListLocalFiles.setOnClickListener(v -> listInternalFiles());
        buttonStartDecord.setOnClickListener(v -> processAll());
        // --- Initial Actions ---
        listInternalFiles(); // List local files on startup
        Log.d(TAG, "onCreate finished");
    }


    @Override
    protected void onDestroy() { // ★ スレッド停止処理を追加
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }

    // ★ ヘルパー関数: MediaExtractor から最初のビデオトラックを探す
    private int selectVideoTrack(MediaExtractor extractor) {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                // H.264 (AVC) であることを確認 (オプション)
                // if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_AVC)) {
                return i;
                // }
            }
        }
        return -1; // ビデオトラックが見つからない場合
    }
    private void processAll() {
        File dir = getFilesDir();
        Log.i(TAG,   " processAll " + dir.getName() );
        for (File f : dir.listFiles()) {
            String name = f.getName();
            if (name.endsWith(".264") || name.endsWith(".bit")) {
                String outPath = new File(dir, name + ".yuv").getAbsolutePath();

                try {
                    H264DecodeTest.decodeRawBitstream(f.getAbsolutePath(), outPath);
                    Log.i(TAG, name + " -> " + outPath + " 完了");
                } catch (IOException e) {
                    Log.e(TAG, "decode error: " + name, e);
                }
            }else{
                Log.i(TAG,   " NOT 264 FILE " );
            }
        }
    }
    // ★ デコード処理を開始するメソッド



    private int parsePort(String portStr) {
        try {
            int port = Integer.parseInt(portStr);
            if (port > 0 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException e) {
            // Ignore
        }
        return -1; // Invalid port
    }

    private void showToast(final String message) {
        mainHandler.post(() -> Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show());
    }

}