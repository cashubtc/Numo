package com.electricdreams.shellshock.feature.history;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.electricdreams.shellshock.R;
import com.electricdreams.shellshock.core.data.model.PaymentHistoryEntry;
import com.electricdreams.shellshock.core.model.Amount;

import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * Full-screen activity to display detailed transaction information
 * following Cash App design guidelines
 */
public class TransactionDetailActivity extends AppCompatActivity {
    
    public static final String EXTRA_TRANSACTION_TOKEN = "transaction_token";
    public static final String EXTRA_TRANSACTION_AMOUNT = "transaction_amount";
    public static final String EXTRA_TRANSACTION_DATE = "transaction_date";
    public static final String EXTRA_TRANSACTION_UNIT = "transaction_unit";
    public static final String EXTRA_TRANSACTION_ENTRY_UNIT = "transaction_entry_unit";
    public static final String EXTRA_TRANSACTION_ENTERED_AMOUNT = "transaction_entered_amount";
    public static final String EXTRA_TRANSACTION_BITCOIN_PRICE = "transaction_bitcoin_price";
    public static final String EXTRA_TRANSACTION_MINT_URL = "transaction_mint_url";
    public static final String EXTRA_TRANSACTION_PAYMENT_REQUEST = "transaction_payment_request";
    public static final String EXTRA_TRANSACTION_POSITION = "transaction_position";

