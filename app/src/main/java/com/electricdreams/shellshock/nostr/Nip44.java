package com.electricdreams.shellshock.nostr;

import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.engines.ChaCha7539Engine;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * NIP-44 v2 encryption helpers (decrypt + conversation key).
 *
 * We only implement the parts needed for the POS listener:
 *  - derive a conversation key between our privkey and remote pubkey
 *  - decrypt payloads produced according to NIP-44 v2
 */
public final class Nip44 {

    public static final String TAG = "Nip44";

    private static final X9ECParameters SECP256K1_PARAMS = SECNamedCurves.getByName("secp256k1");
    private static final BigInteger CURVE_P = SECP256K1_PARAMS.getCurve().getField().getCharacteristic();

    private Nip44() {}

    /**
     * Derive a 32-byte conversation key between private key A and x-only public key B.
     *
     * conversation_key = HKDF-Extract(salt="nip44-v2", IKM=shared_x)
     * where shared_x is the 32-byte x-coordinate of (privA * pubB).
     */
    public static byte[] getConversationKey(byte[] priv32, byte[] pubX32) {
        if (priv32 == null || priv32.length != 32 || pubX32 == null || pubX32.length != 32) {
            throw new IllegalArgumentException("priv and pub must be 32 bytes");
        }
        BigInteger d = new BigInteger(1, priv32);
        if (d.signum() <= 0 || d.compareTo(SECP256K1_PARAMS.getN()) >= 0) {
            throw new IllegalArgumentException("invalid private key scalar");
        }

        BigInteger x = new BigInteger(1, pubX32);
        ECPoint P = liftX(x);
        if (P == null) {
            throw new IllegalArgumentException("invalid x-only public key");
        }

        ECPoint shared = P.multiply(d).normalize();
        byte[] sharedX = shared.getAffineXCoord().getEncoded(); // 32 bytes

        // NIP-44 v2: conversation_key = HKDF-EXTRACT(IKM=shared_x, salt="nip44-v2")
        byte[] salt = "nip44-v2".getBytes(StandardCharsets.UTF_8);
        return hkdfExtract(salt, sharedX);
    }

    /**
     * Decrypt a NIP-44 v2 payload using a precomputed 32-byte conversation key.
     */
    public static String decrypt(String payloadBase64, byte[] conversationKey) throws Exception {
        if (conversationKey == null || conversationKey.length != 32) {
            throw new IllegalArgumentException("conversationKey must be 32 bytes");
        }
        Decoded dec = decodePayload(payloadBase64);

        // Derive per-message keys via HKDF-EXPAND (L=76) from the already-extracted conversationKey.
        // Spec: keys = hkdf_expand(OKM=conversation_key, info=nonce, L=76)
        byte[] okm = hkdfExpand(conversationKey, dec.nonce, 76);

        byte[] chachaKey = Arrays.copyOfRange(okm, 0, 32);
        byte[] chachaNonce = Arrays.copyOfRange(okm, 32, 44); // 12 bytes
        byte[] hmacKey = Arrays.copyOfRange(okm, 44, 76);

        // Verify MAC = HMAC-SHA256(key=hmacKey, data=nonce||ciphertext)
        HMac hmac = new HMac(new SHA256Digest());
        hmac.init(new KeyParameter(hmacKey));
        hmac.update(dec.nonce, 0, dec.nonce.length);
        hmac.update(dec.ciphertext, 0, dec.ciphertext.length);
        byte[] macCalc = new byte[32];
        hmac.doFinal(macCalc, 0);
        if (!constantTimeEquals(macCalc, dec.mac)) {
            throw new IllegalArgumentException("NIP-44: invalid MAC");
        }

        // Decrypt with ChaCha20 (RFC7539 variant, 12-byte nonce)
        ChaCha7539Engine engine = new ChaCha7539Engine();
        engine.init(false, new ParametersWithIV(new KeyParameter(chachaKey), chachaNonce));
        byte[] padded = new byte[dec.ciphertext.length];
        engine.processBytes(dec.ciphertext, 0, dec.ciphertext.length, padded, 0);

        // Remove padding and return plaintext string
        return unpad(padded);
    }

    // --- HKDF helpers (RFC 5869, SHA-256) ---

    /** HKDF-Extract: PRK = HMAC(salt, IKM) */
    private static byte[] hkdfExtract(byte[] salt, byte[] ikm) {
        HMac mac = new HMac(new SHA256Digest());
        if (salt == null) {
            salt = new byte[32]; // zero salt when not provided
        }
        mac.init(new KeyParameter(salt));
        mac.update(ikm, 0, ikm.length);
        byte[] prk = new byte[32];
        mac.doFinal(prk, 0);
        return prk;
    }

    /** HKDF-Expand: OKM = T(1) || T(2) || ... truncated to 'length' */
    private static byte[] hkdfExpand(byte[] prk, byte[] info, int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("HKDF: invalid length");
        }

        HMac mac = new HMac(new SHA256Digest());
        KeyParameter keyParam = new KeyParameter(prk);
        mac.init(keyParam);

        int hashLen = 32; // SHA-256 output length
        int n = (int) Math.ceil((double) length / hashLen);
        if (n > 255) {
            throw new IllegalArgumentException("HKDF: length too large");
        }

