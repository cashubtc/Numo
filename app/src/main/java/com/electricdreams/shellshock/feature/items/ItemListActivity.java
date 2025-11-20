package com.electricdreams.shellshock.feature.items;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.electricdreams.shellshock.R;
import com.electricdreams.shellshock.core.model.Item;
import com.electricdreams.shellshock.core.util.ItemManager;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.Currency;
import java.util.List;
import java.util.Locale;

public class ItemListActivity extends AppCompatActivity {

    private ItemManager itemManager;
    private RecyclerView recyclerView;
    private TextView emptyView;
    private ItemAdapter adapter;

    private final ActivityResultLauncher<Intent> addItemLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    refreshItems();
                }
            });

    private final ActivityResultLauncher<String> selectCsvLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    processCsvFile(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_item_list);

        // Set up toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle(R.string.item_list_title);

        // Initialize views
        recyclerView = findViewById(R.id.items_recycler_view);
        emptyView = findViewById(R.id.empty_view);
        FloatingActionButton fabAddItem = findViewById(R.id.fab_add_item);

        // Get item manager instance
        itemManager = ItemManager.getInstance(this);

        // Set up RecyclerView
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ItemAdapter(itemManager.getAllItems());
        recyclerView.setAdapter(adapter);

        // Set up empty view
        updateEmptyViewVisibility();

        // Set up add item FAB
        fabAddItem.setOnClickListener(v -> {
            Intent intent = new Intent(ItemListActivity.this, ItemEntryActivity.class);
            addItemLauncher.launch(intent);
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void refreshItems() {
        adapter.updateItems(itemManager.getAllItems());
        updateEmptyViewVisibility();
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

    private void processCsvFile(Uri uri) {
        // Implement CSV file processing here
        Toast.makeText(this, "Processing CSV file...", Toast.LENGTH_SHORT).show();
        
        // Would need to copy the file to a temp location and then import it
        // For this demo, we'll manually add some sample items instead
        Toast.makeText(this, "CSV import not implemented in this demo", Toast.LENGTH_SHORT).show();
    }

    private class ItemAdapter extends RecyclerView.Adapter<ItemAdapter.ItemViewHolder> {
        
        private List<Item> items;
        
        ItemAdapter(List<Item> items) {
            this.items = items;
        }
        
        void updateItems(List<Item> newItems) {
            this.items = newItems;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ItemViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_product, parent, false);
            return new ItemViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ItemViewHolder holder, int position) {
            Item item = items.get(position);
            holder.bind(item);
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
            private final ImageButton editButton;
            private final ImageButton deleteButton;

            ItemViewHolder(@NonNull View itemView) {
                super(itemView);
                nameView = itemView.findViewById(R.id.item_name);
                variationView = itemView.findViewById(R.id.item_variation);
                descriptionView = itemView.findViewById(R.id.item_description);
                skuView = itemView.findViewById(R.id.item_sku);
                priceView = itemView.findViewById(R.id.item_price);
                quantityView = itemView.findViewById(R.id.item_quantity);
                editButton = itemView.findViewById(R.id.edit_item_button);
                deleteButton = itemView.findViewById(R.id.delete_item_button);
            }

            void bind(Item item) {
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
                String currencySymbol = Currency.getInstance(Locale.getDefault()).getSymbol();
                priceView.setText(String.format("%s%.2f", currencySymbol, item.getPrice()));
                
                // Show quantity
                quantityView.setText("In stock: " + item.getQuantity());
                
                // Set up edit button
                editButton.setOnClickListener(v -> {
                    Intent intent = new Intent(ItemListActivity.this, ItemEntryActivity.class);
                    intent.putExtra(ItemEntryActivity.EXTRA_ITEM_ID, item.getId());
                    addItemLauncher.launch(intent);
                });
                
                // Set up delete button
                deleteButton.setOnClickListener(v -> {
                    new AlertDialog.Builder(ItemListActivity.this)
                            .setTitle("Delete Item")
                            .setMessage("Are you sure you want to delete this item?")
                            .setPositiveButton("Delete", (dialog, which) -> {
                                itemManager.removeItem(item.getId());
                                refreshItems();
                                Toast.makeText(ItemListActivity.this, "Item deleted", Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                });
            }
        }
    }
}
