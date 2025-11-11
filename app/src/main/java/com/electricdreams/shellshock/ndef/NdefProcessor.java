package com.electricdreams.shellshock.nfc;

import android.util.Log;
import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * Handles NDEF (NFC Data Exchange Format) message processing. Responsible for responding to
 * NDEF-related APDU commands.
 */
public class NdefProcessor {
    private static final String TAG = "NdefProcessor";

    // Command Headers
    private static final byte[] NDEF_SELECT_FILE_HEADER = {0x00, (byte) 0xA4, 0x00, 0x0C};
    
    // Step 1: Select AID (Application Identifier)
    private static final byte[] NDEF_SELECT_AID = {
            0x00,                     // CLA (Class)
            (byte) 0xA4,              // INS (Instruction)
            0x04,                     // P1 (Parameter 1)
            0x00,                     // P2 (Parameter 2)
            0x07,                     // Lc (Length of data)
            (byte) 0xD2, 0x76, 0x00, 0x00, (byte) 0x85, 0x01, 0x01, // AID (Application Identifier)
            0x00                      // Le (Length of expected response)
    };

    // Step 2: Select CC File (Capability Container)
    private static final byte[] CC_FILE_ID = {(byte) 0xE1, 0x03};
    private static final byte[] CC_FILE = {
            0x00, 0x0F,               // CCLEN = 15
            0x20,                     // Mapping version (2.0)
            0x00, 0x3B,               // MLe (max read)
            0x00, 0x34,               // MLc (max write)
            0x04,                     // T (NDEF File Control TLV)
            0x06,                     // L
            (byte) 0xE1, 0x04,        // File ID
            (byte) 0x70, (byte) 0xFF, // Size (0x70FF = 28,671 bytes)
            0x00,                     // Read access (unrestricted)
            0x00                      // Write access (unrestricted)
    };

    // Step 3: Select NDEF File
    private static final byte[] NDEF_FILE_ID = {(byte) 0xE1, 0x04};
    
    // Step 4: Read Binary Header
    private static final byte[] NDEF_READ_BINARY_HEADER = {0x00, (byte) 0xB0};
    
    // Step 5: Update Binary Header
    private static final byte[] NDEF_UPDATE_BINARY_HEADER = {0x00, (byte) 0xD6};

    // Success and Error Responses
    private static final byte[] NDEF_RESPONSE_OK = {(byte) 0x90, 0x00};
    public static final byte[] NDEF_RESPONSE_ERROR = {(byte) 0x6A, (byte) 0x82};

    // Message to be sent when in write mode
    private String messageToSend = "";
    
    // Flag to indicate if the processor is in write mode (NDEF tag emulation)
    private boolean isInWriteMode = false;
    
    // NDEF buffer for received data
    private byte[] ndefData = new byte[65536]; // 64KB max
    private int expectedNdefLength = -1;
    
    // Selected file during operation
    private byte[] selectedFile = null;
    
    // Callbacks
    public interface NdefMessageCallback {
        void onNdefMessageReceived(String message);
        void onMessageSent();
    }
    
    private NdefMessageCallback callback;

    public NdefProcessor(NdefMessageCallback callback) {
        this.callback = callback;
    }

    /**
     * Set the message to be sent when in write mode
     */
    public void setMessageToSend(String message) {
        this.messageToSend = message;
        Log.d(TAG, "Message to send set: " + message);
    }

    /**
     * Set whether the processor is in write mode (NDEF tag emulation)
     */
    public void setWriteMode(boolean enabled) {
        this.isInWriteMode = enabled;
        Log.d(TAG, "Write mode set to " + enabled);
        
        if (!enabled) {
            // Clear the message when disabling write mode
            this.messageToSend = "";
        }
    }

    /**
     * Create an NDEF message from a string
     */
    private byte[] createNdefMessage(String message) {
        byte[] languageCode = "en".getBytes(Charset.forName("US-ASCII"));
        byte[] textBytes = message.getBytes(Charset.forName("UTF-8"));
        byte statusByte = (byte) languageCode.length; // UTF-8 + language length
        
        // Create payload
        byte[] payload = new byte[1 + languageCode.length + textBytes.length];
        payload[0] = statusByte;
        System.arraycopy(languageCode, 0, payload, 1, languageCode.length);
        System.arraycopy(textBytes, 0, payload, 1 + languageCode.length, textBytes.length);
        
        // Create type
        byte[] type = "T".getBytes();
        
        // Create record header
        byte[] recordHeader = new byte[3 + type.length + payload.length];
        recordHeader[0] = (byte) 0xD1; // MB + ME + SR + TNF=1 (well-known)
        recordHeader[1] = (byte) type.length;
        recordHeader[2] = (byte) payload.length;
        System.arraycopy(type, 0, recordHeader, 3, type.length);
        System.arraycopy(payload, 0, recordHeader, 3 + type.length, payload.length);
        
        // Create full NDEF message
        int ndefLength = payload.length + 3 + type.length;
        byte[] fullMessage = new byte[2 + recordHeader.length];
        fullMessage[0] = (byte) ((ndefLength >> 8) & 0xFF);
        fullMessage[1] = (byte) (ndefLength & 0xFF);
        System.arraycopy(recordHeader, 0, fullMessage, 2, recordHeader.length);
        
        return fullMessage;
    }

