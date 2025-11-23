package com.electricdreams.shellshock;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
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

import com.electricdreams.shellshock.lightning.LightningPaymentCoordinator;
import com.electricdreams.shellshock.lightning.LightningPaymentResult;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.text.NumberFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.cashudevkit.MintQuote;
import org.cashudevkit.QuoteState;

public class PaymentRequestActivity extends AppCompatActivity {

    private static final String TAG = "PaymentRequestActivity";
    public static final String EXTRA_PAYMENT_AMOUNT = "payment_amount";
    public static final String RESULT_EXTRA_TOKEN = "payment_token";
    public static final String RESULT_EXTRA_AMOUNT = "payment_amount";
    public static final String RESULT_EXTRA_IS_LIGHTNING = "payment_is_lightning";
    public static final String RESULT_EXTRA_LIGHTNING_QUOTE = "payment_lightning_quote";
    public static final String RESULT_EXTRA_LIGHTNING_BOLT11 = "payment_lightning_bolt11";

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
    private TextView instructionText;
    private android.view.View closeButton;
    private android.view.View shareButton;
    private ImageView qrCenterIcon;
    private View lightningProgressContainer;
    private TextView lightningProgressText;
    private MaterialButtonToggleGroup paymentMethodToggle;

    private long paymentAmount = 0;
    private String hcePaymentRequest = null;
    private String qrPaymentRequest = null;
    private NostrPaymentListener nostrListener = null;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LightningPaymentCoordinator lightningPaymentCoordinator;
    private String lightningMintUrl;
    private String lightningBolt11;
    private String lightningQuoteJson;
    private String currentSharePayload;
    private boolean lightningInvoiceReady = false;

    private enum PaymentMode { CASHU, LIGHTNING }
    private PaymentMode currentPaymentMode = PaymentMode.CASHU;

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
        instructionText = findViewById(R.id.instruction_text);
        closeButton = findViewById(R.id.close_button);
        shareButton = findViewById(R.id.share_button);
        qrCenterIcon = findViewById(R.id.qr_center_icon);
        lightningProgressContainer = findViewById(R.id.lightning_progress_container);
        lightningProgressText = findViewById(R.id.lightning_progress_text);
        paymentMethodToggle = findViewById(R.id.payment_method_toggle);
        statusText.setVisibility(View.VISIBLE);

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
        
        shareButton.setOnClickListener(v -> shareCurrentCode());

        lightningPaymentCoordinator = new LightningPaymentCoordinator(new LightningInvoiceListener());

        // Initialize payment request
        initializePaymentRequest();
        setupPaymentToggle();
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
        setStatusText("Preparing payment request...");

