package com.electricdreams.shellshock;

import android.app.PendingIntent;
import android.text.Layout;
import android.content.Intent;
import android.graphics.Color;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.MenuItem;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.widget.Toolbar;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.NavUtils;
import androidx.core.content.ContextCompat;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.snackbar.Snackbar;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class TopUpActivity extends AppCompatActivity {

    private static final String TAG = "TopUpActivity";
    private TextInputEditText proofTokenEditText;
    private Button topUpSubmitButton;
    private AlertDialog nfcDialog, rescanDialog, processingDialog;
    private NfcAdapter nfcAdapter;
    private SatocashNfcClient satocashClient;
    private SatocashWallet satocashWallet;
    private String pendingProofToken;
    private String savedPin;
    private boolean waitingForRescan = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private View rootView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.Theme_Shellshock);
        setContentView(R.layout.activity_top_up);

        // Set up the toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Top Up");
        }

        rootView = findViewById(android.R.id.content);
        proofTokenEditText = findViewById(R.id.top_up_amount_edit_text);
        topUpSubmitButton = findViewById(R.id.top_up_submit_button);

        // Handle incoming share intent
        Intent intent = getIntent();
        String action = intent.getAction();
        String type = intent.getType();

        if (Intent.ACTION_SEND.equals(action) && type != null && "text/plain".equals(type)) {
            String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (sharedText != null) {
                pendingProofToken = sharedText;
                proofTokenEditText.setText(sharedText);
                showStatusMessage("Token ready to be imported", true);
                // Automatically show NFC dialog
                showNfcDialog();
            }
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            showStatusMessage("NFC is not available on this device", false);
            topUpSubmitButton.setEnabled(false);
        }

        topUpSubmitButton.setOnClickListener(v -> {
            String proofToken = proofTokenEditText.getText().toString();
            if (!proofToken.isEmpty()) {
                pendingProofToken = proofToken;
                showStatusMessage("Tap your card to import the proofs", true);
                showNfcDialog();
            } else {
                showStatusMessage("Please enter a Cashu proof token", false);
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            // Handle the Up button press
            NavUtils.navigateUpFromSameTask(this);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showStatusMessage(final String message, final boolean success) {
        mainHandler.post(() -> {
            Snackbar snackbar = Snackbar.make(rootView, message, Snackbar.LENGTH_LONG);
            View snackbarView = snackbar.getView();
            
            // Get the text view inside Snackbar view
            TextView textView = snackbarView.findViewById(com.google.android.material.R.id.snackbar_text);
            textView.setTextColor(Color.WHITE);
            
            if (success) {
                snackbarView.setBackgroundColor(Color.parseColor("#4CAF50")); // Green
            } else {
                snackbarView.setBackgroundColor(Color.parseColor("#F44336")); // Red
            }
            
            snackbar.show();
        });
    }

    private void showNfcDialog() {
        mainHandler.post(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_Shellshock);
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
    
    private void showRescanDialog() {
        mainHandler.post(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_Shellshock);
            LayoutInflater inflater = this.getLayoutInflater();
            View dialogView = inflater.inflate(R.layout.dialog_nfc_modern, null);
            builder.setView(dialogView);

            TextView nfcAmountDisplay = dialogView.findViewById(R.id.nfc_amount_display);
            nfcAmountDisplay.setText("Ready to import proofs");

            // Update the dialog title to provide clear instructions
            TextView titleView = dialogView.findViewById(R.id.nfc_dialog_title);
            if (titleView != null) {
                titleView.setText("Scan Card Again");
            }
            
            // Add message below the amount
            TextView hintView = dialogView.findViewById(R.id.nfc_hint_text);
            if (hintView != null) {
                hintView.setText("PIN accepted. Please scan your card again to complete import.");
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
        });
    }
    
    private void showProcessingDialog() {
        mainHandler.post(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_Shellshock);
            View dialogView = getLayoutInflater().inflate(R.layout.dialog_nfc_modern, null);
            builder.setView(dialogView);

            TextView titleView = dialogView.findViewById(R.id.nfc_dialog_title);
            if (titleView != null) {
                titleView.setText("Processing Import");
            }
            
            TextView amountView = dialogView.findViewById(R.id.nfc_amount_display);
            if (amountView != null) {
                amountView.setText("Importing proofs...");
            }
            
            builder.setCancelable(false);
            processingDialog = builder.create();
            processingDialog.show();
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
            showStatusMessage("No proof token set to import", false);
            return;
        }
        
        // If we're waiting for a rescan with a saved PIN, handle it differently
        if (waitingForRescan && savedPin != null) {
            processImportWithSavedPin(tag);
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
                
                // Store client in class variable AFTER successful connection
                satocashClient = tempClient;
                satocashWallet = new SatocashWallet(satocashClient);

                satocashClient.selectApplet(SatocashNfcClient.SATOCASH_AID);
                Log.d(TAG, "Satocash Applet found and selected!");
                
                satocashClient.initSecureChannel();
                Log.d(TAG, "Secure Channel Initialized!");

                // First try importing without PIN
                try {
                    CompletableFuture<Integer> importFuture = satocashWallet.importProofsFromToken(pendingProofToken);
                    int importedCount = importFuture.join();
                    showStatusMessage("Success: Imported " + importedCount + " proofs", true);

                    mainHandler.post(() -> {
                        if (nfcDialog != null && nfcDialog.isShowing()) {
                            nfcDialog.dismiss();
                        }
                        pendingProofToken = "";
                        proofTokenEditText.setText("");
                    });
                    return;
                } catch (RuntimeException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof SatocashNfcClient.SatocashException) {
                        SatocashNfcClient.SatocashException satocashEx = (SatocashNfcClient.SatocashException) cause;
                        int statusWord = satocashEx.getSw();
                        Log.d(TAG, String.format("Status Word received: 0x%04X", statusWord));
                        
                        if (statusWord == 0x9C06) { // Unauthorized - PIN required
                            Log.d(TAG, "PIN authentication needed");
                            
                            try {
                                // Close the connection properly before showing PIN dialog
                                if (satocashClient != null) {
                                    satocashClient.close();
                                    Log.d(TAG, "NFC connection closed before PIN entry");
                                    // Null out the client to avoid using a closed connection
                                    final SatocashNfcClient closedClient = satocashClient;
                                    satocashClient = null;
                                }
                            } catch (IOException ioe) {
                                Log.e(TAG, "Error closing NFC connection before PIN entry: " + ioe.getMessage());
                            }
                            
                            // Get PIN and set up for rescan
                            CompletableFuture<String> pinFuture = new CompletableFuture<>();
                            mainHandler.post(() -> {
                                if (nfcDialog != null && nfcDialog.isShowing()) {
                                    nfcDialog.dismiss();
                                }
                                showPinDialog(pin -> {
                                    pinFuture.complete(pin);
                                });
                            });
                            
                            String enteredPin = pinFuture.join();
                            if (enteredPin != null) {
                                // Save the PIN for the next scan
                                savedPin = enteredPin;
                                waitingForRescan = true;
                                
                                // Show dialog asking user to rescan the card
                                mainHandler.post(() -> {
                                    showRescanDialog();
                                });
                            } else {
                                showStatusMessage("Operation cancelled", false);
                            }
                            return;
                        } else {
                            showStatusMessage("Card error: " + satocashEx.getMessage(), false);
                        }
                    } else {
                        showStatusMessage("Error: " + e.getMessage(), false);
                    }
                }

            } catch (IOException e) {
                showStatusMessage("NFC Communication Error: " + e.getMessage(), false);
            } catch (SatocashNfcClient.SatocashException e) {
                showStatusMessage("Satocash Card Error: " + e.getMessage(), false);
            } catch (Exception e) {
                showStatusMessage("Error: " + e.getMessage(), false);
            } finally {
                try {
                    // Only close if we haven't already closed and aren't in PIN flow
                    if (satocashClient != null && !waitingForRescan) {
                        satocashClient.close();
                        Log.d(TAG, "NFC connection closed in finally block.");
                        satocashClient = null;
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error closing NFC connection: " + e.getMessage());
                }
                
                mainHandler.post(() -> {
                    if (nfcDialog != null && nfcDialog.isShowing()) {
                        nfcDialog.dismiss();
                    }
                });
            }
        }).start();
    }
    
    private void processImportWithSavedPin(Tag tag) {
        if (savedPin == null) {
            Log.e(TAG, "No saved PIN available for import");
            showStatusMessage("No saved PIN available", false);
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
                Log.d(TAG, "Connected to NFC card for PIN import");
                
                // Store in class variable AFTER successful connection
                satocashClient = tempClient;
                satocashWallet = new SatocashWallet(satocashClient);

                satocashClient.selectApplet(SatocashNfcClient.SATOCASH_AID);
                Log.d(TAG, "Satocash Applet found and selected!");

                satocashClient.initSecureChannel();
                Log.d(TAG, "Secure Channel Initialized!");

                // Authenticate with saved PIN first
                Log.d(TAG, "Authenticating with saved PIN...");
                boolean authenticated = satocashWallet.authenticatePIN(savedPin).join();
                
                if (authenticated) {
                    Log.d(TAG, "PIN Verified! Card Ready.");
                    
                    // Import proofs
                    CompletableFuture<Integer> importFuture = satocashWallet.importProofsFromToken(pendingProofToken);
                    int importedCount = importFuture.join();
                    
                    // Reset state variables
                    waitingForRescan = false;
                    savedPin = null;
                    
                    showStatusMessage("Success: Imported " + importedCount + " proofs", true);

                    mainHandler.post(() -> {
                        pendingProofToken = "";
                        proofTokenEditText.setText("");
                    });
                } else {
                    String message = "PIN Verification Failed";
                    Log.e(TAG, message);
                    // Reset state
                    waitingForRescan = false;
                    savedPin = null;
                    showStatusMessage(message, false);
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
                    showStatusMessage(message, false);
                } else {
                    String message = "Authentication Failed: " + re.getMessage();
                    Log.e(TAG, message);
                    // Reset state
                    waitingForRescan = false;
                    savedPin = null;
                    showStatusMessage(message, false);
                }
            } catch (Exception e) {
                String message = "An unexpected error occurred: " + e.getMessage();
                Log.e(TAG, message);
                // Reset state
                waitingForRescan = false;
                savedPin = null;
                showStatusMessage(message, false);
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

    private interface PinDialogCallback {
        void onPin(String pin);
    }
}
