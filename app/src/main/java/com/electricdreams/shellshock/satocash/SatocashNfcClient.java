package com.electricdreams.shellshock;

import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

// Bouncy Castle for uncompressed point serialization (recommended for secp256k1)
// implementation 'org.bouncycastle:bcprov-jdk15on:1.70'
// Must be initialized: Security.addProvider(new BouncyCastleProvider());
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.jce.spec.ECPublicKeySpec;
import org.bouncycastle.math.ec.ECPoint;

import java.security.Security;


public class SatocashNfcClient {

    private static final String TAG = "SatocashNfcClient";

    // Satocash specific constants
    private static final byte CLA_BITCOIN = (byte) 0xB0;
    private static final byte INS_SETUP = 0x2A;
    private static final byte INS_SATOCASH_GET_STATUS = (byte) 0xB0;
    private static final byte INS_GET_STATUS = 0x3C;
    private static final byte INS_INIT_SECURE_CHANNEL = (byte) 0x81;
    private static final byte INS_PROCESS_SECURE_CHANNEL = (byte) 0x82;
    private static final byte INS_VERIFY_PIN = 0x42;
    private static final byte INS_CHANGE_PIN = 0x44;
    private static final byte INS_UNBLOCK_PIN = 0x46;
    private static final byte INS_LOGOUT_ALL = 0x60;

    // Satocash specific instructions
    private static final byte INS_SATOCASH_IMPORT_MINT = (byte) 0xB1;
    private static final byte INS_SATOCASH_EXPORT_MINT = (byte) 0xB2;
    private static final byte INS_SATOCASH_REMOVE_MINT = (byte) 0xB3;
    private static final byte INS_SATOCASH_IMPORT_KEYSET = (byte) 0xB4;
    private static final byte INS_SATOCASH_EXPORT_KEYSET = (byte) 0xB5;
    private static final byte INS_SATOCASH_REMOVE_KEYSET = (byte) 0xB6;
    private static final byte INS_SATOCASH_IMPORT_PROOF = (byte) 0xB7;
    private static final byte INS_SATOCASH_EXPORT_PROOFS = (byte) 0xB8;
    private static final byte INS_SATOCASH_GET_PROOF_INFO = (byte) 0xB9;

    // Configuration instructions
    private static final byte INS_CARD_LABEL = 0x3D;
    private static final byte INS_SET_NDEF = 0x3F;
    private static final byte INS_SET_NFC_POLICY = 0x3E;
    private static final byte INS_SET_PIN_POLICY = 0x3A;
    private static final byte INS_SET_PINLESS_AMOUNT = 0x3B;
    private static final byte INS_BIP32_GET_AUTHENTIKEY = 0x73;
    private static final byte INS_EXPORT_AUTHENTIKEY = (byte) 0xAD;
    private static final byte INS_PRINT_LOGS = (byte) 0xA9;

    // PKI instructions
    private static final byte INS_EXPORT_PKI_PUBKEY = (byte) 0x98;
    private static final byte INS_IMPORT_PKI_CERTIFICATE = (byte) 0x92;
    private static final byte INS_EXPORT_PKI_CERTIFICATE = (byte) 0x93;
    private static final byte INS_SIGN_PKI_CSR = (byte) 0x94;
    private static final byte INS_LOCK_PKI = (byte) 0x99;
    private static final byte INS_CHALLENGE_RESPONSE_PKI = (byte) 0x9A;

    private IsoDep isoDep;
    private final SecureChannel secureChannel;
    private boolean secureChannelActive = false;
    private boolean authenticated = false;

    // AID for the Satocash applet
    public static final byte[] SATOCASH_AID = {
            (byte) 0x53, 0x61, 0x74, 0x6F, 0x63, 0x61, 0x73, 0x68
    };

    // Common JavaCard applet AIDs to try (from Python client)
    private static final byte[][] COMMON_AIDS = {
            {(byte) 0xA0, 0x00, 0x00, 0x00, 0x04, 0x53, 0x61, 0x74, 0x6F, 0x63, 0x61, 0x73, 0x68},
            {(byte) 0x53, 0x61, 0x74, 0x6F, 0x63, 0x61, 0x73, 0x68},
            {(byte) 0xA0, 0x00, 0x00, 0x00, 0x62, 0x03, 0x01, 0x08, 0x01},
            {(byte) 0xA0, 0x00, 0x00, 0x01, 0x51, 0x00, 0x00, 0x00},
    };

    // Status Words (SW)
    private static final int SW_SUCCESS = 0x9000;
    private static final int SW_PIN_FAILED = 0x63C0;
    private static final int SW_OPERATION_NOT_ALLOWED = 0x9C03;
    private static final int SW_SETUP_NOT_DONE = 0x9C04;
    private static final int SW_SETUP_ALREADY_DONE = 0x9C07;
    private static final int SW_UNSUPPORTED_FEATURE = 0x9C05;
    private static final int SW_UNAUTHORIZED = 0x9C06;
    private static final int SW_NO_MEMORY_LEFT = 0x9C01;
    private static final int SW_OBJECT_NOT_FOUND = 0x9C08;
    private static final int SW_INCORRECT_P1 = 0x9C10;
    private static final int SW_INCORRECT_P2 = 0x9C11;
    private static final int SW_SEQUENCE_END = 0x9C12;
    private static final int SW_INVALID_PARAMETER = 0x9C0F;
    private static final int SW_SIGNATURE_INVALID = 0x9C0B;
    private static final int SW_IDENTITY_BLOCKED = 0x9C0C;
    private static final int SW_INTERNAL_ERROR = 0x9CFF;
    private static final int SW_INCORRECT_INITIALIZATION = 0x9C13;
    private static final int SW_LOCK_ERROR = 0x9C30;
    private static final int SW_HMAC_UNSUPPORTED_KEYSIZE = 0x9C1E;
    private static final int SW_HMAC_UNSUPPORTED_MSGSIZE = 0x9C1F;
    private static final int SW_SECURE_CHANNEL_REQUIRED = 0x9C20;
    private static final int SW_SECURE_CHANNEL_UNINITIALIZED = 0x9C21;
    private static final int SW_SECURE_CHANNEL_WRONG_IV = 0x9C22;
    private static final int SW_SECURE_CHANNEL_WRONG_MAC = 0x9C23;
    private static final int SW_PKI_ALREADY_LOCKED = 0x9C40;
    private static final int SW_NFC_DISABLED = 0x9C48;
    private static final int SW_NFC_BLOCKED = 0x9C49;
    private static final int SW_INS_DEPRECATED = 0x9C26;
    private static final int SW_RESET_TO_FACTORY = 0xFF00;
    private static final int SW_DEBUG_FLAG = 0x9FFF;
    private static final int SW_OBJECT_ALREADY_PRESENT = 0x9C60;
    private static final int SW_UNKNOWN_ERROR = 0x6F00; // General error

