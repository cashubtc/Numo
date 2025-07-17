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
    private Button submitButton;
    private GridLayout keyboardGrid;
    private TextView notificationTextView;
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
        submitButton = findViewById(R.id.submitButton);
        keyboardGrid = findViewById(R.id.keyboardGrid);
        notificationTextView = findViewById(R.id.notificationTextView);
        
        // Initialize NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        
        // Setup button listeners
        setupButtons();
        
        // Setup number pad
        setupNumberPad();
        
        Log.d(TAG, "POSActivity created successfully");
    }
    
    private void setupButtons() {
        submitButton.setOnClickListener(v -> {
            if (currentAmount > 0) {
                // Start NFC payment process
                startNfcPaymentProcess();
            } else {
                showTemporaryNotification("Please enter an amount greater than 0", 3000);
            }
        });
    }
    
    private void setupNumberPad() {
        keyboardGrid.removeAllViews();
        
        // Create number pad layout: 3x4 grid
        String[] buttonTexts = {
            "1", "2", "3",
            "4", "5", "6", 
            "7", "8", "9",
            ".", "0", "⌫"
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
            
            // Add long press clear for backspace button
            if (buttonText.equals("⌫")) {
                button.setOnLongClickListener(v -> {
                    currentAmount = 0;
                    updateDisplay();
                    return true; // Consume the long click
                });
            }
            
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
            case ".":
                // For now, ignore decimal points since we're working with SAT
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
    
    private void startNfcPaymentProcess() {
        if (nfcAdapter == null) {
            showNotification("NFC is not available on this device");
            return;
        }
        
        showNotification("Please tap your NFC card to process payment of " + currentAmount + " SAT");
        // NFC handling will be done in onNewIntent when card is tapped
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
        if (tag != null && currentAmount > 0) {
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
                        });
                        
                        Log.d(TAG, "Payment successful. Received token: " + tokenString);
                        
                    } else {
                        showNotification("PIN entry cancelled");
                    }
                    
                } catch (Exception e) {
                    showNotification("Payment failed: " + e.getMessage());
                    Log.e(TAG, "Payment failed", e);
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
        }
    }
    
    private void showPinInputDialog(PinCallback callback) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter PIN");
        
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("PIN");
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 20);
        layout.addView(input);
        
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
