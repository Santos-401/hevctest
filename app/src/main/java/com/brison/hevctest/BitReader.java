package com.brison.hevctest;

/**
 * バイト配列からビット単位で読み出すユーティリティクラス
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

    public int readBit() {
        if (byteOffset >= data.length) {
            return 0;
        }
        int value = (data[byteOffset] >> (7 - bitOffset)) & 0x01;
        bitOffset++;
        if (bitOffset == 8) {
            bitOffset = 0;
            byteOffset++;
        }
        return value;
    }

    public int readBits(int n) {
        int value = 0;
        for (int i = 0; i < n; i++) {
            value = (value << 1) | readBit();
        }
        return value;
    }

    public int readUE() {
        int zeros = 0;
        while (readBit() == 0 && byteOffset < data.length) {
            zeros++;
        }
        int value = (1 << zeros) - 1 + readBits(zeros);
        return value;
    }

    public int readSE() {
        int ueVal = readUE();
        int sign = ((ueVal & 0x01) == 0) ? -1 : 1;
        return sign * ((ueVal + 1) / 2);
    }
}
