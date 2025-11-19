package com.electricdreams.shellshock.core.model;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.util.Objects;

/**
 * Model class for an item in the merchant's catalog
 */
public class Item implements Parcelable {
    private String id; // Unique identifier for the item
    private String name; // Item name
    private String variationName; // Optional variation name
    private String sku; // Stock keeping unit
    private String description; // Item description
    private String category; // Category
    private String gtin; // Global Trade Item Number
    private double price; // Price in default currency
    private int quantity; // Available quantity
    private boolean alertEnabled; // Whether stock alerts are enabled
    private int alertThreshold; // Threshold for stock alerts

    public Item() {
        // Default constructor
    }

    public Item(String id, String name, double price) {
        this.id = id;
        this.name = name;
        this.price = price;
    }

    protected Item(Parcel in) {
        id = in.readString();
        name = in.readString();
        variationName = in.readString();
        sku = in.readString();
        description = in.readString();
        category = in.readString();
        gtin = in.readString();
        price = in.readDouble();
        quantity = in.readInt();
        alertEnabled = in.readByte() != 0;
        alertThreshold = in.readInt();
    }

    public static final Creator<Item> CREATOR = new Creator<Item>() {
        @Override
        public Item createFromParcel(Parcel in) {
            return new Item(in);
        }

        @Override
        public Item[] newArray(int size) {
            return new Item[size];
        }
    };

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVariationName() {
        return variationName;
    }

    public void setVariationName(String variationName) {
        this.variationName = variationName;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getGtin() {
        return gtin;
    }

    public void setGtin(String gtin) {
        this.gtin = gtin;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public boolean isAlertEnabled() {
        return alertEnabled;
    }

    public void setAlertEnabled(boolean alertEnabled) {
        this.alertEnabled = alertEnabled;
    }

    public int getAlertThreshold() {
        return alertThreshold;
    }

    public void setAlertThreshold(int alertThreshold) {
        this.alertThreshold = alertThreshold;
    }

    /**
     * Get display name combining name and variation if available
     */
    public String getDisplayName() {
        if (variationName != null && !variationName.isEmpty()) {
            return name + " - " + variationName;
        }
        return name;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(id);
        dest.writeString(name);
        dest.writeString(variationName);
        dest.writeString(sku);
        dest.writeString(description);
        dest.writeString(category);
        dest.writeString(gtin);
        dest.writeDouble(price);
        dest.writeInt(quantity);
        dest.writeByte((byte) (alertEnabled ? 1 : 0));
        dest.writeInt(alertThreshold);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Item item = (Item) o;
        return Objects.equals(id, item.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
