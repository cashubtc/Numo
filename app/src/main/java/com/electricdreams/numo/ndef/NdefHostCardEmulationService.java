package com.electricdreams.numo.ndef;

import android.content.Context;
import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.content.Intent;
import android.util.Log;

import com.electricdreams.numo.R;

import java.util.List;

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
    
    // NFC reading state tracking
    private boolean isNfcReading = false;
    private Handler nfcTimeoutHandler;
    private Runnable nfcTimeoutRunnable;
    private static final long NFC_TIMEOUT_MS = 2000; // 2 seconds
    
    /**
     * Callback interface for Cashu payments
     */
    public interface CashuPaymentCallback {
        void onCashuTokenReceived(String token);
        void onCashuPaymentError(String errorMessage);
        void onNfcReadingStarted();
        void onNfcReadingStopped();
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
        
        // Initialize NFC timeout handler
        nfcTimeoutHandler = new Handler(Looper.getMainLooper());
        nfcTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "NFC reading timeout - no APDU received for " + NFC_TIMEOUT_MS + "ms");
                stopNfcReading();
            }
        };
        
        try {
            // Create the NDEF processor
            ndefProcessor = new NdefProcessor(new NdefProcessor.NdefMessageCallback() {
                @Override
                public void onNdefMessageReceived(String message) {
                    try {
                        // Log all NDEF messages at the INFO level to ensure visibility
                        Log.i(TAG, "========== RECEIVED NDEF MESSAGE ==========");
                        Log.i(TAG, "Message content: " + message);
                        
                        // Stop NFC reading indicator since we received the complete message
                        stopNfcReading();
                        
                        // First try to extract a Cashu token from the message
                        String cashuToken = CashuPaymentHelper.extractCashuToken(message);

                        if (cashuToken != null) {
                            Log.i(TAG, "Extracted Cashu token: " + cashuToken);

                            // HCE layer should only extract and forward the token.
                            // Validation, swap-to-Lightning-mint, and redemption are
                            // handled at a higher level (CashuPaymentHelper / Kotlin).

                            if (paymentCallback != null) {
                                Log.i(TAG, "Forwarding raw Cashu token to payment callback");
                                paymentCallback.onCashuTokenReceived(cashuToken);
                            } else {
                                Log.e(TAG, "Payment callback is null, can't deliver raw Cashu token");
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
        
        // Clean up NFC reading timeout
        if (nfcTimeoutHandler != null) {
            nfcTimeoutHandler.removeCallbacks(nfcTimeoutRunnable);
        }
        
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
            
            // Start/reset NFC reading indicator when we receive APDU commands
            // Only track reading if we have a payment callback set (meaning we're expecting a payment)
            if (paymentCallback != null) {
                startOrResetNfcReading();
            }
            
            // Try to process with the NDEF processor
            Log.i(TAG, "Delegating to NDEF processor");
            byte[] response = ndefProcessor.processCommandApdu(commandApdu);
            
            if (response != NdefConstants.NDEF_RESPONSE_ERROR) {
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
            // Explicitly enable incoming message processing
            ndefProcessor.setProcessIncomingMessages(true);
            Log.i(TAG, "NDEF processor ready to send and receive messages");
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
        
        // Stop NFC reading if active
        stopNfcReading();
        
        if (ndefProcessor != null) {
            ndefProcessor.setMessageToSend("");
            // Disable write mode when clearing payment request
            ndefProcessor.setWriteMode(false);
            // Explicitly disable incoming message processing when payment is not expected
            ndefProcessor.setProcessIncomingMessages(false);
            Log.i(TAG, "NDEF processor no longer processing incoming messages");
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
    
    /**
     * Start or reset the NFC reading indicator
     * Called when an APDU command is received
     */
    private void startOrResetNfcReading() {
        // If not currently reading, notify callback that we've started
        if (!isNfcReading) {
            Log.i(TAG, "NFC reading started");
            isNfcReading = true;
            if (paymentCallback != null) {
                paymentCallback.onNfcReadingStarted();
            }
        }
        
        // Reset the timeout - cancel any pending timeout and schedule a new one
        nfcTimeoutHandler.removeCallbacks(nfcTimeoutRunnable);
        nfcTimeoutHandler.postDelayed(nfcTimeoutRunnable, NFC_TIMEOUT_MS);
    }
    
    /**
     * Stop the NFC reading indicator
     * Called when the NDEF message is received or timeout occurs
     */
    private void stopNfcReading() {
        if (isNfcReading) {
            Log.i(TAG, "NFC reading stopped");
            isNfcReading = false;
            
            // Cancel any pending timeout
            nfcTimeoutHandler.removeCallbacks(nfcTimeoutRunnable);
            
            // Notify callback
            if (paymentCallback != null) {
                paymentCallback.onNfcReadingStopped();
            }
        }
    }

}
