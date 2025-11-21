package com.electricdreams.shellshock.nostr;

import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

/**
 * Minimal secp256k1 keypair + NIP-19 identities (npub/nsec/nprofile).
 *
 * - Secret key: 32-byte scalar in [1, n-1]
 * - Public key: 32-byte x-only per BIP-340 (no y coordinate)
 */
public final class NostrKeyPair {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ECDomainParameters SECP256K1;

    static {
        X9ECParameters params = SECNamedCurves.getByName("secp256k1");
        SECP256K1 = new ECDomainParameters(
                params.getCurve(), params.getG(), params.getN(), params.getH());
    }

    private final BigInteger secret;
    private final byte[] pubX; // 32-byte x-only pubkey

    private NostrKeyPair(BigInteger secret, byte[] pubX) {
        this.secret = secret;
        this.pubX = pubX;
    }

    /**
     * Generate a new random secp256k1 keypair.
     */
    public static NostrKeyPair generate() {
        BigInteger n = SECP256K1.getN();
        BigInteger d;
        do {
            byte[] sk = new byte[32];
            RANDOM.nextBytes(sk);
            d = new BigInteger(1, sk);
        } while (d.signum() <= 0 || d.compareTo(n) >= 0);

        ECPoint Q = SECP256K1.getG().multiply(d).normalize();
        byte[] x = Q.getAffineXCoord().getEncoded(); // 32 bytes

        return new NostrKeyPair(d, x);
    }

    public byte[] getSecretKeyBytes() {
        byte[] out = new byte[32];
        byte[] src = secret.toByteArray();
        int srcPos = Math.max(0, src.length - 32);
        int len = Math.min(32, src.length);
        int destPos = 32 - len;
        System.arraycopy(src, srcPos, out, destPos, len);
        return out;
    }

    public byte[] getPublicKeyBytes() {
        return Arrays.copyOf(pubX, 32);
    }

    public String getHexPub() {
        return bytesToHex(pubX);
    }

    public String getHexSec() {
        return bytesToHex(getSecretKeyBytes());
    }

    public String getNsec() {
        return Nip19.encodeNsec(getSecretKeyBytes());
    }

    public String getNpub() {
        return Nip19.encodeNpub(getPublicKeyBytes());
    }

    public String getNprofile(List<String> relays) {
        return Nip19.encodeNprofile(getPublicKeyBytes(), relays);
    }

    private static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
