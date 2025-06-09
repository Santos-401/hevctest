package com.brison.hevctest;

/**
 * バイト配列をビット単位で読み出すユーティリティクラス。
 * H.264/HEVC の SPS/PPS 解析などで使われる Exp-Golomb コードもサポートします。
 */
public class BitReader {
    private final byte[] data;
    private int byteOffset;
    private int bitOffset;

    public BitReader(byte[] data) {
        this.data = data;
        this.byteOffset = 0;
        this.bitOffset = 0;
    }

    /**
     * まだ読み出せるビットがあるかを返す。
     * @return データ長未満なら true、そうでなければ false
     */
    public boolean hasMore() {
        return byteOffset < data.length;
    }

    /**
     * 1ビットだけ読み出し、0 or 1 を返す。
     * 末尾を超えた場合は -1 を返す。
     * @return 0 または 1、データ末尾なら -1
     */
    public int readBit() {
        if (!hasMore()) {
            return -1;
        }
        int value = (data[byteOffset] >> (7 - bitOffset)) & 0x01;
        bitOffset++;
        if (bitOffset == 8) {
            bitOffset = 0;
            byteOffset++;
        }
        return value;
    }

    /**
     * 指定したビット数(n)を連続して読み出し、ビッグエンディアンで結合して返す。
     * 途中で末尾を超えたら -1 を返す。
     * @param n 読み取るビット数 (1～32)
     * @return 結合結果、失敗時は -1
     */
    public int readBits(int n) {
        int value = 0;
        for (int i = 0; i < n; i++) {
            int bit = readBit();
            if (bit < 0) {
                return -1;
            }
            value = (value << 1) | bit;
        }
        return value;
    }

    /**
     * Exp-Golomb 符号 (符号なし) を読み出す。
     * 末尾を超えた場合は簡易的に 0 を返す。
     * @return デコード値
     */
    public int readUE() {
        int zeros = 0;
        while (true) {
            if (!hasMore()) {
                break;
            }
            int bit = readBit();
            if (bit == 1) {
                break;
            } else if (bit == 0) {
                zeros++;
            } else {
                // -1 が返ってきた場合はデータ末尾
                break;
            }
        }
        if (zeros == 0) {
            return 0;
        }
        int codeNum = (1 << zeros) - 1;
        int trailing = readBits(zeros);
        if (trailing < 0) {
            return codeNum;
        }
        return codeNum + trailing;
    }

    /**
     * Exp-Golomb 符号 (符号付き) を読み出す。
     * 末尾を超えた場合は簡易的に 0 を返す。
     * @return デコード値
     */
    public int readSE() {
        int ueVal = readUE();
        int sign = ((ueVal & 0x01) == 0) ? -1 : 1;
        return sign * ((ueVal + 1) / 2);
    }
}
