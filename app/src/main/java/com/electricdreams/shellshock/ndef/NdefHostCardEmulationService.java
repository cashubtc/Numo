package com.electricdreams.shellshock.ndef;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.os.IBinder;
import android.content.Intent;
import android.util.Log;

/**
 * Host Card Emulation service for NDEF tag emulation to receive Cashu payments
 */
public class NdefHostCardEmulationService extends HostApduService {
    private static final String TAG = "NdefHCEService";
    
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
    private static NdefHostCardEmulationService instance;
    
    /**
     * Callback interface for Cashu payments
     */
    public interface CashuPaymentCallback {
        void onCashuTokenReceived(String token);
        void onCashuPaymentError(String errorMessage);
    }
    
    /**
     * Get the singleton instance
     */
    public static NdefHostCardEmulationService getInstance() {
        Log.i(TAG, "getInstance called, instance: " + (instance != null ? "available" : "null"));
        return instance;
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "=== NdefHostCardEmulationService created ===");
        
        try {
            // Create the NDEF processor
            ndefProcessor = new NdefProcessor(new NdefProcessor.NdefMessageCallback() {
                @Override
                public void onNdefMessageReceived(String message) {
                    try {
                        // Log all NDEF messages at the INFO level to ensure visibility
                        Log.i(TAG, "========== RECEIVED NDEF MESSAGE ==========");
                        Log.i(TAG, "Message content: " + message);
                        
                        // First try to extract a Cashu token from the message
                        String cashuToken = CashuPaymentHelper.extractCashuToken(message);
                        
                        if (cashuToken != null) {
                            Log.i(TAG, "Extracted Cashu token: " + cashuToken);
                            
                            // Validate the token against expected amount
                            boolean isValid = false;
                            if (expectedAmount > 0) {
                                Log.i(TAG, "Validating token for expected amount: " + expectedAmount);
                                isValid = CashuPaymentHelper.validateToken(cashuToken, expectedAmount);
                                if (!isValid) {
                                    String errorMsg = "Token validation failed for amount: " + expectedAmount;
                                    Log.e(TAG, errorMsg);
                                    
                                    // Clear payment request on validation failure
                                    clearPaymentRequest();
                                    
                                    // Notify callback of validation error
                                    if (paymentCallback != null) {
                                        Log.i(TAG, "Calling error callback with: " + errorMsg);
                                        paymentCallback.onCashuPaymentError(errorMsg);
                                    } else {
                                        Log.e(TAG, "Payment callback is null, can't report validation error");
                                    }
                                    return;
                                }
                                Log.i(TAG, "Token passed amount validation for " + expectedAmount + " sats");
                            } else {
                                // If no expected amount, just do basic validation
                                Log.w(TAG, "No expected amount set for validation, performing basic check only");
                                isValid = true;
                            }
                            
                            if (isValid) {
                                Log.i(TAG, "Token passed validation, attempting redemption...");
                                
                                try {
                                    // Try to redeem the token - this will throw an exception if redemption fails
                                    String redeemedToken = CashuPaymentHelper.redeemToken(cashuToken);
                                    Log.i(TAG, "Token successfully redeemed and reissued: " + redeemedToken.substring(0, Math.min(redeemedToken.length(), 20)) + "...");
                                    
                                    // Notify the callback with the redeemed token
                                    if (paymentCallback != null) {
                                        Log.i(TAG, "Calling payment success callback");
                                        paymentCallback.onCashuTokenReceived(redeemedToken);
                                    } else {
                                        Log.e(TAG, "Payment callback is null, can't deliver redeemed token");
                                    }
                                } catch (CashuPaymentHelper.RedemptionException e) {
                                    // This is a specific redemption failure
                                    String errorMsg = "Token redemption failed: " + e.getMessage();
                                    Log.e(TAG, errorMsg, e);
                                    
                                    // Reset service state on error
                                    clearPaymentRequest();
                                    
                                    // Notify callback of error
                                    if (paymentCallback != null) {
                                        Log.i(TAG, "Calling payment error callback for redemption failure");
                                        paymentCallback.onCashuPaymentError(errorMsg);
                                    } else {
                                        Log.e(TAG, "Payment callback is null, can't report redemption error");
                                    }
                                } catch (Exception e) {
                                    // This is an unexpected error
                                    String errorMsg = "Unexpected error during token redemption: " + e.getMessage();
                                    Log.e(TAG, errorMsg, e);
                                    
                                    // Reset service state on error
                                    clearPaymentRequest();
                                    
                                    // Notify callback of error
                                    if (paymentCallback != null) {
                                        Log.i(TAG, "Calling payment error callback for unexpected error");
                                        paymentCallback.onCashuPaymentError(errorMsg);
                                    } else {
                                        Log.e(TAG, "Payment callback is null, can't report redemption error");
                                    }
                                } finally {
                                    // DO NOT reset the write mode to ensure service can continue to read tokens
                                    Log.i(TAG, "Maintaining write mode for continued token reception");
                                    // if (ndefProcessor != null) {
                                    //     ndefProcessor.setWriteMode(false);
                                    // } else {
                                    //     Log.e(TAG, "NDEF processor is null in finally block");
                                    // }
                                }
                            } else {
                                Log.e(TAG, "Token failed validation, ignoring");
                            }
                        } else {
                            Log.i(TAG, "No Cashu token found in received message");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error in onNdefMessageReceived: " + e.getMessage(), e);
                    }
                }
                
                @Override
                public void onMessageSent() {
                    Log.i(TAG, "NDEF message sent to peer device");
                }
            });
            
            // Set the instance
            instance = this;
            Log.i(TAG, "NdefHostCardEmulationService initialization complete");
        } catch (Exception e) {
            Log.e(TAG, "Error creating HCE service: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void onDestroy() {
        Log.i(TAG, "=== HCE Service onDestroy called ===");
        super.onDestroy();
        Log.i(TAG, "NdefHostCardEmulationService destroyed");
        
        // Clear the instance if this is the current one
        if (instance == this) {
            instance = null;
        }
    }

    @Override
    public byte[] processCommandApdu(byte[] commandApdu, Bundle extras) {
        try {
            // Log all incoming APDUs at INFO level for visibility
            Log.i(TAG, "=== Received APDU command ===");
            Log.i(TAG, "Hex: " + bytesToHex(commandApdu));
            if (commandApdu.length > 0) {
                String description = "";
                if (commandApdu.length >= 2) {
                    byte ins = commandApdu[1];
                    switch (ins) {
                        case (byte)0xA4: description = "SELECT"; break;
                        case (byte)0xB0: description = "READ BINARY"; break;
                        case (byte)0xD6: description = "UPDATE BINARY"; break;
                        default: description = "UNKNOWN";
                    }
                }
                Log.i(TAG, "Command: " + description);
            }
            
            // Try to process with the NDEF processor
            Log.i(TAG, "Delegating to NDEF processor");
            byte[] response = ndefProcessor.processCommandApdu(commandApdu);
            
            if (response != NdefProcessor.NDEF_RESPONSE_ERROR) {
                Log.i(TAG, "NDEF processor handled command successfully");
                Log.i(TAG, "Response: " + bytesToHex(response));
                return response;
            }
            
            // If not handled by the NDEF processor, try other commands
            if (isAidSelectCommand(commandApdu)) {
                Log.i(TAG, "AID select command received and handled");
                return STATUS_SUCCESS;
            }
            
            // Unknown command
            Log.w(TAG, "Unknown command not handled by NDEF processor: " + bytesToHex(commandApdu));
            return STATUS_FAILED;
        } catch (Exception e) {
            Log.e(TAG, "Error processing APDU command: " + e.getMessage(), e);
            return STATUS_FAILED;
        }
    }

    @Override
    public void onDeactivated(int reason) {
        Log.i(TAG, "=== HCE Service deactivated with reason: " + reason + " ===");
    }
    
    /**
     * Set the payment request to be sent
     * @param paymentRequest The payment request string
     * @param amount The expected amount in sats
     */
    public void setPaymentRequest(String paymentRequest, long amount) {
        Log.i(TAG, "Setting payment request: " + paymentRequest + " for amount: " + amount);
        this.expectedAmount = amount;
        if (ndefProcessor != null) {
            ndefProcessor.setMessageToSend(paymentRequest);
            ndefProcessor.setWriteMode(true); // Enable write mode to send payment request
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
        Log.i(TAG, "Clearing payment request");
        this.expectedAmount = 0;
        if (ndefProcessor != null) {
            ndefProcessor.setMessageToSend("");
            // Keep write mode enabled to still be able to receive tokens
            // ndefProcessor.setWriteMode(false);
        } else {
            Log.e(TAG, "NDEF processor is null, can't clear payment request");
        }
    }
    
    /**
     * Set the payment callback
     */
    public void setPaymentCallback(CashuPaymentCallback callback) {
        this.paymentCallback = callback;
        Log.i(TAG, "Payment callback set: " + (callback != null ? "yes" : "no"));
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
                Log.i(TAG, "NFC is not supported on this device");
                return false;
            }
            
            if (!adapter.isEnabled()) {
                Log.i(TAG, "NFC is disabled on this device");
                return false;
            }
            
            if (!context.getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) {
                Log.i(TAG, "HCE is not supported on this device");
                return false;
            }
            
            Log.i(TAG, "HCE is available on this device");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error checking HCE availability: " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "=== HCE Service onStartCommand called ===");
        Log.i(TAG, "Intent: " + (intent != null ? intent.toString() : "null"));
        Log.i(TAG, "Flags: " + flags);
        Log.i(TAG, "StartId: " + startId);
        
        return START_STICKY;
    }

    /**
     * Log bind events (using a wrapped method since onBind is final)
     */
    public void logBindEvent(Intent intent) {
        Log.i(TAG, "=== HCE Service bind event ===");
        Log.i(TAG, "Intent: " + (intent != null ? intent.toString() : "null"));
    }

    @Override
    public void onTrimMemory(int level) {
        Log.i(TAG, "=== HCE Service onTrimMemory called with level: " + level);
        super.onTrimMemory(level);
    }

    @Override
    public void onLowMemory() {
        Log.i(TAG, "=== HCE Service onLowMemory called ===");
        super.onLowMemory();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.i(TAG, "=== HCE Service onTaskRemoved called ===");
        Log.i(TAG, "Root Intent: " + (rootIntent != null ? rootIntent.toString() : "null"));
        super.onTaskRemoved(rootIntent);
    }
}