    // Multi-APDU operations
    private static final byte OP_INIT = 0x01;
    private static final byte OP_PROCESS = 0x02;
    private static final byte OP_FINALIZE = 0x03;

    public enum ProofInfoType {
        METADATA_STATE(0),
        METADATA_KEYSET_INDEX(1),
        METADATA_AMOUNT_EXPONENT(2),
        METADATA_MINT_INDEX(3),
        METADATA_UNIT(4);

        private final int value;
        ProofInfoType(int value) {
            this.value = value;
        }
        public int getValue() {
            return value;
        }
    }

    public enum Unit {
        EMPTY(0),
        SAT(1),
        MSAT(2),
        USD(3),
        EUR(4);

        private final int value;
        Unit(int value) {
            this.value = value;
        }
        public int getValue() {
            return value;
        }
    }

    public static class SatocashException extends RuntimeException {
        private final int sw;

        public SatocashException(String message, int sw) {
            super(message);
            this.sw = sw;
        }

        public int getSw() {
            return sw;
        }
    }

    private static class SecureChannel {
        private PrivateKey clientPrivateKey;
        private PublicKey clientPublicKey;
        private PublicKey cardEphemeralPublicKey;
        private PublicKey cardAuthentikeyPublicKey;
        private SecretKey sessionKey;
        private SecretKey macKey;
        private boolean initialized = false;

        private static final byte[] CST_SC_KEY = "sc_key".getBytes();
        private static final byte[] CST_SC_MAC = "sc_mac".getBytes();
        private static final int SIZE_SC_IV = 16;
        private static final int SIZE_SC_IV_RANDOM = 12;
        private static final int SIZE_SC_IV_COUNTER = 4;
        private static final int SIZE_SC_MACKEY = 20;

        private int ivCounter = 1;
        private byte[] ivRandom = new byte[SIZE_SC_IV_RANDOM];
        private final SecureRandom secureRandom = new SecureRandom();

        // Static initializer for Bouncy Castle
        static {

            final Provider provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
            if (provider == null) {
                Security.addProvider(new BouncyCastleProvider());
            } else {
                if (!provider.getClass().equals(BouncyCastleProvider.class)) {
                    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
                    Security.insertProviderAt(new BouncyCastleProvider(), 1);
                }
            }
        }

        public SecureChannel() {
            secureRandom.nextBytes(ivRandom);
        }

        public byte[] generateClientKeypair() throws NoSuchAlgorithmException, InvalidAlgorithmParameterException, NoSuchProviderException {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
            keyGen.initialize(new ECGenParameterSpec("secp256k1"), secureRandom);
            KeyPair keyPair = keyGen.generateKeyPair();
            clientPrivateKey = keyPair.getPrivate();
            clientPublicKey = keyPair.getPublic();

            // Export public key in uncompressed format (0x04 || X || Y)
            // This requires Bouncy Castle's specific EC public key handling
            if (clientPublicKey != null) {
                ECPublicKey bcPubKey = (org.bouncycastle.jce.interfaces.ECPublicKey) clientPublicKey;
                return bcPubKey.getQ().getEncoded(false); // false for uncompressed
            } else {
                Log.e(TAG, "Client public key is not a Bouncy Castle ECPublicKey. Cannot get uncompressed bytes.");
                throw new NoSuchAlgorithmException("Bouncy Castle EC public key not found for uncompressed export.");
            }
        }

        public Map<String, byte[]> parseCardResponse(byte[] response) throws SatocashException {
            if (response.length < 6) {
                throw new SatocashException("Secure channel response too short", SW_UNKNOWN_ERROR);
            }

            ByteBuffer buffer = ByteBuffer.wrap(response);
            Map<String, byte[]> parsed = new HashMap<>();

            int coordXSize = buffer.getShort() & 0xFFFF;
            if (coordXSize != 32) { // Expecting 32 bytes for X coordinate
                throw new SatocashException("Unexpected ephemeral coordinate X size: " + coordXSize, SW_UNKNOWN_ERROR);
            }
            byte[] ephemeralCoordX = new byte[coordXSize];
            buffer.get(ephemeralCoordX);
            parsed.put("ephemeral_coordx", ephemeralCoordX);

            int sigSize = buffer.getShort() & 0xFFFF;
            byte[] ephemeralSignature = new byte[sigSize];
            buffer.get(ephemeralSignature);
            parsed.put("ephemeral_signature", ephemeralSignature);

            int sig2Size = buffer.getShort() & 0xFFFF;
            byte[] authentikeySignature = new byte[sig2Size];
            buffer.get(authentikeySignature);
            parsed.put("authentikey_signature", authentikeySignature);

            if (buffer.hasRemaining()) {
                int authentikeyCoordXSize = buffer.getShort() & 0xFFFF;
                byte[] authentikeyCoordX = new byte[authentikeyCoordXSize];
                buffer.get(authentikeyCoordX);
                parsed.put("authentikey_coordx", authentikeyCoordX);
            }

            return parsed;
        }

        public PublicKey recoverCardPublicKey(byte[] coordX, byte[] signature) throws SatocashException {
            // This method attempts to recover the full EC public key from its X coordinate
            // and a signature, by trying both possible Y parities.
            // This requires Bouncy Castle.

            ECNamedCurveParameterSpec curveSpec = ECNamedCurveTable.getParameterSpec("secp256k1");
            if (curveSpec == null) {
                throw new SatocashException("secp256k1 curve not found.", SW_INTERNAL_ERROR);
            }

            // Try both Y parities (0x02 for even, 0x03 for odd)
            for (byte prefix : new byte[]{0x02, 0x03}) {
                try {
                    byte[] encodedPoint = new byte[1 + coordX.length];
                    encodedPoint[0] = prefix;
                    System.arraycopy(coordX, 0, encodedPoint, 1, coordX.length);

                    ECPoint point = curveSpec.getCurve().decodePoint(encodedPoint);
                    ECPublicKeySpec pubSpec = new ECPublicKeySpec(point, curveSpec);
                    KeyFactory keyFactory = KeyFactory.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
                    return keyFactory.generatePublic(pubSpec);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to recover public key with prefix " + String.format("0x%02X", prefix) + ": " + e.getMessage());
                }
            }
            throw new SatocashException("Could not recover public key from X coordinate and signature.", SW_UNKNOWN_ERROR);
        }

        public void deriveKeys(byte[] sharedSecret) throws NoSuchAlgorithmException, InvalidKeyException {
            Mac macSha1 = Mac.getInstance("HmacSHA1");
            macSha1.init(new SecretKeySpec(sharedSecret, "HmacSHA1"));
            macKey = new SecretKeySpec(macSha1.doFinal(CST_SC_MAC), "HmacSHA1");
            Log.d(TAG, "Derived MAC key: " + bytesToHex(macKey.getEncoded()));

            macSha1.init(new SecretKeySpec(sharedSecret, "HmacSHA1"));
            byte[] sessionKeyFull = macSha1.doFinal(CST_SC_KEY);
            sessionKey = new SecretKeySpec(Arrays.copyOfRange(sessionKeyFull, 0, 16), "AES");
            Log.d(TAG, "Derived session key: " + bytesToHex(sessionKey.getEncoded()));
        }