    /**
     * Process an APDU command and return the appropriate response
     */
    public byte[] processCommandApdu(byte[] commandApdu) {
        // Check if NDEF AID is selected
        if (Arrays.equals(commandApdu, NDEF_SELECT_AID)) {
            Log.d(TAG, "NDEF AID selected");
            return NDEF_RESPONSE_OK;
        }
        
        // Handle File Selection
        if (commandApdu.length >= 7 && Arrays.equals(Arrays.copyOfRange(commandApdu, 0, 4), NDEF_SELECT_FILE_HEADER)) {
            Log.d(TAG, "SELECT FILE command received");
            return handleSelectFile(commandApdu);
        }
        
        // Handle Read Binary
        if (commandApdu.length >= 2 && Arrays.equals(Arrays.copyOfRange(commandApdu, 0, 2), NDEF_READ_BINARY_HEADER)) {
            Log.d(TAG, "READ BINARY command received");
            return handleReadBinary(commandApdu);
        }
        
        // Handle Update Binary
        if (commandApdu.length >= 2 && Arrays.equals(Arrays.copyOfRange(commandApdu, 0, 2), NDEF_UPDATE_BINARY_HEADER)) {
            Log.d(TAG, "UPDATE BINARY command received: " + bytesToHex(commandApdu));
            return handleUpdateBinary(commandApdu);
        }
        
        Log.d(TAG, "Invalid APDU received: " + bytesToHex(commandApdu));
        return NDEF_RESPONSE_ERROR;
    }

    /**
     * Handle SELECT FILE commands
     */
    private byte[] handleSelectFile(byte[] apdu) {
        byte[] fileId = Arrays.copyOfRange(apdu, 5, 7);
        
        if (Arrays.equals(fileId, CC_FILE_ID)) {
            selectedFile = CC_FILE;
            Log.d(TAG, "CC File selected");
            return NDEF_RESPONSE_OK;
        } else if (Arrays.equals(fileId, NDEF_FILE_ID)) {
            if (isInWriteMode && !messageToSend.isEmpty()) {
                Log.d(TAG, "NDEF File selected, using message: " + messageToSend);
                selectedFile = createNdefMessage(messageToSend);
                
                // Notify that the message is being sent
                if (callback != null) {
                    callback.onMessageSent();
                }
            } else {
                // Use empty message if we're not in write mode or no message is set
                Log.d(TAG, "NDEF File selected, using empty message");
                selectedFile = createNdefMessage("");
            }
            return NDEF_RESPONSE_OK;
        } else {
            Log.e(TAG, "Unknown file selected");
            return NDEF_RESPONSE_ERROR;
        }
    }

    /**
     * Handle READ BINARY commands
     */
    private byte[] handleReadBinary(byte[] apdu) {
        if (selectedFile == null || apdu.length < 5) return NDEF_RESPONSE_ERROR;
        
        // Determine the offset and length
        int length = (apdu[4] & 0xFF);
        if (length == 0) length = 256;
        int offset = ((apdu[2] & 0xFF) << 8) | (apdu[3] & 0xFF);
        
        if (offset + length > selectedFile.length) return NDEF_RESPONSE_ERROR;
        
        // Extract the requested data
        byte[] data = Arrays.copyOfRange(selectedFile, offset, offset + length);
        
        // Combine the data with the status word
        byte[] response = new byte[data.length + 2];
        System.arraycopy(data, 0, response, 0, data.length);
        System.arraycopy(NDEF_RESPONSE_OK, 0, response, data.length, 2);
        
        Log.d(TAG, "READ BINARY requested " + length + " bytes at offset " + offset);
        Log.d(TAG, "READ BINARY response: " + bytesToHex(response));
        
        return response;
    }

