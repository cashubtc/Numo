package com.electricdreams.shellshock.core.data.model;

import com.google.gson.annotations.SerializedName;
import java.util.Date;

public class PaymentHistoryEntry {
    @SerializedName("token")
    private final String token;

    @SerializedName("amount")
    private final long amount;

    @SerializedName("date")
    private final Date date;

    public PaymentHistoryEntry(String token, long amount, Date date) {
        this.token = token;
        this.amount = amount;
        this.date = date;
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
}
