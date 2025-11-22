package com.electricdreams.shellshock.nostr;

import com.google.gson.Gson;

/**
 * NIP-59 Gift Wrap / Seal / Rumor unwrap helpers.
 *
 * This focuses on the receiver side for NIP-17 DM-style messages:
 *   kind 1059 (giftwrap) -> kind 13 (seal) -> kind 14 (rumor)
 */
public final class Nip59 {

    private static final Gson gson = new Gson();

    private Nip59() {}

    public static final class UnwrappedDm {
        public final NostrEvent giftwrap;
        public final NostrEvent seal;
        public final NostrEvent rumor; // expected kind 14

        public UnwrappedDm(NostrEvent giftwrap, NostrEvent seal, NostrEvent rumor) {
            this.giftwrap = giftwrap;
            this.seal = seal;
            this.rumor = rumor;
        }
    }

    /**
     * Unwrap a NIP-59 giftwrapped DM addressed to us.
     *
     * @param giftwrap kind 1059 event
     * @param ourPriv32 our 32-byte secp256k1 secret key (ephemeral nostr identity)
     * @return UnwrappedDm with giftwrap, seal (kind 13), and rumor (kind 14)
     */
    public static UnwrappedDm unwrapGiftWrappedDm(NostrEvent giftwrap, byte[] ourPriv32) throws Exception {
        if (giftwrap == null) {
            throw new IllegalArgumentException("giftwrap event is null");
        }
        if (ourPriv32 == null || ourPriv32.length != 32) {
            throw new IllegalArgumentException("ourPriv32 must be 32 bytes");
        }
        if (giftwrap.kind != 1059) {
            throw new IllegalArgumentException("expected kind 1059 giftwrap, got kind=" + giftwrap.kind);
        }
        if (!giftwrap.verify()) {
            android.util.Log.w("Nip59", "Giftwrap Schnorr verification failed; continuing anyway");
        }
        if (giftwrap.pubkey == null || giftwrap.content == null) {
            throw new IllegalArgumentException("giftwrap missing pubkey or content");
        }

        // 1. Decrypt giftwrap.content (kind 1059) to obtain kind 13 seal
        byte[] gwPub = hexToBytes(giftwrap.pubkey);
        if (gwPub == null || gwPub.length != 32) {
            throw new IllegalArgumentException("invalid giftwrap pubkey hex");
        }
        byte[] conv1 = Nip44.getConversationKey(ourPriv32, gwPub);
        String sealJson = Nip44.decrypt(giftwrap.content, conv1);

        NostrEvent seal = gson.fromJson(sealJson, NostrEvent.class);
        if (seal == null) {
            throw new IllegalArgumentException("failed to parse seal JSON");
        }
        if (seal.kind != 13) {
            throw new IllegalArgumentException("expected kind 13 seal, got kind=" + seal.kind);
        }
        if (!seal.verify()) {
            android.util.Log.w("Nip59", "Seal Schnorr verification failed; continuing anyway");
        }
        if (seal.pubkey == null || seal.content == null) {
            throw new IllegalArgumentException("seal missing pubkey or content");
        }

        // 2. Decrypt seal.content (kind 13) to obtain inner rumor (kind 14)
        byte[] authorPub = hexToBytes(seal.pubkey);
        if (authorPub == null || authorPub.length != 32) {
            throw new IllegalArgumentException("invalid seal pubkey hex");
        }
        byte[] conv2 = Nip44.getConversationKey(ourPriv32, authorPub);
        String rumorJson = Nip44.decrypt(seal.content, conv2);

        NostrEvent rumor = gson.fromJson(rumorJson, NostrEvent.class);
        if (rumor == null) {
            throw new IllegalArgumentException("failed to parse rumor JSON");
        }
        if (rumor.kind != 14) {
            throw new IllegalArgumentException("expected kind 14 rumor, got kind=" + rumor.kind);
        }
        // NIP-17: rumor is unsigned, but pubkey must match seal.pubkey
        if (rumor.pubkey == null || !rumor.pubkey.equals(seal.pubkey)) {
            throw new IllegalArgumentException("rumor pubkey does not match seal pubkey");
        }

        return new UnwrappedDm(giftwrap, seal, rumor);
    }

    // Simple hex helper (duplicated here to avoid exposing internals of NostrEvent)
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
}