    /**
     * Handle UPDATE BINARY commands
     */
    private byte[] handleUpdateBinary(byte[] apdu) {
        if (selectedFile == null || apdu.length < 5) {
            Log.e(TAG, "UPDATE BINARY selectedFile is null or apdu.length < 5");
            return NDEF_RESPONSE_ERROR;
        }
        
        int offset = ((apdu[2] & 0xFF) << 8) | (apdu[3] & 0xFF);
        int dataLength = (apdu[4] & 0xFF);
        
        if (apdu.length < 5 + dataLength) {
            Log.e(TAG, "UPDATE BINARY apdu.length < 5 + dataLength");
            return NDEF_RESPONSE_ERROR;
        }
        
        // Cannot write to CC file
        if (Arrays.equals(selectedFile, CC_FILE)) {
            Log.e(TAG, "Attempt to write to CC file is forbidden");
            return NDEF_RESPONSE_ERROR;
        }
        
        byte[] data = Arrays.copyOfRange(apdu, 5, 5 + dataLength);
        
        // Prevent overflow
        if (offset + dataLength > ndefData.length) {
            Log.e(TAG, "UPDATE BINARY command would overflow NDEF data buffer");
            return NDEF_RESPONSE_ERROR;
        }
        
        Log.d(TAG, "UPDATE BINARY success, updated " + dataLength + " bytes at offset " + offset);
        Log.d(TAG, "UPDATE BINARY data: " + new String(data));
        Log.d(TAG, "UPDATE BINARY data hex: " + bytesToHex(data));
        
        // Store the data
        System.arraycopy(data, 0, ndefData, offset, dataLength);
        
        // If this is the first chunk of data, read the expected length
        if (offset == 0 && dataLength >= 2) {
            expectedNdefLength = ((ndefData[0] & 0xFF) << 8) | (ndefData[1] & 0xFF);
        }
        
        // Check if we have received the complete message
        if (expectedNdefLength != -1 && (offset + dataLength) >= (expectedNdefLength + 2)) {
            try {
                processReceivedNdefMessage(ndefData);
                // Reset for next message
                expectedNdefLength = -1;
            } catch (Exception e) {
                Log.e(TAG, "Error processing received NDEF message: " + e.getMessage());
            }
        }
        
        return NDEF_RESPONSE_OK;
    }

    /**
     * Process a received NDEF message
     */
    private void processReceivedNdefMessage(byte[] ndefData) {
        Log.d(TAG, "Processing received NDEF message");
        Log.d(TAG, "Hex dump: " + bytesToHex(Arrays.copyOfRange(ndefData, 0, Math.min(ndefData.length, 64))));
        
        int offset = 0;
        int totalLength = 0;
        
        // Detect framing:
        // Type 4: first two bytes form the NDEF file length
        if (ndefData.length >= 2) {
            Log.d(TAG, "Type 4 style NDEF");
            totalLength = ((ndefData[0] & 0xFF) << 8) | (ndefData[1] & 0xFF);
            offset = 2;
        } else {
            Log.e(TAG, "Invalid NDEF data");
            return;
        }
        
        try {
            // Read record header starting at offset
            byte header = ndefData[offset];
            int typeLength = ndefData[offset + 1] & 0xFF;
            
            // Determine payload length field size based on the SR flag (0x10)
            int payloadLength;
            int typeFieldStart;
            if ((header & 0x10) != 0) { // Short record: 1 byte payload length
                payloadLength = ndefData[offset + 2] & 0xFF;
                typeFieldStart = offset + 3;
            } else { // Normal record: payload length is 4 bytes
                payloadLength = ((ndefData[offset + 2] & 0xFF) << 24) |
                        ((ndefData[offset + 3] & 0xFF) << 16) |
                        ((ndefData[offset + 4] & 0xFF) << 8) |
                        (ndefData[offset + 5] & 0xFF);
                typeFieldStart = offset + 6;
            }
            
            // Verify the record type is "T" (0x54) for a Text record
            if (ndefData[typeFieldStart] != 0x54) {
                Log.d(TAG, "NDEF message is not a Text Record. Found type: " + 
                        (char) ndefData[typeFieldStart] + ", returning");
                return;
            }
            
            // Payload starts immediately after the type field
            int payloadStart = typeFieldStart + typeLength;
            if (payloadStart >= ndefData.length) {
                Log.e(TAG, "Payload start index out of bounds, returning");
                return;
            }
            
            // For a Text record, first payload byte is the status byte.
            byte status = ndefData[payloadStart];
            // Lower 6 bits of status indicate the language code length.
            int languageCodeLength = status & 0x3F;
            int textStart = payloadStart + 1 + languageCodeLength;
            int textLength = payloadLength - 1 - languageCodeLength;
            
            if (textStart + textLength > ndefData.length) {
                Log.e(TAG, "Text extraction bounds exceed data size.");
                return;
            }
            
            byte[] textBytes = Arrays.copyOfRange(ndefData, textStart, textStart + textLength);
            String text = new String(textBytes);
            
            Log.d(TAG, "Extracted text: " + text);
            
            // Call the callback if set
            if (callback != null) {
                callback.onNdefMessageReceived(text);
            }
            
            // Clear the buffer for next message
            Arrays.fill(ndefData, (byte) 0);
        } catch (Exception e) {
            Log.e(TAG, "Error extracting text from NDEF message: " + e.getMessage());
        }
    }

    /**
     * Convert a byte array to a hex string
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
