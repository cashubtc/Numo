package com.electricdreams.shellshock.ndef;

import android.util.Log;
import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * Handles NDEF (NFC Data Exchange Format) message processing. Responsible for responding to
 * NDEF-related APDU commands.
 */
public class NdefProcessor {
    private static final String TAG = "NdefProcessor";

    // Timeout for waiting for NDEF message completion (3 seconds)
    private static final long MESSAGE_TIMEOUT_MS = 3000;
    
    // Track last message activity time for timeout handling
    private long lastMessageActivityTime = 0;

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
        Log.i(TAG, "Message to send set: " + message);
    }

    /**
     * Set whether the processor is in write mode (NDEF tag emulation)
     * Note: The processor will always be able to receive NDEF messages, regardless of write mode
     * When write mode is enabled, it will also send messages
     */
    public void setWriteMode(boolean enabled) {
        this.isInWriteMode = enabled;
        Log.i(TAG, "Write mode set to " + enabled);
        
        if (enabled) {
            Log.i(TAG, "Processor is now in write mode, ready to send message: " + 
                  (messageToSend.isEmpty() ? "<empty>" : messageToSend));
        } else {
            // Keep the message when disabling write mode, just don't send it
            Log.i(TAG, "Processor is now in read-only mode, message preserved but not being sent");
        }
        
        // Always keep the ability to receive NDEF messages, regardless of write mode setting
        Log.i(TAG, "Processor will always accept incoming NDEF messages");
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
            Log.d(TAG, "NDEF AID selected (write mode: " + isInWriteMode + 
                  ", has message: " + !messageToSend.isEmpty() + ")");
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
            // Check if we should send a message (only if in write mode)
            if (isInWriteMode && !messageToSend.isEmpty()) {
                Log.d(TAG, "NDEF File selected, in write mode with message: " + messageToSend);
                selectedFile = createNdefMessage(messageToSend);
                
                // Notify that the message is being sent
                if (callback != null) {
                    callback.onMessageSent();
                }
            } else {
                // Only send empty message if write mode is disabled or no message is set
                Log.d(TAG, "NDEF File selected, using empty message (write mode: " + isInWriteMode + 
                      ", has message: " + !messageToSend.isEmpty() + ")");
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
        
        Log.d(TAG, "UPDATE BINARY with offset=" + offset + ", length=" + dataLength);
        
        if (apdu.length < 5 + dataLength) {
            Log.e(TAG, "UPDATE BINARY apdu.length < 5 + dataLength: " + apdu.length + " < " + (5 + dataLength));
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
        
        Log.d(TAG, "UPDATE BINARY storing " + dataLength + " bytes at offset " + offset);
        if (dataLength > 0) {
            try {
                Log.d(TAG, "Data (if text): " + new String(data, "UTF-8"));
            } catch (Exception e) {
                // Ignore if not valid UTF-8
            }
            Log.d(TAG, "Data (hex): " + bytesToHex(data));
        }
        
        // Store the data
        System.arraycopy(data, 0, ndefData, offset, dataLength);
        
        // Update the last message activity time whenever we receive data
        lastMessageActivityTime = System.currentTimeMillis();
        
        // If this updates the length header (offset 0, length >=2)
        if (offset == 0 && dataLength >= 2) {
            int newLength = ((ndefData[0] & 0xFF) << 8) | (ndefData[1] & 0xFF);
            
            // Don't reset expectedNdefLength if the new length is 0 (could be initialization)
            if (newLength > 0) {
                Log.d(TAG, "NDEF message length updated: " + newLength + " bytes");
                expectedNdefLength = newLength;
                
                // Check if we have any non-zero data beyond the header
                boolean hasData = false;
                for (int i = 2; i < newLength + 2; i++) {
                    if (ndefData[i] != 0) {
                        hasData = true;
                        break;
                    }
                }
                
                if (hasData) {
                    Log.d(TAG, "Length header updated and there appears to be data already in buffer. Processing message.");
                    try {
                        processReceivedNdefMessage(ndefData);
                        // Reset for next message
                        expectedNdefLength = -1;
                        Arrays.fill(ndefData, (byte) 0);
                        lastMessageActivityTime = 0;
                        return NDEF_RESPONSE_OK;
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing received NDEF message after length update: " + e.getMessage(), e);
                    }
                }
                
                // Original check for cases where data is provided with the header
                else if (expectedNdefLength > 0 && offset + dataLength >= expectedNdefLength + 2) {
                    Log.d(TAG, "Length header updated and we have enough data to process the message");
                    try {
                        processReceivedNdefMessage(ndefData);
                        // Reset for next message
                        expectedNdefLength = -1;
                        Arrays.fill(ndefData, (byte) 0);
                        lastMessageActivityTime = 0;
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing received NDEF message after length update: " + e.getMessage(), e);
                    }
                }
            } else if (newLength == 0) {
                // This is likely an initialization or empty message - log but don't process
                Log.d(TAG, "Received zero-length NDEF message header - ignoring as likely initialization");
                // We'll still set expectedNdefLength for completeness, but we won't process this as a complete message
                expectedNdefLength = newLength;
                return NDEF_RESPONSE_OK;
            }
        }
        
        // Check if we have received the complete message
        if (expectedNdefLength > 0) { // Changed from != -1 to > 0 to prevent processing zero-length messages
            Log.d(TAG, "Current position: " + (offset + dataLength) + ", need: " + (expectedNdefLength + 2));
            
            if ((offset + dataLength) >= (expectedNdefLength + 2)) {
                Log.d(TAG, "Complete NDEF message received, processing...");
                try {
                    processReceivedNdefMessage(ndefData);
                    // Reset for next message
                    expectedNdefLength = -1;
                    Arrays.fill(ndefData, (byte) 0);
                    lastMessageActivityTime = 0;
                } catch (Exception e) {
                    Log.e(TAG, "Error processing received NDEF message: " + e.getMessage(), e);
                }
            } else {
                // Check if we have data in the buffer but are just waiting for more
                // This might indicate we received chunks out of order or the final message was incomplete
                boolean hasData = false;
                for (int i = 2; i < Math.min(ndefData.length, expectedNdefLength + 2); i++) {
                    if (ndefData[i] != 0) {
                        hasData = true;
                        break;
                    }
                }
                
                if (hasData) {
                    // We have some data already - start a timeout handler to process partial data if needed
                    Log.d(TAG, "Waiting for more data to complete NDEF message, but data already exists in buffer");
                    
                    // Set last activity time for timeout tracking
                    if (lastMessageActivityTime == 0) {
                        lastMessageActivityTime = System.currentTimeMillis();
                    } else {
                        // Check if we've been waiting too long without receiving new data
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastMessageActivityTime > MESSAGE_TIMEOUT_MS) {
                            Log.i(TAG, "Message reception timeout reached. Processing with available data.");
                            try {
                                processReceivedNdefMessage(ndefData);
                                // Reset for next message
                                expectedNdefLength = -1;
                                Arrays.fill(ndefData, (byte) 0);
                                lastMessageActivityTime = 0;
                            } catch (Exception e) {
                                Log.e(TAG, "Error processing received NDEF message after timeout: " + e.getMessage(), e);
                            }
                        }
                    }
                } else {
                    Log.d(TAG, "Waiting for more data to complete NDEF message");
                }
            }
        }
        
        return NDEF_RESPONSE_OK;
    }

    /**
     * Process a received NDEF message
     */
    private void processReceivedNdefMessage(byte[] ndefData) {
        Log.i(TAG, "Processing received NDEF message");
        Log.i(TAG, "Hex dump: " + bytesToHex(Arrays.copyOfRange(ndefData, 0, Math.min(ndefData.length, 100))));
        
        int offset = 0;
        int totalLength = 0;
        
        // Detect framing:
        // Type 4: first two bytes form the NDEF file length
        if (ndefData.length >= 2) {
            Log.i(TAG, "Type 4 style NDEF");
            totalLength = ((ndefData[0] & 0xFF) << 8) | (ndefData[1] & 0xFF);
            Log.i(TAG, "NDEF message total length from header: " + totalLength);
            
            // Validate message length - don't process empty or very short messages
            if (totalLength <= 0) {
                Log.e(TAG, "Invalid NDEF data - zero or negative length in header, ignoring message");
                return;
            }
            
            // Ensure we have enough data
            if (totalLength + 2 > ndefData.length) {
                Log.e(TAG, "Incomplete NDEF data - header specifies " + totalLength + 
                      " bytes but we only have " + (ndefData.length - 2) + " bytes of payload");
                return;
            }
            
            offset = 2;
        } else {
            Log.e(TAG, "Invalid NDEF data - length less than 2 bytes");
            return;
        }
        
        try {
            if (offset >= ndefData.length) {
                Log.e(TAG, "Invalid offset beyond data length");
                return;
            }
            
            // Read record header starting at offset
            byte header = ndefData[offset];
            Log.i(TAG, "NDEF header byte: 0x" + String.format("%02X", header));
            
            if (offset + 1 >= ndefData.length) {
                Log.e(TAG, "Invalid data - can't read type length");
                return;
            }
            
            int typeLength = ndefData[offset + 1] & 0xFF;
            Log.i(TAG, "NDEF type length: " + typeLength);
            
            // Additional validation for type length
            if (typeLength <= 0) {
                Log.e(TAG, "Invalid type length: " + typeLength);
                return;
            }
            
            // Determine payload length field size based on the SR flag (0x10)
            int payloadLength;
            int typeFieldStart;
            
            // Check SR (Short Record) flag
            boolean isShortRecord = (header & 0x10) != 0;
            Log.i(TAG, "Is short record: " + isShortRecord);
            
            if (isShortRecord) { // Short record: 1 byte payload length
                if (offset + 2 >= ndefData.length) {
                    Log.e(TAG, "Invalid data - can't read short record payload length");
                    return;
                }
                
                payloadLength = ndefData[offset + 2] & 0xFF;
                typeFieldStart = offset + 3;
                Log.i(TAG, "Short record payload length: " + payloadLength);
            } else { // Normal record: payload length is 4 bytes
                if (offset + 5 >= ndefData.length) {
                    Log.e(TAG, "Invalid data - can't read normal record payload length");
                    return;
                }
                
                payloadLength = ((ndefData[offset + 2] & 0xFF) << 24) |
                        ((ndefData[offset + 3] & 0xFF) << 16) |
                        ((ndefData[offset + 4] & 0xFF) << 8) |
                        (ndefData[offset + 5] & 0xFF);
                typeFieldStart = offset + 6;
                Log.i(TAG, "Normal record payload length: " + payloadLength);
            }
            
            // Validate payload length
            if (payloadLength <= 0) {
                Log.e(TAG, "Invalid payload length: " + payloadLength);
                return;
            }
            
            // Safety check for typeFieldStart
            if (typeFieldStart >= ndefData.length) {
                Log.e(TAG, "Invalid typeFieldStart beyond data length");
                return;
            }
            
            // Check TNF (Type Name Format)
            int tnf = header & 0x07;
            Log.i(TAG, "TNF: " + tnf);
            
            // Check if we have a valid type field
            if (typeFieldStart + typeLength > ndefData.length) {
                Log.e(TAG, "Type field extends beyond data bounds");
                return;
            }
            
            // Get the record type
            byte[] typeField = Arrays.copyOfRange(ndefData, typeFieldStart, typeFieldStart + typeLength);
            String typeStr = new String(typeField);
            Log.i(TAG, "Record type: " + typeStr + " (hex: " + bytesToHex(typeField) + ")");
            
            // For text records, verify the record type is "T" (0x54)
            // For URI records, verify the record type is "U" (0x55)
            boolean isTextRecord = (typeLength == 1 && ndefData[typeFieldStart] == 0x54);
            boolean isUriRecord = (typeLength == 1 && ndefData[typeFieldStart] == 0x55);
            
            Log.i(TAG, "Is Text Record: " + isTextRecord);
            Log.i(TAG, "Is URI Record: " + isUriRecord);
            
            if (!isTextRecord && !isUriRecord) {
                Log.w(TAG, "NDEF message is neither a Text Record nor URI Record. Type: " + 
                      bytesToHex(typeField) + ", returning");
                return;
            }
            
            // Payload starts immediately after the type field
            int payloadStart = typeFieldStart + typeLength;
            Log.i(TAG, "Payload start position: " + payloadStart);
            
            if (payloadStart >= ndefData.length) {
                Log.e(TAG, "Payload start index out of bounds, returning");
                return;
            }
            
            if (isTextRecord) {
                // For a Text record, first payload byte is the status byte
                byte status = ndefData[payloadStart];
                // Lower 6 bits of status indicate the language code length
                int languageCodeLength = status & 0x3F;
                Log.i(TAG, "Language code length: " + languageCodeLength);
                
                int textStart = payloadStart + 1 + languageCodeLength;
                int textLength = payloadLength - 1 - languageCodeLength;
                Log.i(TAG, "Text start position: " + textStart + ", length: " + textLength);
                
                if (textStart + textLength > ndefData.length) {
                    Log.e(TAG, "Text extraction bounds exceed data size: " + 
                          textStart + " + " + textLength + " > " + ndefData.length);
                    return;
                }
                
                byte[] textBytes = Arrays.copyOfRange(ndefData, textStart, textStart + textLength);
                String text = new String(textBytes, "UTF-8");
                
                Log.i(TAG, "Extracted text: " + text);
                
                // Call the callback if set
                if (callback != null) {
                    Log.i(TAG, "Calling onNdefMessageReceived with text: " + text);
                    callback.onNdefMessageReceived(text);
                } else {
                    Log.e(TAG, "Callback is null, can't deliver message");
                }
            } else if (isUriRecord) {
                // URI Record handling - first byte is the URI identifier code
                byte uriIdentifierCode = ndefData[payloadStart];
                Log.i(TAG, "URI identifier code: " + uriIdentifierCode);
                
                int uriStart = payloadStart + 1;
                int uriLength = payloadLength - 1;
                Log.i(TAG, "URI start position: " + uriStart + ", length: " + uriLength);
                
                if (uriStart + uriLength > ndefData.length) {
                    Log.e(TAG, "URI extraction bounds exceed data size");
                    return;
                }
                
                byte[] uriBytes = Arrays.copyOfRange(ndefData, uriStart, uriStart + uriLength);
                String uri = new String(uriBytes, "UTF-8");
                
                // Prepend the URI prefix according to the identifier code
                String prefix = getUriPrefix(uriIdentifierCode);
                String fullUri = prefix + uri;
                Log.i(TAG, "Extracted URI: " + fullUri);
                
                // Call the callback if set
                if (callback != null) {
                    Log.i(TAG, "Calling onNdefMessageReceived with URI: " + fullUri);
                    callback.onNdefMessageReceived(fullUri);
                } else {
                    Log.e(TAG, "Callback is null, can't deliver message");
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting data from NDEF message: " + e.getMessage(), e);
        }
    }
    
    /**
     * Get the URI prefix for a URI identifier code
     */
    private String getUriPrefix(byte code) {
        switch (code) {
            case 0x00: return ""; // No prefix
            case 0x01: return "http://www.";
            case 0x02: return "https://www.";
            case 0x03: return "http://";
            case 0x04: return "https://";
            case 0x05: return "tel:";
            case 0x06: return "mailto:";
            case 0x07: return "ftp://anonymous:anonymous@";
            case 0x08: return "ftp://ftp.";
            case 0x09: return "ftps://";
            case 0x0A: return "sftp://";
            case 0x0B: return "smb://";
            case 0x0C: return "nfs://";
            case 0x0D: return "ftp://";
            case 0x0E: return "dav://";
            case 0x0F: return "news:";
            case 0x10: return "telnet://";
            case 0x11: return "imap:";
            case 0x12: return "rtsp://";
            case 0x13: return "urn:";
            case 0x14: return "pop:";
            case 0x15: return "sip:";
            case 0x16: return "sips:";
            case 0x17: return "tftp:";
            case 0x18: return "btspp://";
            case 0x19: return "btl2cap://";
            case 0x1A: return "btgoep://";
            case 0x1B: return "tcpobex://";
            case 0x1C: return "irdaobex://";
            case 0x1D: return "file://";
            case 0x1E: return "urn:epc:id:";
            case 0x1F: return "urn:epc:tag:";
            case 0x20: return "urn:epc:pat:";
            case 0x21: return "urn:epc:raw:";
            case 0x22: return "urn:epc:";
            case 0x23: return "urn:nfc:";
            default: return "";
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
