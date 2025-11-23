package com.electricdreams.shellshock.core.model;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Represents a monetary amount with currency.
 * Handles consistent formatting across the app.
 */
public class Amount {

    public enum Currency {
        BTC("₿"),
        USD("$"),
        EUR("€"),
        GBP("£"),
        JPY("¥");

        private final String symbol;

        Currency(String symbol) {
            this.symbol = symbol;
        }

        public String getSymbol() {
            return symbol;
        }

        public static Currency fromCode(String code) {
            try {
                return valueOf(code.toUpperCase());
            } catch (IllegalArgumentException e) {
                // Default or fallback? Or return null?
                // Given the app defaults to USD/BTC, maybe null and handle caller side, 
                // or default to USD if it's a fiat code we don't know?
                // For now, let's return USD as safe fallback for fiat-like strings, 
                // but really we should be careful.
                if ("SATS".equalsIgnoreCase(code) || "SAT".equalsIgnoreCase(code)) {
                    return BTC;
                }
                return USD;
            }
        }
    }

    private final long value; // Sats for BTC, cents/minor units for Fiat
    private final Currency currency;

    /**
     * @param value For BTC: satoshis. For Fiat: minor units (e.g. cents).
     * @param currency The currency of the amount.
     */
    public Amount(long value, Currency currency) {
        this.value = value;
        this.currency = currency;
    }

    @Override
    public String toString() {
        if (currency == Currency.BTC) {
            // BTC (Sats) - No decimals
            // Example: ₿123
            return currency.getSymbol() + NumberFormat.getNumberInstance(Locale.US).format(value);
        } else {
            // Fiat - 2 decimals (except JPY usually)
            // Example: Amount(USD, 123) -> $1.23
            double majorValue = value / 100.0;
            
            if (currency == Currency.JPY) {
                // JPY typically doesn't use decimals
                return String.format(Locale.US, "%s%.0f", currency.getSymbol(), majorValue);
            }
            
            return String.format(Locale.US, "%s%.2f", currency.getSymbol(), majorValue);
        }
    }
    
    public long getValue() {
        return value;
    }
    
    public Currency getCurrency() {
        return currency;
    }
}
