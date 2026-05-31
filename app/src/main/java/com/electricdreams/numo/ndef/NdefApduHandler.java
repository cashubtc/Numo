package com.electricdreams.numo.ndef;

import android.util.Log;
import java.util.Arrays;

/**
 * Handles APDU command processing for NDEF operations
 */
public class NdefApduHandler {
    private static final String TAG = "NdefApduHandler";
    
    private final NdefStateManager stateManager;
    
    public NdefApduHandler(NdefStateManager stateManager) {
        this.stateManager = stateManager;
    }
    
    /**
     * Handle SELECT FILE commands
     */
    public byte[] handleSelectFile(byte[] apdu) {
        byte[] fileId = Arrays.copyOfRange(apdu, 5, 7);
        
        if (Arrays.equals(fileId, NdefConstants.CC_FILE_ID)) {
            stateManager.setSelectedFile(NdefConstants.CC_FILE);
            Log.d(TAG, "CC File selected");
            return NdefConstants.NDEF_RESPONSE_OK;
        } else if (Arrays.equals(fileId, NdefConstants.NDEF_FILE_ID)) {
            // Only respond if we're in payment mode (write mode enabled with a message)
            if (stateManager.isInWriteMode() && !stateManager.getMessageToSend().isEmpty()) {
                Log.d(TAG, "NDEF File selected, in write mode with message: " + stateManager.getMessageToSend());
                byte[] ndefMessage = NdefMessageBuilder.createNdefMessage(stateManager.getMessageToSend());
                stateManager.setSelectedFile(ndefMessage);
                
                // Notify that the message is being sent
                if (stateManager.getCallback() != null) {
                    stateManager.getCallback().onMessageSent();
                }
                return NdefConstants.NDEF_RESPONSE_OK;
            } else {
                // Not in payment mode - return error to prevent exposing "en" language code
                Log.d(TAG, "NDEF File selected but not in payment mode (write mode: " + stateManager.isInWriteMode() + 
                      ", has message: " + !stateManager.getMessageToSend().isEmpty() + ") - returning error");
                return NdefConstants.NDEF_RESPONSE_ERROR;
            }
        } else {
            Log.e(TAG, "Unknown file selected");
            return NdefConstants.NDEF_RESPONSE_ERROR;
        }
    }
    
    /**
     * Handle READ BINARY commands
     */
    public byte[] handleReadBinary(byte[] apdu) {
        byte[] selectedFile = stateManager.getSelectedFile();
        if (selectedFile == null || apdu.length < 5) {
            return NdefConstants.NDEF_RESPONSE_ERROR;
        }
        
        // Determine the offset and length
        int length = (apdu[4] & 0xFF);
        if (length == 0) length = 256;
        int offset = ((apdu[2] & 0xFF) << 8) | (apdu[3] & 0xFF);
        
        if (offset >= selectedFile.length) {
            return NdefConstants.NDEF_RESPONSE_ERROR;
        }
        
        // Adjust length if requesting more than what's available
        if (offset + length > selectedFile.length) {
            length = selectedFile.length - offset;
        }
        
        // Extract the requested data
        byte[] data = Arrays.copyOfRange(selectedFile, offset, offset + length);
        
        // Combine the data with the status word
        byte[] response = new byte[data.length + 2];
        System.arraycopy(data, 0, response, 0, data.length);
        System.arraycopy(NdefConstants.NDEF_RESPONSE_OK, 0, response, data.length, 2);
        
        Log.d(TAG, "READ BINARY requested " + length + " bytes at offset " + offset);
        Log.d(TAG, "READ BINARY response: " + NdefUtils.bytesToHex(response));
        
        return response;
    }
}
