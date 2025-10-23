package com.electricdreams.shellshock;

import com.google.gson.annotations.SerializedName;
import java.util.Date;

public class TokenHistoryEntry {
    @SerializedName("token")
    private final String token;

    @SerializedName("amount")
    private final long amount;

    @SerializedName("date")
    private final Date date;

    public TokenHistoryEntry(String token, long amount, Date date) {
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
