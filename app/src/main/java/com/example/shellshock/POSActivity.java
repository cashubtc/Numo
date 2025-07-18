package com.example.shellshock;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class POSActivity extends AppCompatActivity {
    
    private static final String TAG = "POSActivity";
    private TextView displayTextView;
    private EditText displayField;
    private GridLayout keyboardGrid;
    private TextView notificationTextView;
    private ProgressBar loadingSpinner;
    private NfcAdapter nfcAdapter;
    
    private long currentAmount = 0;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pos);
        
        // Setup toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Point of Sale");
        }
        
        // Initialize views
        displayTextView = findViewById(R.id.displayTextView);
        displayField = findViewById(R.id.displayField);
        keyboardGrid = findViewById(R.id.keyboardGrid);
        notificationTextView = findViewById(R.id.notificationTextView);
        loadingSpinner = findViewById(R.id.loadingSpinner);
        
        // Initialize NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        
        // Check if NFC is available (but don't show notification on startup)
        if (nfcAdapter == null) {
            // NFC not available, but don't show message on startup
        }
        
        // Setup number pad
        setupNumberPad();
        
        Log.d(TAG, "POSActivity created successfully");
    }
    
    private void setupNumberPad() {
        keyboardGrid.removeAllViews();
        
        // Create number pad layout: 3x4 grid
        String[] buttonTexts = {
            "1", "2", "3",
            "4", "5", "6", 
            "7", "8", "9",
            "C", "0", "⌫"
        };
        
        for (int i = 0; i < buttonTexts.length; i++) {
            Button button = new Button(this);
            button.setText(buttonTexts[i]);
            
            // Set layout parameters for grid
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.rowSpec = GridLayout.spec(i / 3);
            params.columnSpec = GridLayout.spec(i % 3);
            params.width = 0;
            params.height = GridLayout.LayoutParams.WRAP_CONTENT;
            params.setMargins(8, 8, 8, 8);
            params.columnSpec = GridLayout.spec(i % 3, 1f);
            button.setLayoutParams(params);
            
            // Style the button
            button.setTextSize(24);
            button.setPadding(16, 16, 16, 16);
            button.setBackground(getDrawable(R.drawable.keypad_button_background));
            button.setTextColor(getResources().getColor(android.R.color.white, getTheme()));
            button.setElevation(4);
            
            // Set click listener
            final String buttonText = buttonTexts[i];
            button.setOnClickListener(v -> handleNumberPadClick(buttonText));
            
            keyboardGrid.addView(button);
        }
    }
    
    private void handleNumberPadClick(String text) {
        // Hide notifications when user starts entering new amounts
        hideNotification();
        
        switch (text) {
            case "⌫": // Backspace
                if (currentAmount > 0) {
                    currentAmount = currentAmount / 10;
                }
                break;
            case "C": // Clear
                currentAmount = 0;
                break;
            default:
                try {
                    int digit = Integer.parseInt(text);
                    if (currentAmount == 0) {
                        currentAmount = digit;
                    } else {
                        currentAmount = currentAmount * 10 + digit;
                    }
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Invalid number pad input: " + text);
                }
                break;
        }
        updateDisplay();
    }
    
    private void updateDisplay() {
        String displayText = currentAmount == 0 ? "0" : String.valueOf(currentAmount);
        displayTextView.setText(displayText);
    }
    
    // Helper methods for notifications
    private void showNotification(String message) {
        runOnUiThread(() -> {
            notificationTextView.setText(message);
            notificationTextView.setVisibility(android.view.View.VISIBLE);
        });
    }
    
    private void hideNotification() {
        runOnUiThread(() -> {
            notificationTextView.setVisibility(android.view.View.GONE);
        });
    }
    
    private void showTemporaryNotification(String message, int durationMs) {
        showNotification(message);
        new android.os.Handler(getMainLooper()).postDelayed(this::hideNotification, durationMs);
    }
    
    // Transition methods for smooth keypad <-> spinner animations
    private void showSpinnerWithTransition() {
        runOnUiThread(() -> {
            // Scale down and fade out keypad
            keyboardGrid.animate()
                    .scaleX(0.8f)
                    .scaleY(0.8f)
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction(() -> {
                        keyboardGrid.setVisibility(android.view.View.GONE);
                        
                        // Scale up and fade in spinner
                        loadingSpinner.setVisibility(android.view.View.VISIBLE);
                        loadingSpinner.setScaleX(0.5f);
                        loadingSpinner.setScaleY(0.5f);
                        loadingSpinner.setAlpha(0f);
                        loadingSpinner.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .alpha(1f)
                                .setDuration(300)
                                .start();
                    })
                    .start();
        });
    }
    
    private void showKeypadWithTransition() {
        runOnUiThread(() -> {
            // Scale down and fade out spinner
            loadingSpinner.animate()
                    .scaleX(0.5f)
                    .scaleY(0.5f)
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction(() -> {
                        loadingSpinner.setVisibility(android.view.View.GONE);
                        
                        // Scale up and fade in keypad
                        keyboardGrid.setVisibility(android.view.View.VISIBLE);
                        keyboardGrid.setScaleX(0.8f);
                        keyboardGrid.setScaleY(0.8f);
                        keyboardGrid.setAlpha(0f);
                        keyboardGrid.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .alpha(1f)
                                .setDuration(300)
                                .start();
                    })
                    .start();
        });
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter != null) {
            android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(
                this, 0, new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                android.app.PendingIntent.FLAG_MUTABLE
            );
            String[][] techLists = new String[][]{new String[]{IsoDep.class.getName()}};
            nfcAdapter.enableForegroundDispatch(this, pendingIntent, null, techLists);
            Log.d(TAG, "NFC foreground dispatch enabled");
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) {
            nfcAdapter.disableForegroundDispatch(this);
            Log.d(TAG, "NFC foreground dispatch disabled");
        }
    }
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction())) {
            handleNfcIntent(intent);
        }
    }
    
    @SuppressLint("SetTextI18n")
    private void handleNfcIntent(Intent intent) {
        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag != null) {
            if (currentAmount > 0) {
                showNotification("NFC card detected. Processing payment...");
                
                // Process payment in background thread
                new Thread(() -> {
                    SatocashNfcClient satocashClient = null;
                    SatocashWallet satocashWallet = null;
                    
                    try {
                        satocashClient = new SatocashNfcClient(tag);
                        satocashClient.connect();
                        satocashWallet = new SatocashWallet(satocashClient);
                        
                        satocashClient.selectApplet(SatocashNfcClient.SATOCASH_AID);
                        showNotification("Satocash card connected...");
                        
                        satocashClient.initSecureChannel();
                        showNotification("Secure channel established...");
                        
                        // Get PIN from user
                        AtomicReference<String> pinRef = new AtomicReference<>();
                        CompletableFuture<Void> pinFuture = new CompletableFuture<>();
                        
                        runOnUiThread(() -> {
                            showPinInputDialog(pin -> {
                                pinRef.set(pin);
                                if (pin != null && !pin.isEmpty()) {
                                    // Show spinner when PIN is successfully entered
                                    showSpinnerWithTransition();
                                }
                                pinFuture.complete(null);
                            });
                        });
                        
                        pinFuture.join(); // Wait for PIN input
                        String pin = pinRef.get();
                        
                        if (pin != null && !pin.isEmpty()) {
                            // Authenticate with PIN
                            SatocashWallet finalSatocashWallet = satocashWallet;
                            satocashWallet.authenticatePIN(pin).join();
                            
                            showNotification("PIN verified. Processing payment...");
                            
                            // Get payment
                            String tokenString = satocashWallet.getPayment(currentAmount, "SAT").join();
                            
                            runOnUiThread(() -> {
                                showNotification("Payment successful! Received token for " + currentAmount + " SAT");
                                // Reset amount after successful payment
                                currentAmount = 0;
                                updateDisplay();
                                // Show keypad again after success
                                showKeypadWithTransition();
                            });
                            
                            Log.d(TAG, "Payment successful. Received token: " + tokenString);
                            
                        } else {
                            showNotification("PIN entry cancelled");
                            // Show keypad again after PIN cancellation
                            showKeypadWithTransition();
                        }
                        
                    } catch (Exception e) {
                        showNotification("Payment failed: " + e.getMessage());
                        Log.e(TAG, "Payment failed", e);
                        // Show keypad again after failure
                        showKeypadWithTransition();
                    } finally {
                        if (satocashClient != null) {
                            try {
                                satocashClient.close();
                            } catch (IOException e) {
                                Log.e(TAG, "Error closing NFC connection", e);
                            }
                        }
                    }
                }).start();
            } else {
                showTemporaryNotification("Please enter an amount to pay first", 3000);
            }
        }
    }
    
    private void showPinInputDialog(PinCallback callback) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter PIN");
        
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_NULL); // Disable system keyboard
        input.setHint("PIN");
        input.setTextSize(18);
        input.setPadding(24, 16, 24, 16);
        input.setBackground(getDrawable(R.drawable.pin_input_background));
        input.setTextColor(getResources().getColor(R.color.colorPrimary, getTheme())); // Ivory white text
        input.setHintTextColor(getResources().getColor(android.R.color.darker_gray, getTheme()));
        input.setFocusable(false); // Prevent focus to avoid keyboard
        input.setClickable(false); // Prevent clicking to avoid keyboard
        input.setCursorVisible(false); // Hide cursor since no typing needed
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 24, 32, 24); // Increased padding for more breathing room
        layout.addView(input);
        
        // Add spacing consistent with button margins (increased for better separation)
        android.view.View spacer = new android.view.View(this);
        LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 
            32 // Increased spacing for better visual separation
        );
        spacer.setLayoutParams(spacerParams);
        layout.addView(spacer);
        
        // Create custom keypad with Macademia-style spacing
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
            rowParams.setMargins(0, 8, 0, 8); // Match main keypad spacing
            rowLayout.setLayoutParams(rowParams);
            
            for (String text : row) {
                Button button = new Button(this);
                button.setText(text);
                LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, // Match main keypad height
                    1.0f
                );
                buttonParams.setMargins(8, 8, 8, 8); // Exactly match main keypad margins
                button.setLayoutParams(buttonParams);
                
                // Match main keypad button styling exactly
                button.setTextSize(24); // Same as main keypad
                button.setPadding(16, 16, 16, 16); // Same as main keypad
                button.setBackground(getDrawable(R.drawable.keypad_button_background)); // Same drawable
                button.setTextColor(getResources().getColor(android.R.color.white, getTheme()));
                button.setElevation(4); // Same elevation
                
                button.setOnClickListener(v -> {
                    if ("⌫".equals(text)) {
                        if (input.getText().length() > 0) {
                            input.getText().delete(input.getText().length() - 1, input.getText().length());
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
        
        layout.addView(keypadLayout);
        builder.setView(layout);
        
        builder.setPositiveButton("OK", (dialog, which) -> {
            String pin = input.getText().toString();
            callback.onPinEntered(pin);
            dialog.dismiss();
        });
        
        builder.setNegativeButton("Cancel", (dialog, which) -> {
            callback.onPinEntered(null);
            dialog.cancel();
        });
        
        builder.setOnCancelListener(dialog -> callback.onPinEntered(null));
        
        AlertDialog dialog = builder.create();
        dialog.show();
        
        // Style the OK and Cancel buttons with ivory white text
        Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        
        if (positiveButton != null) {
            positiveButton.setTextColor(getResources().getColor(R.color.colorPrimary, getTheme()));
        }
        
        if (negativeButton != null) {
            negativeButton.setTextColor(getResources().getColor(R.color.colorPrimary, getTheme()));
        }
    }
    
    interface PinCallback {
        void onPinEntered(String pin);
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_top_up) {
            startActivity(new Intent(this, TopUpActivity.class));
            return true;
        } else if (item.getItemId() == R.id.action_balance_check) {
            startActivity(new Intent(this, BalanceCheckActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
