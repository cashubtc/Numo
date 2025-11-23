package com.electricdreams.shellshock.core.data.model;

import com.cashujdk.nut00.Token;
import com.google.gson.annotations.SerializedName;
import java.util.Date;

/**
 * Represents a payment transaction in the history.
 * Stores comprehensive information about received payments.
 */
public class PaymentHistoryEntry {
    @SerializedName("token")
    private final String token;

    @SerializedName("amount")
    private final long amount;

    @SerializedName("date")
    private final Date date;

    @SerializedName("unit")
    private final String unit; // Unit of the cashu token (e.g., "sat")

    @SerializedName("entryUnit")
    private final String entryUnit; // Unit with which it was entered (e.g., "USD", "sat")

    @SerializedName("enteredAmount")
    private final long enteredAmount; // Amount as it was entered (cents for fiat, sats for BTC)

    @SerializedName("bitcoinPrice")
    private final Double bitcoinPrice; // Bitcoin price at time of payment (can be null)

    @SerializedName("mintUrl")
    private final String mintUrl; // Mint from which it was received

    @SerializedName("paymentRequest")
    private final String paymentRequest; // The payment request it was received with (optional)

    @SerializedName("isLightning")
    private final boolean lightningPayment;

    @SerializedName("lightningQuote")
    private final String lightningQuoteJson;

    @SerializedName("lightningBolt11")
    private final String lightningBolt11;

    /**
     * Constructor for creating a payment history entry
     * @param token The cashu token received
     * @param amount The amount in the smallest unit (sats for BTC, cents for fiat)
     * @param date The date/time the payment was received
     * @param unit The unit of the cashu token (e.g., "sat")
     * @param entryUnit The unit with which the amount was entered (e.g., "USD", "sat")
     * @param enteredAmount The amount as it was entered (cents for fiat, sats for BTC)
     * @param bitcoinPrice The Bitcoin price at time of payment (can be null)
     * @param mintUrl The mint URL from which the token was received
     * @param paymentRequest The payment request used (can be null)
     */
    public PaymentHistoryEntry(String token, long amount, Date date, String unit, 
                              String entryUnit, long enteredAmount, Double bitcoinPrice,
                              String mintUrl, String paymentRequest,
                              boolean lightningPayment, String lightningQuoteJson,
                              String lightningBolt11) {
        this.token = token;
        this.amount = amount;
        this.date = date;
        this.unit = unit != null ? unit : "sat";
        this.entryUnit = entryUnit != null ? entryUnit : "sat";
        this.enteredAmount = enteredAmount;
        this.bitcoinPrice = bitcoinPrice;
        this.mintUrl = mintUrl;
        this.paymentRequest = paymentRequest;
        this.lightningPayment = lightningPayment;
        this.lightningQuoteJson = lightningQuoteJson;
        this.lightningBolt11 = lightningBolt11;
    }

    /**
     * Legacy constructor for backward compatibility
     */
    public PaymentHistoryEntry(String token, long amount, Date date) {
        this(token, amount, date, "sat", "sat", amount, null,
                extractMintFromToken(token), null, false, null, null);
    }

    /**
     * Extract mint URL from a cashu token
     */
    private static String extractMintFromToken(String tokenString) {
        try {
            if (tokenString != null && !tokenString.isEmpty()) {
                Token token = Token.decode(tokenString);
                return token.mint;
            }
        } catch (Exception e) {
            // If we can't decode, return null
        }
        return null;
    }

    public String getToken() {
        return token;
    }

    public long getAmount() {
        return amount;
    }

    public Date getDate() {
        return date;
    }

    public String getUnit() {
        return unit;
    }

    public String getEntryUnit() {
        return entryUnit;
    }

    public long getEnteredAmount() {
        return enteredAmount;
    }

    public Double getBitcoinPrice() {
        return bitcoinPrice;
    }

    public String getMintUrl() {
        return mintUrl;
    }

    public String getPaymentRequest() {
        return paymentRequest;
    }

    public boolean isLightningPayment() {
        return lightningPayment;
    }

    public String getLightningQuoteJson() {
        return lightningQuoteJson;
    }

    public String getLightningBolt11() {
        return lightningBolt11;
    }
}
