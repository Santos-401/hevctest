package com.brison.hevctest;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;
import android.widget.ProgressBar;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "com.brison.hevctest"; // Changed example to hevctest
    private Button buttonRefreshFileList;
    private ListView listViewFiles;
    private ExecutorService executorService;
    private Handler mainHandler;
    private ArrayAdapter<String> fileListAdapter;
    private List<DocumentFileWrapper> currentFiles; // Can hold internal or USB files
    private Button buttonStartDecord;
    private ProgressBar progressBar;
    private Button buttonSelectUsbStorage;

    private static final int REQUEST_CODE_OPEN_TREE = 1001;
    private Uri usbTreeUri; // Root URI for selected USB directory

    // Helper class to wrap File or DocumentFile
    // NOTE: This class should ideally be a static nested class or a separate public class.
    // For brevity in this diff, it's an inner class. Consider refactoring.
    static class DocumentFileWrapper {
        private File internalFile;
        private DocumentFile usbDocumentFile;
        private Context context; // Needed for DocumentFile.fromSingleUri if only URI is known initially

        public DocumentFileWrapper(Context context, File internalFile) {
            this.context = context.getApplicationContext();
            this.internalFile = internalFile;
        }

        public DocumentFileWrapper(Context context, DocumentFile usbDocumentFile) {
            this.context = context.getApplicationContext();
            this.usbDocumentFile = usbDocumentFile;
        }

        public boolean isUsbFile() {
            return usbDocumentFile != null;
        }

        public boolean isInternalFile() {
            return internalFile != null;
        }

        public String getName() {
            if (usbDocumentFile != null) return usbDocumentFile.getName();
            if (internalFile != null) return internalFile.getName();
            return "unknown";
        }

        public long getLength() {
            if (usbDocumentFile != null) return usbDocumentFile.length();
            if (internalFile != null) return internalFile.length();
            return 0;
        }

        public Uri getUri() {
            if (usbDocumentFile != null) return usbDocumentFile.getUri();
            if (internalFile != null) return Uri.fromFile(internalFile);
            return null;
        }

        public String getAbsolutePath() { // Only relevant for internal files
            if (internalFile != null) return internalFile.getAbsolutePath();
            return null;
        }

        public DocumentFile getDocumentFile() { // Only for USB files
            return usbDocumentFile;
        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.d(TAG, "onCreate started");

        buttonRefreshFileList = findViewById(R.id.buttonListLocalFiles); // Ensure this ID is in your layout
        listViewFiles = findViewById(R.id.listViewLocalFiles); // Ensure this ID is in your layout
        buttonStartDecord = findViewById(R.id.startButton);
        progressBar = findViewById(R.id.progressBar);
        buttonSelectUsbStorage = findViewById(R.id.buttonSelectUsbStorage); // ADD THIS BUTTON TO YOUR LAYOUT XML

        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        currentFiles = new ArrayList<>();
        fileListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        listViewFiles.setAdapter(fileListAdapter);

        buttonRefreshFileList.setOnClickListener(v -> {
            if (usbTreeUri != null) {
                listUsbFiles();
            } else {
                listInternalFiles();
            }
        });

        buttonSelectUsbStorage.setOnClickListener(v -> launchUsbDirectorySelection());

        buttonStartDecord.setOnClickListener(v -> {
            setProcessingState(true);
            executorService.execute(() -> {
                try {
                    processSelectedFiles();
                    mainHandler.post(() -> Toast.makeText(MainActivity.this, "処理が完了しました", Toast.LENGTH_SHORT).show());
                } catch (IOException e) {
                    Log.e(TAG, "デコード処理中にエラーが発生", e);
                    mainHandler.post(() -> Toast.makeText(MainActivity.this, "エラー: " + e.getMessage(), Toast.LENGTH_LONG).show());
                } finally {
                    mainHandler.post(() -> {
                        setProcessingState(false);
                        // Refresh list to reflect new YUV files (if any visible)
                        if (usbTreeUri != null) listUsbFiles(); else listInternalFiles();
                    });
                }
            });
        });

        listInternalFiles(); // Initial listing from internal storage
        buttonStartDecord.setEnabled(!currentFiles.isEmpty());
        Log.d(TAG, "onCreate finished");
    }

    private void launchUsbDirectorySelection() {
        StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
        List<StorageVolume> volumes = sm.getStorageVolumes();
        StorageVolume removableVolume = null;
        for (StorageVolume vol : volumes) {
            if (vol.isRemovable()) {
                removableVolume = vol;
                break;
            }
        }

        if (removableVolume != null) {
            Intent intent = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                intent = removableVolume.createOpenDocumentTreeIntent();
            }
            startActivityForResult(intent, REQUEST_CODE_OPEN_TREE);
        } else {
            Toast.makeText(this, "リムーバブルUSBストレージが見つかりません。", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_OPEN_TREE && resultCode == Activity.RESULT_OK) {
            if (data != null && data.getData() != null) {
                usbTreeUri = data.getData();
                final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                try {
                    getContentResolver().takePersistableUriPermission(usbTreeUri, takeFlags);
                    Log.i(TAG, "Access granted to USB directory: " + usbTreeUri);
                    Toast.makeText(this, "USBストレージ選択完了", Toast.LENGTH_SHORT).show();
                    listUsbFiles(); // List files from the selected USB directory
                } catch (SecurityException e) {
                    Log.e(TAG, "Failed to take persistable URI permission: " + usbTreeUri, e);
                    Toast.makeText(this, "USBストレージへのアクセス許可に失敗", Toast.LENGTH_LONG).show();
                    usbTreeUri = null; // Reset URI
                    listInternalFiles(); // Fallback to internal
                }
            } else {
                Toast.makeText(this, "USBストレージ選択データなし", Toast.LENGTH_LONG).show();
                listInternalFiles(); // Fallback
            }
        }
    }

    private void listInternalFiles() {
        Log.d(TAG, "listInternalFiles called");
        currentFiles.clear();
        File internalDir = getFilesDir();
        if (!internalDir.exists()) {
            updateStatus("内部ストレージ(/files)が見つかりません");
            return;
        }
        updateStatus("スマホ内ファイル検索中: " + internalDir.getAbsolutePath());
        File[] files = internalDir.listFiles(f -> f.isFile() && (f.getName().endsWith(".264") || f.getName().endsWith(".h264") || f.getName().endsWith(".avc") || f.getName().endsWith(".jsv") || f.getName().endsWith(".jvt") || f.getName().endsWith(".bit") || f.getName().endsWith(".bin")));

        if (files != null) {
            Arrays.sort(files, (f1, f2) -> f1.getName().compareTo(f2.getName()));
            for (File file : files) {
                currentFiles.add(new DocumentFileWrapper(this, file));
            }
        }
        displayCurrentFiles();
        if (currentFiles.isEmpty()) {
            updateStatus("スマホ内に処理対象ファイルが見つかりません");
        } else {
            updateStatus(currentFiles.size() + " 個のローカルファイル");
        }
    }

    private void listUsbFiles() {
        Log.d(TAG, "listUsbFiles called for URI: " + usbTreeUri);
        if (usbTreeUri == null) {
            updateStatus("USBストレージが選択されていません。");
            listInternalFiles(); // Fallback to internal if USB URI is lost
            return;
        }
        currentFiles.clear();
        DocumentFile selectedDir = DocumentFile.fromTreeUri(this, usbTreeUri);
        if (selectedDir == null || !selectedDir.isDirectory() || !selectedDir.canRead()) {
            updateStatus("選択されたUSBディレクトリにアクセスできません。");
            Log.w(TAG, "Cannot access selected USB directory: " + usbTreeUri);
            usbTreeUri = null; // Invalidate URI
            listInternalFiles(); // Fallback
            return;
        }

        updateStatus("USBファイル検索中: " + selectedDir.getName());
        for (DocumentFile file : selectedDir.listFiles()) {
            String fileName = file.getName();
            if (file.isFile() && fileName != null &&
                    (fileName.endsWith(".264") || fileName.endsWith(".h264") || fileName.endsWith(".avc") ||
                            fileName.endsWith(".jsv") || fileName.endsWith(".jvt") ||
                            fileName.endsWith(".bit") || fileName.endsWith(".bin"))) {
                currentFiles.add(new DocumentFileWrapper(this, file));
            }
        }
        // Sort USB files by name for consistent display
        currentFiles.sort((dfw1, dfw2) -> dfw1.getName().compareToIgnoreCase(dfw2.getName()));

        displayCurrentFiles();
        if (currentFiles.isEmpty()) {
            updateStatus("選択されたUSBディレクトリに処理対象ファイルが見つかりません。");
        } else {
            updateStatus(currentFiles.size() + " 個のUSBファイル");
        }
    }

    private void displayCurrentFiles() {
        fileListAdapter.clear();
        List<String> displayNames = new ArrayList<>();
        for (DocumentFileWrapper wrapper : currentFiles) {
            displayNames.add(wrapper.getName() + " (" + formatFileSize(wrapper.getLength()) + (wrapper.isUsbFile() ? " [USB]" : " [App]")+")");
        }
        fileListAdapter.addAll(displayNames);
        fileListAdapter.notifyDataSetChanged();
        buttonStartDecord.setEnabled(!currentFiles.isEmpty());
    }

    private String formatFileSize(long size) {
        if (size <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        if (digitGroups >= units.length) digitGroups = units.length - 1; // Prevent ArrayOutOfBounds
        return String.format("%.1f %s", size / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    private void updateStatus(String message) {
        Log.i(TAG, "Status: " + message);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); // Simple toast for status
    }

    private void updateStatusOnUiThread(final String message) {
        mainHandler.post(() -> updateStatus(message));
    }
    /**
     * 処理中/待機中のUI状態を切り替えるメソッド
     * @param isProcessing trueの場合、プログレスバーを表示しボタンを無効化
     */
    private void setProcessingState(boolean isProcessing) {
        if (isProcessing) {
            progressBar.setVisibility(View.VISIBLE);
            buttonRefreshFileList.setEnabled(false);
            buttonStartDecord.setEnabled(false);
            buttonSelectUsbStorage.setEnabled(false);
        } else {
            progressBar.setVisibility(View.GONE);
            buttonRefreshFileList.setEnabled(true);
            buttonStartDecord.setEnabled(!currentFiles.isEmpty());
            buttonSelectUsbStorage.setEnabled(true);
        }
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

    private void processSelectedFiles() throws IOException {
        if (currentFiles.isEmpty()) {
            updateStatusOnUiThread("処理するファイルがありません。");
            return;
        }

        DocumentFile outputParentDirDocFile = null;
        String internalOutputParentDirPath = null;

        if (usbTreeUri != null) { // Processing USB files
            DocumentFile rootDir = DocumentFile.fromTreeUri(this, usbTreeUri);
            if (rootDir == null || !rootDir.isDirectory() || !rootDir.canWrite()) {
                throw new IOException("USBルートディレクトリに書き込めません: " + usbTreeUri);
            }
            String resultFolderName = "result_usb";
            outputParentDirDocFile = rootDir.findFile(resultFolderName);
            if (outputParentDirDocFile == null) {
                outputParentDirDocFile = rootDir.createDirectory(resultFolderName);
                if (outputParentDirDocFile == null) {
                    throw new IOException("USBに '" + resultFolderName + "' ディレクトリを作成できませんでした。");
                }
            } else if (!outputParentDirDocFile.isDirectory()) {
                throw new IOException("USBの '" + resultFolderName + "' はディレクトリではありません。");
            }
            Log.i(TAG, "USB出力ディレクトリ: " + outputParentDirDocFile.getUri());
        } else { // Processing internal files
            File internalAppDir = getFilesDir();
            File internalResultDir = new File(internalAppDir, "result");
            if (!internalResultDir.exists() && !internalResultDir.mkdirs()) {
                throw new IOException("内部ストレージに 'result_internal' ディレクトリを作成できませんでした。");
            }
            internalOutputParentDirPath = internalResultDir.getAbsolutePath();
            Log.i(TAG, "内部出力ディレクトリ: " + internalOutputParentDirPath);
        }

        for (DocumentFileWrapper wrapper : currentFiles) {
            String inputFileName = wrapper.getName();
            if (inputFileName == null || inputFileName.equals("unknown")) {
                Log.w(TAG, "Skipping file with unknown name.");
                continue;
            }
            String outputFileName = inputFileName + ".yuv"; // Basic naming convention

            try {
                if (wrapper.isUsbFile()) {
                    if (outputParentDirDocFile == null) throw new IOException("USB出力ディレクトリがありません。");
                    Uri inputFileUri = wrapper.getUri();
                    if (inputFileUri == null) {
                        Log.e(TAG, "USB Input File URI is null for " + inputFileName);
                        continue;
                    }

                    DocumentFile existingOutputFile = outputParentDirDocFile.findFile(outputFileName);
                    if (existingOutputFile != null && existingOutputFile.exists()) {
                        if (!existingOutputFile.delete()) {
                            Log.w(TAG, "既存のUSB出力ファイル " + outputFileName + " を削除できませんでした。処理を続行します。");
                        }
                    }
                    DocumentFile outputFileDoc = outputParentDirDocFile.createFile("application/octet-stream", outputFileName);
                    if (outputFileDoc == null || !outputFileDoc.canWrite()) {
                        throw new IOException("USBに出力ファイルを作成または書き込みできません: " + outputFileName);
                    }
                    Uri outputFileUri = outputFileDoc.getUri();

                    Log.i(TAG, "Processing USB: " + inputFileName + " -> " + outputFileName);
                    if (inputFileName.endsWith(".264") || inputFileName.endsWith(".h264") || inputFileName.endsWith(".avc") || inputFileName.endsWith(".jsv") || inputFileName.endsWith(".jvt")|| inputFileName.endsWith(".26l")) {
                        // This will be changed to a method that accepts streams/URIs
                        H264DecodeTest.decodeStreamToStream(this, inputFileUri, outputFileUri); // Target method
                    } else if (inputFileName.endsWith(".bit") || inputFileName.endsWith(".bin")) {
                        HevcDecoder decoder = new HevcDecoder();
                        // This will be changed to a method that accepts streams/URIs
                        decoder.decodeUriToUri(this, inputFileUri, outputFileUri); // Target method
                    }
                    updateStatusOnUiThread(inputFileName + " -> " + outputFileName + " [USB] 完了");

                } else if (wrapper.isInternalFile()) { // Internal file processing
                    if (internalOutputParentDirPath == null) throw new IOException("内部出力ディレクトリパスがありません。");
                    String inputPath = wrapper.getAbsolutePath();
                    if (inputPath == null) {
                        Log.e(TAG, "Internal file path is null for " + inputFileName);
                        continue;
                    }
                    String outputPath = new File(internalOutputParentDirPath, outputFileName).getAbsolutePath();

                    Log.i(TAG, "Processing Internal: " + inputFileName + " -> " + outputFileName);
                    if (inputFileName.endsWith(".264") || inputFileName.endsWith(".h264") || inputFileName.endsWith(".avc") || inputFileName.endsWith(".jsv") || inputFileName.endsWith(".jvt")|| inputFileName.endsWith(".26l")) {
                        // Original method: H264DecodeTest.decodeRawBitstream(String inputPath, String outputDirPath)
                        // outputDirPath is the parent directory. The method constructs filename.yuv inside.
                        H264DecodeTest.decodeRawBitstream(inputPath, internalOutputParentDirPath);
                    } else if (inputFileName.endsWith(".bit") || inputFileName.endsWith(".bin")) {
                        HevcDecoder decoder = new HevcDecoder();
                        // Original method: decoder.decodeToYuv(String inputPath, String outputPath)
                        decoder.decodeToYuv(inputPath, outputPath);
                    }
                    updateStatusOnUiThread(inputFileName + " -> " + outputFileName + " [App] 完了");
                }
            } catch (Exception e) { // Catch exceptions per file to allow others to process
                Log.e(TAG, "ファイル処理エラー " + inputFileName + ": " + e.getMessage(), e);
                updateStatusOnUiThread("エラー " + inputFileName + ": " + e.getMessage());
            }
        }
    }
}