        byte[] okm = new byte[length];
        byte[] t = new byte[0];
        int pos = 0;

        for (int i = 1; i <= n; i++) {
            mac.reset();
            mac.init(keyParam);
            if (t.length > 0) {
                mac.update(t, 0, t.length);
            }
            if (info != null) {
                mac.update(info, 0, info.length);
            }
            mac.update((byte) i);
            t = new byte[hashLen];
            mac.doFinal(t, 0);

            int copyLen = Math.min(hashLen, length - pos);
            System.arraycopy(t, 0, okm, pos, copyLen);
            pos += copyLen;
        }

        return okm;
    }

    // --- Internal structures & helpers ---

    private static final class Decoded {
        final byte version;
        final byte[] nonce;      // 32 bytes
        final byte[] ciphertext; // variable
        final byte[] mac;        // 32 bytes

        Decoded(byte version, byte[] nonce, byte[] ciphertext, byte[] mac) {
            this.version = version;
            this.nonce = nonce;
            this.ciphertext = ciphertext;
            this.mac = mac;
        }
    }

    private static Decoded decodePayload(String payload) {
        if (payload == null || payload.isEmpty()) {
            throw new IllegalArgumentException("empty payload");
        }
        if (payload.charAt(0) == '#') {
            throw new IllegalArgumentException("unsupported NIP-44 version prefix '#'");
        }
        // Spec limits: 132..87472 chars, but we only enforce rough sanity
        byte[] data = java.util.Base64.getDecoder().decode(payload);
        if (data.length < 99) {
            throw new IllegalArgumentException("NIP-44: payload too short");
        }
        byte version = data[0];
        if (version != 2) {
            throw new IllegalArgumentException("NIP-44: unknown version " + version);
        }
        byte[] nonce = Arrays.copyOfRange(data, 1, 33);
        byte[] mac = Arrays.copyOfRange(data, data.length - 32, data.length);
        byte[] ciphertext = Arrays.copyOfRange(data, 33, data.length - 32);
        if (nonce.length != 32 || mac.length != 32 || ciphertext.length < 2) {
            throw new IllegalArgumentException("NIP-44: invalid payload structure");
        }
        return new Decoded(version, nonce, ciphertext, mac);
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= (a[i] ^ b[i]);
        }
        return diff == 0;
    }

    // --- Padding helpers (unpad only) ---

    private static String unpad(byte[] padded) {
        if (padded.length < 3) {
            throw new IllegalArgumentException("NIP-44: padded message too short");
        }
        int len = ((padded[0] & 0xff) << 8) | (padded[1] & 0xff);
        if (len <= 0 || len > 65535) {
            throw new IllegalArgumentException("NIP-44: invalid plaintext length " + len);
        }
        if (padded.length != 2 + calcPaddedLen(len)) {
            throw new IllegalArgumentException("NIP-44: invalid padding length");
        }
        if (2 + len > padded.length) {
            throw new IllegalArgumentException("NIP-44: inconsistent lengths");
        }
        byte[] plain = Arrays.copyOfRange(padded, 2, 2 + len);
        return new String(plain, StandardCharsets.UTF_8);
    }

    /**
     * Calculate padded length as defined in NIP-44 pseudocode.
     */
    private static int calcPaddedLen(int unpaddedLen) {
        if (unpaddedLen <= 0 || unpaddedLen > 65535) {
            throw new IllegalArgumentException("NIP-44: invalid unpadded length");
        }
        if (unpaddedLen <= 32) {
            return 32;
        }
        int nextPower = 1 << (31 - Integer.numberOfLeadingZeros(unpaddedLen - 1));
        nextPower <<= 1; // 2^(floor(log2(len-1)) + 1)
        int chunk = (nextPower <= 256) ? 32 : nextPower / 8;
        return chunk * ((unpaddedLen - 1) / chunk + 1);
    }

    // --- Shared x-only lift (duplicate of NostrEvent.liftX to avoid dependency cycle) ---

    /**
     * Lift x-only pubkey to a curve point with even Y per BIP-340,
     * using BouncyCastle's decodePoint.
     */
    private static ECPoint liftX(BigInteger x) {
        BigInteger p = SECP256K1_PARAMS.getCurve().getField().getCharacteristic();
        if (x.signum() <= 0 || x.compareTo(p) >= 0) {
            return null;
        }
        byte[] xBytes = to32Bytes(x);
        byte[] comp = new byte[33];
        comp[0] = 0x02; // even Y
        System.arraycopy(xBytes, 0, comp, 1, 32);
        try {
            return SECP256K1_PARAMS.getCurve().decodePoint(comp).normalize();
        } catch (IllegalArgumentException e) {
            //Log.w(TAG, "liftX: decodePoint failed: " + e.getMessage());
            return null;
        }
    }

    private static byte[] to32Bytes(BigInteger v) {
        byte[] src = v.toByteArray();
        if (src.length == 32) return src;
        byte[] out = new byte[32];
        if (src.length > 32) {
            System.arraycopy(src, src.length - 32, out, 0, 32);
        } else {
            System.arraycopy(src, 0, out, 32 - src.length, src.length);
        }
        return out;
    }
}
