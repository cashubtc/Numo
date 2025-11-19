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
import androidx.appcompat.app.AlertDialog;
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
import com.electricdreams.shellshock.core.worker.BitcoinPriceWorker;

import java.util.List;

public class BasketActivity extends AppCompatActivity {

    private BasketManager basketManager;
    private BitcoinPriceWorker bitcoinPriceWorker;
    
    private RecyclerView recyclerView;
    private TextView emptyView;
    private TextView basketCountView;
    private TextView basketTotalView;
    private Button clearBasketButton;
    private Button continueShoppingButton;
    private Button checkoutButton;
    
    private BasketAdapter adapter;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_basket);
        
        // Set up toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        
        // Initialize managers
        basketManager = BasketManager.getInstance();
        bitcoinPriceWorker = BitcoinPriceWorker.getInstance(this);
        
        // Initialize views
        recyclerView = findViewById(R.id.basket_recycler_view);
        emptyView = findViewById(R.id.empty_view);
        basketCountView = findViewById(R.id.basket_count);
        basketTotalView = findViewById(R.id.basket_total);
        clearBasketButton = findViewById(R.id.clear_basket_button);
        continueShoppingButton = findViewById(R.id.continue_shopping_button);
        checkoutButton = findViewById(R.id.checkout_button);
        
        // Set up RecyclerView
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BasketAdapter(basketManager.getBasketItems());
        recyclerView.setAdapter(adapter);
        
        // Set up empty view
        updateEmptyViewVisibility();
        
        // Set up buttons
        clearBasketButton.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Clear Basket")
                    .setMessage("Are you sure you want to clear your basket?")
                    .setPositiveButton("Clear", (dialog, which) -> {
                        basketManager.clearBasket();
                        updateBasketSummary();
                        adapter.updateItems(basketManager.getBasketItems());
                        updateEmptyViewVisibility();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        
        continueShoppingButton.setOnClickListener(v -> finish());
        
        checkoutButton.setOnClickListener(v -> proceedToCheckout());
        
        // Update basket summary
        updateBasketSummary();
        
        // Make sure bitcoin price worker is running
        bitcoinPriceWorker.start();
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
            clearBasketButton.setEnabled(false);
            checkoutButton.setEnabled(false);
        } else {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            clearBasketButton.setEnabled(true);
            checkoutButton.setEnabled(true);
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
        } else {
            formattedTotal = "0.00";
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
        
        // Add flag to clear the activity stack and start fresh
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        
        // Clear the basket after checkout
        basketManager.clearBasket();
        
        // Start the activity
        startActivity(intent);
        finish();
    }
    
    private class BasketAdapter extends RecyclerView.Adapter<BasketAdapter.BasketViewHolder> {
        
        private List<BasketItem> basketItems;
        
        BasketAdapter(List<BasketItem> basketItems) {
            this.basketItems = basketItems;
        }
        
        void updateItems(List<BasketItem> newItems) {
            this.basketItems = newItems;
            notifyDataSetChanged();
        }
        
        @NonNull
        @Override
        public BasketViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_basket, parent, false);
            return new BasketViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull BasketViewHolder holder, int position) {
            BasketItem basketItem = basketItems.get(position);
            holder.bind(basketItem);
        }
        
        @Override
        public int getItemCount() {
            return basketItems.size();
        }
        
        class BasketViewHolder extends RecyclerView.ViewHolder {
            private final TextView nameView;
            private final TextView variationView;
            private final TextView quantityView;
            private final TextView priceView;
            private final TextView totalView;
            private final ImageButton removeButton;
            
            BasketViewHolder(@NonNull View itemView) {
                super(itemView);
                nameView = itemView.findViewById(R.id.item_name);
                variationView = itemView.findViewById(R.id.item_variation);
                quantityView = itemView.findViewById(R.id.item_quantity);
                priceView = itemView.findViewById(R.id.item_price);
                totalView = itemView.findViewById(R.id.item_total);
                removeButton = itemView.findViewById(R.id.remove_item_button);
            }
            
            void bind(BasketItem basketItem) {
                Item item = basketItem.getItem();
                int quantity = basketItem.getQuantity();
                
                nameView.setText(item.getName());
                
                // Show variation if available
                if (item.getVariationName() != null && !item.getVariationName().isEmpty()) {
                    variationView.setVisibility(View.VISIBLE);
                    variationView.setText(item.getVariationName());
                } else {
                    variationView.setVisibility(View.GONE);
                }
                
                // Show quantity
                quantityView.setText("Qty: " + quantity);
                
                // Format price with currency symbol
                String currencySymbol = CurrencyManager.getInstance(itemView.getContext()).getCurrentSymbol();
                priceView.setText(String.format("%s%.2f", currencySymbol, item.getPrice()));
                
                // Calculate and format total price
                double total = item.getPrice() * quantity;
                totalView.setText(String.format("%s%.2f", currencySymbol, total));
                
                // Set up remove button
                removeButton.setOnClickListener(v -> {
                    basketManager.removeItem(item.getId());
                    int position = getAdapterPosition();
                    if (position != RecyclerView.NO_POSITION) {
                        basketItems.remove(position);
                        notifyItemRemoved(position);
                        updateBasketSummary();
                        updateEmptyViewVisibility();
                    }
                });
            }
        }
    }
}
