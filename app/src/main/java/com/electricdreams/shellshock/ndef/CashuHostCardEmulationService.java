package com.electricdreams.shellshock.ndef;

import android.nfc.cardemulation.HostApduService;
import com.electricdreams.shellshock.ndef.CashuPaymentHelper;
import com.electricdreams.shellshock.ndef.NdefProcessor;
import android.os.Bundle;
import android.util.Log;

/**
 * Host Card Emulation service for NDEF tag emulation to receive Cashu payments
 */
public class CashuHostCardEmulationService extends HostApduService {
    private static final String TAG = "CashuHCEService";
    
    // Status words for NFC communication
    private static final byte[] STATUS_SUCCESS = {(byte) 0x90, (byte) 0x00};
    private static final byte[] STATUS_FAILED = {(byte) 0x6F, (byte) 0x00};
    
    // AID for our service
    private static final byte[] AID_SELECT_APDU = {
        0x00, // CLA (Class)
        (byte) 0xA4, // INS (Instruction)
        0x04, // P1 (Parameter 1)
        0x00, // P2 (Parameter 2)
        0x07, // Lc (Length of data)
        (byte) 0xD2, 0x76, 0x00, 0x00, (byte) 0x85, 0x01, 0x01, // AID (Application Identifier for NDEF Type 4)
        0x00 // Le (Length of expected response)
    };
    
    private NdefProcessor ndefProcessor;
    private CashuPaymentCallback paymentCallback;
    private long expectedAmount = 0; // The expected amount for token validation
    
    // Singleton instance for access from activities
    private static CashuHostCardEmulationService instance;
    
    /**
     * Callback interface for Cashu payments
     */
    public interface CashuPaymentCallback {
        void onCashuTokenReceived(String token);
    }
    
    /**
     * Get the singleton instance
     */
    public static CashuHostCardEmulationService getInstance() {
        Log.d(TAG, "getInstance called, instance: " + (instance != null ? "available" : "null"));
        return instance;
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "CashuHostCardEmulationService created");
        
