package com.electricdreams.shellshock.feature.items

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.shellshock.ModernPOSActivity
import com.electricdreams.shellshock.R
import com.electricdreams.shellshock.core.model.Amount
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
    private lateinit var currencyManager: CurrencyManager

    // Views
    private lateinit var searchInput: EditText
    private lateinit var scanButton: ImageButton
    private lateinit var basketSection: LinearLayout
    private lateinit var basketRecyclerView: RecyclerView
    private lateinit var basketTotalView: TextView
    private lateinit var clearBasketButton: TextView
    private lateinit var itemsRecyclerView: RecyclerView
    private lateinit var emptyView: LinearLayout
    private lateinit var noResultsView: LinearLayout
    private lateinit var checkoutContainer: CardView
    private lateinit var checkoutButton: Button

    private lateinit var itemsAdapter: ItemsAdapter
    private lateinit var basketAdapter: BasketAdapter

    private var allItems: List<Item> = emptyList()
    private var filteredItems: List<Item> = emptyList()

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == CheckoutScannerActivity.RESULT_BASKET_UPDATED) {
            // Refresh basket and items
            refreshBasket()
            itemsAdapter.notifyDataSetChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_item_selection)

        itemManager = ItemManager.getInstance(this)
        basketManager = BasketManager.getInstance()
        bitcoinPriceWorker = BitcoinPriceWorker.getInstance(this)
        currencyManager = CurrencyManager.getInstance(this)

        initViews()
        setupListeners()
        setupRecyclerViews()

        loadItems()
        refreshBasket()

        bitcoinPriceWorker.start()
    }

    private fun initViews() {
        findViewById<View>(R.id.back_button).setOnClickListener { finish() }

        searchInput = findViewById(R.id.search_input)
        scanButton = findViewById(R.id.scan_button)
        basketSection = findViewById(R.id.basket_section)
        basketRecyclerView = findViewById(R.id.basket_recycler_view)
        basketTotalView = findViewById(R.id.basket_total)
        clearBasketButton = findViewById(R.id.clear_basket_button)
        itemsRecyclerView = findViewById(R.id.items_recycler_view)
        emptyView = findViewById(R.id.empty_view)
        noResultsView = findViewById(R.id.no_results_view)
        checkoutContainer = findViewById(R.id.checkout_container)
        checkoutButton = findViewById(R.id.checkout_button)
    }

    private fun setupListeners() {
        scanButton.setOnClickListener {
            val intent = Intent(this, CheckoutScannerActivity::class.java)
            scannerLauncher.launch(intent)
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterItems(s?.toString() ?: "")
            }
        })

        clearBasketButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Basket")
                .setMessage("Remove all items from basket?")
                .setPositiveButton("Clear") { _, _ ->
                    basketManager.clearBasket()
                    refreshBasket()
                    itemsAdapter.notifyDataSetChanged()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        checkoutButton.setOnClickListener {
            proceedToCheckout()
        }
    }

    private fun setupRecyclerViews() {
        // Items list
        itemsRecyclerView.layoutManager = LinearLayoutManager(this)
        itemsAdapter = ItemsAdapter()
        itemsRecyclerView.adapter = itemsAdapter

        // Basket list
        basketRecyclerView.layoutManager = LinearLayoutManager(this)
        basketAdapter = BasketAdapter()
        basketRecyclerView.adapter = basketAdapter
    }

    private fun loadItems() {
        allItems = itemManager.getAllItems()
        filteredItems = allItems
        itemsAdapter.updateItems(filteredItems)
        updateEmptyState()
    }

    private fun filterItems(query: String) {
        filteredItems = if (query.isBlank()) {
            allItems
        } else {
            itemManager.searchItems(query)
        }
        itemsAdapter.updateItems(filteredItems)
        updateEmptyState()
    }

    private fun updateEmptyState() {
        val hasItems = allItems.isNotEmpty()
        val hasResults = filteredItems.isNotEmpty()
        val isSearching = searchInput.text.isNotBlank()

        when {
            !hasItems -> {
                emptyView.visibility = View.VISIBLE
                noResultsView.visibility = View.GONE
                itemsRecyclerView.visibility = View.GONE
            }
            !hasResults && isSearching -> {
                emptyView.visibility = View.GONE
                noResultsView.visibility = View.VISIBLE
                itemsRecyclerView.visibility = View.GONE
            }
            else -> {
                emptyView.visibility = View.GONE
                noResultsView.visibility = View.GONE
                itemsRecyclerView.visibility = View.VISIBLE
            }
        }
    }

    private fun refreshBasket() {
        val basketItems = basketManager.getBasketItems()
        basketAdapter.updateItems(basketItems)

        if (basketItems.isEmpty()) {
            // Hide basket section with animation
            if (basketSection.visibility == View.VISIBLE) {
                animateBasketSection(false)
            }
            checkoutContainer.visibility = View.GONE
        } else {
            // Show basket section with animation
            if (basketSection.visibility != View.VISIBLE) {
                animateBasketSection(true)
            }
            checkoutContainer.visibility = View.VISIBLE
            updateCheckoutButton()
        }

        updateBasketTotal()
    }

    private fun animateBasketSection(show: Boolean) {
        if (show) {
            basketSection.visibility = View.VISIBLE
            basketSection.alpha = 0f
            basketSection.translationY = -50f

            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(basketSection, "alpha", 0f, 1f),
                    ObjectAnimator.ofFloat(basketSection, "translationY", -50f, 0f)
                )
                duration = 300
                interpolator = DecelerateInterpolator()
                start()
            }
        } else {
            val animSet = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(basketSection, "alpha", 1f, 0f),
                    ObjectAnimator.ofFloat(basketSection, "translationY", 0f, -50f)
                )
                duration = 200
                interpolator = DecelerateInterpolator()
            }
            animSet.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    basketSection.visibility = View.GONE
                }
            })
            animSet.start()
        }
    }

    private fun updateBasketTotal() {
        val itemCount = basketManager.getTotalItemCount()
        val fiatTotal = basketManager.getTotalPrice()
        val satsTotal = basketManager.getTotalSatsDirectPrice()

        val formattedTotal = if (itemCount > 0) {
            val currencyCode = currencyManager.getCurrentCurrency()
            val currency = Amount.Currency.fromCode(currencyCode)

            when {
                fiatTotal > 0 && satsTotal > 0 -> {
                    val fiatAmount = Amount.fromMajorUnits(fiatTotal, currency)
                    "$fiatAmount + $satsTotal sats"
                }
                satsTotal > 0 -> "$satsTotal sats"
                else -> Amount.fromMajorUnits(fiatTotal, currency).toString()
            }
        } else {
            "0.00"
        }

        basketTotalView.text = formattedTotal
    }

    private fun updateCheckoutButton() {
        val fiatTotal = basketManager.getTotalPrice()
        val satsTotal = basketManager.getTotalSatsDirectPrice()
        val currencyCode = currencyManager.getCurrentCurrency()
        val currency = Amount.Currency.fromCode(currencyCode)

        val buttonText = when {
            fiatTotal > 0 && satsTotal > 0 -> {
                val fiatAmount = Amount.fromMajorUnits(fiatTotal, currency)
                "Charge $fiatAmount + $satsTotal sats"
            }
            satsTotal > 0 -> "Charge $satsTotal sats"
            else -> {
                val fiatAmount = Amount.fromMajorUnits(fiatTotal, currency)
                "Charge $fiatAmount"
            }
        }

        checkoutButton.text = buttonText
    }

    private fun proceedToCheckout() {
        if (basketManager.getTotalItemCount() == 0) {
            Toast.makeText(this, "Your basket is empty", Toast.LENGTH_SHORT).show()
            return
        }

        val satoshisAmount = basketManager.getTotalSatoshis(bitcoinPriceWorker.getCurrentPrice())
        val intent = Intent(this, ModernPOSActivity::class.java).apply {
            putExtra("EXTRA_PAYMENT_AMOUNT", satoshisAmount)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        basketManager.clearBasket()
        startActivity(intent)
        finish()
    }

    override fun onResume() {
        super.onResume()
        // Refresh in case items were modified
        loadItems()
        refreshBasket()
    }

    // ==================== Items Adapter ====================

    private inner class ItemsAdapter : RecyclerView.Adapter<ItemsAdapter.ItemViewHolder>() {

        private var items: List<Item> = emptyList()
        private val basketQuantities: MutableMap<String, Int> = mutableMapOf()

        fun updateItems(newItems: List<Item>) {
            items = newItems
            refreshBasketQuantities()
            notifyDataSetChanged()
        }

        private fun refreshBasketQuantities() {
            basketQuantities.clear()
            for (basketItem in basketManager.getBasketItems()) {
                basketQuantities[basketItem.item.id!!] = basketItem.quantity
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_product_selection, parent, false)
            return ItemViewHolder(view)
        }

        override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
            val item = items[position]
            val quantity = basketQuantities[item.id] ?: 0
            holder.bind(item, quantity, position == items.lastIndex)
        }

        override fun getItemCount(): Int = items.size

        inner class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val nameView: TextView = itemView.findViewById(R.id.item_name)
            private val variationView: TextView = itemView.findViewById(R.id.item_variation)
            private val priceView: TextView = itemView.findViewById(R.id.item_price)
            private val stockView: TextView = itemView.findViewById(R.id.item_quantity)
            private val quantityView: TextView = itemView.findViewById(R.id.basket_quantity)
            private val decreaseButton: ImageButton = itemView.findViewById(R.id.decrease_quantity_button)
            private val increaseButton: ImageButton = itemView.findViewById(R.id.increase_quantity_button)
            private val itemImageView: ImageView = itemView.findViewById(R.id.item_image)
            private val imagePlaceholder: ImageView? = itemView.findViewById(R.id.item_image_placeholder)
            private val divider: View? = itemView.findViewById(R.id.divider)

            fun bind(item: Item, basketQuantity: Int, isLast: Boolean) {
                nameView.text = item.name ?: ""

                if (!item.variationName.isNullOrEmpty()) {
                    variationView.text = item.variationName
                    variationView.visibility = View.VISIBLE
                } else {
                    variationView.visibility = View.GONE
                }

                priceView.text = item.getFormattedPrice()

                if (item.trackInventory) {
                    stockView.visibility = View.VISIBLE
                    stockView.text = "${item.quantity} in stock"
                } else {
                    stockView.visibility = View.GONE
                }

                quantityView.text = basketQuantity.toString()

                // Load image
                loadItemImage(item)

                // Button states
                decreaseButton.isEnabled = basketQuantity > 0
                decreaseButton.alpha = if (basketQuantity > 0) 1f else 0.4f

                val hasStock = if (item.trackInventory) item.quantity > basketQuantity else true
                increaseButton.isEnabled = hasStock
                increaseButton.alpha = if (hasStock) 1f else 0.4f

                // Hide divider on last item
                divider?.visibility = if (isLast) View.GONE else View.VISIBLE

                decreaseButton.setOnClickListener {
                    if (basketQuantity > 0) {
                        updateBasketItem(item, basketQuantity - 1)
                    }
                }

                increaseButton.setOnClickListener {
                    if (hasStock) {
                        updateBasketItem(item, basketQuantity + 1)
                    } else {
                        Toast.makeText(itemView.context, "No more stock available", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            private fun loadItemImage(item: Item) {
                if (!item.imagePath.isNullOrEmpty()) {
                    val imageFile = File(item.imagePath!!)
                    if (imageFile.exists()) {
                        val bitmap: Bitmap? = BitmapFactory.decodeFile(imageFile.absolutePath)
                        if (bitmap != null) {
                            itemImageView.setImageBitmap(bitmap)
                            imagePlaceholder?.visibility = View.GONE
                            return
                        }
                    }
                }
                itemImageView.setImageBitmap(null)
                imagePlaceholder?.visibility = View.VISIBLE
            }

            private fun updateBasketItem(item: Item, newQuantity: Int) {
                val wasEmpty = basketManager.getTotalItemCount() == 0

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

                // Animate quantity change
                quantityView.text = newQuantity.toString()
                val scaleAnim = AnimatorSet().apply {
                    playSequentially(
                        ObjectAnimator.ofFloat(quantityView, "scaleX", 1f, 1.2f).setDuration(100),
                        ObjectAnimator.ofFloat(quantityView, "scaleX", 1.2f, 1f).setDuration(100)
                    )
                }
                val scaleAnimY = AnimatorSet().apply {
                    playSequentially(
                        ObjectAnimator.ofFloat(quantityView, "scaleY", 1f, 1.2f).setDuration(100),
                        ObjectAnimator.ofFloat(quantityView, "scaleY", 1.2f, 1f).setDuration(100)
                    )
                }
                AnimatorSet().apply {
                    playTogether(scaleAnim, scaleAnimY)
                    start()
                }

                notifyItemChanged(adapterPosition)
                refreshBasket()
            }
        }
    }

    // ==================== Basket Adapter ====================

    private inner class BasketAdapter : RecyclerView.Adapter<BasketAdapter.BasketViewHolder>() {

        private var basketItems: List<BasketItem> = emptyList()

        fun updateItems(newItems: List<BasketItem>) {
            val oldSize = basketItems.size
            basketItems = newItems

            // Simple diff - animate new items
            if (newItems.size > oldSize) {
                notifyItemInserted(0) // Newest items at top
            } else {
                notifyDataSetChanged()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BasketViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_basket_compact, parent, false)
            return BasketViewHolder(view)
        }

        override fun onBindViewHolder(holder: BasketViewHolder, position: Int) {
            // Show newest first (reverse order)
            val basketItem = basketItems[basketItems.size - 1 - position]
            holder.bind(basketItem)
        }

        override fun getItemCount(): Int = basketItems.size

        inner class BasketViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val quantityBadge: TextView = itemView.findViewById(R.id.item_quantity_badge)
            private val nameView: TextView = itemView.findViewById(R.id.item_name)
            private val variationView: TextView = itemView.findViewById(R.id.item_variation)
            private val totalView: TextView = itemView.findViewById(R.id.item_total)
            private val removeButton: ImageButton = itemView.findViewById(R.id.remove_button)

            fun bind(basketItem: BasketItem) {
                val item = basketItem.item

                quantityBadge.text = basketItem.quantity.toString()
                nameView.text = item.name ?: ""

                if (!item.variationName.isNullOrEmpty()) {
                    variationView.text = item.variationName
                    variationView.visibility = View.VISIBLE
                } else {
                    variationView.visibility = View.GONE
                }

                // Format total
                if (basketItem.isSatsPrice()) {
                    totalView.text = "${basketItem.getTotalSats()} sats"
                } else {
                    val currencyCode = currencyManager.getCurrentCurrency()
                    val currency = Amount.Currency.fromCode(currencyCode)
                    val totalAmount = Amount.fromMajorUnits(basketItem.getTotalPrice(), currency)
                    totalView.text = totalAmount.toString()
                }

                removeButton.setOnClickListener {
                    basketManager.removeItem(item.id!!)
                    refreshBasket()
                    itemsAdapter.notifyDataSetChanged()
                }
            }
        }
    }
}
