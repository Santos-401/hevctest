package com.brison.hevctest;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build; // 追加
import android.util.Log;
import android.util.Pair;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays; // 追加
import java.util.List;
import java.util.Locale; // 追加
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
                StringBuilder sb = new StringBuilder();
                for(int j=0; j < Math.min(nal.length, 10); j++) {
                    sb.append(String.format(Locale.US, "%02X ", nal[j]));
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
        // 3. 解像度、プロファイル、レベル取得 (H264ResolutionExtractor を利用)
        H264ResolutionExtractor.H264SPSInfo spsInfo = H264ResolutionExtractor.extractSPSInfo(csd[0]); // csd[0] (SPS NAL) を渡す
        int width;
        int height;
        int profileIdc = -1; // Keep for logging, but won't set in MediaFormat directly
        int levelIdc = -1;   // Keep for logging

        if (spsInfo != null) {
            width  = spsInfo.width;
            height = spsInfo.height;
            profileIdc = spsInfo.profile_idc;
            levelIdc = spsInfo.level_idc;
            Log.i(TAG, "Extracted SPS Info: Resolution: " + width + "x" + height + ", ProfileIDC: " + profileIdc + ", LevelIDC: " + levelIdc + ", VUI Present: " + spsInfo.vui_parameters_present_flag);
        } else {
            Log.e(TAG, "SPS Info extraction failed with H264ResolutionExtractor.extractSPSInfo(csd[0]).");
            Log.e(TAG, "Cannot proceed without valid SPS information for width, height.");
            return 0; 
        }

        // 4. MediaFormat作成
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        Log.i(TAG, "Initial MediaFormat with resolution: " + width + "x" + height);

        byte[] spsData = csd[0]; 
        byte[] ppsData = csd[1]; 

//        if (spsData != null) {
//            int spsOffset = getStartCodeOffset(spsData);
//            if (spsOffset != -1 && spsData.length > spsOffset) {
//                ByteBuffer spsBuffer = ByteBuffer.allocate(spsData.length - spsOffset);
//                spsBuffer.put(spsData, spsOffset, spsData.length - spsOffset);
//                spsBuffer.flip();
//                format.setByteBuffer("csd-0", spsBuffer);
//                Log.d(TAG, "Set csd-0 (SPS). Original NAL length: " + spsData.length + ", Offset: " + spsOffset + ", Buffer size: " + spsBuffer.remaining());
//                if (spsBuffer.remaining() > 0 && spsBuffer.remaining() <= 10) {
//                    byte[] csd0Bytes = new byte[spsBuffer.remaining()];
//                    spsBuffer.get(csd0Bytes);
//                    Log.d(TAG, "csd-0 (first " + csd0Bytes.length + " bytes): " + toHexString(csd0Bytes));
//                    spsBuffer.rewind();
//                }
//            } else {
//                Log.e(TAG, "Invalid SPS start code format or empty SPS payload, csd-0 not set. NAL Length: " + (spsData != null ? spsData.length : "null") + ", Offset: " + spsOffset);
//            }
//        } else {
//            Log.e(TAG, "SPS data is null, csd-0 not set.");
//        }
//
//        if (ppsData != null) {
//            int ppsOffset = getStartCodeOffset(ppsData);
//            if (ppsOffset != -1 && ppsData.length > ppsOffset) {
//                ByteBuffer ppsBuffer = ByteBuffer.allocate(ppsData.length - ppsOffset);
//                ppsBuffer.put(ppsData, ppsOffset, ppsData.length - ppsOffset);
//                ppsBuffer.flip();
//                format.setByteBuffer("csd-1", ppsBuffer);
//                Log.d(TAG, "Set csd-1 (PPS). Original NAL length: " + ppsData.length + ", Offset: " + ppsOffset + ", Buffer size: " + ppsBuffer.remaining());
//                if (ppsBuffer.remaining() > 0 && ppsBuffer.remaining() <= 10) {
//                    byte[] csd1Bytes = new byte[ppsBuffer.remaining()];
//                    ppsBuffer.get(csd1Bytes);
//                    Log.d(TAG, "csd-1 (first " + csd1Bytes.length + " bytes): " + toHexString(csd1Bytes));
//                    ppsBuffer.rewind();
//                }
//            } else {
//                Log.e(TAG, "Invalid PPS start code format or empty PPS payload, csd-1 not set. NAL Length: " + (ppsData != null ? ppsData.length : "null") + ", Offset: " + ppsOffset);
//            }
//        } else {
//            Log.e(TAG, "PPS data is null, csd-1 not set.");
//        }

        File outDir = new File(outputDirPath);
        if (!outDir.exists()) outDir.mkdirs();

        // --- Start of HW/SW Fallback Logic ---

        // 1. Attempt Hardware Decode
        String hwCodecName = selectHardwareCodecName(format);
        if (hwCodecName == null || MediaFormat.MIMETYPE_VIDEO_AVC.equals(hwCodecName)) {
            Log.i(TAG, "selectHardwareCodecName did not return a specific codec. Using MIME type: " + MediaFormat.MIMETYPE_VIDEO_AVC + " for HW attempt.");
            hwCodecName = MediaFormat.MIMETYPE_VIDEO_AVC; // Use MIME type, system will pick a decoder
        } else {
            Log.i(TAG, "selectHardwareCodecName returned specific HW codec: " + hwCodecName);
        }

        File hwOutFile = new File(outDir, inputFile.getName() + "_hw.yuv");
        Log.i(TAG, "Attempting Hardware Decode with: " + hwCodecName);
        Log.i(TAG, "Final MediaFormat for HW decoder configure: " + format.toString());

        try {
            return decodeWithCodec(nals, format, hwCodecName, hwOutFile, true, spsInfo); // isHardwareAttempt = true
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Hardware decode failed with codec: " + hwCodecName + ". Attempting software decode.", e);

            // 2. Attempt Software Decode as Fallback
            String swCodecName = selectSoftwareCodecName();
             if (swCodecName == null || MediaFormat.MIMETYPE_VIDEO_AVC.equals(swCodecName)) {
                Log.i(TAG, "selectSoftwareCodecName did not return a specific codec. Using MIME type: " + MediaFormat.MIMETYPE_VIDEO_AVC + " for SW attempt.");
                swCodecName = MediaFormat.MIMETYPE_VIDEO_AVC; // Use MIME type
            } else {
                Log.i(TAG, "selectSoftwareCodecName returned specific SW codec: " + swCodecName);
            }

            File swOutFile = new File(outDir, inputFile.getName() + "_sw.yuv");
            Log.i(TAG, "Attempting Software Decode with: " + swCodecName);
            // Create a new MediaFormat instance for software decoder if the original 'format' was modified by the HW attempt, 
            // or if we want to ensure SW decoder gets a pristine format object.
            // For now, assume 'format' can be reused or is appropriately reset/reconfigured in decodeWithCodec if needed.
            // However, CSD and color info should still be valid. The main concern would be if HW decoder added specific keys.
            Log.i(TAG, "Final MediaFormat for SW decoder configure: " + format.toString()); 

            try {
                return decodeWithCodec(nals, format, swCodecName, swOutFile, false, spsInfo); // isHardwareAttempt = false
            } catch (IOException | RuntimeException swException) {
                Log.e(TAG, "Software decode also failed with codec: " + swCodecName, swException);
                if (swException instanceof IOException) {
                    throw (IOException) swException;
                } else {
                    throw new IOException("Software decode failed after hardware decode failed due to RuntimeException", swException);
                }
            }
        }
        // --- End of HW/SW Fallback Logic ---
    }

    /**
     * 指定コーデックでデコード処理を実行し、YUVをファイル出力
     */
    private static int decodeWithCodec(
            List<byte[]> nals,
            MediaFormat format,
            String codecNameOrType, // Can be specific name or MIME type
            File outputFile,
            boolean isHardwareAttempt, 
            H264ResolutionExtractor.H264SPSInfo spsInfo 
    ) throws IOException {
        MediaCodec decoder = null;
        AtomicInteger frameCount = new AtomicInteger(0);

        Log.i(TAG, "H.264: Decoding with " + (isHardwareAttempt ? "HW" : "SW") + " using: " + codecNameOrType + ", Total NAL units: " + nals.size());
        MediaFormat currentFormat = new MediaFormat(format); // Create a copy to avoid modifying the original shared format object

        try {
            if (codecNameOrType.contains(".")) { 
                Log.i(TAG, "Creating MediaCodec by name: " + codecNameOrType);
                decoder = MediaCodec.createByCodecName(codecNameOrType);
            } else { 
                Log.i(TAG, "Creating MediaCodec by type: " + codecNameOrType);
                decoder = MediaCodec.createDecoderByType(codecNameOrType);
            }
            Log.i(TAG, "Configuring decoder with format: " + currentFormat.toString());

            boolean vuiPresent = false; 
            if (spsInfo != null && spsInfo.vui_parameters_present_flag) { 
                 vuiPresent = true;
            }
            Log.i(TAG, "VUI parameters " + (vuiPresent ? "are present" : "are NOT present (or spsInfo unavailable)"));

//            if (!vuiPresent) {
//                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
//                    if (!currentFormat.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
//                        currentFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
//                        Log.i(TAG, "Set default KEY_COLOR_STANDARD to BT709 because VUI is not present.");
//                    }
//                    if (!currentFormat.containsKey(MediaFormat.KEY_COLOR_RANGE)) {
//                        currentFormat.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
//                        Log.i(TAG, "Set default KEY_COLOR_RANGE to LIMITED because VUI is not present.");
//                    }
//                    if (!currentFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
//                        currentFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
//                        Log.i(TAG, "Set default KEY_COLOR_TRANSFER to SDR_VIDEO because VUI is not present.");
//                    }
//                } else {
//                    Log.w(TAG, "Cannot explicitly set default color format keys on API < 24 (VUI not present).");
//                }
//            }

            decoder.configure(currentFormat, null, null, 0);
            Log.i(TAG, "Decoder configured and starting.");
            decoder.start();

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean inputEOS = false;
                boolean outputEOS = false;
                int nalUnitIdx = 0;
                long presentationTimeUs = 0;

                while (!outputEOS) {
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
                                int nalType = -1;
                                int nalHeaderOffset = getStartCodeOffset(nal); 
                                if (nalHeaderOffset != -1 && nal.length > nalHeaderOffset) {
                                    nalType = nal[nalHeaderOffset] & 0x1F;
                                }
                                Log.d(TAG, "H.264: Queuing NAL " + nalUnitIdx + "/" + nals.size() + ", Type: " + nalType + ", Len: " + nal.length + ", PTS: " + presentationTimeUs);
                                ByteBuffer ib = decoder.getInputBuffer(inIndex);
                                if (ib != null) {
                                    ib.clear();
                                    ib.put(nal);
                                    decoder.queueInputBuffer(inIndex, 0, nal.length, presentationTimeUs, 0);
                                    presentationTimeUs += 1000000L / 30; // 30 FPS
                                } else {
                                    Log.e(TAG, "H.264: getInputBuffer returned null for index " + inIndex);
                                }
                                nalUnitIdx++;
                            } else {
                                Log.i(TAG, "H.264: All NALs sent, queuing EOS for input.");
                                decoder.queueInputBuffer(inIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputEOS = true;
                            }
                        }
                    }

                    int outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                    switch (outIndex) {
                        case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                            MediaFormat newFormat = decoder.getOutputFormat();
                            Log.i(TAG, "H.264: Output format changed to: " + newFormat);
                            break;
                        case MediaCodec.INFO_TRY_AGAIN_LATER:
                            break;
                        case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                            Log.d(TAG, "H.264: Output buffers changed (deprecated).");
                            break;
                        default:
                            if (outIndex < 0) {
                                Log.w(TAG, "H.264: dequeueOutputBuffer returned unexpected index: " + outIndex);
                                break;
                            }
                            Log.d(TAG, "H.264: Output Buffer Idx: " + outIndex + ", Size: " + info.size + ", Flags: " + info.flags + ", PTS: " + info.presentationTimeUs);
                            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                Log.d(TAG, "H.264: Skipping codec config buffer.");
                            } else if (info.size > 0) {
                                ByteBuffer outBuf = decoder.getOutputBuffer(outIndex);
                                if (outBuf != null) {
                                    Log.i(TAG, "H.264: Writing " + info.size + " bytes YUV for PTS " + info.presentationTimeUs);
                                    byte[] yuvData = new byte[info.size];
                                    outBuf.get(yuvData);
                                    fos.write(yuvData);
                                    frameCount.incrementAndGet();
                                } else {
                                    Log.w(TAG, "H.264: Output buffer was null for index " + outIndex + " but info.size was " + info.size);
                                }
                            } else if (info.size == 0 && (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0) {
                                Log.w(TAG, "H.264: Output buffer index " + outIndex + " has size 0 but not EOS. Flags: " + info.flags);
                            }

                            decoder.releaseOutputBuffer(outIndex, false);
                            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                Log.i(TAG, "H.264: Output EOS reached.");
                                outputEOS = true;
                            }
                            break;
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "H.264: MediaCodec config/op failed (IllegalArgumentException): " + e.getMessage(), e);
            throw new IOException("H.264: MediaCodec config/op failed (IllegalArgumentException): " + e.getMessage(), e);
        } catch (MediaCodec.CodecException e) {
            Log.e(TAG, "H.264: MediaCodec op failed (CodecException): " + e.getMessage());
            Log.e(TAG, "  DiagnosticInfo: " + e.getDiagnosticInfo());
            Log.e(TAG, "  Is Transient: " + e.isTransient());
            Log.e(TAG, "  Is Recoverable: " + e.isRecoverable());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { 
                Log.e(TAG, "  Error Code: 0x" + Integer.toHexString(e.getErrorCode()));
            }
            throw new IOException("H.264: MediaCodec op failed (CodecException): " + e.getMessage(), e);
        } catch (IllegalStateException e) {
            Log.e(TAG, "H.264: MediaCodec op failed (IllegalStateException): " + e.getMessage(), e);
            throw new IOException("H.264: MediaCodec op failed (IllegalStateException): " + e.getMessage(), e);
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

    private static String selectHardwareCodecName(MediaFormat format) {
        Log.d(TAG, "selectHardwareCodecName called with format: " + (format != null ? format.toString() : "null")); // Added for debugging
        MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
        String mimeType = format.getString(MediaFormat.KEY_MIME);
        if (mimeType == null) return MediaFormat.MIMETYPE_VIDEO_AVC; 

        for (MediaCodecInfo info : list.getCodecInfos()) {
            boolean isHardware = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isHardware = info.isHardwareAccelerated();
            } else {
                String lname = info.getName().toLowerCase(Locale.US);
                if (!lname.startsWith("omx.google.") && !lname.startsWith("c2.android.")) {
                    isHardware = true; 
                }
            }
            if (info.isEncoder() || !isHardware) continue;

            try {
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mimeType);
                if (caps == null) continue;

                if (!caps.isFormatSupported(format)) {
                     // Log detailed reason if format is not supported by a potential HW codec
                    Log.d(TAG, "selectHardwareCodecName: Codec " + info.getName() + " does not support format: " + format.toString());
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        MediaFormat defaultFormat = caps.getDefaultFormat();
                        Log.d(TAG, "selectHardwareCodecName: Codec " + info.getName() + " default format: " + defaultFormat.toString());
                        // You could add more detailed checks here, e.g. comparing specific keys
                    }
                    continue;
                }
                Log.i(TAG, "selectHardwareCodecName: Found potential HW codec: " + info.getName() + " that supports the format.");
                return info.getName(); 
            } catch (IllegalArgumentException e) {
                // MimeType not supported by this codec
            }
        }
        Log.w(TAG, "selectHardwareCodecName: No specific HW codec found supporting the format " + format + ". Will use MIME type for createDecoderByType.");
        return mimeType;
    }

    private static String selectHardwareCodecName(int width, int height) {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        return selectHardwareCodecName(format);
    }

    private static String selectSoftwareCodecName() {
        Log.d(TAG, "selectSoftwareCodecName called"); // Added for debugging
        MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
        String preferredCodec = null;
        String c2Codec = null;

        for (MediaCodecInfo info : list.getCodecInfos()) {
            boolean isSoftware = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isSoftware = !info.isHardwareAccelerated();
            } else {
                String lname = info.getName().toLowerCase(Locale.US);
                if (lname.startsWith("omx.google.") || lname.startsWith("c2.android.")) {
                    isSoftware = true;
                }
            }
            if (info.isEncoder() || !isSoftware) continue;

            String name = info.getName().toLowerCase(Locale.US);
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_AVC)) {
                    if (name.equals("omx.google.h264.decoder")) {
                        Log.i(TAG, "selectSoftwareCodecName: Found preferred SW codec: " + info.getName());
                        return info.getName(); 
                    }
                    if (name.startsWith("c2.android.") && name.contains("avc.decoder")) {
                        if (c2Codec == null) c2Codec = info.getName(); 
                    }
                    if (name.startsWith("omx.google.") && name.contains("avc.decoder")) {
                         if (preferredCodec == null) preferredCodec = info.getName();
                    }
                    break; 
                }
            }
        }
        if (c2Codec != null) { 
            Log.i(TAG, "selectSoftwareCodecName: Selected SW codec (c2.android): " + c2Codec);
            return c2Codec;
        }
        if (preferredCodec != null) { 
             Log.i(TAG, "selectSoftwareCodecName: Selected SW codec (omx.google generic): " + preferredCodec);
            return preferredCodec;
        }

        Log.w(TAG, "selectSoftwareCodecName: No specific SW codec (omx.google/c2.android) found for AVC. Will use MIME type.");
        return MediaFormat.MIMETYPE_VIDEO_AVC; 
    }

    private static List<byte[]> splitNalUnits(byte[] data) {
        List<byte[]> nals = new ArrayList<>();
        if (data == null || data.length < 3) {
            Log.w(TAG, "splitNalUnits: Data is null or too short.");
            return nals;
        }
        int len = data.length;
        int currentNalStartIdx = -1;
        int currentNalPrefixLen = 0;
        for (int i = 0; i + 2 < len; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (i + 3 < len && data[i + 2] == 0 && data[i + 3] == 1) { // 00 00 00 01
                    currentNalStartIdx = i; currentNalPrefixLen = 4; break;
                } else if (data[i + 2] == 1) { // 00 00 01
                    currentNalStartIdx = i; currentNalPrefixLen = 3; break;
                }
            }
        }
        if (currentNalStartIdx == -1) {
            Log.w(TAG, "splitNalUnits: No NAL start codes found.");
            return nals;
        }
        while (currentNalStartIdx < len) {
            int nextNalStartIdx = -1;
            int nextNalPrefixLen = 0;
            for (int i = currentNalStartIdx + currentNalPrefixLen; i + 2 < len; i++) {
                if (data[i] == 0 && data[i + 1] == 0) {
                    if (i + 3 < len && data[i + 2] == 0 && data[i + 3] == 1) {
                        nextNalStartIdx = i; nextNalPrefixLen = 4; break;
                    } else if (data[i + 2] == 1) {
                        nextNalStartIdx = i; nextNalPrefixLen = 3; break;
                    }
                }
            }
            int currentNalEndIdx = (nextNalStartIdx != -1) ? nextNalStartIdx : len;
            int nalLength = currentNalEndIdx - currentNalStartIdx;
            if (nalLength > 0) {
                byte[] nal = new byte[nalLength];
                System.arraycopy(data, currentNalStartIdx, nal, 0, nalLength);
                nals.add(nal);
            } else {
                Log.w(TAG, "splitNalUnits: Found NAL unit with zero length at offset " + currentNalStartIdx);
            }
            if (nextNalStartIdx != -1) {
                currentNalStartIdx = nextNalStartIdx;
                currentNalPrefixLen = nextNalPrefixLen;
            } else {
                break;
            }
        }
        Log.i(TAG, "splitNalUnits: Split into " + nals.size() + " NAL units.");
        return nals;
    }

    private static byte[][] extractSpsAndPps(List<byte[]> nals) {
        byte[] sps = null, pps = null;
        for (byte[] nal : nals) {
            if (nal == null || nal.length < 4) continue; 
            int headerOffset = getStartCodeOffset(nal);
            if (headerOffset == -1 || nal.length <= headerOffset) continue;

            int nalType = nal[headerOffset] & 0x1F;
            if (nalType == 7) { // SPS
                if (sps == null) { 
                    sps = nal;
                    Log.d(TAG, "extractSpsAndPps: Found SPS, length " + nal.length);
                } else {
                    Log.w(TAG, "extractSpsAndPps: Found multiple SPS NALs, using the first one.");
                }
            } else if (nalType == 8) { // PPS
                if (pps == null) { 
                    pps = nal;
                    Log.d(TAG, "extractSpsAndPps: Found PPS, length " + nal.length);
                } else {
                    Log.w(TAG, "extractSpsAndPps: Found multiple PPS NALs, using the first one.");
                }
            }
        }
        if (sps == null) Log.e(TAG, "extractSpsAndPps: SPS NAL unit not found in the provided NAL list.");
        if (pps == null) Log.e(TAG, "extractSpsAndPps: PPS NAL unit not found in the provided NAL list.");
        return new byte[][]{sps, pps};
    }

    private static int getStartCodeOffset(byte[] nalUnit) {
        if (nalUnit == null || nalUnit.length < 3) return -1;
        if (nalUnit.length >= 4 && nalUnit[0] == 0 && nalUnit[1] == 0 && nalUnit[2] == 0 && nalUnit[3] == 1) return 4;
        if (nalUnit[0] == 0 && nalUnit[1] == 0 && nalUnit[2] == 1) return 3;
        return -1; 
    }

    private static void mapProfileAndLevel(MediaFormat format, int profileIdc, int levelIdc) {
        // This method is currently not called due to changes in decodeRawBitstream
        // It's kept for reference or future use if explicit profile/level setting is needed.
        int avcProfile = 0;
        int avcLevel = 0;
        Log.d(TAG, "mapProfileAndLevel: Input profileIdc=" + profileIdc + ", levelIdc=" + levelIdc);

        switch (profileIdc) {
            case 66:  avcProfile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline; break;
            case 77:  avcProfile = MediaCodecInfo.CodecProfileLevel.AVCProfileMain; break;
            case 88:  avcProfile = MediaCodecInfo.CodecProfileLevel.AVCProfileExtended; break;
            case 100: avcProfile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh; break;
            // ... (other cases)
            default: Log.w(TAG, "Unrecognized H.264 profile_idc: " + profileIdc); break;
        }

        switch (levelIdc) {
            case 10: avcLevel = MediaCodecInfo.CodecProfileLevel.AVCLevel1; break;
            // ... (other cases)
            case 21: avcLevel = MediaCodecInfo.CodecProfileLevel.AVCLevel21; break;
            default: Log.w(TAG, "Unrecognized H.264 level_idc: " + levelIdc); break;
        }

        if (avcProfile != 0) {
            format.setInteger(MediaFormat.KEY_PROFILE, avcProfile);
            Log.i(TAG, "Set H.264 KEY_PROFILE to " + avcProfile + " (mapped from profile_idc " + profileIdc + ")");
        } else {
            Log.w(TAG, "KEY_PROFILE not set as profile_idc " + profileIdc + " was not mapped.");
        }
        if (avcLevel != 0) {
            format.setInteger(MediaFormat.KEY_LEVEL, avcLevel);
            Log.i(TAG, "Set H.264 KEY_LEVEL to " + avcLevel + " (mapped from level_idc " + levelIdc + ")");
        } else {
            Log.w(TAG, "KEY_LEVEL not set as level_idc " + levelIdc + " was not mapped.");
        }
    }

    private static String toHexString(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02X ", b));
        }
        return sb.toString().trim();
    }
}