    private PaymentHistoryEntry entry;
    private int position;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transaction_detail);

        // Get transaction data from intent
        Intent intent = getIntent();
        String token = intent.getStringExtra(EXTRA_TRANSACTION_TOKEN);
        long amount = intent.getLongExtra(EXTRA_TRANSACTION_AMOUNT, 0);
        long dateMillis = intent.getLongExtra(EXTRA_TRANSACTION_DATE, System.currentTimeMillis());
        String unit = intent.getStringExtra(EXTRA_TRANSACTION_UNIT);
        String entryUnit = intent.getStringExtra(EXTRA_TRANSACTION_ENTRY_UNIT);
        long enteredAmount = intent.getLongExtra(EXTRA_TRANSACTION_ENTERED_AMOUNT, amount);
        double bitcoinPriceValue = intent.getDoubleExtra(EXTRA_TRANSACTION_BITCOIN_PRICE, -1.0);
        Double bitcoinPrice = bitcoinPriceValue > 0 ? bitcoinPriceValue : null;
        String mintUrl = intent.getStringExtra(EXTRA_TRANSACTION_MINT_URL);
        String paymentRequest = intent.getStringExtra(EXTRA_TRANSACTION_PAYMENT_REQUEST);
        position = intent.getIntExtra(EXTRA_TRANSACTION_POSITION, -1);

        // Create entry object
        entry = new PaymentHistoryEntry(
            token,
            amount,
            new java.util.Date(dateMillis),
            unit,
            entryUnit,
            enteredAmount,
            bitcoinPrice,
            mintUrl,
            paymentRequest
        );

        // Setup UI
        setupViews();
    }

    private void setupViews() {
        // Back button
        ImageButton backButton = findViewById(R.id.back_button);
        backButton.setOnClickListener(v -> finish());

        // Share button
        ImageButton shareButton = findViewById(R.id.share_button);
        shareButton.setOnClickListener(v -> shareTransaction());

        // Display transaction details
        displayTransactionDetails();

        // Setup action buttons
        setupActionButtons();
    }

    private void displayTransactionDetails() {
        // Amount display
        TextView amountText = findViewById(R.id.detail_amount);
        TextView amountValueText = findViewById(R.id.detail_amount_value);
        
        Amount.Currency currency = Amount.Currency.fromCode(entry.getUnit());
        Amount amount = new Amount(entry.getAmount(), currency);
        String formattedAmount = amount.toString();
        
        amountText.setText(formattedAmount);
        amountValueText.setText(formattedAmount);

        // Date
        TextView dateText = findViewById(R.id.detail_date);
        SimpleDateFormat dateFormat = new SimpleDateFormat("MMMM d, yyyy 'at' h:mm a", Locale.getDefault());
        dateText.setText(dateFormat.format(entry.getDate()));

        // Mint name/URL
        TextView mintNameText = findViewById(R.id.mint_name);
        TextView mintUrlText = findViewById(R.id.detail_mint_url);
        
        if (entry.getMintUrl() != null && !entry.getMintUrl().isEmpty()) {
            String mintName = extractMintName(entry.getMintUrl());
            mintNameText.setText("From " + mintName);
            mintUrlText.setText(entry.getMintUrl());
        } else {
            mintNameText.setVisibility(View.GONE);
            mintUrlText.setText("Unknown");
        }

        // Token unit
        TextView tokenUnitText = findViewById(R.id.detail_token_unit);
        tokenUnitText.setText(entry.getUnit());

        // Entry unit
        TextView entryUnitText = findViewById(R.id.detail_entry_unit);
        entryUnitText.setText(entry.getEntryUnit());

        // Entered Amount
        TextView enteredAmountText = findViewById(R.id.detail_entered_amount);
        View enteredAmountRow = findViewById(R.id.entered_amount_row);
        View enteredAmountDivider = findViewById(R.id.entered_amount_divider);
        
        if (entry.getEntryUnit() != null && !entry.getEntryUnit().equals("sat")) {
            // Show entered amount in fiat currency
            Amount.Currency entryCurrency = Amount.Currency.fromCode(entry.getEntryUnit());
            Amount enteredAmount = new Amount(entry.getEnteredAmount(), entryCurrency);
            enteredAmountText.setText(enteredAmount.toString());
            enteredAmountRow.setVisibility(View.VISIBLE);
            enteredAmountDivider.setVisibility(View.VISIBLE);
        } else {
            // Hide entered amount row if it's the same as the amount (sats)
            enteredAmountRow.setVisibility(View.GONE);
            enteredAmountDivider.setVisibility(View.GONE);
        }

        // Bitcoin Price
        TextView bitcoinPriceText = findViewById(R.id.detail_bitcoin_price);
        View bitcoinPriceRow = findViewById(R.id.bitcoin_price_row);
        View bitcoinPriceDivider = findViewById(R.id.bitcoin_price_divider);
        
        if (entry.getBitcoinPrice() != null && entry.getBitcoinPrice() > 0) {
            // Format Bitcoin price with currency manager's format
            String formattedPrice = String.format(Locale.US, "$%,.2f", entry.getBitcoinPrice());
            bitcoinPriceText.setText(formattedPrice);
            bitcoinPriceRow.setVisibility(View.VISIBLE);
            bitcoinPriceDivider.setVisibility(View.VISIBLE);
        } else {
            // Hide Bitcoin price row if not available
            bitcoinPriceRow.setVisibility(View.GONE);
            bitcoinPriceDivider.setVisibility(View.GONE);
        }

        // Token
        TextView tokenText = findViewById(R.id.detail_token);
        tokenText.setText(entry.getToken());

        // Payment request (if available)
        TextView paymentRequestHeader = findViewById(R.id.payment_request_header);
        TextView paymentRequestText = findViewById(R.id.detail_payment_request);
        
        if (entry.getPaymentRequest() != null && !entry.getPaymentRequest().isEmpty()) {
            paymentRequestHeader.setVisibility(View.VISIBLE);
            paymentRequestText.setVisibility(View.VISIBLE);
            paymentRequestText.setText(entry.getPaymentRequest());
        } else {
            paymentRequestHeader.setVisibility(View.GONE);
            paymentRequestText.setVisibility(View.GONE);
        }
    }

    private String extractMintName(String mintUrl) {
        try {
            Uri uri = Uri.parse(mintUrl);
            String host = uri.getHost();
            if (host != null) {
                // Remove "www." prefix if present
                if (host.startsWith("www.")) {
                    host = host.substring(4);
                }
                return host;
            }
        } catch (Exception e) {
            // If parsing fails, return the full URL
        }
        return mintUrl;
    }

    private void setupActionButtons() {
        Button copyButton = findViewById(R.id.btn_copy);
        Button openWithButton = findViewById(R.id.btn_open_with);
        Button deleteButton = findViewById(R.id.btn_delete);

        copyButton.setOnClickListener(v -> copyToken());
        openWithButton.setOnClickListener(v -> openWithApp());
        deleteButton.setOnClickListener(v -> showDeleteConfirmation());
    }

    private void copyToken() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Cashu Token", entry.getToken());
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "Token copied to clipboard", Toast.LENGTH_SHORT).show();
    }

    private void openWithApp() {
        String cashuUri = "cashu:" + entry.getToken();
        Intent uriIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(cashuUri));
        uriIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, cashuUri);
        
        Intent chooserIntent = Intent.createChooser(uriIntent, "Open payment with...");
        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[] { shareIntent });
        
        try {
            startActivity(chooserIntent);
        } catch (Exception e) {
            Toast.makeText(this, "No apps available to handle this payment", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareTransaction() {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        
        Amount.Currency currency = Amount.Currency.fromCode(entry.getUnit());
        Amount amount = new Amount(entry.getAmount(), currency);
        
        String shareText = "Cashu Payment\n" +
                "Amount: " + amount.toString() + "\n" +
                "Token: " + entry.getToken();
        
        shareIntent.putExtra(Intent.EXTRA_TEXT, shareText);
        startActivity(Intent.createChooser(shareIntent, "Share Transaction"));
    }

    private void showDeleteConfirmation() {
        new AlertDialog.Builder(this)
            .setTitle("Delete Payment")
            .setMessage("Are you sure you want to delete this payment from history?")
            .setPositiveButton("Delete", (dialog, which) -> {
                deleteTransaction();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void deleteTransaction() {
        // Return result to calling activity with position to delete
        Intent resultIntent = new Intent();
        resultIntent.putExtra("position_to_delete", position);
        setResult(RESULT_OK, resultIntent);
        finish();
    }
}
