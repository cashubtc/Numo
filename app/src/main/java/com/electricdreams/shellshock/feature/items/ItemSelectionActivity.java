package com.electricdreams.shellshock.feature.items;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.electricdreams.shellshock.ModernPOSActivity;
import com.electricdreams.shellshock.R;
import com.electricdreams.shellshock.core.model.BasketItem;
import com.electricdreams.shellshock.core.model.Item;
import com.electricdreams.shellshock.core.util.BasketManager;
import com.electricdreams.shellshock.core.util.CurrencyManager;
import com.electricdreams.shellshock.core.util.ItemManager;
import com.electricdreams.shellshock.core.worker.BitcoinPriceWorker;

import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ItemSelectionActivity extends AppCompatActivity {

    private ItemManager itemManager;
    private BasketManager basketManager;
    private BitcoinPriceWorker bitcoinPriceWorker;
    
    private RecyclerView recyclerView;
    private TextView emptyView;
    private TextView basketCountView;
    private TextView basketTotalView;
    private Button viewBasketButton;
    private Button checkoutButton;
    
    private ItemAdapter adapter;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_item_selection);
        
        // Set up toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        
        // Initialize managers
        itemManager = ItemManager.getInstance(this);
        basketManager = BasketManager.getInstance();
        bitcoinPriceWorker = BitcoinPriceWorker.getInstance(this);
        
        // Initialize views
        recyclerView = findViewById(R.id.items_recycler_view);
        emptyView = findViewById(R.id.empty_view);
        basketCountView = findViewById(R.id.basket_count);
        basketTotalView = findViewById(R.id.basket_total);
        viewBasketButton = findViewById(R.id.view_basket_button);
        checkoutButton = findViewById(R.id.checkout_button);
        
        // Set up RecyclerView
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ItemAdapter(itemManager.getAllItems());
        recyclerView.setAdapter(adapter);
        
        // Set up empty view
        updateEmptyViewVisibility();
        
        // Set up buttons
        viewBasketButton.setOnClickListener(v -> {
            Intent intent = new Intent(ItemSelectionActivity.this, BasketActivity.class);
            startActivity(intent);
        });
        
        checkoutButton.setOnClickListener(v -> proceedToCheckout());
        
        // Update basket summary
        updateBasketSummary();
        
        // Make sure bitcoin price worker is running
        bitcoinPriceWorker.start();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // Refresh the basket summary when returning to this activity
        updateBasketSummary();
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    
    private void updateEmptyViewVisibility() {
        if (adapter.getItemCount() == 0) {
            emptyView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
    }
    
    private void updateBasketSummary() {
        int itemCount = basketManager.getTotalItemCount();
        double totalPrice = basketManager.getTotalPrice();
        
        basketCountView.setText(String.valueOf(itemCount));
        
        // Format price with currency symbol
        String formattedTotal;
        if (itemCount > 0) {
            String currencySymbol = CurrencyManager.getInstance(this).getCurrentSymbol();
            formattedTotal = String.format("%s%.2f", currencySymbol, totalPrice);
            checkoutButton.setEnabled(true);
        } else {
            formattedTotal = "0.00";
            checkoutButton.setEnabled(false);
        }
        
        basketTotalView.setText(formattedTotal);
    }
    
    private void proceedToCheckout() {
        if (basketManager.getTotalItemCount() == 0) {
            Toast.makeText(this, "Your basket is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Calculate the total amount in satoshis
        long satoshisAmount = basketManager.getTotalSatoshis(bitcoinPriceWorker.getCurrentPrice());
        
        // Create intent to go to ModernPOSActivity with the amount
        Intent intent = new Intent(this, ModernPOSActivity.class);
        
        // Add the satoshi amount as an extra (the ModernPOSActivity will need to be modified to accept this)
        intent.putExtra("EXTRA_PAYMENT_AMOUNT", satoshisAmount);
        
        // Don't clear the activity stack to allow returning to the item selection after payment
        
        // Clear the basket after checkout
        basketManager.clearBasket();
        
        // Start the activity
        startActivity(intent);
    }
    
    private class ItemAdapter extends RecyclerView.Adapter<ItemAdapter.ItemViewHolder> {
        
        private List<Item> items;
        private final Map<String, Integer> basketQuantities = new HashMap<>();
        
        ItemAdapter(List<Item> items) {
            this.items = items;
            // Initialize basket quantities
            for (BasketItem basketItem : basketManager.getBasketItems()) {
                basketQuantities.put(basketItem.getItem().getId(), basketItem.getQuantity());
            }
        }
        
        void updateItems(List<Item> newItems) {
            this.items = newItems;
            notifyDataSetChanged();
        }
        
        @NonNull
        @Override
        public ItemViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_product_selection, parent, false);
            return new ItemViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull ItemViewHolder holder, int position) {
            Item item = items.get(position);
            
            // Get quantity from map, default to 0
            int quantity = basketQuantities.getOrDefault(item.getId(), 0);
            
            holder.bind(item, quantity);
        }
        
        @Override
        public int getItemCount() {
            return items.size();
        }
        
        class ItemViewHolder extends RecyclerView.ViewHolder {
            private final TextView nameView;
            private final TextView variationView;
            private final TextView descriptionView;
            private final TextView skuView;
            private final TextView priceView;
            private final TextView quantityView;
            private final ImageButton decreaseButton;
            private final TextView basketQuantityView;
            private final ImageButton increaseButton;
            
            ItemViewHolder(@NonNull View itemView) {
                super(itemView);
                nameView = itemView.findViewById(R.id.item_name);
                variationView = itemView.findViewById(R.id.item_variation);
                descriptionView = itemView.findViewById(R.id.item_description);
                skuView = itemView.findViewById(R.id.item_sku);
                priceView = itemView.findViewById(R.id.item_price);
                quantityView = itemView.findViewById(R.id.item_quantity);
                decreaseButton = itemView.findViewById(R.id.decrease_quantity_button);
                basketQuantityView = itemView.findViewById(R.id.basket_quantity);
                increaseButton = itemView.findViewById(R.id.increase_quantity_button);
            }
            
            void bind(Item item, int basketQuantity) {
                nameView.setText(item.getName());
                
                // Show variation if available
                if (item.getVariationName() != null && !item.getVariationName().isEmpty()) {
                    variationView.setVisibility(View.VISIBLE);
                    variationView.setText(item.getVariationName());
                } else {
                    variationView.setVisibility(View.GONE);
                }
                
                // Show description if available
                if (item.getDescription() != null && !item.getDescription().isEmpty()) {
                    descriptionView.setVisibility(View.VISIBLE);
                    descriptionView.setText(item.getDescription());
                } else {
                    descriptionView.setVisibility(View.GONE);
                }
                
                // Show SKU if available
                if (item.getSku() != null && !item.getSku().isEmpty()) {
                    skuView.setText("SKU: " + item.getSku());
                } else {
                    skuView.setText("");
                }
                
                // Format price with currency symbol
                String currencySymbol = CurrencyManager.getInstance(itemView.getContext()).getCurrentSymbol();
                priceView.setText(String.format("%s%.2f", currencySymbol, item.getPrice()));
                
                // Show quantity
                quantityView.setText("In stock: " + item.getQuantity());
                
                // Show basket quantity
                basketQuantityView.setText(String.valueOf(basketQuantity));
                
                // Enable/disable decrease button based on basket quantity
                decreaseButton.setEnabled(basketQuantity > 0);
                
                // Enable/disable increase button based on stock
                boolean hasStock = item.getQuantity() > basketQuantity || item.getQuantity() == 0;
                increaseButton.setEnabled(hasStock);
                
                // Set up decrease button
                decreaseButton.setOnClickListener(v -> {
                    if (basketQuantity > 0) {
                        updateBasketItem(item, basketQuantity - 1);
                    }
                });
                
                // Set up increase button
                increaseButton.setOnClickListener(v -> {
                    // Check if we have stock available or if stock tracking is disabled (quantity = 0)
                    if (hasStock) {
                        updateBasketItem(item, basketQuantity + 1);
                    } else {
                        Toast.makeText(itemView.getContext(), "No more stock available", Toast.LENGTH_SHORT).show();
                    }
                });
                
                // Set up item click listener
                itemView.setOnClickListener(v -> {
                    // If we have stock available or stock tracking is disabled, add one item
                    if (hasStock) {
                        updateBasketItem(item, basketQuantity + 1);
                    } else {
                        Toast.makeText(itemView.getContext(), "No more stock available", Toast.LENGTH_SHORT).show();
                    }
                });
            }
            
            private void updateBasketItem(Item item, int newQuantity) {
                if (newQuantity <= 0) {
                    // Remove item from basket
                    basketManager.removeItem(item.getId());
                    basketQuantities.remove(item.getId());
                } else {
                    // Try to update item quantity first
                    boolean updated = basketManager.updateItemQuantity(item.getId(), newQuantity);
                    
                    // If update failed, item doesn't exist in basket yet, so add it
                    if (!updated) {
                        basketManager.addItem(item, newQuantity);
                    }
                    
                    basketQuantities.put(item.getId(), newQuantity);
                    
                    // Show a toast when adding an item
                    if (newQuantity == 1) {
                        Toast.makeText(itemView.getContext(), 
                                item.getDisplayName() + " added to basket", 
                                Toast.LENGTH_SHORT).show();
                    }
                }
                
                // Update UI
                notifyItemChanged(getAdapterPosition());
                updateBasketSummary();
            }
        }
    }
}
