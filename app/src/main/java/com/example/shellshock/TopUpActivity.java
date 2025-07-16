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
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.Theme_ShellShock);
        setContentView(R.layout.activity_top_up);

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
                showNfcDialog();
            } else {
                logStatus("Please enter a Cashu proof token first.");
                Toast.makeText(this, "Please enter a Cashu proof token", Toast.LENGTH_SHORT).show();
            }
        });
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

    private void showNfcDialog() {
        mainHandler.post(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_ShellShock);
            LayoutInflater inflater = this.getLayoutInflater();
            View dialogView = inflater.inflate(R.layout.dialog_nfc_modern, null);
            builder.setView(dialogView);

            TextView nfcAmountDisplay = dialogView.findViewById(R.id.nfc_amount_display);
            nfcAmountDisplay.setText("Ready to import proofs");

            builder.setCancelable(true);
            nfcDialog = builder.create();
            nfcDialog.show();
        });
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

        new Thread(() -> {
            try {
                satocashClient = new SatocashNfcClient(tag);
                satocashClient.connect();
                satocashWallet = new SatocashWallet(satocashClient);

                logStatus("Selecting Satocash Applet...");
                satocashClient.selectApplet(SatocashNfcClient.SATOCASH_AID);
                logStatus("Satocash Applet found and selected!");

                logStatus("Initializing Secure Channel...");
                satocashClient.initSecureChannel();
                logStatus("Secure Channel Initialized!");

                // Create a CompletableFuture for the PIN dialog result
                CompletableFuture<String> pinFuture = new CompletableFuture<>();
                showPinDialog(pin -> pinFuture.complete(pin));
                String pin = pinFuture.join();

                if (pin != null) {
                    try {
                        CompletableFuture<Boolean> authFuture = satocashWallet.authenticatePIN(pin);
                        boolean authenticated = authFuture.join();
                        
                        if (authenticated) {
                            logStatus("PIN Verified. Importing proofs...");

                            try {
                                CompletableFuture<Integer> importFuture = satocashWallet.importProofsFromToken(pendingProofToken);
                                int importedCount = importFuture.join();
                                logStatus("Successfully imported " + importedCount + " proofs to card!");

                                mainHandler.post(() -> {
                                    if (nfcDialog != null && nfcDialog.isShowing()) {
                                        nfcDialog.dismiss();
                                    }
                                    Toast.makeText(TopUpActivity.this,
                                            "Imported " + importedCount + " proofs!", Toast.LENGTH_LONG).show();
                                    pendingProofToken = "";
                                });
                            } catch (Exception e) {
                                String message = "Import failed: " + e.getMessage();
                                logStatus(message);
                                Log.e(TAG, message, e);
                            }
                        } else {
                            String message = "PIN Verification Failed";
                            logStatus(message);
                            Log.e(TAG, message);
                        }

                    } catch (RuntimeException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof SatocashNfcClient.SatocashException) {
                            SatocashNfcClient.SatocashException satocashEx = (SatocashNfcClient.SatocashException) cause;
                            String message = String.format("PIN Verification Failed: %s (SW: 0x%04X)",
                                    satocashEx.getMessage(), satocashEx.getSw());
                            logStatus(message);
                            Log.e(TAG, message);
                        } else {
                            String message = "SatocashWallet Failed: " + e.getMessage();
                            logStatus(message);
                            Log.e(TAG, message, e);
                        }
                    }
                } else {
                    logStatus("PIN entry cancelled.");
                }

            } catch (IOException e) {
                String message = "NFC Communication Error: " + e.getMessage();
                logStatus(message);
                Log.e(TAG, message, e);
            } catch (SatocashNfcClient.SatocashException e) {
                String message = String.format("Satocash Card Error: %s (SW: 0x%04X)",
                        e.getMessage(), e.getSw());
                logStatus(message);
                Log.e(TAG, message);
            } catch (Exception e) {
                String message = "An unexpected error occurred: " + e.getMessage();
                logStatus(message);
                Log.e(TAG, message);
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
