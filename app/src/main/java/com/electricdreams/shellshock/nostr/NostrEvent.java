package com.electricdreams.shellshock.nostr;

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
                return false;
            }
            if (pubkey == null || sig == null) {
                return false;
            }
            byte[] msg = hexToBytes(id);
            byte[] sigBytes = hexToBytes(sig);
            byte[] pubBytes = hexToBytes(pubkey);
            if (msg == null || sigBytes == null || pubBytes == null) {
                return false;
            }
            if (sigBytes.length != 64 || pubBytes.length != 32) {
                return false;
            }
            return verifySchnorr(pubBytes, msg, sigBytes);
        } catch (Exception e) {
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
            return false;
        }

        // Parse pubkey x coordinate
        BigInteger px = new BigInteger(1, pub);
        if (px.signum() <= 0 || px.compareTo(SECP256K1.getCurve().getField().getCharacteristic()) >= 0) {
            return false;
        }

        // Lift x to a curve point with even Y (BIP-340)
        ECPoint P = liftX(px);
        if (P == null) return false;

        // Parse signature components
        byte[] rBytes = new byte[32];
        System.arraycopy(sig, 0, rBytes, 0, 32);
        BigInteger r = new BigInteger(1, rBytes);
        BigInteger s = new BigInteger(1, sig, 32, 32);
        BigInteger n = SECP256K1.getN();

        if (r.signum() <= 0 || r.compareTo(SECP256K1.getCurve().getField().getCharacteristic()) >= 0) {
            return false;
        }
        if (s.signum() <= 0 || s.compareTo(n) >= 0) {
            return false;
        }

        // Compute e = int(hash(bytes(r) || pub || msg)) mod n
        byte[] rPubMsg = new byte[32 + pub.length + msg.length];
        System.arraycopy(rBytes, 0, rPubMsg, 0, 32);
        System.arraycopy(pub, 0, rPubMsg, 32, pub.length);
        System.arraycopy(msg, 0, rPubMsg, 32 + pub.length, msg.length);

        BigInteger e = new BigInteger(1, sha256(rPubMsg)).mod(n);
        if (e.signum() == 0) {
            return false;
        }

        // R = s*G - e*P
        ECPoint R = SECP256K1.getG().multiply(s).subtract(P.multiply(e)).normalize();
        if (R.isInfinity()) return false;

        // Check that R has even Y and x(R) == r
        if (R.getAffineYCoord().toBigInteger().testBit(0)) {
            return false; // Y is odd
        }
        BigInteger xR = R.getAffineXCoord().toBigInteger();
        return xR.equals(r);
    }

    /**
     * Lift x-only pubkey to a curve point with even Y per BIP-340.
     */
    private static ECPoint liftX(BigInteger x) {
        // y^2 = x^3 + 7 over secp256k1
        BigInteger p = SECP256K1_PARAMS.getCurve().getField().getCharacteristic();
        BigInteger rhs = x.modPow(BigInteger.valueOf(3), p).add(BigInteger.valueOf(7)).mod(p);
        BigInteger y = sqrtModP(rhs, p);
        if (y == null) return null;
        if (y.testBit(0)) {
            y = p.subtract(y); // make y even
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
        if (r.multiply(r).mod(p).compareTo(n.mod(p)) != 0) {
            return null; // no sqrt exists
        }
        return r;
    }
}