        public void completeHandshake(byte[] cardResponse) throws SatocashException, NoSuchAlgorithmException, InvalidKeyException, InvalidKeySpecException {
            Map<String, byte[]> parsed = parseCardResponse(cardResponse);

            // Recover card's ephemeral public key using the X coordinate and signature
            cardEphemeralPublicKey = recoverCardPublicKey(parsed.get("ephemeral_coordx"), parsed.get("ephemeral_signature"));

            byte[] sharedSecret;
            try {
                KeyAgreement keyAgreement = KeyAgreement.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME);
                keyAgreement.init(clientPrivateKey);
                keyAgreement.doPhase(cardEphemeralPublicKey, true);
                sharedSecret = keyAgreement.generateSecret();
            } catch (InvalidKeyException | NoSuchProviderException e) {
                throw new SatocashException("ECDH key agreement failed: " + e.getMessage(), SW_UNKNOWN_ERROR);
            }

            deriveKeys(sharedSecret);
            initialized = true;
            Log.d(TAG, "Secure channel established!");
        }

        public byte[] generateIv() {
            ivCounter += 2;
            secureRandom.nextBytes(ivRandom);
            ByteBuffer ivBuffer = ByteBuffer.allocate(SIZE_SC_IV);
            ivBuffer.put(ivRandom);
            ivBuffer.putInt(ivCounter);
            return ivBuffer.array();
        }

        public byte[] encryptCommand(byte[] commandApdu) throws SatocashException, NoSuchPaddingException, NoSuchAlgorithmException, InvalidKeyException, InvalidAlgorithmParameterException, IOException, IllegalBlockSizeException, BadPaddingException {
            if (!initialized) {
                throw new SatocashException("Secure channel not initialized", SW_SECURE_CHANNEL_UNINITIALIZED);
            }

            byte[] iv = generateIv();
            int blockSize = 16;
            int paddingLength = blockSize - (commandApdu.length % blockSize);
            ByteArrayOutputStream paddedCommandStream = new ByteArrayOutputStream();
            paddedCommandStream.write(commandApdu);
            for (int i = 0; i < paddingLength; i++) {
                paddedCommandStream.write(paddingLength);
            }
            byte[] paddedCommand = paddedCommandStream.toByteArray();

            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, sessionKey, new IvParameterSpec(iv));
            byte[] encryptedData = cipher.doFinal(paddedCommand);

            ByteBuffer macDataBuffer = ByteBuffer.allocate(SIZE_SC_IV + 2 + encryptedData.length);
            macDataBuffer.put(iv);
            macDataBuffer.putShort((short) encryptedData.length);
            macDataBuffer.put(encryptedData);
            byte[] macData = macDataBuffer.array();

            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(macKey);
            byte[] calculatedMac = mac.doFinal(macData);

            ByteBuffer secureDataBuffer = ByteBuffer.allocate(SIZE_SC_IV + 2 + encryptedData.length + 2 + calculatedMac.length);
            secureDataBuffer.put(iv);
            secureDataBuffer.putShort((short) encryptedData.length);
            secureDataBuffer.put(encryptedData);
            secureDataBuffer.putShort((short) calculatedMac.length);
            secureDataBuffer.put(calculatedMac);

