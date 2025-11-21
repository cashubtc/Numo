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

        byte[] salt = "nip44-v2".getBytes(StandardCharsets.UTF_8);
        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(sharedX, salt, null));
        byte[] conv = new byte[32];
        hkdf.generateBytes(conv, 0, 32);
        return conv;
    }

    /**
     * Decrypt a NIP-44 v2 payload using a precomputed 32-byte conversation key.
     */
    public static String decrypt(String payloadBase64, byte[] conversationKey) throws Exception {
        if (conversationKey == null || conversationKey.length != 32) {
            throw new IllegalArgumentException("conversationKey must be 32 bytes");
        }
        Decoded dec = decodePayload(payloadBase64);

        // Derive per-message keys via HKDF-expand (L=76)
        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(conversationKey, null, dec.nonce));
        byte[] okm = new byte[76];
        hkdf.generateBytes(okm, 0, 76);

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

    private static ECPoint liftX(BigInteger x) {
        if (x.signum() <= 0 || x.compareTo(CURVE_P) >= 0) {
            return null;
        }
        // y^2 = x^3 + 7 mod p
        BigInteger rhs = x.modPow(BigInteger.valueOf(3), CURVE_P)
                .add(BigInteger.valueOf(7))
                .mod(CURVE_P);
        BigInteger y = sqrtModP(rhs, CURVE_P);
        if (y == null) return null;
        if (y.testBit(0)) {
            y = CURVE_P.subtract(y); // make y even
        }
        return SECP256K1_PARAMS.getCurve().createPoint(x, y).normalize();
    }

    /**
     * sqrt(n) mod p for secp256k1 (p % 4 == 3, so sqrt is simple).
     */
    private static BigInteger sqrtModP(BigInteger n, BigInteger p) {
        if (n.signum() == 0) return BigInteger.ZERO;
        BigInteger exp = p.add(BigInteger.ONE).shiftRight(2);
        BigInteger r = n.modPow(exp, p);
        if (!r.multiply(r).mod(p).equals(n.mod(p))) {
            return null;
        }
        return r;
    }
}
