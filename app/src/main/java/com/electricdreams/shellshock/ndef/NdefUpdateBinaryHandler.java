package com.electricdreams.shellshock.ndef;

import android.util.Log;
import java.util.Arrays;

/**
 * Handles UPDATE BINARY APDU commands for NDEF operations
 */
public class NdefUpdateBinaryHandler {
    private static final String TAG = "NdefUpdateBinaryHandler";
    
    private final NdefStateManager stateManager;
    private final NdefMessageParser messageParser;
    
    public NdefUpdateBinaryHandler(NdefStateManager stateManager, NdefMessageParser messageParser) {
        this.stateManager = stateManager;
        this.messageParser = messageParser;
    }
    
    /**
     * Handle UPDATE BINARY commands
     */
    public byte[] handleUpdateBinary(byte[] apdu) {
        byte[] selectedFile = stateManager.getSelectedFile();
        if (selectedFile == null || apdu.length < 5) {
            Log.e(TAG, "UPDATE BINARY selectedFile is null or apdu.length < 5");
            return NdefConstants.NDEF_RESPONSE_ERROR;
        }
        
        int offset = ((apdu[2] & 0xFF) << 8) | (apdu[3] & 0xFF);
        int dataLength = (apdu[4] & 0xFF);
        
        Log.d(TAG, "UPDATE BINARY with offset=" + offset + ", length=" + dataLength);
        
        if (apdu.length < 5 + dataLength) {
            Log.e(TAG, "UPDATE BINARY apdu.length < 5 + dataLength: " + apdu.length + " < " + (5 + dataLength));
            return NdefConstants.NDEF_RESPONSE_ERROR;
        }
        
        // Cannot write to CC file
        if (Arrays.equals(selectedFile, NdefConstants.CC_FILE)) {
            Log.e(TAG, "Attempt to write to CC file is forbidden");
            return NdefConstants.NDEF_RESPONSE_ERROR;
        }
        
        byte[] data = Arrays.copyOfRange(apdu, 5, 5 + dataLength);
        
        // Prevent overflow
        byte[] ndefData = stateManager.getNdefData();
        if (offset + dataLength > ndefData.length) {
            Log.e(TAG, "UPDATE BINARY command would overflow NDEF data buffer");
            return NdefConstants.NDEF_RESPONSE_ERROR;
        }
        
        Log.d(TAG, "UPDATE BINARY storing " + dataLength + " bytes at offset " + offset);
        if (dataLength > 0) {
            logDataContent(data);
        }
        
        // Store the data
        System.arraycopy(data, 0, ndefData, offset, dataLength);
        
        // Update the last message activity time whenever we receive data
        stateManager.updateLastMessageActivityTime();
        
        // Process length header updates
        if (offset == 0 && dataLength >= 2) {
            return handleLengthHeaderUpdate(ndefData, offset, dataLength);
        }
        
        // Check if we have received the complete message
        return checkForCompleteMessage(offset, dataLength);
    }
    
    /**
     * Log data content for debugging
     */
    private void logDataContent(byte[] data) {
        try {
            Log.d(TAG, "Data (if text): " + new String(data, "UTF-8"));
        } catch (Exception e) {
            // Ignore if not valid UTF-8
        }
        Log.d(TAG, "Data (hex): " + NdefUtils.bytesToHex(data));
    }
    
    /**
     * Handle updates to the length header
     */
    private byte[] handleLengthHeaderUpdate(byte[] ndefData, int offset, int dataLength) {
        int newLength = ((ndefData[0] & 0xFF) << 8) | (ndefData[1] & 0xFF);
        
        // Don't reset expectedNdefLength if the new length is 0 (could be initialization)
        if (newLength > 0) {
            Log.d(TAG, "NDEF message length updated: " + newLength + " bytes");
            stateManager.setExpectedNdefLength(newLength);
            
            // Check if we have any non-zero data beyond the header
            boolean hasData = hasNonZeroData(ndefData, newLength);
            
            if (hasData) {
                Log.d(TAG, "Length header updated and there appears to be data already in buffer. Processing message.");
                return processMessageAndReset(ndefData);
            }
            
            // Original check for cases where data is provided with the header
            else if (stateManager.getExpectedNdefLength() > 0 && offset + dataLength >= stateManager.getExpectedNdefLength() + 2) {
                Log.d(TAG, "Length header updated and we have enough data to process the message");
                return processMessageAndReset(ndefData);
            }
        } else if (newLength == 0) {
            // This is likely an initialization or empty message - log but don't process
            Log.d(TAG, "Received zero-length NDEF message header - ignoring as likely initialization");
            // We'll still set expectedNdefLength for completeness, but we won't process this as a complete message
            stateManager.setExpectedNdefLength(newLength);
            return NdefConstants.NDEF_RESPONSE_OK;
        }
        
        return NdefConstants.NDEF_RESPONSE_OK;
    }
    
