package com.electricdreams.shellshock.nostr;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;

import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Minimal NIP-01 event representation.
 */
public final class NostrEvent {

    public String id;              // 32-byte lowercase hex of sha256(serialized event)
    public String pubkey;          // 32-byte lowercase hex (x-only pubkey)
    public long created_at;        // unix timestamp (seconds)
    public int kind;               // kind number
    public List<List<String>> tags = new ArrayList<>();
    public String content = "";   // arbitrary string
    public String sig;             // 64-byte lowercase hex of Schnorr signature

    private static final Gson gson = new Gson();
    private static final String TAG = "NostrEventVerify";

    private static final X9ECParameters SECP256K1_PARAMS = SECNamedCurves.getByName("secp256k1");
    private static final ECDomainParameters SECP256K1 = new ECDomainParameters(
            SECP256K1_PARAMS.getCurve(), SECP256K1_PARAMS.getG(),
            SECP256K1_PARAMS.getN(), SECP256K1_PARAMS.getH()
    );

    public NostrEvent() {
    }

    /**
     * Compute the event id as defined in NIP-01:
     *
     * sha256 over the UTF-8 JSON serialization of:
     *   [0, pubkey, created_at, kind, tags, content]
     */
    public String computeId() {
        JsonArray arr = new JsonArray();
        arr.add(new JsonPrimitive(0));
        arr.add(new JsonPrimitive(pubkey != null ? pubkey : ""));
        arr.add(new JsonPrimitive(created_at));
        arr.add(new JsonPrimitive(kind));

        JsonArray tagsArray = new JsonArray();
        if (tags != null) {
            for (List<String> tag : tags) {
                JsonArray t = new JsonArray();
                if (tag != null) {
                    for (String v : tag) {
                        t.add(new JsonPrimitive(v != null ? v : ""));
                    }
                }
                tagsArray.add(t);
            }
        }
        arr.add(tagsArray);

        arr.add(new JsonPrimitive(content != null ? content : ""));

        byte[] jsonBytes = arr.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] hash = sha256(jsonBytes);
        return bytesToHex(hash);
    }

    /**
     * Verify that:
     *  - id equals computeId()
     *  - sig is a valid Schnorr signature for id under pubkey
     */
    public boolean verify() {
        try {
            String expectedId = computeId();
            if (id == null || !id.equals(expectedId)) {
                Log.w(TAG, "ID mismatch for kind=" + kind + " eventId=" + id + " computed=" + expectedId);
                return false;
            }
            if (pubkey == null || sig == null) {
                Log.w(TAG, "Missing pubkey or sig for kind=" + kind);
                return false;
            }
            byte[] msg = hexToBytes(id);
            byte[] sigBytes = hexToBytes(sig);
            byte[] pubBytes = hexToBytes(pubkey);
            if (msg == null || sigBytes == null || pubBytes == null) {
                Log.w(TAG, "Hex decode failed for kind=" + kind);
                return false;
            }
            if (sigBytes.length != 64 || pubBytes.length != 32) {
                Log.w(TAG, "Unexpected lengths for kind=" + kind + " sigLen=" + sigBytes.length + " pubLen=" + pubBytes.length);
                return false;
            }
            boolean ok = verifySchnorr(pubBytes, msg, sigBytes);
            if (!ok) {
                Log.w(TAG, "Schnorr verify failed for kind=" + kind + " id=" + id + " pubkey=" + pubkey);
            }
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "Exception during verify for kind=" + kind + ": " + e.getMessage(), e);
            return false;
        }
    }

    // --- Helpers ---

    private static byte[] sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null) return null;
        String s = hex.trim();
        if ((s.length() & 1) != 0) return null;
        int len = s.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16);
            int lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /**
     * Verify a BIP-340 Schnorr signature.
     */
    private static boolean verifySchnorr(byte[] pub, byte[] msg, byte[] sig) {
        if (pub.length != 32 || msg.length != 32 || sig.length != 64) {
            Log.w(TAG, "verifySchnorr: wrong lengths pub=" + pub.length + " msg=" + msg.length + " sig=" + sig.length);
            return false;
        }

        // Parse pubkey x coordinate
        BigInteger px = new BigInteger(1, pub);
        BigInteger p = SECP256K1_PARAMS.getCurve().getField().getCharacteristic();
        if (px.signum() <= 0 || px.compareTo(p) >= 0) {
            Log.w(TAG, "verifySchnorr: pubkey x out of range");
            return false;
        }

        // Lift x to a curve point with even Y (BIP-340) using BouncyCastle's decodePoint
        ECPoint P = liftX(px);
        if (P == null) {
            Log.w(TAG, "verifySchnorr: liftX returned null");
            return false;
        }

        // Parse signature components
        byte[] rBytes = Arrays.copyOfRange(sig, 0, 32);
        byte[] sBytes = Arrays.copyOfRange(sig, 32, 64);

        BigInteger r = new BigInteger(1, rBytes);
        BigInteger s = new BigInteger(1, sBytes);
        BigInteger n = SECP256K1.getN();

        if (r.signum() <= 0 || r.compareTo(p) >= 0) {
            Log.w(TAG, "verifySchnorr: r out of range");
            return false;
        }
        if (s.signum() <= 0 || s.compareTo(n) >= 0) {
            Log.w(TAG, "verifySchnorr: s out of range");
            return false;
        }

        // e = int(hash(bytes(r) || pub || msg)) mod n
        byte[] rPubMsg = new byte[32 + pub.length + msg.length];
        System.arraycopy(rBytes, 0, rPubMsg, 0, 32);
        System.arraycopy(pub, 0, rPubMsg, 32, pub.length);
        System.arraycopy(msg, 0, rPubMsg, 32 + pub.length, msg.length);

        BigInteger e = new BigInteger(1, sha256(rPubMsg)).mod(n);
        if (e.signum() == 0) {
            Log.w(TAG, "verifySchnorr: e == 0");
            return false;
        }

        // R = s*G - e*P
        ECPoint R = SECP256K1.getG().multiply(s).subtract(P.multiply(e)).normalize();
        if (R.isInfinity()) {
            Log.w(TAG, "verifySchnorr: R is infinity");
            return false;
        }

        // Check that R has even y and x(R) == r
        if (R.getAffineYCoord().toBigInteger().testBit(0)) {
            Log.w(TAG, "verifySchnorr: R.y is odd");
        }
        BigInteger xR = R.getAffineXCoord().toBigInteger();
        return xR.equals(r);
    }

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
            Log.w(TAG, "liftX: decodePoint failed: " + e.getMessage());
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
