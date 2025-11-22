package com.electricdreams.shellshock.nostr;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Minimal Bech32 encoder/decoder for NIP-19 usage (nsec/npub/nprofile).
 *
 * Supports Bech32 (not Bech32m), no segwit-specific logic.
 */
public final class Bech32 {

    public static class Bech32Data {
        public final String hrp;
        public final byte[] data;

        public Bech32Data(String hrp, byte[] data) {
            this.hrp = hrp;
            this.data = data;
        }
    }

    private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final int[] GENERATOR = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};

    private Bech32() {}

    private static int polymod(final byte[] values) {
        int chk = 1;
        for (byte v : values) {
            int top = (chk >>> 25) & 0xff;
            chk = (chk & 0x1ffffff) << 5 ^ (v & 0xff);
            for (int i = 0; i < 5; i++) {
                if (((top >>> i) & 1) != 0) {
                    chk ^= GENERATOR[i];
                }
            }
        }
        return chk;
    }

    private static byte[] hrpExpand(final String hrp) {
        int len = hrp.length();
        byte[] ret = new byte[len * 2 + 1];
        for (int i = 0; i < len; i++) {
            int c = hrp.charAt(i) & 0x7f;
            ret[i] = (byte) (c >>> 5);
        }
        ret[len] = 0;
        for (int i = 0; i < len; i++) {
            int c = hrp.charAt(i) & 0x7f;
            ret[len + 1 + i] = (byte) (c & 0x1f);
        }
        return ret;
    }

    private static boolean verifyChecksum(final String hrp, final byte[] data) {
        byte[] hrpExpanded = hrpExpand(hrp);
        byte[] values = new byte[hrpExpanded.length + data.length];
        System.arraycopy(hrpExpanded, 0, values, 0, hrpExpanded.length);
        System.arraycopy(data, 0, values, hrpExpanded.length, data.length);
        return polymod(values) == 1;
    }

    private static byte[] createChecksum(final String hrp, final byte[] data) {
        byte[] hrpExpanded = hrpExpand(hrp);
        byte[] values = new byte[hrpExpanded.length + data.length + 6];
        System.arraycopy(hrpExpanded, 0, values, 0, hrpExpanded.length);
        System.arraycopy(data, 0, values, hrpExpanded.length, data.length);
        int mod = polymod(values) ^ 1;
        byte[] ret = new byte[6];
        for (int i = 0; i < 6; i++) {
            ret[i] = (byte) ((mod >>> (5 * (5 - i))) & 0x1f);
        }
        return ret;
    }

    public static String encode(final String hrp, final byte[] data) {
        if (hrp == null || hrp.isEmpty()) {
            throw new IllegalArgumentException("hrp is empty");
        }
        byte[] checksum = createChecksum(hrp, data);
        byte[] combined = new byte[data.length + checksum.length];
        System.arraycopy(data, 0, combined, 0, data.length);
        System.arraycopy(checksum, 0, combined, data.length, checksum.length);

        StringBuilder sb = new StringBuilder(hrp.length() + 1 + combined.length);
        sb.append(hrp.toLowerCase());
        sb.append('1');
        for (byte b : combined) {
            int idx = (b & 0xff);
            if (idx < 0 || idx >= CHARSET.length()) {
                throw new IllegalArgumentException("invalid data value: " + idx);
            }
            sb.append(CHARSET.charAt(idx));
        }
        return sb.toString();
    }

    public static Bech32Data decode(final String bech) {
        if (bech == null) {
            throw new IllegalArgumentException("bech is null");
        }
        String s = bech.trim();
        int pos = s.lastIndexOf('1');
        if (pos <= 0 || pos + 7 > s.length()) {
            throw new IllegalArgumentException("invalid bech32 string: missing separator or too short");
        }
        String hrp = s.substring(0, pos).toLowerCase();
        String dataPart = s.substring(pos + 1);
        byte[] data = new byte[dataPart.length()];
        for (int i = 0; i < dataPart.length(); i++) {
            int idx = CHARSET.indexOf(dataPart.charAt(i));
            if (idx == -1) {
                throw new IllegalArgumentException("invalid bech32 character: " + dataPart.charAt(i));
            }
            data[i] = (byte) idx;
        }
        if (!verifyChecksum(hrp, data)) {
            throw new IllegalArgumentException("invalid bech32 checksum");
        }
        // strip checksum
        byte[] payload = Arrays.copyOf(data, data.length - 6);
        return new Bech32Data(hrp, payload);
    }

    /**
     * Convert a byte array from one bit resolution to another.
     * E.g. 8-bit bytes to 5-bit groups for Bech32.
     */
    public static byte[] convertBits(byte[] data, int fromBits, int toBits, boolean pad) {
        int acc = 0;
        int bits = 0;
        int maxv = (1 << toBits) - 1;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte value : data) {
            int b = value & 0xff;
            if ((b >> fromBits) != 0) {
                throw new IllegalArgumentException("invalid value " + b + " for fromBits=" + fromBits);
            }
            acc = (acc << fromBits) | b;
            bits += fromBits;
            while (bits >= toBits) {
                bits -= toBits;
                out.write((acc >> bits) & maxv);
            }
        }
        if (pad) {
            if (bits > 0) {
                out.write((acc << (toBits - bits)) & maxv);
            }
        } else if (bits >= fromBits || ((acc << (toBits - bits)) & maxv) != 0) {
            throw new IllegalArgumentException("invalid leftover bits");
        }
        return out.toByteArray();
    }
}