    /**
     * Check if there's non-zero data in the buffer
     */
    private boolean hasNonZeroData(byte[] ndefData, int expectedLength) {
        for (int i = 2; i < expectedLength + 2; i++) {
            if (ndefData[i] != 0) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Check if we have received the complete message
     */
    private byte[] checkForCompleteMessage(int offset, int dataLength) {
        int expectedNdefLength = stateManager.getExpectedNdefLength();
        
        if (expectedNdefLength > 0) { // Changed from != -1 to > 0 to prevent processing zero-length messages
            Log.d(TAG, "Current position: " + (offset + dataLength) + ", need: " + (expectedNdefLength + 2));
            
            if ((offset + dataLength) >= (expectedNdefLength + 2)) {
                Log.d(TAG, "Complete NDEF message received, processing...");
                return processMessageAndReset(stateManager.getNdefData());
            } else {
                return handlePartialMessage();
            }
        }
        
        return NdefConstants.NDEF_RESPONSE_OK;
    }
    
    /**
     * Handle cases where we have partial message data
     */
    private byte[] handlePartialMessage() {
        // Check if we have data in the buffer but are just waiting for more
        // This might indicate we received chunks out of order or the final message was incomplete
        byte[] ndefData = stateManager.getNdefData();
        int expectedNdefLength = stateManager.getExpectedNdefLength();
        
        boolean hasData = hasNonZeroData(ndefData, expectedNdefLength);
        
        if (hasData) {
            // We have some data already - start a timeout handler to process partial data if needed
            Log.d(TAG, "Waiting for more data to complete NDEF message, but data already exists in buffer");
            
            // Set last activity time for timeout tracking
            if (stateManager.getLastMessageActivityTime() == 0) {
                stateManager.updateLastMessageActivityTime();
            } else {
                // Check if we've been waiting too long without receiving new data
                long currentTime = System.currentTimeMillis();
                if (currentTime - stateManager.getLastMessageActivityTime() > NdefConstants.MESSAGE_TIMEOUT_MS) {
                    Log.i(TAG, "Message reception timeout reached. Processing with available data.");
                    return processMessageAndReset(ndefData);
                }
            }
        } else {
            Log.d(TAG, "Waiting for more data to complete NDEF message");
        }
        
        return NdefConstants.NDEF_RESPONSE_OK;
    }
    
    /**
     * Process the received message and reset state
     */
    private byte[] processMessageAndReset(byte[] ndefData) {
        try {
            // Make a defensive copy of just the relevant portion of the buffer
            int expectedNdefLength = stateManager.getExpectedNdefLength();
            int copyLength = ndefData.length;
            if (expectedNdefLength > 0 && expectedNdefLength + 2 <= ndefData.length) {
                // 2 extra bytes for the NDEF length header
                copyLength = expectedNdefLength + 2;
            }

            final byte[] ndefCopy = Arrays.copyOf(ndefData, copyLength);
            final boolean shouldProcess = stateManager.isProcessIncomingMessages();

            Log.d(TAG, "Spawning async task to process received NDEF message (length=" + copyLength + ", process=" + shouldProcess + ")");

            // Process the message on a background thread so we can return 0x9000
            // to the reader immediately and not block the APDU flow on payment logic.
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        messageParser.processReceivedNdefMessage(ndefCopy, shouldProcess);
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing received NDEF message asynchronously: " + e.getMessage(), e);
                    } finally {
                        // Once processing is done (success or failure), reset for the next message
                        stateManager.resetForNextMessage();
                        Log.d(TAG, "Async NDEF processing complete, state reset for next message");
                    }
                }
            }, "NdefMessageProcessor");

            worker.start();
        } catch (Exception e) {
            Log.e(TAG, "Error scheduling async processing for received NDEF message: " + e.getMessage(), e);
        }

        // Always acknowledge the UPDATE BINARY APDU immediately at the transport layer.
        // Any payment/token errors are handled at the application layer and must not
        // delay or change the APDU status word.
        return NdefConstants.NDEF_RESPONSE_OK;
    }
}
