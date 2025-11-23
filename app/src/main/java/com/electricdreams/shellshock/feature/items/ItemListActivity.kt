package com.electricdreams.shellshock.feature.items

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.shellshock.R
import com.electricdreams.shellshock.core.model.Item
import com.electricdreams.shellshock.core.util.ItemManager
import java.util.Currency
import java.util.Locale

class ItemListActivity : AppCompatActivity() {

    private lateinit var itemManager: ItemManager
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var adapter: ItemAdapter

    private val addItemLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                refreshItems()
            }
        }

    private val selectCsvLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                processCsvFile(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_item_list)

        // Set up back button
        findViewById<View?>(R.id.back_button)?.setOnClickListener { finish() }

        // Set up toolbar (if present)
        findViewById<Toolbar?>(R.id.toolbar)?.let { toolbar ->
            setSupportActionBar(toolbar)
            supportActionBar?.apply {
                setDisplayHomeAsUpEnabled(true)
                setTitle(R.string.item_list_title)
            }
        }

        recyclerView = findViewById(R.id.items_recycler_view)
        emptyView = findViewById(R.id.empty_view)
        val fabAddItem: ImageButton = findViewById(R.id.fab_add_item)

        itemManager = ItemManager.getInstance(this)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = ItemAdapter(itemManager.getAllItems())
        recyclerView.adapter = adapter

        updateEmptyViewVisibility()

        fabAddItem.setOnClickListener {
            val intent = Intent(this, ItemEntryActivity::class.java)
            addItemLauncher.launch(intent)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun refreshItems() {
        adapter.updateItems(itemManager.getAllItems())
        updateEmptyViewVisibility()
    }

    private fun updateEmptyViewVisibility() {
        if (adapter.itemCount == 0) {
            emptyView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun processCsvFile(uri: Uri) {
        // Placeholder CSV processing
        Toast.makeText(this, "Processing CSV file...", Toast.LENGTH_SHORT).show()
        Toast.makeText(this, "CSV import not implemented in this demo", Toast.LENGTH_SHORT).show()
    }

    private inner class ItemAdapter(private var items: List<Item>) :
        RecyclerView.Adapter<ItemAdapter.ItemViewHolder>() {

        fun updateItems(newItems: List<Item>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_product, parent, false)
            return ItemViewHolder(view)
        }

        override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
            val item = items[position]
            holder.bind(item)
        }

        override fun getItemCount(): Int = items.size

        inner class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val nameView: TextView = itemView.findViewById(R.id.item_name)
            private val variationView: TextView = itemView.findViewById(R.id.item_variation)
            private val descriptionView: TextView = itemView.findViewById(R.id.item_description)
            private val skuView: TextView = itemView.findViewById(R.id.item_sku)
            private val priceView: TextView = itemView.findViewById(R.id.item_price)
            private val quantityView: TextView = itemView.findViewById(R.id.item_quantity)
            private val editButton: ImageButton = itemView.findViewById(R.id.edit_item_button)
            private val deleteButton: ImageButton = itemView.findViewById(R.id.delete_item_button)
            private val itemImageView: ImageView = itemView.findViewById(R.id.item_image)
            private val imagePlaceholder: ImageView = itemView.findViewById(R.id.item_image_placeholder)

            fun bind(item: Item) {
                nameView.text = item.name

                if (!item.variationName.isNullOrEmpty()) {
                    variationView.visibility = View.VISIBLE
                    variationView.text = item.variationName
                } else {
                    variationView.visibility = View.GONE
                }

                if (!item.description.isNullOrEmpty()) {
                    descriptionView.visibility = View.VISIBLE
                    descriptionView.text = item.description
                } else {
                    descriptionView.visibility = View.GONE
                }

                if (!item.sku.isNullOrEmpty()) {
                    skuView.text = "SKU: ${item.sku}"
                } else {
                    skuView.text = ""
                }

                val currencySymbol = Currency.getInstance(Locale.getDefault()).symbol
                priceView.text = String.format("%s%.2f", currencySymbol, item.price)

                quantityView.text = "In stock: ${item.quantity}"

                if (!item.imagePath.isNullOrEmpty()) {
                    val bitmap = itemManager.loadItemImage(item)
                    if (bitmap != null) {
                        itemImageView.setImageBitmap(bitmap)
                        imagePlaceholder.visibility = View.GONE
                    } else {
                        imagePlaceholder.visibility = View.VISIBLE
                    }
                } else {
                    itemImageView.setImageBitmap(null)
                    imagePlaceholder.visibility = View.VISIBLE
                }

                val editClickListener = View.OnClickListener {
                    val intent = Intent(this@ItemListActivity, ItemEntryActivity::class.java)
                    intent.putExtra(ItemEntryActivity.EXTRA_ITEM_ID, item.id)
                    addItemLauncher.launch(intent)
                }

                itemView.setOnClickListener(editClickListener)
                editButton.setOnClickListener(editClickListener)

                deleteButton.setOnClickListener {
                    val builder = AlertDialog.Builder(this@ItemListActivity)
                    val dialogView = layoutInflater.inflate(R.layout.dialog_delete_confirmation, null)
                    builder.setView(dialogView)

                    val dialog = builder.create()

                    val cancelButton: Button = dialogView.findViewById(R.id.dialog_cancel_button)
                    val confirmButton: Button = dialogView.findViewById(R.id.dialog_confirm_button)

                    cancelButton.setOnClickListener { dialog.dismiss() }

                    confirmButton.setOnClickListener {
                        item.id?.let { id -> itemManager.removeItem(id) }
                        refreshItems()
                        Toast.makeText(this@ItemListActivity, "Item deleted", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }

                    dialog.show()
                }
            }
        }
    }
}
