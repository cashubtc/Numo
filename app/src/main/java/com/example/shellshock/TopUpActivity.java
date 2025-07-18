package com.example.shellshock;

import android.app.PendingIntent;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.textfield.TextInputEditText;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class TopUpActivity extends AppCompatActivity {

    private static final String TAG = "TopUpActivity";
    private TextInputEditText proofTokenEditText;
    private Button topUpSubmitButton;
    private TextView statusTextView;
    private AlertDialog nfcDialog;
    private NfcAdapter nfcAdapter;
    private SatocashNfcClient satocashClient;
    private SatocashWallet satocashWallet;
    private String pendingProofToken;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Full-screen NFC dialog components
    private TextView fullScreenNfcStatusText;
    private TextView fullScreenNfcLogsText;
    private com.airbnb.lottie.LottieAnimationView fullScreenNfcAnimation;
    private android.widget.ScrollView fullScreenScrollView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_top_up);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Top Up Wallet");
        }

        proofTokenEditText = findViewById(R.id.top_up_amount_edit_text);
        topUpSubmitButton = findViewById(R.id.top_up_submit_button);
        statusTextView = findViewById(R.id.statusTextView);

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            logStatus("NFC is not available on this device. Cannot flash proofs.");
            topUpSubmitButton.setEnabled(false);
        } else {
            logStatus("NFC available. Enter token or place card to flash directly.");
        }

        topUpSubmitButton.setOnClickListener(v -> {
            String proofToken = proofTokenEditText.getText().toString();
            if (!proofToken.isEmpty()) {
                pendingProofToken = proofToken;
                logStatus("Token set. Ready to flash to card: " + proofToken.substring(0, Math.min(proofToken.length(), 30)) + "...");
                Toast.makeText(this, "Token set. Now tap a card to import proofs.", Toast.LENGTH_LONG).show();
                
                // Show full screen NFC import interface immediately
                showFullScreenNfcImport();
            } else {
                logStatus("Please enter a Cashu proof token first.");
                Toast.makeText(this, "Please enter a Cashu proof token", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    private void logStatus(String message) {
        Log.d(TAG, message);
        mainHandler.post(() -> {
            statusTextView.append(message + "\n");
            // Scroll to the bottom
            int scrollAmount = statusTextView.getLayout().getLineTop(statusTextView.getLineCount()) - statusTextView.getHeight();
            if (scrollAmount > 0) {
                statusTextView.scrollTo(0, scrollAmount);
            } else {
                statusTextView.scrollTo(0, 0);
            }
        });
    }
    
    private void showFullScreenNfcImport() {
        // Create full-screen dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        
        // Create the full-screen layout programmatically
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setBackgroundColor(getResources().getColor(android.R.color.black, getTheme()));
        mainLayout.setPadding(32, 32, 32, 32);
        
        // Title
        TextView titleText = new TextView(this);
        titleText.setText("Import Proofs");
        titleText.setTextColor(getResources().getColor(R.color.colorPrimary, getTheme()));
        titleText.setTextSize(32);
        titleText.setTypeface(null, android.graphics.Typeface.BOLD);
        titleText.setGravity(android.view.Gravity.CENTER);
        titleText.setPadding(0, 0, 0, 24);
        mainLayout.addView(titleText);
        
        // Status indicator
        TextView statusText = new TextView(this);
        statusText.setText("Waiting for NFC card...");
        statusText.setTextColor(getResources().getColor(R.color.colorAccent, getTheme()));
        statusText.setTextSize(18);
        statusText.setGravity(android.view.Gravity.CENTER);
        statusText.setPadding(0, 0, 0, 32);
        mainLayout.addView(statusText);
        
        // NFC Animation
        com.airbnb.lottie.LottieAnimationView nfcAnimation = new com.airbnb.lottie.LottieAnimationView(this);
        LinearLayout.LayoutParams animParams = new LinearLayout.LayoutParams(200, 200);
        animParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        animParams.setMargins(0, 0, 0, 32);
        nfcAnimation.setLayoutParams(animParams);
        nfcAnimation.setAnimation(R.raw.nfc_scan);
        nfcAnimation.setRepeatCount(com.airbnb.lottie.LottieDrawable.INFINITE);
        nfcAnimation.setSpeed(0.8f);
        nfcAnimation.playAnimation();
        mainLayout.addView(nfcAnimation);
        
        // Logs section title
        TextView logsTitle = new TextView(this);
        logsTitle.setText("Activity Logs:");
        logsTitle.setTextColor(getResources().getColor(R.color.colorPrimary, getTheme()));
        logsTitle.setTextSize(16);
        logsTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        logsTitle.setPadding(0, 0, 0, 12);
        mainLayout.addView(logsTitle);
        
        // Logs display area
        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 
            0,
            1.0f // Take remaining space
        );
        scrollView.setLayoutParams(scrollParams);
        
        TextView logsDisplay = new TextView(this);
        logsDisplay.setTextColor(getResources().getColor(android.R.color.white, getTheme()));
        logsDisplay.setTextSize(14);
        logsDisplay.setTypeface(android.graphics.Typeface.MONOSPACE);
        logsDisplay.setBackground(getDrawable(R.drawable.pin_input_background));
        logsDisplay.setPadding(16, 16, 16, 16);
        logsDisplay.setText("System ready. Please tap your NFC card to begin import process...\n");
        scrollView.addView(logsDisplay);
        mainLayout.addView(scrollView);
        
        // Close button
        Button closeButton = new Button(this);
        closeButton.setText("Cancel");
        closeButton.setTextColor(getResources().getColor(android.R.color.white, getTheme()));
        closeButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
            getResources().getColor(R.color.textColorSecondary, getTheme())));
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        closeParams.setMargins(0, 24, 0, 0);
        closeButton.setLayoutParams(closeParams);
        closeButton.setPadding(0, 24, 0, 24);
        mainLayout.addView(closeButton);
        
        builder.setView(mainLayout);
        
        // Create and show dialog
        AlertDialog fullScreenDialog = builder.create();
        
        // Store references for updating during NFC process
        nfcDialog = fullScreenDialog; // Reuse the same dialog variable
        
        closeButton.setOnClickListener(v -> {
            fullScreenDialog.dismiss();
            nfcDialog = null;
        });
        
        // Override the update method to work with this full-screen layout
        fullScreenNfcStatusText = statusText;
        fullScreenNfcLogsText = logsDisplay;
        fullScreenNfcAnimation = nfcAnimation;
        fullScreenScrollView = scrollView;
        
        fullScreenDialog.show();
        
        // Make truly fullscreen
        if (fullScreenDialog.getWindow() != null) {
            fullScreenDialog.getWindow().setFlags(
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
            );
            fullScreenDialog.getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
    }

    private void showNfcDialog() {
        mainHandler.post(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_ShellShock);
            android.view.LayoutInflater inflater = this.getLayoutInflater();
            View dialogView = inflater.inflate(R.layout.dialog_nfc_modern, null);
            builder.setView(dialogView);

            TextView nfcAmountDisplay = dialogView.findViewById(R.id.nfc_amount_display);
            TextView progressText = dialogView.findViewById(R.id.progress_text);
            
            // Set initial state - only show once NFC is detected (don't preset any text)
            nfcAmountDisplay.setText("Waiting for NFC card...");
            progressText.setText("Please tap your card");

            builder.setCancelable(true);
            nfcDialog = builder.create();
            
            // Make dialog background transparent to show the custom background
            if (nfcDialog.getWindow() != null) {
                nfcDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }
            
            nfcDialog.show();
        });
    }
    
    // Helper method to update dialog status during import process
    private void updateNfcDialogStatus(String status, String progressMessage) {
        if (nfcDialog != null && nfcDialog.isShowing()) {
            // Check if we're using full-screen interface
            if (fullScreenNfcStatusText != null && fullScreenNfcLogsText != null) {
                // Update full-screen interface
                mainHandler.post(() -> {
                    fullScreenNfcStatusText.setText(status);
                    
                    // Add log entry with timestamp
                    String timestamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                            .format(new java.util.Date());
                    String logEntry = "[" + timestamp + "] " + progressMessage + "\n";
                    fullScreenNfcLogsText.append(logEntry);
                    
                    // Auto-scroll to bottom
                    fullScreenScrollView.post(() -> fullScreenScrollView.fullScroll(android.view.View.FOCUS_DOWN));
                    
                    // Update animation speed
                    if (fullScreenNfcAnimation != null) {
                        if ("Operation Complete!".equals(status)) {
                            fullScreenNfcAnimation.setSpeed(0.3f);
                        } else if (status.contains("Error") || status.contains("Failed")) {
                            fullScreenNfcAnimation.setSpeed(0.5f);
                        } else {
                            fullScreenNfcAnimation.setSpeed(0.8f);
                        }
                    }
                });
            } else {
                // Use original small dialog interface
                View dialogView = nfcDialog.findViewById(android.R.id.content);
                if (dialogView != null) {
                    TextView nfcAmountDisplay = dialogView.findViewById(R.id.nfc_amount_display);
                    TextView progressText = dialogView.findViewById(R.id.progress_text);
                    
                    if (nfcAmountDisplay != null) {
                        // Add a subtle fade animation when updating status
                        nfcAmountDisplay.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                            nfcAmountDisplay.setText(status);
                            nfcAmountDisplay.animate().alpha(1f).setDuration(150).start();
                        }).start();
                    }
                    if (progressText != null) {
                        progressText.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                            progressText.setText(progressMessage);
                            progressText.animate().alpha(1f).setDuration(150).start();
                        }).start();
                    }
                    
                    // Change animation speed based on status
                    com.airbnb.lottie.LottieAnimationView nfcAnimation = dialogView.findViewById(R.id.nfc_animation);
                    if (nfcAnimation != null) {
                        if ("Operation Complete!".equals(status)) {
                            nfcAnimation.setSpeed(0.3f);
                        } else if (status.contains("Error") || status.contains("Failed")) {
                            nfcAnimation.setSpeed(0.5f);
                        } else {
                            nfcAnimation.setSpeed(0.8f);
                        }
                    }
                }
            }
        }
    }

    private void showPinDialog(PinDialogCallback callback) {
        mainHandler.post(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Enter PIN");

            // Create layout for PIN input
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            int padding = (int) (50 * getResources().getDisplayMetrics().density);
            int paddingVertical = (int) (20 * getResources().getDisplayMetrics().density);
            layout.setPadding(padding, paddingVertical, padding, paddingVertical);

            // Add PIN input field
            EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            input.setHint("PIN");
            layout.addView(input);

            // Add keypad layout
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
                    {"C", "0", "⌫"}
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
                        if ("⌫".equals(text)) {
                            if (input.length() > 0) {
                                input.getText().delete(input.length() - 1, input.length());
                            }
                        } else if ("C".equals(text)) {
                            input.setText(""); // Clear the entire PIN
                        } else {
                            input.append(text);
                        }
                    });
                    rowLayout.addView(button);
                }
                keypadLayout.addView(rowLayout);
            }

            // Add button layout
            LinearLayout buttonLayout = new LinearLayout(this);
            buttonLayout.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams buttonLayoutParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            buttonLayoutParams.topMargin = (int) (20 * getResources().getDisplayMetrics().density);
            buttonLayout.setLayoutParams(buttonLayoutParams);

            // Cancel button
            Button cancelButton = new Button(this);
            cancelButton.setText("Cancel");
            LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1.0f
            );
            cancelParams.rightMargin = (int) (8 * getResources().getDisplayMetrics().density);
            cancelButton.setLayoutParams(cancelParams);

            // OK button
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
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction())) {
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (tag != null) {
                handleNfcImport(tag);
            }
        }
    }

    private void handleNfcImport(Tag tag) {
        if (pendingProofToken == null || pendingProofToken.isEmpty()) {
            logStatus("No proof token set to import. Please enter it first.");
            Toast.makeText(this, "No token to import. Please enter one first.", Toast.LENGTH_LONG).show();
            return;
        }

        logStatus("NFC Tag discovered. Attempting to import proofs...");
        mainHandler.post(() -> updateNfcDialogStatus("NFC Card Detected", "Connecting to card..."));

        new Thread(() -> {
            try {
                satocashClient = new SatocashNfcClient(tag);
                satocashClient.connect();
                satocashWallet = new SatocashWallet(satocashClient);

                logStatus("Selecting Satocash Applet...");
                mainHandler.post(() -> updateNfcDialogStatus("Card Connected", "Selecting Satocash applet..."));
                satocashClient.selectApplet(SatocashNfcClient.SATOCASH_AID);
                logStatus("Satocash Applet found and selected!");

                logStatus("Initializing Secure Channel...");
                mainHandler.post(() -> updateNfcDialogStatus("Applet Selected", "Initializing secure channel..."));
                satocashClient.initSecureChannel();
                logStatus("Secure Channel Initialized!");

                // Update dialog to show PIN request
                mainHandler.post(() -> updateNfcDialogStatus("Secure Channel Ready", "PIN verification required..."));

                // Create a CompletableFuture for the PIN dialog result
                CompletableFuture<String> pinFuture = new CompletableFuture<>();
                showPinDialog(pin -> pinFuture.complete(pin));
                String pin = pinFuture.join();

                if (pin != null) {
                    try {
                        mainHandler.post(() -> updateNfcDialogStatus("Authenticating", "Verifying PIN..."));
                        CompletableFuture<Boolean> authFuture = satocashWallet.authenticatePIN(pin);
                        boolean authenticated = authFuture.join();
                        
                        if (authenticated) {
                            logStatus("PIN Verified. Importing proofs...");
                            mainHandler.post(() -> updateNfcDialogStatus("PIN Verified", "Processing card operation..."));

                            try {
                                CompletableFuture<Integer> importFuture = satocashWallet.importProofsFromToken(pendingProofToken);
                                int importedCount = importFuture.join();
                                logStatus("Successfully imported " + importedCount + " proofs to card!");

                                mainHandler.post(() -> {
                                    updateNfcDialogStatus("Operation Complete!", "Successfully processed " + importedCount + " items");
                                    
                                    // Show success for a moment before closing
                                    new android.os.Handler(getMainLooper()).postDelayed(() -> {
                                        if (nfcDialog != null && nfcDialog.isShowing()) {
                                            nfcDialog.dismiss();
                                        }
                                        Toast.makeText(TopUpActivity.this,
                                                "Imported " + importedCount + " proofs!", Toast.LENGTH_LONG).show();
                                        pendingProofToken = "";
                                    }, 2000); // Show success message for 2 seconds
                                });
                            } catch (Exception e) {
                                String message = "Operation failed: " + e.getMessage();
                                logStatus(message);
                                Log.e(TAG, message, e);
                                mainHandler.post(() -> updateNfcDialogStatus("Operation Failed", "Card operation failed"));
                            }
                        } else {
                            String message = "PIN Verification Failed";
                            logStatus(message);
                            Log.e(TAG, message);
                            mainHandler.post(() -> updateNfcDialogStatus("Authentication Failed", "Incorrect PIN entered"));
                        }

                    } catch (RuntimeException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof SatocashNfcClient.SatocashException) {
                            SatocashNfcClient.SatocashException satocashEx = (SatocashNfcClient.SatocashException) cause;
                            String message = String.format("PIN Verification Failed: %s (SW: 0x%04X)",
                                    satocashEx.getMessage(), satocashEx.getSw());
                            logStatus(message);
                            Log.e(TAG, message);
                            mainHandler.post(() -> updateNfcDialogStatus("Authentication Error", "PIN verification failed"));
                        } else {
                            String message = "SatocashWallet Failed: " + e.getMessage();
                            logStatus(message);
                            Log.e(TAG, message, e);
                            mainHandler.post(() -> updateNfcDialogStatus("Wallet Error", message));
                        }
                    }
                } else {
                    logStatus("PIN entry cancelled.");
                    mainHandler.post(() -> updateNfcDialogStatus("Cancelled", "PIN entry was cancelled"));
                }

            } catch (IOException e) {
                String message = "NFC Communication Error: " + e.getMessage();
                logStatus(message);
                Log.e(TAG, message, e);
                mainHandler.post(() -> updateNfcDialogStatus("Communication Error", "Failed to communicate with card"));
            } catch (SatocashNfcClient.SatocashException e) {
                String message = String.format("Satocash Card Error: %s (SW: 0x%04X)",
                        e.getMessage(), e.getSw());
                logStatus(message);
                Log.e(TAG, message);
                mainHandler.post(() -> updateNfcDialogStatus("Card Error", "Card operation failed"));
            } catch (Exception e) {
                String message = "An unexpected error occurred: " + e.getMessage();
                logStatus(message);
                Log.e(TAG, message);
                mainHandler.post(() -> updateNfcDialogStatus("Error", "Unexpected error occurred"));
            } finally {
                try {
                    if (satocashClient != null) {
                        satocashClient.close();
                        Log.d(TAG, "NFC connection closed.");
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error closing NFC connection: " + e.getMessage());
                }
                logStatus("NFC interaction finished. Ready for next action.");
            }
        }).start();
    }

    private interface PinDialogCallback {
        void onPin(String pin);
    }
}
