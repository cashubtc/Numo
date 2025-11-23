package com.electricdreams.shellshock;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.electricdreams.shellshock.ndef.CashuPaymentHelper;
import com.electricdreams.shellshock.ndef.NdefHostCardEmulationService;
import com.electricdreams.shellshock.nostr.NostrKeyPair;
import com.electricdreams.shellshock.nostr.NostrPaymentListener;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.text.NumberFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class PaymentRequestActivity extends AppCompatActivity {

    private static final String TAG = "PaymentRequestActivity";
    public static final String EXTRA_PAYMENT_AMOUNT = "payment_amount";
    public static final String RESULT_EXTRA_TOKEN = "payment_token";
    public static final String RESULT_EXTRA_AMOUNT = "payment_amount";

    // Nostr relays to use for NIP-17 gift-wrapped DMs
    private static final String[] NOSTR_RELAYS = new String[] {
            "wss://relay.primal.net",
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://nostr.mom"
    };

    private ImageView qrImageView;
    private TextView largeAmountDisplay;
    private TextView statusText;
    private android.view.View closeButton;
    private android.view.View shareButton;

    private long paymentAmount = 0;
    private String hcePaymentRequest = null;
    private String qrPaymentRequest = null;
    private NostrPaymentListener nostrListener = null;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_payment_request);

        // Toolbar removed in new design
        /*
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }
        */

        // Initialize views
        qrImageView = findViewById(R.id.payment_request_qr);
        largeAmountDisplay = findViewById(R.id.large_amount_display);
        statusText = findViewById(R.id.payment_status_text);
        closeButton = findViewById(R.id.close_button);
        shareButton = findViewById(R.id.share_button);

        // Get payment amount from intent
        paymentAmount = getIntent().getLongExtra(EXTRA_PAYMENT_AMOUNT, 0);
        
        if (paymentAmount <= 0) {
            Log.e(TAG, "Invalid payment amount: " + paymentAmount);
            Toast.makeText(this, "Invalid payment amount", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Display payment amount
        String formattedAmount = new com.electricdreams.shellshock.core.model.Amount(paymentAmount, com.electricdreams.shellshock.core.model.Amount.Currency.BTC).toString();
        largeAmountDisplay.setText("Pay " + formattedAmount);

        // Set up buttons
        closeButton.setOnClickListener(v -> {
            Log.d(TAG, "Payment cancelled by user");
            cancelPayment();
        });
        
        shareButton.setOnClickListener(v -> {
            if (qrPaymentRequest != null) {
                sharePaymentRequest(qrPaymentRequest);
            }
        });

        // Initialize payment request
        initializePaymentRequest();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            cancelPayment();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        cancelPayment();
        super.onBackPressed();
    }

    private void initializePaymentRequest() {
        statusText.setText("Preparing payment request...");

        // Get allowed mints
        com.electricdreams.shellshock.core.util.MintManager mintManager =
                com.electricdreams.shellshock.core.util.MintManager.getInstance(this);
        List<String> allowedMints = mintManager.getAllowedMints();
        Log.d(TAG, "Using " + allowedMints.size() + " allowed mints for payment request");

        // Check if NDEF is available
        final boolean ndefAvailable = NdefHostCardEmulationService.isHceAvailable(this);

        // HCE (NDEF) PaymentRequest
        if (ndefAvailable) {
            hcePaymentRequest = CashuPaymentHelper.createPaymentRequest(
                    paymentAmount,
                    "Payment of " + paymentAmount + " sats",
                    allowedMints
            );

            if (hcePaymentRequest == null) {
                Log.e(TAG, "Failed to create payment request for HCE");
                Toast.makeText(this, "Failed to prepare NDEF payment data", Toast.LENGTH_SHORT).show();
            } else {
                Log.d(TAG, "Created HCE payment request: " + hcePaymentRequest);

                // Start HCE service in the background
                Intent serviceIntent = new Intent(this, NdefHostCardEmulationService.class);
                startService(serviceIntent);
                setupNdefPayment();
            }
        }

        // Generate ephemeral nostr identity for QR payment
        NostrKeyPair eph = NostrKeyPair.generate();
        String nostrPubHex = eph.getHexPub();
        byte[] nostrSecret = eph.getSecretKeyBytes();

        List<String> relayList = Arrays.asList(NOSTR_RELAYS);
        String nprofile = com.electricdreams.shellshock.nostr.Nip19.encodeNprofile(
                eph.getPublicKeyBytes(),
                relayList
        );

        Log.d(TAG, "Ephemeral nostr pubkey=" + nostrPubHex + " nprofile=" + nprofile);

        // QR-specific PaymentRequest WITH Nostr transport
        qrPaymentRequest = CashuPaymentHelper.createPaymentRequestWithNostr(
                paymentAmount,
                "Payment of " + paymentAmount + " sats",
                allowedMints,
                nprofile
        );

        if (qrPaymentRequest == null) {
            Log.e(TAG, "Failed to create QR payment request with Nostr transport");
            statusText.setText("Error creating payment request");
        } else {
            Log.d(TAG, "Created QR payment request with Nostr: " + qrPaymentRequest);

            // Generate and display QR code
            try {
                Bitmap qrBitmap = generateQrBitmap(qrPaymentRequest, 512);
                if (qrBitmap != null) {
                    qrImageView.setImageBitmap(qrBitmap);
                }
                statusText.setText("Waiting for payment...");
            } catch (Exception e) {
                Log.e(TAG, "Error generating QR bitmap: " + e.getMessage(), e);
                statusText.setText("Error generating QR code");
            }

            // Start Nostr listener for this ephemeral identity
            setupNostrPayment(nostrSecret, nostrPubHex, relayList);
        }
    }

    private void setupNdefPayment() {
        if (hcePaymentRequest == null) return;

        new Handler().postDelayed(() -> {
            NdefHostCardEmulationService hceService = NdefHostCardEmulationService.getInstance();
            if (hceService != null) {
                Log.d(TAG, "Setting up NDEF payment with HCE service");

                // Set the payment request to the HCE service with expected amount
                hceService.setPaymentRequest(hcePaymentRequest, paymentAmount);

                // Set up callback for when a token is received or an error occurs
                hceService.setPaymentCallback(new NdefHostCardEmulationService.CashuPaymentCallback() {
                    @Override
                    public void onCashuTokenReceived(String token) {
                        runOnUiThread(() -> {
                            try {
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

                Log.d(TAG, "NDEF payment service ready");
            }
        }, 1000);
    }

    private void setupNostrPayment(byte[] nostrSecret, String nostrPubHex, List<String> relayList) {
        if (nostrListener != null) {
            nostrListener.stop();
            nostrListener = null;
        }

        nostrListener = new NostrPaymentListener(
                nostrSecret,
                nostrPubHex,
                paymentAmount,
                com.electricdreams.shellshock.core.util.MintManager.getInstance(this).getAllowedMints(),
                relayList,
                token -> runOnUiThread(() -> handlePaymentSuccess(token)),
                (msg, t) -> Log.e(TAG, "NostrPaymentListener error: " + msg, t)
        );
        nostrListener.start();
        Log.d(TAG, "Nostr payment listener started");
    }

    private Bitmap generateQrBitmap(String text, int size) throws Exception {
        java.util.Map<com.google.zxing.EncodeHintType, Object> hints = new java.util.EnumMap<>(com.google.zxing.EncodeHintType.class);
        hints.put(com.google.zxing.EncodeHintType.ERROR_CORRECTION, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L);
        hints.put(com.google.zxing.EncodeHintType.MARGIN, 1); // Small margin to ensure dots don't get cut off

        MultiFormatWriter writer = new MultiFormatWriter();
        // We don't use the matrix from here for drawing, but we need it to know dimensions if we wanted to use the writer's scaling.
        // But we use QRCodeWriter directly below.

        // Better approach: Generate raw matrix then scale up with dots
        com.google.zxing.qrcode.QRCodeWriter qrWriter = new com.google.zxing.qrcode.QRCodeWriter();
        BitMatrix rawMatrix = qrWriter.encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
        
        int matrixWidth = rawMatrix.getWidth();
        int matrixHeight = rawMatrix.getHeight();
        
        // Calculate scale factor to fit requested size
        int scale = size / matrixWidth;
        int outputWidth = matrixWidth * scale;
        int outputHeight = matrixHeight * scale;
        
        Bitmap outputBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas outputCanvas = new android.graphics.Canvas(outputBitmap);
        outputCanvas.drawColor(0xFFFFFFFF);
        
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(0xFF000000);
        paint.setAntiAlias(true);
        
        float radius = (float) scale / 2f;
        
        for (int x = 0; x < matrixWidth; x++) {
            for (int y = 0; y < matrixHeight; y++) {
                if (rawMatrix.get(x, y)) {
                    float cx = x * scale + radius;
                    float cy = y * scale + radius;
                    // Draw circle with full radius for solid look
                    outputCanvas.drawCircle(cx, cy, radius, paint);
                }
            }
        }
        
        return outputBitmap;
    }

    private void handlePaymentSuccess(String token) {
        Log.d(TAG, "Payment successful! Token: " + token);
        statusText.setText("Payment successful!");

        // Return result to calling activity
        Intent resultIntent = new Intent();
        resultIntent.putExtra(RESULT_EXTRA_TOKEN, token);
        resultIntent.putExtra(RESULT_EXTRA_AMOUNT, paymentAmount);
        setResult(Activity.RESULT_OK, resultIntent);

        // Clean up and finish
        cleanupAndFinish();
    }

    private void handlePaymentError(String errorMessage) {
        Log.e(TAG, "Payment error: " + errorMessage);
        statusText.setText("Payment failed: " + errorMessage);
        Toast.makeText(this, "Payment failed: " + errorMessage, Toast.LENGTH_LONG).show();

        // Return error result
        setResult(Activity.RESULT_CANCELED);
        
        // Clean up and finish after delay to let user see the error
        new Handler().postDelayed(this::cleanupAndFinish, 3000);
    }

    private void cancelPayment() {
        Log.d(TAG, "Payment cancelled");
        setResult(Activity.RESULT_CANCELED);
        cleanupAndFinish();
    }

    private void cleanupAndFinish() {
        // Stop Nostr listener
        if (nostrListener != null) {
            Log.d(TAG, "Stopping NostrPaymentListener");
            nostrListener.stop();
            nostrListener = null;
        }

        // Clean up HCE service
        try {
            NdefHostCardEmulationService hceService = NdefHostCardEmulationService.getInstance();
            if (hceService != null) {
                Log.d(TAG, "Cleaning up HCE service");
                hceService.clearPaymentRequest();
                hceService.setPaymentCallback(null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error cleaning up HCE service: " + e.getMessage(), e);
        }

        finish();
    }

    @Override
    protected void onDestroy() {
        cleanupAndFinish();
        super.onDestroy();
    }
    private void sharePaymentRequest(String paymentRequest) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, paymentRequest);
        startActivity(Intent.createChooser(shareIntent, "Share Payment Request"));
    }
}
