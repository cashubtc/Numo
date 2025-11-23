package com.electricdreams.shellshock.feature.items

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.shellshock.ModernPOSActivity
import com.electricdreams.shellshock.R
import com.electricdreams.shellshock.core.model.BasketItem
import com.electricdreams.shellshock.core.model.Item
import com.electricdreams.shellshock.core.util.BasketManager
import com.electricdreams.shellshock.core.util.CurrencyManager
import com.electricdreams.shellshock.core.util.ItemManager
import com.electricdreams.shellshock.core.worker.BitcoinPriceWorker
import java.io.File

class ItemSelectionActivity : AppCompatActivity() {

    private lateinit var itemManager: ItemManager
    private lateinit var basketManager: BasketManager
    private lateinit var bitcoinPriceWorker: BitcoinPriceWorker

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var basketCountView: TextView
    private lateinit var basketTotalView: TextView
    private lateinit var viewBasketButton: android.widget.Button
    private lateinit var checkoutButton: android.widget.Button

    private lateinit var adapter: ItemAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_item_selection)

        findViewById<View?>(R.id.back_button)?.setOnClickListener { finish() }

        findViewById<Toolbar?>(R.id.toolbar)?.let { toolbar ->
            setSupportActionBar(toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
        }

        itemManager = ItemManager.getInstance(this)
        basketManager = BasketManager.getInstance()
        bitcoinPriceWorker = BitcoinPriceWorker.getInstance(this)

        recyclerView = findViewById(R.id.items_recycler_view)
        emptyView = findViewById(R.id.empty_view)
        basketCountView = findViewById(R.id.basket_count)
        basketTotalView = findViewById(R.id.basket_total)
        viewBasketButton = findViewById(R.id.view_basket_button)
        checkoutButton = findViewById(R.id.checkout_button)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = ItemAdapter(itemManager.getAllItems())
        recyclerView.adapter = adapter

        updateEmptyViewVisibility()

        viewBasketButton.setOnClickListener {
            startActivity(Intent(this, BasketActivity::class.java))
        }

        checkoutButton.setOnClickListener { proceedToCheckout() }

        updateBasketSummary()
        bitcoinPriceWorker.start()
    }

    override fun onResume() {
        super.onResume()
        updateBasketSummary()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
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

    private fun updateBasketSummary() {
        val itemCount = basketManager.getTotalItemCount()
        val totalPrice = basketManager.getTotalPrice()

        basketCountView.text = itemCount.toString()

        val formattedTotal = if (itemCount > 0) {
            val currencySymbol = CurrencyManager.getInstance(this).getCurrentSymbol()
            checkoutButton.isEnabled = true
            String.format("%s%.2f", currencySymbol, totalPrice)
        } else {
            checkoutButton.isEnabled = false
            "0.00"
        }

        basketTotalView.text = formattedTotal
    }

    private fun proceedToCheckout() {
        if (basketManager.getTotalItemCount() == 0) {
            Toast.makeText(this, "Your basket is empty", Toast.LENGTH_SHORT).show()
            return
        }

        val satoshisAmount = basketManager.getTotalSatoshis(bitcoinPriceWorker.getCurrentPrice())
        val intent = Intent(this, ModernPOSActivity::class.java).apply {
            putExtra("EXTRA_PAYMENT_AMOUNT", satoshisAmount)
        }

        basketManager.clearBasket()
        startActivity(intent)
    }

    private inner class ItemAdapter(private var items: List<Item>) :
        RecyclerView.Adapter<ItemAdapter.ItemViewHolder>() {

        private val basketQuantities: MutableMap<String, Int> = mutableMapOf()

        init {
            for (basketItem in basketManager.getBasketItems()) {
                basketQuantities[basketItem.item.id!!] = basketItem.quantity
            }
        }

        fun updateItems(newItems: List<Item>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_product_selection, parent, false)
            return ItemViewHolder(view)
        }

        override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
            val item = items[position]
            val quantity = basketQuantities[item.id] ?: 0
            holder.bind(item, quantity)
        }

        override fun getItemCount(): Int = items.size

        inner class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val nameView: TextView = itemView.findViewById(R.id.item_name)
            private val variationView: TextView = itemView.findViewById(R.id.item_variation)
            private val descriptionView: TextView = itemView.findViewById(R.id.item_description)
            private val skuView: TextView = itemView.findViewById(R.id.item_sku)
            private val priceView: TextView = itemView.findViewById(R.id.item_price)
            private val quantityView: TextView = itemView.findViewById(R.id.item_quantity)
            private val decreaseButton: ImageButton = itemView.findViewById(R.id.decrease_quantity_button)
            private val basketQuantityView: TextView = itemView.findViewById(R.id.basket_quantity)
            private val increaseButton: ImageButton = itemView.findViewById(R.id.increase_quantity_button)
            private val itemImageView: ImageView = itemView.findViewById(R.id.item_image)

            fun bind(item: Item, basketQuantity: Int) {
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

                val currencySymbol = CurrencyManager.getInstance(itemView.context).getCurrentSymbol()
                priceView.text = String.format("%s%.2f", currencySymbol, item.price)

                quantityView.text = "In stock: ${item.quantity}"

                basketQuantityView.text = basketQuantity.toString()

                if (!item.imagePath.isNullOrEmpty()) {
                    val imageFile = File(item.imagePath!!)
                    if (imageFile.exists()) {
                        val bitmap: Bitmap? = BitmapFactory.decodeFile(imageFile.absolutePath)
                        if (bitmap != null) {
                            itemImageView.setImageBitmap(bitmap)
                        } else {
                            itemImageView.setImageResource(R.drawable.ic_image_placeholder)
                        }
                    } else {
                        itemImageView.setImageResource(R.drawable.ic_image_placeholder)
                    }
                } else {
                    itemImageView.setImageResource(R.drawable.ic_image_placeholder)
                }

                decreaseButton.isEnabled = basketQuantity > 0

                val hasStock = item.quantity > basketQuantity || item.quantity == 0
                increaseButton.isEnabled = hasStock

                decreaseButton.setOnClickListener {
                    if (basketQuantity > 0) {
                        updateBasketItem(item, basketQuantity - 1)
                    }
                }

                increaseButton.setOnClickListener {
                    if (hasStock) {
                        updateBasketItem(item, basketQuantity + 1)
                    } else {
                        Toast.makeText(
                            itemView.context,
                            "No more stock available",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }

            private fun updateBasketItem(item: Item, newQuantity: Int) {
                if (newQuantity <= 0) {
                    basketManager.removeItem(item.id!!)
                    basketQuantities.remove(item.id!!)
                } else {
                    val updated = basketManager.updateItemQuantity(item.id!!, newQuantity)
                    if (!updated) {
                        basketManager.addItem(item, newQuantity)
                    }
                    basketQuantities[item.id!!] = newQuantity
                }

                notifyItemChanged(adapterPosition)
                updateBasketSummary()
            }
        }
    }
}