        // Get allowed mints
        com.electricdreams.shellshock.core.util.MintManager mintManager =
                com.electricdreams.shellshock.core.util.MintManager.getInstance(this);
        List<String> allowedMints = mintManager.getAllowedMints();
        Log.d(TAG, "Using " + allowedMints.size() + " allowed mints for payment request");
        lightningMintUrl = allowedMints != null && !allowedMints.isEmpty() ? allowedMints.get(0) : null;

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
            setStatusText("Error creating payment request");
        } else {
            Log.d(TAG, "Created QR payment request with Nostr: " + qrPaymentRequest);

            // Generate and display QR code
            try {
                Bitmap qrBitmap = generateQrBitmap(qrPaymentRequest, 512);
                if (qrBitmap != null) {
                    qrImageView.setImageBitmap(qrBitmap);
                }
                setStatusText("Waiting for payment...");
            } catch (Exception e) {
                Log.e(TAG, "Error generating QR bitmap: " + e.getMessage(), e);
                setStatusText("Error generating QR code");
            }

            // Start Nostr listener for this ephemeral identity
            setupNostrPayment(nostrSecret, nostrPubHex, relayList);
        }
    }

    private void setupPaymentToggle() {
        if (paymentMethodToggle == null) {
            return;
        }
        paymentMethodToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            if (checkedId == R.id.toggle_cashu) {
                switchToCashu();
            } else if (checkedId == R.id.toggle_lightning) {
                switchToLightning();
            }
        });
        paymentMethodToggle.check(R.id.toggle_cashu);
        switchToCashu();
    }

    private void switchToCashu() {
        currentPaymentMode = PaymentMode.CASHU;
        if (lightningPaymentCoordinator != null) {
            lightningPaymentCoordinator.cancelCurrentInvoice();
        }
        hideLightningSpinner();
        updateCenterIcon(false);
        if (instructionText != null) {
            instructionText.setText("Scan or Tap to Pay");
        }
        if (qrPaymentRequest != null) {
            try {
                Bitmap qrBitmap = generateQrBitmap(qrPaymentRequest, 512);
                qrImageView.setImageBitmap(qrBitmap);
                currentSharePayload = qrPaymentRequest;
            } catch (Exception e) {
                Log.e(TAG, "Error generating Cashu QR: " + e.getMessage());
                currentSharePayload = null;
            }
        } else {
            currentSharePayload = null;
        }
        setStatusText("Waiting for payment...");
    }

    private void switchToLightning() {
        currentPaymentMode = PaymentMode.LIGHTNING;
        if (instructionText != null) {
            instructionText.setText("Scan Lightning invoice");
        }
        if (lightningMintUrl == null || lightningMintUrl.isEmpty()) {
            Toast.makeText(this, "No mint available for Lightning requests", Toast.LENGTH_SHORT).show();
            if (paymentMethodToggle != null) {
                paymentMethodToggle.check(R.id.toggle_cashu);
            }
            return;
        }
        lightningBolt11 = null;
        lightningQuoteJson = null;
        lightningInvoiceReady = false;
        showLightningSpinner("Creating Lightning invoice...");
        if (lightningPaymentCoordinator != null) {
            lightningPaymentCoordinator.startNewInvoice(
                    paymentAmount,
                    "Payment of " + paymentAmount + " sats",
                    lightningMintUrl
            );
        }
    }

    private void showLightningSpinner(String message) {
        if (lightningProgressContainer != null) {
            lightningProgressContainer.setVisibility(View.VISIBLE);
        }
        if (lightningProgressText != null) {
            lightningProgressText.setText(message);
        }
        if (qrImageView != null) {
            qrImageView.setImageBitmap(null);
        }
        currentSharePayload = null;
        setStatusText(message);
    }

    private void hideLightningSpinner() {
        if (lightningProgressContainer != null) {
            lightningProgressContainer.setVisibility(View.GONE);
        }
    }

    private void updateCenterIcon(boolean lightning) {
        if (qrCenterIcon == null) {
            return;
        }
        if (lightning) {
            qrCenterIcon.setImageResource(R.drawable.ic_lightning);
        } else {
            qrCenterIcon.setImageResource(R.drawable.cashu);
        }
    }

    private void setStatusText(String message) {
        if (statusText != null) {
            statusText.setVisibility(View.VISIBLE);
            statusText.setText(message);
        }
    }

    private void shareCurrentCode() {
        if (currentSharePayload == null || currentSharePayload.isEmpty()) {
            Toast.makeText(this, "Nothing to share yet", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, currentSharePayload);
        startActivity(Intent.createChooser(shareIntent, "Share Code"));
    }

    private String getLightningStatusMessage(QuoteState state) {
        if (state == null) {
            return "Waiting for Lightning payment...";
        }
        switch (state) {
            case ISSUED:
            case UNPAID:
                return "Waiting for Lightning payment...";
            case PENDING:
                return "Invoice detected. Waiting for confirmation...";
            case PAID:
                return "Lightning payment detected!";
            default:
                return "Lightning invoice status: " + state.name();
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
        handlePaymentSuccess(token, false, null, null);
    }

    private void handlePaymentSuccess(String token, boolean lightning,
                                      String lightningQuote, String lightningBolt) {
        Log.d(TAG, "Payment successful! Token: " + token);
        setStatusText("Payment successful!");

        Intent resultIntent = new Intent();
        resultIntent.putExtra(RESULT_EXTRA_TOKEN, token);
        resultIntent.putExtra(RESULT_EXTRA_AMOUNT, paymentAmount);
        resultIntent.putExtra(RESULT_EXTRA_IS_LIGHTNING, lightning);
        if (lightning) {
            resultIntent.putExtra(RESULT_EXTRA_LIGHTNING_QUOTE, lightningQuote);
            resultIntent.putExtra(RESULT_EXTRA_LIGHTNING_BOLT11, lightningBolt);
        }
        setResult(Activity.RESULT_OK, resultIntent);
        cleanupAndFinish();
    }

    private void handlePaymentError(String errorMessage) {
        Log.e(TAG, "Payment error: " + errorMessage);
        setStatusText("Payment failed: " + errorMessage);
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

        if (lightningPaymentCoordinator != null) {
            lightningPaymentCoordinator.close();
            lightningPaymentCoordinator = null;
        }

        finish();
    }

    @Override
    protected void onDestroy() {
        cleanupAndFinish();
        super.onDestroy();
    }

    private class LightningInvoiceListener implements LightningPaymentCoordinator.Listener {
        @Override
        public void onLightningInvoiceLoading() {
            runOnUiThread(() -> showLightningSpinner("Creating Lightning invoice..."));
        }

        @Override
        public void onLightningInvoiceReady(MintQuote quote) {
            runOnUiThread(() -> {
                lightningBolt11 = quote.getRequest();
                hideLightningSpinner();
                updateCenterIcon(true);
                setStatusText("Waiting for Lightning payment...");
                if (lightningBolt11 != null) {
                    try {
                        Bitmap qrBitmap = generateQrBitmap(lightningBolt11, 512);
                        qrImageView.setImageBitmap(qrBitmap);
                        currentSharePayload = lightningBolt11;
                        lightningInvoiceReady = true;
                    } catch (Exception e) {
                        Log.e(TAG, "Unable to render Lightning invoice: " + e.getMessage());
                    }
                }
            });
        }

        @Override
        public void onLightningInvoiceStateUpdated(QuoteState state) {
            runOnUiThread(() -> setStatusText(getLightningStatusMessage(state)));
        }

        @Override
        public void onLightningInvoiceFailed(Throwable error) {
            runOnUiThread(() -> {
                Toast.makeText(PaymentRequestActivity.this,
                        "Lightning invoice error: " + error.getMessage(),
                        Toast.LENGTH_LONG).show();
                if (paymentMethodToggle != null) {
                    paymentMethodToggle.check(R.id.toggle_cashu);
                } else {
                    switchToCashu();
                }
            });
        }

        @Override
        public void onLightningPaymentSuccess(LightningPaymentResult result) {
            lightningQuoteJson = result.getSnapshotJson();
            lightningBolt11 = result.getBolt11();
            handlePaymentSuccess(result.getToken(), true, lightningQuoteJson, lightningBolt11);
        }
    }
}
