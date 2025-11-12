package com.electricdreams.shellshock;

import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ScrollView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.constraintlayout.widget.ConstraintLayout;
import android.view.ViewGroup;

import java.io.IOException;
import java.text.NumberFormat;
import java.util.*;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import com.electricdreams.shellshock.ndef.NdefHostCardEmulationService;
import com.electricdreams.shellshock.ndef.CashuPaymentHelper;

public class ModernPOSActivity extends AppCompatActivity implements SatocashWallet.OperationFeedback {

    private static final String TAG = "ModernPOSActivity";
    private static final String PREFS_NAME = "ShellshockPrefs";
    private static final String KEY_NIGHT_MODE = "nightMode";
    
    private TextView amountDisplay;
    private Button submitButton;
    private StringBuilder currentInput = new StringBuilder();
    private AlertDialog nfcDialog;
    private AlertDialog paymentMethodDialog;
    private TextView tokenDisplay;
    private Button copyTokenButton;
    private Button openWithButton;
    private Button resetButton;
    private ScrollView tokenScrollContainer;
    private LinearLayout tokenActionsContainer;
    private ConstraintLayout inputModeContainer;
    private NfcAdapter nfcAdapter;
    private SatocashNfcClient satocashClient;

    // Store PIN for re-scan flow
    private String savedPin = null;
    private boolean waitingForRescan = false;
    private AlertDialog rescanDialog, processingDialog;
    private SatocashWallet satocashWallet;
    private long requestedAmount = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MenuItem themeMenuItem;
    private boolean isNightMode = false;
    private android.os.Vibrator vibrator;

    // Vibration patterns (in milliseconds)
    private static final long[] PATTERN_SUCCESS = {0, 50, 100, 50}; // Two quick pulses
    private static final long[] PATTERN_ERROR = {0, 100}; // One longer pulse
    private static final int VIBRATE_KEYPAD = 20; // Short keypad press

    // Status Words (SW) constants copied from SatocashNfcClient for error handling
    private static class SW {
        public static final int UNAUTHORIZED = 0x9C06;
        public static final int PIN_FAILED = 0x63C0;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Load theme preference before setting content view
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        isNightMode = prefs.getBoolean(KEY_NIGHT_MODE, false);
        if (isNightMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        }

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_modern_pos);

        // Find all views
        amountDisplay = findViewById(R.id.amount_display);
        submitButton = findViewById(R.id.submit_button);
        GridLayout keypad = findViewById(R.id.keypad);
        tokenDisplay = findViewById(R.id.token_display);
        copyTokenButton = findViewById(R.id.copy_token_button);
        openWithButton = findViewById(R.id.open_with_button);
        resetButton = findViewById(R.id.reset_button);
        tokenScrollContainer = findViewById(R.id.token_scroll_container);
        tokenActionsContainer = findViewById(R.id.token_actions_container);
        inputModeContainer = findViewById(R.id.input_mode_container);

        // Set up bottom navigation
        ImageButton topUpButton = findViewById(R.id.action_top_up);
        ImageButton balanceCheckButton = findViewById(R.id.action_balance_check);
        ImageButton historyButton = findViewById(R.id.action_history);

        topUpButton.setOnClickListener(v -> startActivity(new Intent(this, TopUpActivity.class)));
        balanceCheckButton.setOnClickListener(v -> startActivity(new Intent(this, BalanceCheckActivity.class)));
        historyButton.setOnClickListener(v -> startActivity(new Intent(this, TokenHistoryActivity.class)));

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        vibrator = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        String[] buttonLabels = {
            "1", "2", "3",
            "4", "5", "6",
            "7", "8", "9",
            "C", "0", "◀"
        };

        LayoutInflater inflater = LayoutInflater.from(this);
        
        // Update keypad button creation to use weight for equal sizing
        for (String label : buttonLabels) {
            Button button = (Button) inflater.inflate(R.layout.keypad_button, keypad, false);
            button.setText(label);
            button.setOnClickListener(v -> onKeypadButtonClick(label));
            
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = 0;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.setMargins(4, 4, 4, 4);
            button.setLayoutParams(params);
            
            keypad.addView(button);
        }

        submitButton.setOnClickListener(v -> {
            String amount = currentInput.toString();
            if (!amount.isEmpty()) {
                requestedAmount = Long.parseLong(amount);
                showPaymentMethodDialog(requestedAmount);
            } else {
                Toast.makeText(this, "Please enter an amount", Toast.LENGTH_SHORT).show();
            }
        });

        copyTokenButton.setOnClickListener(v -> {
            String token = tokenDisplay.getText().toString();
            if (!token.isEmpty() && !token.startsWith("Error:")) {
                copyTokenToClipboard(token);
            }
        });

