package com.electricdreams.shellshock.core.model;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

/**
 * Model class for an item in the customer's basket with quantity
 */
public class BasketItem implements Parcelable {
    private Item item;
    private int quantity;

    public BasketItem(Item item, int quantity) {
        this.item = item;
        this.quantity = quantity;
    }

    protected BasketItem(Parcel in) {
        item = in.readParcelable(Item.class.getClassLoader());
        quantity = in.readInt();
    }

    public static final Creator<BasketItem> CREATOR = new Creator<BasketItem>() {
        @Override
        public BasketItem createFromParcel(Parcel in) {
            return new BasketItem(in);
        }

        @Override
        public BasketItem[] newArray(int size) {
            return new BasketItem[size];
        }
    };

    public Item getItem() {
        return item;
    }

    public void setItem(Item item) {
        this.item = item;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    /**
     * Increase quantity by one
     */
    public void increaseQuantity() {
        this.quantity++;
    }

    /**
     * Decrease quantity by one, ensuring it doesn't go below 0
     * @return true if quantity is greater than 0 after decrease, false otherwise
     */
    public boolean decreaseQuantity() {
        if (quantity > 0) {
            quantity--;
            return quantity > 0;
        }
        return false;
    }

    /**
     * Calculate the total price for this basket item (price * quantity)
     * @return Total price
     */
    public double getTotalPrice() {
        return item.getPrice() * quantity;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(item, flags);
        dest.writeInt(quantity);
    }
}
