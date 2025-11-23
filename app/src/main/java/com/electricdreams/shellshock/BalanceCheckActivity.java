package com.electricdreams.shellshock;

import android.app.PendingIntent;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class BalanceCheckActivity extends AppCompatActivity {

    private static final String TAG = "BalanceCheckActivity";
    private TextView balanceDisplay;
    private TextView cardInfoDisplay;
    private NfcAdapter nfcAdapter;
    private SatocashNfcClient satocashClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "============================================");
        Log.d(TAG, "BalanceCheckActivity onCreate() called!");
        Log.d(TAG, "============================================");
        setTheme(R.style.Theme_Shellshock);
        setContentView(R.layout.activity_balance_check);
        
        // Setup back button
        View backButton = findViewById(R.id.back_button);
        if (backButton != null) {
            backButton.setOnClickListener(v -> onBackPressed());
        }

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("NFC Balance Check");
        }

        balanceDisplay = findViewById(R.id.balance_display);
        cardInfoDisplay = findViewById(R.id.card_info_display);

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        Log.d(TAG, "NFC Adapter available: " + (nfcAdapter != null));
        
        // Show initial message to tap card
        updateCardInfoDisplay("Tap your NFC card to check balance");
        
        Log.d(TAG, "BalanceCheckActivity onCreate() completed - ready for NFC tap");
    }

    private void updateBalanceDisplay(long balance) {
        mainHandler.post(() -> {
            balanceDisplay.setText("Balance: " + new com.electricdreams.shellshock.core.model.Amount(balance, com.electricdreams.shellshock.core.model.Amount.Currency.BTC).toString());
            balanceDisplay.setVisibility(View.VISIBLE);
        });
    }

    private void updateCardInfoDisplay(String info) {
        mainHandler.post(() -> {
            cardInfoDisplay.setText(info);
            cardInfoDisplay.setVisibility(View.VISIBLE);
        });
    }

    private void handleBalanceCheckError(String message) {
        mainHandler.post(() -> {
            balanceDisplay.setText(String.format("Error: %s", message));
            balanceDisplay.setVisibility(View.VISIBLE);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "BalanceCheckActivity onResume() called");
        if (nfcAdapter != null) {
            Log.d(TAG, "Enabling NFC foreground dispatch...");
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, new Intent(this, getClass())
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_MUTABLE);
            String[][] techList = new String[][]{new String[]{IsoDep.class.getName()}};
            nfcAdapter.enableForegroundDispatch(this, pendingIntent, null, techList);
            Log.d(TAG, "✅ NFC foreground dispatch enabled for BalanceCheckActivity");
        } else {
            Log.e(TAG, "❌ NFC Adapter is null - cannot enable foreground dispatch");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "BalanceCheckActivity onPause() called");
        if (nfcAdapter != null) {
            nfcAdapter.disableForegroundDispatch(this);
            Log.d(TAG, "✅ NFC foreground dispatch disabled");
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d(TAG,"=== NFC onNewIntent triggered ===");
        Log.d(TAG,"Action: " + intent.getAction());
        
        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction())) {
            Log.d(TAG,"✅ ACTION_TECH_DISCOVERED matched!");
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (tag != null) {
                Log.d(TAG,"✅ Tag received: " + tag.toString());
                Log.d(TAG,"Tag ID: " + android.util.Base64.encodeToString(tag.getId(), android.util.Base64.NO_WRAP));
                Log.d(TAG,"Technologies: " + java.util.Arrays.toString(tag.getTechList()));
                
                try {
                    Log.d(TAG,"🔍 Checking if IsoDep available...");
                    android.nfc.tech.IsoDep isoDep = android.nfc.tech.IsoDep.get(tag);
                    if (isoDep != null) {
                        Log.d(TAG,"✅ IsoDep detected - proceeding with balance check");
                        checkNfcBalance(tag);
                    } else {
                        Log.e(TAG,"❌ IsoDep not available on this tag");
                        handleBalanceCheckError("Card does not support IsoDep");
                    }
                } catch (Exception e) {
                    Log.e(TAG,"❌ Error checking tag technologies: " + e.getMessage());
                    handleBalanceCheckError("Invalid NFC tag");
                }
            } else {
                Log.e(TAG,"❌ Tag was null in ACTION_TECH_DISCOVERED");
            }
        } else {
            Log.d(TAG,"❌ Skipping non-NFC action: " + intent.getAction());
        }
    }

    private void checkNfcBalance(Tag tag) {
        Log.d(TAG, "=== NFC BALANCE CHECK STARTED (NO PIN REQUIRED) ===");
        new Thread(() -> {
            try {
                Log.d(TAG, "1. Creating Satocash client...");
                satocashClient = new SatocashNfcClient(tag);
                
                Log.d(TAG, "2. Connecting to NFC card...");
                satocashClient.connect();
                Log.d(TAG, "✅ Successfully connected to NFC card");
                
                Log.d(TAG, "3. Selecting Satocash applet...");
                satocashClient.selectApplet(SatocashNfcClient.SATOCASH_AID);
                Log.d(TAG, "✅ Satocash Applet found and selected!");

                Log.d(TAG, "4. Initializing secure channel...");
                satocashClient.initSecureChannel();
                Log.d(TAG, "✅ Secure Channel Initialized!");

                Log.d(TAG, "5. Getting accurate card balance (no PIN authentication)...");
                // Get balance using getProofInfo without PIN authentication
                long totalBalance = getCardBalance();
                Log.d(TAG, "✅ Balance check complete: " + totalBalance + " ₿");
                
                updateBalanceDisplay(totalBalance);

            } catch (IOException e) {
                Log.e(TAG, "❌ NFC Communication Error: " + e.getMessage(), e);
                handleBalanceCheckError("NFC Communication Error: " + e.getMessage());
            } catch (SatocashNfcClient.SatocashException e) {
                Log.e(TAG, "❌ Satocash Card Error: " + e.getMessage() + " (SW: 0x" + Integer.toHexString(e.getSw()) + ")", e);
                handleBalanceCheckError("Satocash Card Error: " + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "❌ Unexpected Error: " + e.getMessage(), e);
                handleBalanceCheckError("Error: " + e.getMessage());
            } finally {
                try {
                    if (satocashClient != null) {
                        satocashClient.close();
                        Log.d(TAG, "✅ NFC connection closed.");
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error closing NFC connection: " + e.getMessage());
                }
            }
            Log.d(TAG, "=== NFC BALANCE CHECK COMPLETED ===");
        }).start();
    }

    public long getCardBalance() {
        Log.d(TAG, "Getting card balance using getProofInfo (no PIN required)...");
        
        try {
            // First get card status to determine total number of proofs
            Map<String, Object> status = satocashClient.getStatus();
            Log.d(TAG, "Card status: " + status.toString());
            
            int nbProofsUnspent = (int) status.getOrDefault("nb_proofs_unspent", 0);
            int nbProofsSpent = (int) status.getOrDefault("nb_proofs_spent", 0);
            int totalProofs = nbProofsUnspent + nbProofsSpent;
            
            if (totalProofs == 0) {
                Log.d(TAG, "No proofs found in card");
                updateCardInfoDisplay("Card has no proofs");
                return 0;
            }
            
            Log.d(TAG, "Total proofs in card: " + totalProofs + " (" + nbProofsUnspent + " unspent, " + nbProofsSpent + " spent)");
            
            // Use getProofInfo to get the state of all proofs (unspent vs spent)
            List<Integer> proofStates = satocashClient.getProofInfo(
                SatocashNfcClient.Unit.SAT,
                SatocashNfcClient.ProofInfoType.METADATA_STATE,
                0,                // Start from index 0
                totalProofs       // Get state for all actual proofs
            );
            
            Log.d(TAG, "Retrieved state info for " + proofStates.size() + " proofs");
            Log.d(TAG, "ProofState: " + proofStates);

            if (proofStates.isEmpty()) {
                updateCardInfoDisplay("No proof state data available");
                return 0;
            }
            
            // Get amounts for all proofs
            List<Integer> amounts = satocashClient.getProofInfo(
                SatocashNfcClient.Unit.SAT,
                SatocashNfcClient.ProofInfoType.METADATA_AMOUNT_EXPONENT,
                0,
                totalProofs
            );
            
            if (amounts.isEmpty()) {
                Log.e(TAG, "Amounts data missing or mismatched");
                updateCardInfoDisplay("Proof data inconsistent");
                return 0;
            }
            
            // Calculate total balance by summing up amounts for unspent proofs
            long totalBalance = 0;
            int unspentCount = 0;
            for (int i = 0; i < proofStates.size(); i++) {

                int state = proofStates.get(i);
                Log.d(TAG,"state: " + state);
                if (state == 1) { // State 0 = unspent

                        unspentCount++;
                        int amountExponent = amounts.get(i);
                        long amount = (long) Math.pow(2, amountExponent);
                        totalBalance += amount;
                        Log.d(TAG, "Proof " + i + ": " + amount + " ₿ (exp=" + amountExponent + ")");

                }
            }
            
            Log.d(TAG, "Total balance: " + totalBalance + " ₿ from " + unspentCount + " active proofs");
            updateCardInfoDisplay("Card has " + unspentCount + " active proofs worth " + new com.electricdreams.shellshock.core.model.Amount(totalBalance, com.electricdreams.shellshock.core.model.Amount.Currency.BTC).toString());
            return totalBalance;
            
        } catch (SatocashNfcClient.SatocashException e) {
            Log.e(TAG, "Satocash exception: " + e.getMessage(), e);
            mainHandler.post(() -> {
                handleBalanceCheckError("NFC card error: " + e.getMessage() + " (SW: 0x" + Integer.toHexString(e.getSw()) + ")");
            });
            return 0;
        } catch (IOException e) {
            Log.e(TAG, "IO exception: " + e.getMessage(), e);
            mainHandler.post(() -> {
                handleBalanceCheckError("Communication error: " + e.getMessage());
            });
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected exception: " + e.getMessage(), e);
            mainHandler.post(() -> {
                handleBalanceCheckError("Unexpected error: " + e.getMessage());
            });
            return 0;
        }
    }
}
