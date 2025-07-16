package com.example.shellshock;

import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class ModernPOSActivity extends AppCompatActivity {

    private static final String TAG = "ModernPOSActivity";
    private TextView amountDisplay;
    private Button submitButton;
    private StringBuilder currentInput = new StringBuilder();
    private AlertDialog nfcDialog;
    private TextView tokenDisplay;
    private Button copyTokenButton;
    private NfcAdapter nfcAdapter;
    private SatocashNfcClient satocashClient;
    private SatocashWallet satocashWallet;
    private long requestedAmount = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.Theme_ShellShock);
        setContentView(R.layout.activity_modern_pos);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        amountDisplay = findViewById(R.id.amount_display);
        submitButton = findViewById(R.id.submit_button);
        GridLayout keypad = findViewById(R.id.keypad);
        tokenDisplay = findViewById(R.id.token_display);
        copyTokenButton = findViewById(R.id.copy_token_button);

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);

        String[] buttonLabels = {
                "1", "2", "3",
                "4", "5", "6",
                "7", "8", "9",
                "C", "0", "◀"
        };

        LayoutInflater inflater = LayoutInflater.from(this);

        for (String label : buttonLabels) {
            Button button = (Button) inflater.inflate(R.layout.keypad_button, keypad, false);
            button.setText(label);
            button.setOnClickListener(v -> onKeypadButtonClick(label));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = GridLayout.LayoutParams.WRAP_CONTENT;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            int margin = 8; // in pixels
            params.setMargins(margin, margin, margin, margin);
            button.setLayoutParams(params);
            keypad.addView(button);
        }

        submitButton.setOnClickListener(v -> {
            String amount = currentInput.toString();
            if (!amount.isEmpty()) {
                requestedAmount = Long.parseLong(amount);
                showNfcDialog(requestedAmount);
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
    }

    private void copyTokenToClipboard(String token) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Cashu Token", token);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "Token copied to clipboard", Toast.LENGTH_SHORT).show();
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
        }
        return super.onOptionsItemSelected(item);
    }

    private void onKeypadButtonClick(String label) {
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

    private void updateDisplay() {
        if (currentInput.length() == 0) {
            amountDisplay.setText("0");
        } else {
            amountDisplay.setText(currentInput.toString());
        }
    }

    private void showNfcDialog(long amount) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_ShellShock);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_nfc_modern, null);
        builder.setView(dialogView);

        TextView nfcAmountDisplay = dialogView.findViewById(R.id.nfc_amount_display);
        nfcAmountDisplay.setText(String.format("%d sats", amount));

        builder.setCancelable(true);
        nfcDialog = builder.create();
        nfcDialog.show();
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

    private void handlePaymentSuccess(String token) {
        requestedAmount = 0;
        currentInput.setLength(0);

        mainHandler.post(() -> {
            if (nfcDialog != null && nfcDialog.isShowing()) {
                nfcDialog.dismiss();
            }
            tokenDisplay.setVisibility(View.VISIBLE);
            tokenDisplay.setText(token);
            copyTokenButton.setVisibility(View.VISIBLE);
            amountDisplay.setText("0");
            Toast.makeText(this, "Payment successful!", Toast.LENGTH_SHORT).show();
        });
    }

    private void handlePaymentError(String message) {
        requestedAmount = 0;
        currentInput.setLength(0);

        mainHandler.post(() -> {
            if (nfcDialog != null && nfcDialog.isShowing()) {
                nfcDialog.dismiss();
            }
            tokenDisplay.setVisibility(View.VISIBLE);
            tokenDisplay.setText("Error: " + message);
            copyTokenButton.setVisibility(View.GONE);
            amountDisplay.setText("0");
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
                handleNfcPayment(tag);
            }
        }
    }

    private void handleNfcPayment(Tag tag) {
        if (requestedAmount <= 0) {
            Toast.makeText(this, "Please enter an amount first", Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            try {
                satocashClient = new SatocashNfcClient(tag);
                satocashClient.connect();
                Log.d(TAG, "Connected to NFC card");

                satocashWallet = new SatocashWallet(satocashClient);
                Log.d(TAG, "Created Satocash wallet instance");

                satocashClient.selectApplet(SatocashNfcClient.SATOCASH_AID);
                Log.d(TAG, "Satocash Applet found and selected!");

                satocashClient.initSecureChannel();
                Log.d(TAG, "Secure Channel Initialized!");

                // Create a CompletableFuture for the PIN dialog result
                CompletableFuture<String> pinFuture = new CompletableFuture<>();
                
                showPinDialog(pin -> pinFuture.complete(pin));
                
                // Wait for PIN input
                String pin = pinFuture.join();
                
                if (pin != null) {
                    try {
                        CompletableFuture<Boolean> authFuture = satocashWallet.authenticatePIN(pin);
                        boolean authenticated = authFuture.join();
                        
                        if (authenticated) {
                            Log.d(TAG, "PIN Verified! Card Ready.");

                            try {
                                Log.d(TAG, "Starting payment for " + requestedAmount + " SAT...");
                                CompletableFuture<String> paymentFuture = satocashWallet.getPayment(requestedAmount, "SAT");
                                String token = paymentFuture.join();
                                Log.d(TAG, "Payment successful! Token received.");
                                handlePaymentSuccess(token);
                            } catch (Exception e) {
                                Log.e(TAG, "Payment failed: " + e.getMessage());
                                handlePaymentError(e.getMessage());
                            }
                        } else {
                            String message = "PIN Verification Failed";
                            Log.e(TAG, message);
                            handlePaymentError(message);
                        }

                    } catch (RuntimeException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof SatocashNfcClient.SatocashException) {
                            SatocashNfcClient.SatocashException satocashEx = (SatocashNfcClient.SatocashException) cause;
                            String message = String.format("PIN Verification Failed: %s (SW: 0x%04X)",
                                    satocashEx.getMessage(), satocashEx.getSw());
                            Log.e(TAG, message);
                            handlePaymentError(message);
                        } else {
                            String message = "Authentication Failed: " + e.getMessage();
                            Log.e(TAG, message);
                            handlePaymentError(message);
                        }
                    }
                } else {
                    Log.d(TAG, "PIN entry cancelled.");
                    handlePaymentError("PIN entry cancelled");
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
                    if (satocashClient != null) {
                        satocashClient.close();
                        Log.d(TAG, "NFC connection closed.");
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error closing NFC connection: " + e.getMessage());
                }
            }
        }).start();
    }

    private interface PinDialogCallback {
        void onPin(String pin);
    }
}