            return secureDataBuffer.array();
        }

        public byte[] decryptResponse(byte[] encryptedResponse) throws SatocashException, NoSuchPaddingException, NoSuchAlgorithmException, InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException {
            if (!initialized) {
                throw new SatocashException("Secure channel not initialized", SW_SECURE_CHANNEL_UNINITIALIZED);
            }

            if (encryptedResponse.length < SIZE_SC_IV + 2 + 2) {
                throw new SatocashException("Secure channel response too short", SW_UNKNOWN_ERROR);
            }

            ByteBuffer buffer = ByteBuffer.wrap(encryptedResponse);

            byte[] iv = new byte[SIZE_SC_IV];
            buffer.get(iv);

            int dataSize = buffer.getShort() & 0xFFFF;

            byte[] encryptedData = new byte[dataSize];
            buffer.get(encryptedData);

            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, sessionKey, new IvParameterSpec(iv));
            byte[] paddedData = cipher.doFinal(encryptedData);

            int paddingLength = paddedData[paddedData.length - 1] & 0xFF;
            if (paddingLength == 0 || paddingLength > paddedData.length) {
                Log.e(TAG, "Invalid PKCS#7 padding length: " + paddingLength);
                throw new SatocashException("Invalid PKCS#7 padding", SW_UNKNOWN_ERROR);
            }
            for (int i = 0; i < paddingLength; i++) {
                if ((paddedData[paddedData.length - 1 - i] & 0xFF) != paddingLength) {
                    Log.e(TAG, "PKCS#7 padding byte mismatch.");
                    throw new SatocashException("PKCS#7 padding byte mismatch", SW_UNKNOWN_ERROR);
                }
            }
            return Arrays.copyOfRange(paddedData, 0, paddedData.length - paddingLength);
        }
    }

    private final IsoDep mIsoDep;
    private byte[] selectedAid = null;

    public SatocashNfcClient(Tag tag) throws IOException {
        mIsoDep = IsoDep.get(tag);
        if (mIsoDep == null) {
            throw new IOException("Tag does not support IsoDep technology.");
        }
        secureChannel = new SecureChannel();
    }

    public void connect() throws IOException {
        if (!mIsoDep.isConnected()) {
            mIsoDep.connect();
            mIsoDep.setTimeout(5000); // Set a timeout for APDU transmissions
            Log.d(TAG, "Connected to IsoDep tag.");
        }
    }

    public void close() throws IOException {
        if (mIsoDep.isConnected()) {
            mIsoDep.close();
            Log.d(TAG, "Disconnected from IsoDep tag.");
        }
        secureChannelActive = false;
        authenticated = false;
    }

    public byte[] sendApdu(byte cla, byte ins, byte p1, byte p2, byte[] data, Integer le) throws SatocashException {
        if (!mIsoDep.isConnected()) {
            throw new SatocashException("IsoDep not connected. Call connect() first.", SW_UNKNOWN_ERROR);
        }

        ByteArrayOutputStream apduStream = new ByteArrayOutputStream();
        apduStream.write(cla);
        apduStream.write(ins);
        apduStream.write(p1);
        apduStream.write(p2);

        if (data != null && data.length > 0) {
            apduStream.write((byte) data.length);
            try {
                apduStream.write(data);
            } catch (IOException e) {
                throw new SatocashException("Error writing APDU data: " + e.getMessage(), SW_INTERNAL_ERROR);
            }
        }

        if (le != null) {
            apduStream.write((byte) (le & 0xFF));
        }

        byte[] apdu = apduStream.toByteArray();
        Log.d(TAG, "Sending APDU: " + bytesToHex(apdu));

        try {
            byte[] response = mIsoDep.transceive(apdu);
            int sw = ((response[response.length - 2] & 0xFF) << 8) | (response[response.length - 1] & 0xFF);
            byte[] responseData = Arrays.copyOfRange(response, 0, response.length - 2);

            Log.d(TAG, "Response: " + bytesToHex(responseData) + " SW: " + String.format("0x%04X", sw));

            if (sw != SW_SUCCESS) {
                throw new SatocashException("APDU command failed", sw);
            }
            return responseData;

        } catch (IOException e) {
            Log.e(TAG, "APDU transmission error: " + e.getMessage(), e);
            throw new SatocashException("APDU transmission error: " + e.getMessage(), SW_UNKNOWN_ERROR);
        }
    }

    public byte[] sendSecureApdu(byte cla, byte ins, byte p1, byte p2, byte[] data) throws SatocashException {
        if (!secureChannelActive) {
            throw new SatocashException("Secure channel not initialized", SW_SECURE_CHANNEL_UNINITIALIZED);
        }

        ByteArrayOutputStream apduStream = new ByteArrayOutputStream();
        apduStream.write(cla);
        apduStream.write(ins);
        apduStream.write(p1);
        apduStream.write(p2);
        if (data != null) {
            apduStream.write((byte) data.length);
            try {
                apduStream.write(data);
            } catch (IOException e) {
                throw new SatocashException("Error preparing secure APDU data: " + e.getMessage(), SW_INTERNAL_ERROR);
            }
        }
        byte[] originalApdu = apduStream.toByteArray();

        try {
            byte[] encryptedData = secureChannel.encryptCommand(originalApdu);

            byte[] response = sendApdu(
                    CLA_BITCOIN,
                    INS_PROCESS_SECURE_CHANNEL,
                    (byte) 0x00, (byte) 0x00,
                    encryptedData,
                    null // Le is not used for secure channel commands
            );

            if (response != null && response.length > 0) {
                return secureChannel.decryptResponse(response);
            } else {
                return null;
            }

        } catch (NoSuchPaddingException | NoSuchAlgorithmException | InvalidKeyException |
                 InvalidAlgorithmParameterException | IOException | IllegalBlockSizeException |
                 BadPaddingException e) {
            Log.e(TAG, "Secure APDU encryption/decryption error: " + e.getMessage(), e);
            throw new SatocashException("Secure APDU processing error: " + e.getMessage(), SW_INTERNAL_ERROR);
        }
    }

    public byte[] discoverApplets() throws SatocashException {
        Log.d(TAG, "Discovering Applets...");
        for (byte[] aid : COMMON_AIDS) {
            Log.d(TAG, "Trying AID: " + bytesToHex(aid));
            try {
                byte[] response = sendApdu((byte) 0x00, (byte) 0xA4, (byte) 0x04, (byte) 0x00, aid, null);
                Log.d(TAG, "Successfully selected AID: " + bytesToHex(aid));
                selectedAid = aid;

                // Try to get status with this AID to confirm it's a Satocash applet
                try {
                    Log.d(TAG, "Testing Satocash status command...");
                    sendApdu(CLA_BITCOIN, INS_SATOCASH_GET_STATUS, (byte) 0x00, (byte) 0x00, null, null);
                    Log.d(TAG, "Satocash applet detected!");
                    return aid;
                } catch (SatocashException e) {
                    Log.w(TAG, "Satocash status failed for AID " + bytesToHex(aid) + ": " + e.getMessage());
                    // Try general status
                    try {
                        sendApdu(CLA_BITCOIN, INS_GET_STATUS, (byte) 0x00, (byte) 0x00, null, null);
                        Log.d(TAG, "Compatible applet detected (general status works) for AID: " + bytesToHex(aid));
                        return aid;
                    } catch (SatocashException e2) {
                        Log.w(TAG, "General status also failed for AID " + bytesToHex(aid) + ": " + e2.getMessage());
                    }
                }
            } catch (SatocashException e) {
                Log.w(TAG, "AID selection failed for " + bytesToHex(aid) + ": " + e.getMessage());
            }
        }
        Log.w(TAG, "No compatible applet found.");
        return null;
    }

    public void selectApplet(byte[] aid) throws SatocashException {
        Log.d(TAG, "Selecting applet with AID: " + bytesToHex(aid));
        byte[] response = sendApdu((byte) 0x00, (byte) 0xA4, (byte) 0x04, (byte) 0x00, aid, null);
        selectedAid = aid;
        Log.d(TAG, "Applet selected successfully.");
    }

    public Map<String, Object> getStatus() throws SatocashException {
        Log.d(TAG, "Getting Status...");
        byte[] response;
        int sw;

        // Try Satocash-specific status first
        try {
            Log.d(TAG, "Trying Satocash status...");
            response = sendApdu(CLA_BITCOIN, INS_SATOCASH_GET_STATUS, (byte) 0x00, (byte) 0x00, null, null);
            Log.d(TAG, "Satocash status successful.");
            return parseSatocashStatus(response);
        } catch (SatocashException e) {
            Log.w(TAG, "Satocash status command failed: " + e.getMessage());
        }

        // Try general status
        try {
            Log.d(TAG, "Trying general status...");
            response = sendApdu(CLA_BITCOIN, INS_GET_STATUS, (byte) 0x00, (byte) 0x00, null, null);
            Log.d(TAG, "General status successful.");
            return parseGeneralStatus(response);
        } catch (SatocashException e) {
            Log.w(TAG, "General status command failed: " + e.getMessage());
        }

        throw new SatocashException("Both status commands failed", SW_UNKNOWN_ERROR);
    }

    private Map<String, Object> parseSatocashStatus(byte[] statusData) throws SatocashException {
        if (statusData.length < 22) {
            throw new SatocashException("Satocash status response too short", SW_UNKNOWN_ERROR);
        }

        ByteBuffer buffer = ByteBuffer.wrap(statusData);
        Map<String, Object> statusInfo = new HashMap<>();

        statusInfo.put("protocol_version", String.format("%d.%d", buffer.get() & 0xFF, buffer.get() & 0xFF));
        statusInfo.put("applet_version", String.format("%d.%d", buffer.get() & 0xFF, buffer.get() & 0xFF));

        statusInfo.put("pin_tries_remaining", buffer.get() & 0xFF);
        statusInfo.put("puk_tries_remaining", buffer.get() & 0xFF);
        statusInfo.put("pin1_tries_remaining", buffer.get() & 0xFF);
        statusInfo.put("puk1_tries_remaining", buffer.get() & 0xFF);

        statusInfo.put("needs_2fa", (buffer.get() & 0xFF) != 0);
        buffer.get(); // rfu
        statusInfo.put("setup_done", (buffer.get() & 0xFF) != 0);
        statusInfo.put("needs_secure_channel", (buffer.get() & 0xFF) != 0);
        statusInfo.put("nfc_policy", buffer.get() & 0xFF);
        statusInfo.put("pin_policy", buffer.get() & 0xFF);
        buffer.get(); // rfu2

        statusInfo.put("max_mints", buffer.get() & 0xFF);
        statusInfo.put("nb_mints", buffer.get() & 0xFF);
        statusInfo.put("max_keysets", buffer.get() & 0xFF);
        statusInfo.put("nb_keysets", buffer.get() & 0xFF);

        if (buffer.remaining() >= 6) {
            statusInfo.put("max_proofs", buffer.getShort() & 0xFFFF);
            statusInfo.put("nb_proofs_unspent", buffer.getShort() & 0xFFFF);
            statusInfo.put("nb_proofs_spent", buffer.getShort() & 0xFFFF);
        } else {
            statusInfo.put("max_proofs", 0);
            statusInfo.put("nb_proofs_unspent", 0);
            statusInfo.put("nb_proofs_spent", 0);
        }

        Log.d(TAG, "Parsed Satocash Status: " + statusInfo.toString());
        return statusInfo;
    }

    private Map<String, Object> parseGeneralStatus(byte[] statusData) throws SatocashException {
        if (statusData.length < 9) {
            throw new SatocashException("General status response too short", SW_UNKNOWN_ERROR);
        }

        ByteBuffer buffer = ByteBuffer.wrap(statusData);
        Map<String, Object> statusInfo = new HashMap<>();

        statusInfo.put("protocol_version", String.format("%d.%d", buffer.get() & 0xFF, buffer.get() & 0xFF));
        statusInfo.put("applet_version", String.format("%d.%d", buffer.get() & 0xFF, buffer.get() & 0xFF));

        statusInfo.put("pin_tries_remaining", buffer.get() & 0xFF);
        statusInfo.put("puk_tries_remaining", buffer.get() & 0xFF);
        statusInfo.put("pin1_tries_remaining", buffer.get() & 0xFF);
        statusInfo.put("puk1_tries_remaining", buffer.get() & 0xFF);

        statusInfo.put("needs_2fa", (buffer.get() & 0xFF) != 0);

        Log.d(TAG, "Parsed General Status: " + statusInfo.toString());
        return statusInfo;
    }

    public boolean setupApplet(String defaultPin, String userPin, String userPuk, int pinTries, int pukTries) throws SatocashException {
        Log.d(TAG, "Setting up applet...");

        ByteArrayOutputStream setupDataStream = new ByteArrayOutputStream();
        try {
            byte[] defaultPinBytes = defaultPin.getBytes("ASCII");
            setupDataStream.write((byte) defaultPinBytes.length);
            setupDataStream.write(defaultPinBytes);

            setupDataStream.write((byte) pinTries);
            setupDataStream.write((byte) pukTries);

            byte[] userPinBytes = userPin.getBytes("ASCII");
            setupDataStream.write((byte) userPinBytes.length);
            setupDataStream.write(userPinBytes);

            byte[] userPukBytes = userPuk.getBytes("ASCII");
            setupDataStream.write((byte) userPukBytes.length);
            setupDataStream.write(userPukBytes);

            // PIN1 configuration (unused)
            setupDataStream.write((byte) pinTries);
            setupDataStream.write((byte) pukTries);
            setupDataStream.write((byte) 0x00); // length of PIN1
            setupDataStream.write((byte) 0x00); // length of PUK1

            // RFU (7 bytes) + option flags (2 bytes)
            setupDataStream.write(new byte[9]);

        } catch (IOException e) {
            throw new SatocashException("Error preparing setup data: " + e.getMessage(), SW_INTERNAL_ERROR);
        }

        byte[] response = sendSecureApdu(CLA_BITCOIN, INS_SETUP, (byte) 0x00, (byte) 0x00, setupDataStream.toByteArray());

        Log.d(TAG, "Setup completed successfully!");
        return true;
    }

    public boolean verifyPin(String pin, int pinId) throws SatocashException {
        Log.d(TAG, "Verifying PIN ID " + pinId + "...");
        byte[] pinBytes = pin.getBytes();
        try {
            byte[] response = sendSecureApdu(CLA_BITCOIN, INS_VERIFY_PIN, (byte) pinId, (byte) 0x00, pinBytes);
            authenticated = true;
            Log.d(TAG, "PIN verified successfully!");
            return true;
        } catch (SatocashException e) {
            if ((e.getSw() & 0xFFF0) == SW_PIN_FAILED) {
                int remainingTries = e.getSw() & 0x000F;
                Log.w(TAG, "PIN verification failed. Remaining tries: " + remainingTries);
                throw new SatocashException("PIN verification failed. Remaining tries: " + remainingTries, e.getSw());
            } else {
                throw e;
            }
        }
    }

    public boolean changePin(String oldPin, String newPin, int pinId) throws SatocashException {
        Log.d(TAG, "Changing PIN ID " + pinId + "...");
        ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
        try {
            byte[] oldPinBytes = oldPin.getBytes("ASCII");
            dataStream.write((byte) oldPinBytes.length);
            dataStream.write(oldPinBytes);

            byte[] newPinBytes = newPin.getBytes("ASCII");
            dataStream.write((byte) newPinBytes.length);
            dataStream.write(newPinBytes);
        } catch (IOException e) {
            throw new SatocashException("Error preparing change PIN data: " + e.getMessage(), SW_INTERNAL_ERROR);
        }

        byte[] response = sendSecureApdu(CLA_BITCOIN, INS_CHANGE_PIN, (byte) pinId, (byte) 0x00, dataStream.toByteArray());
        Log.d(TAG, "PIN changed successfully!");
        return true;
    }

    public boolean unblockPin(String puk, int pinId) throws SatocashException {
        Log.d(TAG, "Unblocking PIN ID " + pinId + "...");
        byte[] pukBytes = puk.getBytes();
        byte[] response = sendSecureApdu(CLA_BITCOIN, INS_UNBLOCK_PIN, (byte) pinId, (byte) 0x00, pukBytes);
        Log.d(TAG, "PIN unblocked successfully!");
        return true;
    }

    public boolean logoutAll() throws SatocashException {
        Log.d(TAG, "Logging out all identities...");
        byte[] response = sendSecureApdu(CLA_BITCOIN, INS_LOGOUT_ALL, (byte) 0x00, (byte) 0x00, null);
        authenticated = false;
        Log.d(TAG, "Logged out successfully!");
        return true;
    }

    public int importMint(String mintUrl) throws SatocashException {
        Log.d(TAG, "Importing mint: " + mintUrl + "...");
        byte[] mintUrlBytes = mintUrl.getBytes();
        ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
        dataStream.write((byte) mintUrlBytes.length);
        try {
            dataStream.write(mintUrlBytes);
        } catch (IOException e) {
            throw new SatocashException("Error preparing mint URL data: " + e.getMessage(), SW_INTERNAL_ERROR);
        }

        byte[] response = sendSecureApdu(CLA_BITCOIN, INS_SATOCASH_IMPORT_MINT, (byte) 0x00, (byte) 0x00, dataStream.toByteArray());
        if (response != null && response.length > 0) {
            int mintIndex = response[0] & 0xFF;
            Log.d(TAG, "Mint imported successfully at index: " + mintIndex);
            return mintIndex;
        } else {
            throw new SatocashException("Mint import failed: empty response", SW_UNKNOWN_ERROR);
        }
    }

    public String exportMint(int mintIndex) throws SatocashException {
        Log.d(TAG, "Exporting mint at index " + mintIndex + "...");
        byte[] response = sendSecureApdu(CLA_BITCOIN, INS_SATOCASH_EXPORT_MINT, (byte) mintIndex, (byte) 0x00, null);
        if (response != null && response.length > 0) {
            int urlSize = response[0] & 0xFF;
            if (urlSize > 0 && response.length > 1) {
                String mintUrl = new String(Arrays.copyOfRange(response, 1, urlSize + 1));
                Log.d(TAG, "Mint URL: " + mintUrl);
                return mintUrl;
            } else {
                Log.d(TAG, "Empty mint slot.");
                return null;
            }
        } else {
            throw new SatocashException("Mint export failed: empty response", SW_UNKNOWN_ERROR);
        }
    }

    public boolean removeMint(int mintIndex) throws SatocashException {
        Log.d(TAG, "Removing mint at index " + mintIndex + "...");
        byte[] response = sendSecureApdu(CLA_BITCOIN, INS_SATOCASH_REMOVE_MINT, (byte) mintIndex, (byte) 0x00, null);
        Log.d(TAG, "Mint removed successfully!");
        return true;
    }

    public int importKeyset(String keysetIdHex, int mintIndex, Unit unit) throws SatocashException {
        Log.d(TAG, "Importing keyset: ID=" + keysetIdHex + ", Mint=" + mintIndex + ", Unit=" + unit.name() + "...");
        byte[] keysetIdBytes = hexStringToByteArray(keysetIdHex);
        ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
        try {
            dataStream.write(keysetIdBytes);
            dataStream.write((byte) mintIndex);
            dataStream.write((byte) unit.getValue());
        } catch (IOException e) {
            throw new SatocashException("Error preparing keyset data: " + e.getMessage(), SW_INTERNAL_ERROR);
        }

        byte[] response = sendSecureApdu(CLA_BITCOIN, INS_SATOCASH_IMPORT_KEYSET, (byte) 0x00, (byte) 0x00, dataStream.toByteArray());
        if (response != null && response.length > 0) {
            int keysetIndex = response[0] & 0xFF;
            Log.d(TAG, "Keyset imported successfully at index: " + keysetIndex);
            return keysetIndex;
        } else {
            throw new SatocashException("Keyset import failed: empty response", SW_UNKNOWN_ERROR);
        }
    }

    public static class KeysetInfo {
        public int index;
        public String id;
        public int mintIndex;
        public int unit;
    }

    public List<KeysetInfo> exportKeysets(List<Integer> keysetIndices) throws SatocashException {
        Log.d(TAG, "Exporting keysets: " + keysetIndices.toString() + "...");
        ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
        dataStream.write((byte) keysetIndices.size());
        for (int idx : keysetIndices) {
            dataStream.write((byte) idx);
        }

        byte[] response = sendSecureApdu(CLA_BITCOIN, INS_SATOCASH_EXPORT_KEYSET, (byte) 0x00, (byte) 0x00, dataStream.toByteArray());
        List<KeysetInfo> keysets = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.wrap(response);

        while (buffer.remaining() >= 11) { // 1 byte index + 8 bytes ID + 1 byte mint_index + 1 byte unit
            KeysetInfo keyset = new KeysetInfo();
            keyset.index = buffer.get() & 0xFF;
            byte[] keysetId = new byte[8];
            buffer.get(keysetId);
            keyset.id = bytesToHex(keysetId);
            keyset.mintIndex = buffer.get() & 0xFF;
            keyset.unit = buffer.get() & 0xFF;
            keysets.add(keyset);
            Log.d(TAG, String.format("  Index: %d, ID: %s, Mint: %d, Unit: %d",
                    keyset.index, keyset.id, keyset.mintIndex, keyset.unit));
        }
        Log.d(TAG, "Exported " + keysets.size() + " keysets.");
        return keysets;
    }

    public boolean removeKeyset(int keysetIndex) throws SatocashException {
        Log.d(TAG, "Removing keyset at index " + keysetIndex + "...");
        byte[] response = sendSecureApdu(CLA_BITCOIN, INS_SATOCASH_REMOVE_KEYSET, (byte) keysetIndex, (byte) 0x00, null);
        Log.d(TAG, "Keyset removed successfully!");
        return true;
    }

    public int importProof(int keysetIndex, int amountExponent, String unblindedKeyHex, String secretHex) throws SatocashException {
        Log.d(TAG, "Importing proof: Keyset=" + keysetIndex + ", AmountExp=" + amountExponent + "...");
        byte[] unblindedKeyBytes = hexStringToByteArray(unblindedKeyHex);
        byte[] secretBytes = hexStringToByteArray(secretHex);

        ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
        dataStream.write((byte) keysetIndex);
        dataStream.write((byte) amountExponent);
        try {
            dataStream.write(unblindedKeyBytes);
            dataStream.write(secretBytes);
        } catch (IOException e) {
            throw new SatocashException("Error preparing proof data: " + e.getMessage(), SW_INTERNAL_ERROR);
        }

        byte[] response = sendSecureApdu(CLA_BITCOIN, INS_SATOCASH_IMPORT_PROOF, (byte) 0x00, (byte) 0x00, dataStream.toByteArray());
        if (response != null && response.length >= 2) {
            int proofIndex = ((response[0] & 0xFF) << 8) | (response[1] & 0xFF);
            Log.d(TAG, "Proof imported successfully at index: " + proofIndex);
            return proofIndex;
        } else {
            throw new SatocashException("Proof import failed: empty or short response", SW_UNKNOWN_ERROR);
        }
    }

    public static class ProofInfo {
        public int index;
        public int state;
        public int keysetIndex;
        public int amountExponent;
        public byte[] unblindedKey;
        public byte[] secret;
    }

    public List<ProofInfo> exportProofs(List<Integer> proofIndices) throws SatocashException, IOException {
        Log.d(TAG, "Exporting proofs: " + proofIndices.toString() + "...");
        ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
        dataStream.write((byte) proofIndices.size());
        for (int idx : proofIndices) {
            dataStream.write(shortToBytes((short) idx));
        }

        List<ProofInfo> allProofs = new ArrayList<>();

        // Step 1: Initialize
        byte[] response = sendSecureApdu(CLA_BITCOIN, INS_SATOCASH_EXPORT_PROOFS, (byte) 0x00, OP_INIT, dataStream.toByteArray());
        if (response != null) {
            allProofs.addAll(parseProofResponse(response));
        }

        // Step 2: Process remaining proofs
        while (allProofs.size() < proofIndices.size()) {
            try {
                response = sendSecureApdu(CLA_BITCOIN, INS_SATOCASH_EXPORT_PROOFS, (byte) 0x00, OP_PROCESS, null);
                if (response == null || response.length == 0) {
                    break; // No more data
                }
                List<ProofInfo> currentProofs = parseProofResponse(response);
                if (currentProofs.isEmpty()) {
                    break; // No more data
                }
                allProofs.addAll(currentProofs);
            } catch (SatocashException e) {
                if (e.getSw() == SW_SEQUENCE_END) {
                    break; // Expected end of sequence
                }
                throw e; // Re-throw other errors
            }
        }
        Log.d(TAG, "Exported " + allProofs.size() + " proofs.");
        return allProofs;
    }

    private List<ProofInfo> parseProofResponse(byte[] response) {
        List<ProofInfo> proofs = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.wrap(response);

        while (buffer.remaining() >= 2 + 1 + 1 + 1 + 33 + 32) { // proof_index (2) + state (1) + keyset_index (1) + amount_exponent (1) + unblinded_key (33) + secret (32) = 70 bytes
            ProofInfo proof = new ProofInfo();
            proof.index = buffer.getShort() & 0xFFFF;
            proof.state = buffer.get() & 0xFF;
            proof.keysetIndex = buffer.get() & 0xFF;
            proof.amountExponent = buffer.get() & 0xFF;
            proof.unblindedKey = new byte[33];
            buffer.get(proof.unblindedKey);
            proof.secret = new byte[32];
            buffer.get(proof.secret);
            proofs.add(proof);
            Log.d(TAG, String.format("  Index: %d, State: %d, Keyset: %d, Amount exp: %d",
                    proof.index, proof.state, proof.keysetIndex, proof.amountExponent));
        }
        return proofs;
    }

    public List<Integer> getProofInfo(Unit unit, ProofInfoType infoType, int indexStart, int indexSize) throws SatocashException, IOException {
        Log.d(TAG, "Getting proof info: Unit=" + unit.name() + ", InfoType=" + infoType.name() + "...");
        ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
        dataStream.write(shortToBytes((short) indexStart));
        dataStream.write(shortToBytes((short) indexSize));
        byte[] response = sendSecureApdu(CLA_BITCOIN, INS_SATOCASH_GET_PROOF_INFO, (byte) unit.getValue(), (byte) infoType.getValue(), dataStream.toByteArray());
        Log.d(TAG, "Got proof info: " + bytesToHex(response));
        List<Integer> intList = new ArrayList<>();
        for (byte b : response) {
            intList.add((int)b & 0xFF);  // Convert to unsigned int
        }
        Log.d(TAG, "Converted to list: " + intList);
        return intList;
    }
    public boolean setCardLabel(String label) throws SatocashException {
        Log.d(TAG, "Setting card label: " + label + "...");
        byte[] labelBytes = label.getBytes();
        ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
        dataStream.write((byte) labelBytes.length);
        try {
            dataStream.write(labelBytes);
        } catch (IOException e) {
            throw new SatocashException("Error preparing label data: " + e.getMessage(), SW_INTERNAL_ERROR);
        }
        byte[] response = sendSecureApdu(CLA_BITCOIN, INS_CARD_LABEL, (byte) 0x00, (byte) 0x00, dataStream.toByteArray());
        Log.d(TAG, "Card label set successfully!");
        return true;
    }

    public String getCardLabel() throws SatocashException {
        Log.d(TAG, "Getting card label...");
        byte[] response = sendSecureApdu(CLA_BITCOIN, INS_CARD_LABEL, (byte) 0x00, (byte) 0x01, null);
        if (response != null && response.length > 0) {
            int labelSize = response[0] & 0xFF;
            if (labelSize > 0 && response.length > 1) {
                String label = new String(Arrays.copyOfRange(response, 1, labelSize + 1));
                Log.d(TAG, "Card label: " + label);
                return label;
            } else {
                Log.d(TAG, "No card label set.");
                return "";
            }
        } else {
            throw new SatocashException("Get card label failed: empty response", SW_UNKNOWN_ERROR);
        }
    }

    public boolean setNfcPolicy(int policy) throws SatocashException {
        Log.d(TAG, "Setting NFC policy: " + policy + "...");
        byte[] response = sendSecureApdu(CLA_BITCOIN, INS_SET_NFC_POLICY, (byte) policy, (byte) 0x00, null);
        Log.d(TAG, "NFC policy set successfully!");
        return true;
    }

    public boolean setPinPolicy(int policy) throws SatocashException {
        Log.d(TAG, "Setting PIN policy: " + policy + "...");
        byte[] response = sendSecureApdu(CLA_BITCOIN, INS_SET_PIN_POLICY, (byte) policy, (byte) 0x00, null);
        Log.d(TAG, "PIN policy set successfully!");
        return true;
    }

    public boolean setPinlessAmount(int amount) throws SatocashException {
        Log.d(TAG, "Setting PIN-less amount: " + amount + "...");
        byte[] amountBytes = ByteBuffer.allocate(4).putInt(amount).array();
        byte[] response = sendSecureApdu(CLA_BITCOIN, INS_SET_PINLESS_AMOUNT, (byte) 0x00, (byte) 0x00, amountBytes);
        Log.d(TAG, "PIN-less amount set successfully!");
        return true;
    }

    public static class AuthentikeyInfo {
        public byte[] coordX;
        public byte[] signature;
    }

    public AuthentikeyInfo exportAuthentikey() throws SatocashException {
        Log.d(TAG, "Exporting authentikey...");
        byte[] response = sendSecureApdu(CLA_BITCOIN, INS_EXPORT_AUTHENTIKEY, (byte) 0x00, (byte) 0x00, null);
        if (response != null && response.length >= 4) {
            ByteBuffer buffer = ByteBuffer.wrap(response);
            int coordXSize = buffer.getShort() & 0xFFFF;
            if (buffer.remaining() >= coordXSize + 2) {
                byte[] coordX = new byte[coordXSize];
                buffer.get(coordX);
                int sigSize = buffer.getShort() & 0xFFFF;
                if (buffer.remaining() >= sigSize) {
                    byte[] signature = new byte[sigSize];
                    buffer.get(signature);
                    AuthentikeyInfo info = new AuthentikeyInfo();
                    info.coordX = coordX;
                    info.signature = signature;
                    Log.d(TAG, "Authentikey exported successfully! CoordX: " + bytesToHex(coordX) + ", Sig: " + bytesToHex(signature));
                    return info;
                }
            }
        }
        throw new SatocashException("Export authentikey failed: unexpected response format", SW_UNKNOWN_ERROR);
    }

    public boolean initSecureChannel() throws SatocashException {
        Log.d(TAG, "Initializing Secure Channel...");
        try {
            byte[] clientPubKeyBytes = secureChannel.generateClientKeypair();
            Log.d(TAG, "Generated client public key: " + bytesToHex(clientPubKeyBytes));

            byte[] response = sendApdu(
                    CLA_BITCOIN,
                    INS_INIT_SECURE_CHANNEL,
                    (byte) 0x00, (byte) 0x00,
                    clientPubKeyBytes,
                    null
            );

            secureChannel.completeHandshake(response);
            secureChannelActive = true;
            Log.d(TAG, "Secure channel initialized successfully!");
            return true;
        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException | InvalidKeyException |
                 InvalidKeySpecException e) {
            Log.e(TAG, "Secure channel initialization failed: " + e.getMessage(), e);
            throw new SatocashException("Secure channel initialization failed: " + e.getMessage(), SW_INTERNAL_ERROR);
        } catch (NoSuchProviderException e) {
            throw new RuntimeException(e);
        }
    }

    public static class LogEntry {
        public int instruction;
        public int param1;
        public int param2;
        public int status;
    }

    public List<LogEntry> printLogs() throws SatocashException {
        Log.d(TAG, "Getting operation logs...");
        List<LogEntry> allLogs = new ArrayList<>();

        // Initialize
        byte[] response = sendSecureApdu(CLA_BITCOIN, INS_PRINT_LOGS, (byte) 0x00, OP_INIT, null);
        if (response != null && response.length >= 4) {
            ByteBuffer buffer = ByteBuffer.wrap(response);
            int totalLogs = buffer.getShort() & 0xFFFF;
            int availLogs = buffer.getShort() & 0xFFFF;
            Log.d(TAG, "Total logs: " + totalLogs + ", Available: " + availLogs);

            if (buffer.remaining() > 0) {
                allLogs.addAll(parseLogEntry(Arrays.copyOfRange(response, buffer.position(), response.length)));
            }
        } else {
            throw new SatocashException("Log initialization failed: empty or short response", SW_UNKNOWN_ERROR);
        }

        // Get remaining logs
        while (true) {
            try {
                response = sendSecureApdu(CLA_BITCOIN, INS_PRINT_LOGS, (byte) 0x00, OP_PROCESS, null);
                if (response == null || response.length == 0) {
                    break;
                }
                List<LogEntry> currentLogs = parseLogEntry(response);
                if (currentLogs.isEmpty()) {
                    break;
                }
                allLogs.addAll(currentLogs);
            } catch (SatocashException e) {
                if (e.getSw() == SW_SEQUENCE_END) {
                    break;
                }
                throw e;
            }
        }
        Log.d(TAG, "Retrieved " + allLogs.size() + " log entries.");
        return allLogs;
    }

    private List<LogEntry> parseLogEntry(byte[] logData) {
        List<LogEntry> logs = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.wrap(logData);
        while (buffer.remaining() >= 7) { // Each log entry is 7 bytes
            LogEntry entry = new LogEntry();
            entry.instruction = buffer.get() & 0xFF;
            entry.param1 = buffer.getShort() & 0xFFFF;
            entry.param2 = buffer.getShort() & 0xFFFF;
            entry.status = buffer.getShort() & 0xFFFF;
            logs.add(entry);
            Log.d(TAG, String.format("  INS: 0x%02X, P1: %d, P2: %d, SW: 0x%04X",
                    entry.instruction, entry.param1, entry.param2, entry.status));
        }
        return logs;
    }

    public byte[] exportPkiPubkey() throws SatocashException {
        Log.d(TAG, "Exporting PKI public key...");
        byte[] response = sendSecureApdu(CLA_BITCOIN, INS_EXPORT_PKI_PUBKEY, (byte) 0x00, (byte) 0x00, null);
        if (response != null && response.length == 65 && response[0] == 0x04) {
            Log.d(TAG, "PKI public key exported successfully: " + bytesToHex(response));
            return response;
        } else {
            throw new SatocashException("Export PKI public key failed: unexpected format", SW_UNKNOWN_ERROR);
        }
    }

    public byte[] signPkiCsr(byte[] hashData) throws SatocashException {
        Log.d(TAG, "Signing PKI CSR...");
        if (hashData.length != 32) {
            throw new SatocashException("Hash data must be exactly 32 bytes", SW_INVALID_PARAMETER);
        }
        byte[] response = sendSecureApdu(CLA_BITCOIN, INS_SIGN_PKI_CSR, (byte) 0x00, (byte) 0x00, hashData);
        if (response != null && response.length > 0) {
            Log.d(TAG, "PKI CSR signed successfully! Signature: " + bytesToHex(response));
            return response;
        } else {
            throw new SatocashException("Sign PKI CSR failed: empty response", SW_UNKNOWN_ERROR);
        }
    }

    public static class PkiChallengeResponse {
        public byte[] deviceChallenge;
        public byte[] signature;
    }

    public PkiChallengeResponse challengeResponsePki(byte[] challenge) throws SatocashException {
        Log.d(TAG, "PKI Challenge-Response...");
        if (challenge.length != 32) {
            throw new SatocashException("Challenge must be exactly 32 bytes", SW_INVALID_PARAMETER);
        }
        byte[] response = sendSecureApdu(CLA_BITCOIN, INS_CHALLENGE_RESPONSE_PKI, (byte) 0x00, (byte) 0x00, challenge);
        if (response != null && response.length >= 34) {
            ByteBuffer buffer = ByteBuffer.wrap(response);
            PkiChallengeResponse pkiResponse = new PkiChallengeResponse();
            pkiResponse.deviceChallenge = new byte[32];
            buffer.get(pkiResponse.deviceChallenge);
            int sigSize = buffer.getShort() & 0xFFFF;
            if (buffer.remaining() >= sigSize) {
                pkiResponse.signature = new byte[sigSize];
                buffer.get(pkiResponse.signature);
                Log.d(TAG, "PKI challenge-response successful! Device Challenge: " + bytesToHex(pkiResponse.deviceChallenge) + ", Signature: " + bytesToHex(pkiResponse.signature));
                return pkiResponse;
            }
        }
        throw new SatocashException("PKI challenge-response failed: unexpected response format", SW_UNKNOWN_ERROR);
    }

    public boolean lockPki() throws SatocashException {
        Log.d(TAG, "Locking PKI...");
        byte[] response = sendSecureApdu(CLA_BITCOIN, INS_LOCK_PKI, (byte) 0x00, (byte) 0x00, null);
        Log.d(TAG, "PKI locked successfully!");
        return true;
    }

    // Helper methods
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    private static byte[] shortToBytes(short s) {
        return new byte[]{(byte) ((s >> 8) & 0xFF), (byte) (s & 0xFF)};
    }
}
