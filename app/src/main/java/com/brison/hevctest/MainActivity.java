package com.brison.hevctest;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * メイン画面。ファイル一覧表示＆デコード実行ボタンを持つ。
 * デコード処理はバックグラウンドスレッド（ExecutorService）で実行するよう修正済み。
 */
public class MainActivity extends AppCompatActivity {
    private ListView listView;
    private Button decodeButton;
    private ProgressBar progressBar;
    private ArrayAdapter<String> adapter;
    private ExecutorService executor; // デコード専用スレッドプール

    private static final String TAG = "MainActivity";

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        listView = findViewById(R.id.listView);
        decodeButton = findViewById(R.id.decodeButton);
        progressBar = findViewById(R.id.progressBar);
        progressBar.setVisibility(View.GONE);

        executor = Executors.newSingleThreadExecutor();

        listInternalFiles();

        decodeButton.setOnClickListener(v -> {
            // UI をブロックしないため、バックグラウンドで実行
            progressBar.setVisibility(View.VISIBLE);
            decodeButton.setEnabled(false);
            executor.execute(() -> {
                processAll();
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    decodeButton.setEnabled(true);
                    Toast.makeText(this, "すべてのデコードが完了しました", Toast.LENGTH_SHORT).show();
                });
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    /**
     * アプリの内部ストレージ内の .264/.bit ファイルを一覧表示する
     */
    private void listInternalFiles() {
        File dir = getFilesDir();
        File[] files = dir.listFiles();
        if (files == null) return;

        ArrayList<String> list = new ArrayList<>();
        for (File f : files) {
            String name = f.getName();
            if (name.endsWith(".264") || name.endsWith(".bit")) {
                list.add(name);
            }
        }
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, list);
        listView.setAdapter(adapter);
    }

    /**
     * 内部ストレージの .264/.bit ファイルをすべてデコードし、.yuv 出力する
     */
    private void processAll() {
        File dir = getFilesDir();
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            String name = f.getName();
            if (name.endsWith(".264") || name.endsWith(".bit")) {
                String outPath = new File(dir, name + ".yuv").getAbsolutePath();
                try {
                    H264DecodeTest.decodeRawBitstream(f.getAbsolutePath(), outPath);
                    Log.i(TAG, name + " -> " + outPath + " 完了");
                } catch (Exception e) {
                    Log.e(TAG, "decode error: " + name, e);
                }
            } else {
                Log.i(TAG, "NOT 264/BIT FILE: " + name);
            }
        }
    }
}