        try {
            // Create the NDEF processor
            ndefProcessor = new NdefProcessor(new NdefProcessor.NdefMessageCallback() {
                @Override
                public void onNdefMessageReceived(String message) {
                    Log.d(TAG, "Received NDEF message: " + message);
                    
                    // Check if it's a Cashu token
                    if (CashuPaymentHelper.isCashuToken(message)) {
                        Log.d(TAG, "Received potential Cashu token, validating...");
                        
                        // Validate the token against expected amount
                        boolean isValid = false;
                        if (expectedAmount > 0) {
                            isValid = CashuPaymentHelper.validateToken(message, expectedAmount);
                            if (!isValid) {
                                Log.e(TAG, "Token validation failed for amount: " + expectedAmount);
                            }
                        } else {
                            // If no expected amount, just do basic validation
                            Log.w(TAG, "No expected amount set for validation, performing basic check only");
                            isValid = true;
                        }
                        
                        if (isValid) {
                            Log.d(TAG, "Token passed validation");
                            
                            // Try to redeem the token
                            String redeemedToken = CashuPaymentHelper.redeemToken(message);
                            if (redeemedToken != null) {
                                Log.d(TAG, "Token successfully redeemed and reissued");
                                
                                // Notify the callback with the redeemed token
                                if (paymentCallback != null) {
                                    paymentCallback.onCashuTokenReceived(redeemedToken);
                                } else {
                                    Log.e(TAG, "Payment callback is null, can't deliver redeemed token");
                                }
                            } else {
                                Log.e(TAG, "Failed to redeem token");
                                // Notify of failure
                                if (paymentCallback != null) {
                                    // Still send the original token if redemption fails
                                    // This allows the app to handle the failure gracefully
                                    Log.w(TAG, "Sending original token despite redemption failure");
                                    paymentCallback.onCashuTokenReceived(message);
                                }
                            }
                        } else {
                            Log.e(TAG, "Token failed validation, ignoring");
                        }
                    } else {
                        Log.d(TAG, "Received message is not a Cashu token: " + message);
                    }
                }
                
                @Override
                public void onMessageSent() {
                    Log.d(TAG, "NDEF message sent");
                }
            });
            
            // Set the instance
            instance = this;
            Log.d(TAG, "CashuHostCardEmulationService initialization complete");
        } catch (Exception e) {
            Log.e(TAG, "Error creating HCE service: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "CashuHostCardEmulationService destroyed");
        
        // Clear the instance if this is the current one
        if (instance == this) {
            instance = null;
        }
    }

    @Override
    public byte[] processCommandApdu(byte[] commandApdu, Bundle extras) {
        Log.d(TAG, "Received APDU: " + bytesToHex(commandApdu));
        
        try {
            // Try to process with the NDEF processor
            byte[] response = ndefProcessor.processCommandApdu(commandApdu);
            
            if (response != NdefProcessor.NDEF_RESPONSE_ERROR) {
                return response;
            }
            
            // If not handled by the NDEF processor, try other commands
            if (isAidSelectCommand(commandApdu)) {
                Log.d(TAG, "AID select command received");
                return STATUS_SUCCESS;
            }
            
            // Unknown command
            Log.d(TAG, "Unknown command: " + bytesToHex(commandApdu));
            return STATUS_FAILED;
        } catch (Exception e) {
            Log.e(TAG, "Error processing APDU command: " + e.getMessage(), e);
            return STATUS_FAILED;
        }
    }

    @Override
    public void onDeactivated(int reason) {
        Log.d(TAG, "Deactivated: " + reason);
    }
    
    /**
     * Set the payment request to be sent
     * @param paymentRequest The payment request string
     * @param amount The expected amount in sats
     */
    public void setPaymentRequest(String paymentRequest, long amount) {
        Log.d(TAG, "Setting payment request: " + paymentRequest + " for amount: " + amount);
        this.expectedAmount = amount;
        if (ndefProcessor != null) {
            ndefProcessor.setMessageToSend(paymentRequest);
            ndefProcessor.setWriteMode(true);
        } else {
            Log.e(TAG, "NDEF processor is null, can't set payment request");
        }
    }
    
    /**
     * Set the payment request to be sent (without specifying an amount)
     * @param paymentRequest The payment request string
     */
    public void setPaymentRequest(String paymentRequest) {
        setPaymentRequest(paymentRequest, 0);
    }
    
    /**
     * Clear the payment request
     */
    public void clearPaymentRequest() {
        Log.d(TAG, "Clearing payment request");
        this.expectedAmount = 0;
        if (ndefProcessor != null) {
            ndefProcessor.setWriteMode(false);
        } else {
            Log.e(TAG, "NDEF processor is null, can't clear payment request");
        }
    }
    
    /**
     * Set the payment callback
     */
    public void setPaymentCallback(CashuPaymentCallback callback) {
        this.paymentCallback = callback;
        Log.d(TAG, "Payment callback set: " + (callback != null ? "yes" : "no"));
    }
    
    /**
     * Check if a command is a SELECT AID command
     */
    private boolean isAidSelectCommand(byte[] command) {
        if (command.length < AID_SELECT_APDU.length) {
            return false;
        }
        
        // Check if the first bytes match the AID select header
        for (int i = 0; i < AID_SELECT_APDU.length; i++) {
            if (command[i] != AID_SELECT_APDU[i]) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Convert a byte array to a hex string
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
    
    /**
     * Static method to check if HCE is available on this device
     */
    public static boolean isHceAvailable(android.content.Context context) {
        try {
            android.nfc.NfcManager manager = (android.nfc.NfcManager) context.getSystemService(android.content.Context.NFC_SERVICE);
            android.nfc.NfcAdapter adapter = manager.getDefaultAdapter();
            
            if (adapter == null) {
                Log.d(TAG, "NFC is not supported on this device");
                return false;
            }
            
            if (!adapter.isEnabled()) {
                Log.d(TAG, "NFC is disabled on this device");
                return false;
            }
            
            if (!context.getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) {
                Log.d(TAG, "HCE is not supported on this device");
                return false;
            }
            
            Log.d(TAG, "HCE is available on this device");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error checking HCE availability: " + e.getMessage(), e);
            return false;
        }
    }
}