        openWithButton.setOnClickListener(v -> {
            String token = tokenDisplay.getText().toString();
            if (!token.isEmpty() && !token.startsWith("Error:")) {
                openTokenWithApp(token);
            }
        });

        resetButton.setOnClickListener(v -> resetToInputMode());

        // Ensure initial state
        resetToInputMode();
        updateDisplay(); // Make sure first display is correct
    }

    private void openTokenWithApp(String token) {
        String cashuUri = "cashu:" + token;
        
        // Create intent for viewing the URI
        Intent uriIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(cashuUri));
        uriIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        // Create a fallback intent for sharing as text
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, cashuUri);
        
        // Combine both intents into a chooser
        Intent chooserIntent = Intent.createChooser(uriIntent, "Open token with...");
        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[] { shareIntent });
        
        try {
            startActivity(chooserIntent);
        } catch (Exception e) {
            Toast.makeText(this, "No apps available to handle this token", Toast.LENGTH_SHORT).show();
        }
    }

    private void resetToInputMode() {
        tokenScrollContainer.setVisibility(View.GONE);
        tokenActionsContainer.setVisibility(View.GONE);
        inputModeContainer.setVisibility(View.VISIBLE);
        
        // Clear token display
        tokenDisplay.setText("");
        
        // Reset buttons
        copyTokenButton.setVisibility(View.GONE);
        openWithButton.setVisibility(View.GONE);
        
        // Reset PIN flow state
        savedPin = null;
        waitingForRescan = false;
        
        // Dismiss any active dialogs
        if (rescanDialog != null && rescanDialog.isShowing()) {
            rescanDialog.dismiss();
        }
        
        // Reset amount display
        currentInput.setLength(0);
        updateDisplay();
    }

    private void switchToTokenMode() {
        inputModeContainer.setVisibility(View.GONE);
        tokenScrollContainer.setVisibility(View.VISIBLE);
        tokenActionsContainer.setVisibility(View.VISIBLE);
        tokenDisplay.setVisibility(View.VISIBLE);
        copyTokenButton.setVisibility(View.VISIBLE);
        openWithButton.setVisibility(View.VISIBLE);
    }

    private void copyTokenToClipboard(String token) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Cashu Token", token);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "Token copied to clipboard", Toast.LENGTH_SHORT).show();
    }

    private void onKeypadButtonClick(String label) {
        vibrateKeypad();

        switch (label) {
            case "C":
                currentInput.setLength(0);
                break;
            case "◀":
                if (currentInput.length() > 0) {
                    currentInput.setLength(currentInput.length() - 1);
                }
                break;
            default:
                if (currentInput.length() < 9) { // Limit input to 9 digits
                    currentInput.append(label);
                }
                break;
        }
        updateDisplay();
    }

    private String formatAmount(String amount) {
        try {
            long value = amount.isEmpty() ? 0 : Long.parseLong(amount);
            return "₿ " + NumberFormat.getNumberInstance(Locale.US).format(value);
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private void updateDisplay() {
        // Update amount display
        String displayAmount = formatAmount(currentInput.toString());
        amountDisplay.setText(displayAmount);
        
        // Update submit button text
        long value = currentInput.toString().isEmpty() ? 0 : Long.parseLong(currentInput.toString());
        if (value > 0) {
            submitButton.setText("Charge " + displayAmount);
            submitButton.setEnabled(true);
        } else {
            submitButton.setText("Charge");
            submitButton.setEnabled(false);
        }
    }

    private void showPaymentMethodDialog(long amount) {
        // Create a unified payment experience
        proceedWithUnifiedPayment(amount);
    }
    
    /**
     * Unified payment flow that automatically detects the payment method based on the NFC card/device
     */
    private void proceedWithUnifiedPayment(long amount) {
        // Store the requested amount for processing
        requestedAmount = amount;

        // For NDEF capability, we check and prepare upfront
        final boolean ndefAvailable = NdefHostCardEmulationService.isHceAvailable(this);
        String paymentRequestLocal = null;
        
        if (ndefAvailable) {
            // Create the payment request in case we need it
            paymentRequestLocal = CashuPaymentHelper.createPaymentRequest(
                amount, 
                "Payment of " + amount + " sats"
            );
            
            if (paymentRequestLocal == null) {
                Log.e(TAG, "Failed to create payment request");
                Toast.makeText(this, "Failed to prepare NDEF payment data", Toast.LENGTH_SHORT).show();
            } else {
                Log.d(TAG, "Created payment request: " + paymentRequestLocal);
                
                // Start HCE service in the background
                Intent serviceIntent = new Intent(this, NdefHostCardEmulationService.class);
                startService(serviceIntent);
            }
        }
        final String finalPaymentRequest = paymentRequestLocal;
        
        // Show the unified NFC scan dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_Shellshock);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_nfc_modern, null);
        builder.setView(dialogView);

        // Set up dialog content
        TextView nfcAmountDisplay = dialogView.findViewById(R.id.nfc_amount_display);
        nfcAmountDisplay.setText(formatAmount(String.valueOf(amount)));
        
        TextView titleView = dialogView.findViewById(R.id.nfc_dialog_title);
        if (titleView != null) {
            titleView.setText("Scan Card or Device");
        }
        
        TextView hintView = dialogView.findViewById(R.id.nfc_hint_text);
        if (hintView != null) {
            if (ndefAvailable && finalPaymentRequest != null) {
                hintView.setText("Scan a Satocash card or bring your phone near an NFC device for payment");
            } else {
                hintView.setText("Scan your Satocash card to make the payment");
            }
            hintView.setVisibility(View.VISIBLE);
        }
        
        // Hide payment method buttons in unified flow
        Button smartcardButton = dialogView.findViewById(R.id.btn_smartcard_payment);
        Button ndefButton = dialogView.findViewById(R.id.btn_ndef_payment);
        if (smartcardButton != null) smartcardButton.setVisibility(View.GONE);
        if (ndefButton != null) ndefButton.setVisibility(View.GONE);
        
        // Add cancel button functionality if available
        Button cancelButton = dialogView.findViewById(R.id.nfc_cancel_button);
        if (cancelButton != null) {
            cancelButton.setOnClickListener(v -> {
                if (nfcDialog != null && nfcDialog.isShowing()) {
                    nfcDialog.dismiss();
                }
                // Clean up HCE service if it was started
                if (ndefAvailable) {
                    resetHceService();
                }
                Toast.makeText(this, "Payment canceled", Toast.LENGTH_SHORT).show();
            });
        }

        // Make dialog cancellable
        builder.setCancelable(true);
        builder.setOnCancelListener(dialog -> {
            // Clean up HCE service if it was started
            if (ndefAvailable) {
                stopHceService();
            }
        });

        // If we have NDEF capability, setup the HCE service with the payment request
        if (ndefAvailable && finalPaymentRequest != null) {
            new Handler().postDelayed(() -> {
                NdefHostCardEmulationService hceService = NdefHostCardEmulationService.getInstance();
                if (hceService != null) {
                    Log.d(TAG, "Setting up NDEF payment with HCE service in unified flow");
                    
                    // Set the payment request to the HCE service with expected amount
                    hceService.setPaymentRequest(finalPaymentRequest, amount);
                    
                    // Set up callback for when a token is received or an error occurs
                    hceService.setPaymentCallback(new NdefHostCardEmulationService.CashuPaymentCallback() {
                        @Override
                        public void onCashuTokenReceived(String token) {
                            runOnUiThread(() -> {
                                try {
                                    // Set the received token and handle the success
                                    // This will automatically call stopHceService()
                                    handlePaymentSuccess(token);
                                } catch (Exception e) {
                                    Log.e(TAG, "Error in NDEF payment callback: " + e.getMessage(), e);
                                    handlePaymentError("Error processing NDEF payment: " + e.getMessage());
                                }
                            });
                        }
                        
                        @Override
                        public void onCashuPaymentError(String errorMessage) {
                            runOnUiThread(() -> {
                                Log.e(TAG, "NDEF Payment error callback: " + errorMessage);
                                handlePaymentError("NDEF Payment failed: " + errorMessage);
                            });
                        }
                    });
                    
                    Log.d(TAG, "NDEF payment service ready in unified flow");
                }
            }, 1000);
        }
        
        // Show the dialog
        nfcDialog = builder.create();
        nfcDialog.show();
    }

    private void proceedWithNdefPayment(long amount) {
        // First check if HCE is available on this device
        if (!NdefHostCardEmulationService.isHceAvailable(this)) {
            Toast.makeText(this, "Host Card Emulation is not available on this device", Toast.LENGTH_SHORT).show();
            return;
        }
        
        Log.i(TAG, "Starting NDEF payment flow for " + amount + " sats");
        
        // Create the payment request
        String paymentRequest = CashuPaymentHelper.createPaymentRequest(
                amount, 
                "Payment of " + amount + " sats"
        );
        
        if (paymentRequest == null) {
            Log.e(TAG, "Failed to create payment request");
            Toast.makeText(this, "Failed to create payment request", Toast.LENGTH_SHORT).show();
            return;
        }
        
        Log.i(TAG, "Created payment request: " + paymentRequest);
        
        // Show NDEF payment dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_Shellshock);
        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_ndef_payment, null);
        builder.setView(dialogView);
        
        // Get views from the dialog
        TextView amountDisplay = dialogView.findViewById(R.id.nfc_amount_display);
        TextView statusText = dialogView.findViewById(R.id.nfc_status_text);
        Button cancelButton = dialogView.findViewById(R.id.nfc_cancel_button);
        
        // Set amount display
        amountDisplay.setText(formatAmount(String.valueOf(amount)));
        
        // Create and show the dialog
        AlertDialog ndefDialog = builder.create();
        ndefDialog.setCancelable(false);
        ndefDialog.show();
        
        // Start or use the HCE service
        statusText.setText("Initializing Host Card Emulation...");
        Log.i(TAG, "Starting HCE service");
        
        // Force start the HCE service
        Intent serviceIntent = new Intent(this, NdefHostCardEmulationService.class);
        startService(serviceIntent);
        
        // Wait a bit then check for the service
        new Handler().postDelayed(() -> {
            NdefHostCardEmulationService hceService = NdefHostCardEmulationService.getInstance();
            Log.i(TAG, "HCE service instance after first delay: " + (hceService != null ? "available" : "null"));
            
            if (hceService != null) {
                Log.i(TAG, "Setting up NDEF payment with HCE service");
                setupNdefPayment(hceService, paymentRequest, statusText, ndefDialog, amount);
            } else {
                // Try one more time with a longer delay
                statusText.setText("Waiting for HCE service...");
                Log.i(TAG, "HCE service not available yet, trying with longer delay");
                
                new Handler().postDelayed(() -> {
                    NdefHostCardEmulationService service = NdefHostCardEmulationService.getInstance();
                    Log.i(TAG, "HCE service instance after second delay: " + (service != null ? "available" : "null"));
                    
                    if (service != null) {
                        Log.i(TAG, "Setting up NDEF payment with HCE service (second attempt)");
                        setupNdefPayment(service, paymentRequest, statusText, ndefDialog, amount);
                    } else {
                        String errorMsg = "HCE service not available. Make sure NFC is enabled and Host Card Emulation is supported on your device.";
                        Log.e(TAG, errorMsg);
                        statusText.setText("Error: Host Card Emulation service not available");
                        Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show();
                    }
                }, 3000);
            }
        }, 1000);
        
        // Handle cancel button
        cancelButton.setOnClickListener(v -> {
            Log.i(TAG, "NDEF payment canceled by user");
            // Stop the HCE service properly
            stopHceService();
            ndefDialog.dismiss();
            Toast.makeText(this, "Payment canceled", Toast.LENGTH_SHORT).show();
        });
    }
    
    private void setupNdefPayment(NdefHostCardEmulationService service, String paymentRequest, 
                                  TextView statusText, AlertDialog dialog, long amount) {
        try {
            // Set the payment request to the HCE service with expected amount
            service.setPaymentRequest(paymentRequest, amount);
            
            // Set up callback for when a token is received or an error occurs
            service.setPaymentCallback(new NdefHostCardEmulationService.CashuPaymentCallback() {
                @Override
                public void onCashuTokenReceived(String token) {
                    runOnUiThread(() -> {
                        try {
                            // Dismiss the dialog
                            if (dialog != null && dialog.isShowing()) {
                                dialog.dismiss();
                            }
                            
                            // Set the received token and handle the success
                            // This will automatically call stopHceService()
                            handlePaymentSuccess(token);
                        } catch (Exception e) {
                            Log.e(TAG, "Error in payment callback: " + e.getMessage(), e);
                            handlePaymentError("Error processing payment: " + e.getMessage());
                        }
                    });
                }
                
                @Override
                public void onCashuPaymentError(String errorMessage) {
                    runOnUiThread(() -> {
                        Log.e(TAG, "Payment error callback: " + errorMessage);
                        
                        // Dismiss the dialog
                        if (dialog != null && dialog.isShowing()) {
                            dialog.dismiss();
                        }
                        
                        // Handle the payment error
                        // This will automatically call stopHceService()
                        handlePaymentError("Payment failed: " + errorMessage);
                    });
                }
            });
            
            // Update status text
            statusText.setText("Waiting for payment...\n\nHold your phone against the paying device");
        } catch (Exception e) {
            Log.e(TAG, "Error setting up NDEF payment: " + e.getMessage(), e);
            
            // Clean up if there's an error
            if (service != null) {
                service.clearPaymentRequest();
                service.setPaymentCallback(null);
            }
            
            // Show error to user
            if (dialog != null && dialog.isShowing()) {
                dialog.dismiss();
            }
            handlePaymentError("Error setting up NDEF payment: " + e.getMessage());
        }
    }

    private void showRescanDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_Shellshock);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_nfc_modern, null);
        builder.setView(dialogView);

        TextView nfcAmountDisplay = dialogView.findViewById(R.id.nfc_amount_display);
        if (nfcAmountDisplay != null) {
            nfcAmountDisplay.setText(formatAmount(String.valueOf(requestedAmount)));
        }

        // Update the dialog title to provide clear instructions
        TextView titleView = dialogView.findViewById(R.id.nfc_dialog_title);
        if (titleView != null) {
            titleView.setText("Scan Card Again");
        }
        
        // Add message below the amount
        TextView hintView = dialogView.findViewById(R.id.nfc_hint_text);
        if (hintView != null) {
            hintView.setText("PIN accepted. Please scan your card again to complete payment.");
            hintView.setVisibility(View.VISIBLE);
        }

        builder.setCancelable(true);
        builder.setOnCancelListener(dialog -> {
            // Reset state if dialog is dismissed
            savedPin = null;
            waitingForRescan = false;
        });

        rescanDialog = builder.create();
        rescanDialog.show();
    }

    private void showProcessingDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_Shellshock);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_nfc_modern, null);
        builder.setView(dialogView);

        TextView titleView = dialogView.findViewById(R.id.nfc_dialog_title);
        if (titleView != null) {
            titleView.setText("Processing Payment");
        }
        
        TextView amountView = dialogView.findViewById(R.id.nfc_amount_display);
        if (amountView != null) {
            amountView.setText(formatAmount(String.valueOf(requestedAmount)));
        }
        
        builder.setCancelable(false);
        processingDialog = builder.create();
        processingDialog.show();
    }

    private void vibrateSuccess() {
        if (vibrator != null) {
            vibrator.vibrate(PATTERN_SUCCESS, -1);
        }
    }

    private void vibrateError() {
        if (vibrator != null) {
            vibrator.vibrate(PATTERN_ERROR, -1);
        }
    }

    private void vibrateKeypad() {
        if (vibrator != null) {
            vibrator.vibrate(VIBRATE_KEYPAD);
        }
    }

    private void onCardOperationSuccess() {
        vibrateSuccess();
    }

    private void onCardOperationError() {
        vibrateError();
    }

    @Override
    public void onOperationSuccess() {
        runOnUiThread(() -> vibrateSuccess());
    }

    @Override
    public void onOperationError() {
        runOnUiThread(() -> vibrateError());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter != null) {
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, new Intent(this, getClass())
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_MUTABLE);
            String[][] techList = new String[][]{new String[]{IsoDep.class.getName()}};
            nfcAdapter.enableForegroundDispatch(this, pendingIntent, null, techList);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) {
            nfcAdapter.disableForegroundDispatch(this);
        }
    }
    
    @Override
    protected void onDestroy() {
        // Make sure to stop the HCE service when the activity is destroyed
        stopHceService();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction())) {
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (tag != null) {
                handleNfcPayment(tag);
            }
        }
    }

    private void handleNfcPayment(Tag tag) {
        if (requestedAmount <= 0) {
            Toast.makeText(this, "Please enter an amount first", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // If we're waiting for a rescan with a saved PIN, handle it differently
        if (waitingForRescan && savedPin != null) {
            processPaymentWithSavedPin(tag);
            return;
        }

        // If we're here and waiting for rescan is true but no saved PIN,
        // reset the state as something went wrong
        waitingForRescan = false;

        new Thread(() -> {
            SatocashNfcClient tempClient = null;
            try {
                tempClient = new SatocashNfcClient(tag);
                tempClient.connect();
                Log.d(TAG, "Connected to NFC card");
                
                // Store the client in the class variable AFTER successful connection
                satocashClient = tempClient;

                satocashWallet = new SatocashWallet(satocashClient);
                Log.d(TAG, "Created Satocash wallet instance");

                satocashClient.selectApplet(SatocashNfcClient.SATOCASH_AID);
                Log.d(TAG, "Satocash Applet found and selected!");

                satocashClient.initSecureChannel();
                Log.d(TAG, "Secure Channel Initialized!");

                // Try payment without PIN first
                Log.d(TAG, "Attempting payment without PIN...");
                try {
                    CompletableFuture<String> paymentFuture = satocashWallet.getPayment(requestedAmount, "SAT");
                    String token = paymentFuture.join();
                    Log.d(TAG, "Payment successful without PIN! Token received.");
                    handlePaymentSuccess(token);
                    return;
                } catch (RuntimeException e) {
                    if (e.getMessage() != null && e.getMessage().contains("not enough funds")) {
                        Log.e(TAG, "Insufficient funds error: " + e.getMessage());
                        handlePaymentError("Insufficient funds on card");
                        return;
                    }

                    Throwable cause = e.getCause();
                    if (cause instanceof SatocashNfcClient.SatocashException) {
                        SatocashNfcClient.SatocashException satocashEx = (SatocashNfcClient.SatocashException) cause;
                        Log.d(TAG, "SatocashException caught during payment: " + satocashEx.getMessage());
                        int statusWord = satocashEx.getSw();
                        Log.d(TAG, String.format("Status Word received: 0x%04X", statusWord));

                        if (statusWord == SW.UNAUTHORIZED) {
                            Log.d(TAG, "Got SW_UNAUTHORIZED, need PIN authentication");
                            
                            try {
                                // Make sure to close the connection properly before showing PIN dialog
                                if (satocashClient != null) {
                                    satocashClient.close();
                                    Log.d(TAG, "NFC connection closed before PIN entry");
                                    // Null out the client to avoid using a closed connection later
                                    final SatocashNfcClient closedClient = satocashClient;
                                    satocashClient = null;
                                }
                            } catch (IOException ioe) {
                                Log.e(TAG, "Error closing NFC connection before PIN entry: " + ioe.getMessage());
                            }
                            
                            // With the new flow, we'll save the PIN and ask for a rescan
                            CompletableFuture<String> pinFuture = new CompletableFuture<>();
                            mainHandler.post(() -> {
                                if (nfcDialog != null && nfcDialog.isShowing()) {
                                    nfcDialog.dismiss();
                                }
                                // Get the PIN from the user
                                showPinDialog(pin -> {
                                    pinFuture.complete(pin);
                                });
                            });
                            
                            String enteredPin = pinFuture.join();
                            if (enteredPin != null) {
                                // Save the PIN for the next scan
                                savedPin = enteredPin;
                                waitingForRescan = true;
                                                                
                                // Show a dialog asking user to rescan the card
                                mainHandler.post(() -> {
                                    showRescanDialog();
                                });
                            } else {
                                Log.d(TAG, "PIN entry cancelled.");
                                handlePaymentError("PIN entry cancelled");
                            }
                        } else {
                            String message = String.format("Card Error: (SW: 0x%04X)", statusWord);
                            Log.e(TAG, message);
                            handlePaymentError(message);
                        }
                    } else {
                        String message = "Payment failed: " + e.getMessage();
                        Log.e(TAG, message);
                        handlePaymentError(message);
                    }
                }
            } catch (IOException e) {
                String message = "NFC Communication Error: " + e.getMessage();
                Log.e(TAG, message);
                handlePaymentError(message);
            } catch (SatocashNfcClient.SatocashException e) {
                String message = String.format("Satocash Card Error: %s (SW: 0x%04X)",
                        e.getMessage(), e.getSw());
                Log.e(TAG, message);
                handlePaymentError(message);
            } catch (Exception e) {
                String message = "An unexpected error occurred: " + e.getMessage();
                Log.e(TAG, message);
                handlePaymentError(message);
            } finally {
                try {
                    // Only close the connection if we haven't already closed it
                    // and we're not in the PIN entry flow
                    if (satocashClient != null && !waitingForRescan) {
                        satocashClient.close();
                        Log.d(TAG, "NFC connection closed in finally block.");
                        satocashClient = null;
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error closing NFC connection: " + e.getMessage());
                }
            }
        }).start();
    }

    private void handlePaymentError(String message) {
        requestedAmount = 0;
        currentInput.setLength(0);

        // Ensure HCE service is cleaned up on error
        resetHceService();

        mainHandler.post(() -> {
            if (nfcDialog != null && nfcDialog.isShowing()) {
                nfcDialog.dismiss();
            }
            if (rescanDialog != null && rescanDialog.isShowing()) {
                rescanDialog.dismiss();
            }
            if (processingDialog != null && processingDialog.isShowing()) {
                processingDialog.dismiss();
            }
            if (paymentMethodDialog != null && paymentMethodDialog.isShowing()) {
                paymentMethodDialog.dismiss();
            }
            switchToTokenMode();
            tokenDisplay.setText("Error: " + message);
            copyTokenButton.setVisibility(View.GONE);
            openWithButton.setVisibility(View.GONE);
        });
    }
    
    /**
     * Properly reset the HCE service state
     * Instead of stopping the service entirely, we just reset its state
     */
    private void resetHceService() {
        try {
            NdefHostCardEmulationService hceService = NdefHostCardEmulationService.getInstance();
            if (hceService != null) {
                Log.d(TAG, "Resetting HCE service state...");
                // Clear any pending payment request
                hceService.clearPaymentRequest();
                // Remove payment callback
                hceService.setPaymentCallback(null);
                Log.d(TAG, "HCE service state reset successfully");
            } else {
                Log.d(TAG, "No active HCE service to reset");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error resetting HCE service: " + e.getMessage(), e);
        }
    }
    
    /**
     * Properly stop and cleanup the HCE service - only call this when app is exiting
     */
    private void stopHceService() {
        try {
            // First reset the service state
            resetHceService();
            
            // Then explicitly stop the service (only when app is exiting)
            NdefHostCardEmulationService hceService = NdefHostCardEmulationService.getInstance();
            if (hceService != null) {
                Log.d(TAG, "Stopping HCE service...");
                Intent serviceIntent = new Intent(this, NdefHostCardEmulationService.class);
                stopService(serviceIntent);
                Log.d(TAG, "HCE service stopped successfully");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping HCE service: " + e.getMessage(), e);
        }
    }

    private void handlePaymentSuccess(String token) {
        long amount = requestedAmount;
        requestedAmount = 0;
        currentInput.setLength(0);

        Log.d(TAG, "Payment successful! Token: " + token);

        // Ensure HCE service is cleaned up on success
        stopHceService();

        TokenHistoryActivity.addToHistory(this, token, amount);

        mainHandler.post(() -> {
            if (nfcDialog != null && nfcDialog.isShowing()) {
                nfcDialog.dismiss();
            }
            if (rescanDialog != null && rescanDialog.isShowing()) {
                rescanDialog.dismiss();
            }
            if (processingDialog != null && processingDialog.isShowing()) {
                processingDialog.dismiss();
            }
            if (paymentMethodDialog != null && paymentMethodDialog.isShowing()) {
                paymentMethodDialog.dismiss();
            }
            switchToTokenMode();
            tokenDisplay.setText(token);
            copyTokenButton.setVisibility(View.VISIBLE);
            openWithButton.setVisibility(View.VISIBLE);
            Toast.makeText(this, "Payment successful!", Toast.LENGTH_SHORT).show();
        });
    }

    private void showPinDialog(PinDialogCallback callback) {
        mainHandler.post(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Enter PIN");

            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            int padding = (int) (50 * getResources().getDisplayMetrics().density);
            int paddingVertical = (int) (20 * getResources().getDisplayMetrics().density);
            layout.setPadding(padding, paddingVertical, padding, paddingVertical);

            EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            input.setHint("PIN");
            layout.addView(input);

            LinearLayout keypadLayout = new LinearLayout(this);
            keypadLayout.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams keypadParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            keypadLayout.setLayoutParams(keypadParams);

            String[][] buttons = {
                    {"1", "2", "3"},
                    {"4", "5", "6"},
                    {"7", "8", "9"},
                    {"", "0", "DEL"}
            };

            for (String[] row : buttons) {
                LinearLayout rowLayout = new LinearLayout(this);
                rowLayout.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                rowParams.weight = 1.0f;
                rowLayout.setLayoutParams(rowParams);

                for (String text : row) {
                    Button button = new Button(this);
                    button.setText(text);
                    LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );
                    buttonParams.weight = 1.0f;
                    button.setLayoutParams(buttonParams);

                    button.setOnClickListener(v -> {
                        if ("DEL".equals(text)) {
                            if (input.length() > 0) {
                                input.getText().delete(input.length() - 1, input.length());
                            }
                        } else if (!text.isEmpty()) {
                            input.append(text);
                        }
                    });
                    rowLayout.addView(button);
                }
                keypadLayout.addView(rowLayout);
            }

            LinearLayout buttonLayout = new LinearLayout(this);
            buttonLayout.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams buttonLayoutParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            buttonLayoutParams.topMargin = (int) (20 * getResources().getDisplayMetrics().density);
            buttonLayout.setLayoutParams(buttonLayoutParams);

            Button cancelButton = new Button(this);
            cancelButton.setText("Cancel");
            LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1.0f
            );
            cancelParams.rightMargin = (int) (8 * getResources().getDisplayMetrics().density);
            cancelButton.setLayoutParams(cancelParams);

            Button okButton = new Button(this);
            okButton.setText("OK");
            LinearLayout.LayoutParams okParams = new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1.0f
            );
            okParams.leftMargin = (int) (8 * getResources().getDisplayMetrics().density);
            okButton.setLayoutParams(okParams);

            buttonLayout.addView(cancelButton);
            buttonLayout.addView(okButton);

            layout.addView(keypadLayout);
            layout.addView(buttonLayout);
            builder.setView(layout);

            AlertDialog dialog = builder.create();

            cancelButton.setOnClickListener(v -> {
                dialog.cancel();
                callback.onPin(null);
            });

            okButton.setOnClickListener(v -> {
                String pin = input.getText().toString();
                dialog.dismiss();
                callback.onPin(pin);
            });

            dialog.setOnCancelListener(dialogInterface -> callback.onPin(null));

            dialog.show();
        });
    }

    private interface PinDialogCallback {
        void onPin(String pin);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        themeMenuItem = menu.findItem(R.id.action_theme_toggle);
        updateThemeIcon();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.action_top_up) {
            startActivity(new Intent(this, TopUpActivity.class));
            return true;
        } else if (itemId == R.id.action_balance_check) {
            startActivity(new Intent(this, BalanceCheckActivity.class));
            return true;
        } else if (itemId == R.id.action_theme_toggle) {
            toggleTheme();
            return true;
        } else if (itemId == R.id.action_history) {
            startActivity(new Intent(this, TokenHistoryActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void toggleTheme() {
        isNightMode = !isNightMode;
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putBoolean(KEY_NIGHT_MODE, isNightMode);
        editor.apply();

        AppCompatDelegate.setDefaultNightMode(
            isNightMode ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO
        );
        
        updateThemeIcon();
    }

    private void updateThemeIcon() {
        if (themeMenuItem != null) {
            themeMenuItem.setIcon(
                isNightMode ? R.drawable.ic_light_mode : R.drawable.ic_dark_mode
            );
            themeMenuItem.setTitle(
                isNightMode ? "Switch to Light Mode" : "Switch to Dark Mode"
            );
        }
    }
    
    private void processPaymentWithSavedPin(Tag tag) {
        if (savedPin == null) {
            Log.e(TAG, "No saved PIN available for payment");
            handlePaymentError("No saved PIN available");
            return;
        }

        new Thread(() -> {
            SatocashNfcClient tempClient = null;
            try {
                if (rescanDialog != null && rescanDialog.isShowing()) {
                    mainHandler.post(() -> rescanDialog.dismiss());
                }
                
                mainHandler.post(() -> {
                    showProcessingDialog();
                });
                
                tempClient = new SatocashNfcClient(tag);
                tempClient.connect();
                Log.d(TAG, "Connected to NFC card for PIN payment");
                
                // Store in class variable AFTER successful connection
                satocashClient = tempClient;

                satocashWallet = new SatocashWallet(satocashClient);
                Log.d(TAG, "Created Satocash wallet instance");

                satocashClient.selectApplet(SatocashNfcClient.SATOCASH_AID);
                Log.d(TAG, "Satocash Applet found and selected!");

                satocashClient.initSecureChannel();
                Log.d(TAG, "Secure Channel Initialized!");

                // Authenticate with saved PIN first
                Log.d(TAG, "Authenticating with saved PIN...");
                boolean authenticated = satocashWallet.authenticatePIN(savedPin).join();
                
                if (authenticated) {
                    Log.d(TAG, "PIN Verified! Card Ready.");
                    
                    try {
                        Log.d(TAG, "Starting payment for " + requestedAmount + " SAT...");
                        CompletableFuture<String> paymentFuture = satocashWallet.getPayment(requestedAmount, "SAT");
                        String token = paymentFuture.join();
                        Log.d(TAG, "Payment successful! Token received.");
                        
                        // Reset state
                        waitingForRescan = false;
                        savedPin = null;
                        
                        handlePaymentSuccess(token);
                    } catch (Exception pe) {
                        Log.e(TAG, "Payment failed: " + pe.getMessage());
                        // Reset state
                        waitingForRescan = false;
                        savedPin = null;
                        handlePaymentError(pe.getMessage());
                    }
                } else {
                    String message = "PIN Verification Failed";
                    Log.e(TAG, message);
                    // Reset state
                    waitingForRescan = false;
                    savedPin = null;
                    handlePaymentError(message);
                }
            } catch (RuntimeException re) {
                Throwable reCause = re.getCause();
                if (reCause instanceof SatocashNfcClient.SatocashException) {
                    SatocashNfcClient.SatocashException pinEx = (SatocashNfcClient.SatocashException) reCause;
                    String message = String.format("PIN Verification Failed: %s (SW: 0x%04X)",
                            pinEx.getMessage(), pinEx.getSw());
                    Log.e(TAG, message);
                    // Reset state
                    waitingForRescan = false;
                    savedPin = null;
                    handlePaymentError(message);
                } else {
                    String message = "Authentication Failed: " + re.getMessage();
                    Log.e(TAG, message);
                    // Reset state
                    waitingForRescan = false;
                    savedPin = null;
                    handlePaymentError(message);
                }
            } catch (Exception e) {
                String message = "An unexpected error occurred: " + e.getMessage();
                Log.e(TAG, message);
                // Reset state
                waitingForRescan = false;
                savedPin = null;
                handlePaymentError(message);
            } finally {
                try {
                    if (satocashClient != null) {
                        satocashClient.close();
                        Log.d(TAG, "NFC connection closed.");
                        satocashClient = null;
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error closing NFC connection: " + e.getMessage());
                }
                
                mainHandler.post(() -> {
                    if (processingDialog != null && processingDialog.isShowing()) {
                        processingDialog.dismiss();
                    }
                });
            }
        }).start();
    }
}
