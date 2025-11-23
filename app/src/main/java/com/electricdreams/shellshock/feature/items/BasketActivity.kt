package com.electricdreams.shellshock.feature.items

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import com.electricdreams.shellshock.core.worker.BitcoinPriceWorker

class BasketActivity : AppCompatActivity() {

    private lateinit var basketManager: BasketManager
    private lateinit var bitcoinPriceWorker: BitcoinPriceWorker

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var basketCountView: TextView
    private lateinit var basketTotalView: TextView
    private lateinit var clearBasketButton: Button
    private lateinit var continueShoppingButton: Button
    private lateinit var checkoutButton: Button

    private lateinit var adapter: BasketAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_basket)

        findViewById<View?>(R.id.back_button)?.setOnClickListener { finish() }

        findViewById<Toolbar?>(R.id.toolbar)?.let { toolbar ->
            setSupportActionBar(toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
        }

        basketManager = BasketManager.getInstance()
        bitcoinPriceWorker = BitcoinPriceWorker.getInstance(this)

        recyclerView = findViewById(R.id.basket_recycler_view)
        emptyView = findViewById(R.id.empty_view)
        basketCountView = findViewById(R.id.basket_count)
        basketTotalView = findViewById(R.id.basket_total)
        clearBasketButton = findViewById(R.id.clear_basket_button)
        continueShoppingButton = findViewById(R.id.continue_shopping_button)
        checkoutButton = findViewById(R.id.checkout_button)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = BasketAdapter(basketManager.getBasketItems().toMutableList())
        recyclerView.adapter = adapter

        updateEmptyViewVisibility()

        clearBasketButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Basket")
                .setMessage("Are you sure you want to clear your basket?")
                .setPositiveButton("Clear") { _, _ ->
                    basketManager.clearBasket()
                    updateBasketSummary()
                    adapter.updateItems(basketManager.getBasketItems())
                    updateEmptyViewVisibility()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        continueShoppingButton.setOnClickListener { finish() }
        checkoutButton.setOnClickListener { proceedToCheckout() }

        updateBasketSummary()
        bitcoinPriceWorker.start()
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
            clearBasketButton.isEnabled = false
            checkoutButton.isEnabled = false
        } else {
            emptyView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            clearBasketButton.isEnabled = true
            checkoutButton.isEnabled = true
        }
    }

    private fun updateBasketSummary() {
        val itemCount = basketManager.getTotalItemCount()
        val totalPrice = basketManager.getTotalPrice()

        basketCountView.text = itemCount.toString()

        val formattedTotal = if (itemCount > 0) {
            val currencySymbol = CurrencyManager.getInstance(this).getCurrentSymbol()
            String.format("%s%.2f", currencySymbol, totalPrice)
        } else {
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
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        basketManager.clearBasket()
        startActivity(intent)
        finish()
    }

    private inner class BasketAdapter(private var basketItems: MutableList<BasketItem>) :
        RecyclerView.Adapter<BasketAdapter.BasketViewHolder>() {

        fun updateItems(newItems: List<BasketItem>) {
            basketItems = newItems.toMutableList()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BasketViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_basket, parent, false)
            return BasketViewHolder(view)
        }

        override fun onBindViewHolder(holder: BasketViewHolder, position: Int) {
            val basketItem = basketItems[position]
            holder.bind(basketItem)
        }

        override fun getItemCount(): Int = basketItems.size

        inner class BasketViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val nameView: TextView = itemView.findViewById(R.id.item_name)
            private val variationView: TextView = itemView.findViewById(R.id.item_variation)
            private val quantityView: TextView = itemView.findViewById(R.id.item_quantity)
            private val priceView: TextView = itemView.findViewById(R.id.item_price)
            private val totalView: TextView = itemView.findViewById(R.id.item_total)
            private val removeButton: ImageButton = itemView.findViewById(R.id.remove_item_button)

            fun bind(basketItem: BasketItem) {
                val item: Item = basketItem.item
                val quantity: Int = basketItem.quantity

                nameView.text = item.name

                if (!item.variationName.isNullOrEmpty()) {
                    variationView.visibility = View.VISIBLE
                    variationView.text = item.variationName
                } else {
                    variationView.visibility = View.GONE
                }

                quantityView.text = "Qty: $quantity"

                val currencySymbol =
                    CurrencyManager.getInstance(itemView.context).getCurrentSymbol()
                priceView.text = String.format("%s%.2f", currencySymbol, item.price)

                val total = item.price * quantity
                totalView.text = String.format("%s%.2f", currencySymbol, total)

                removeButton.setOnClickListener {
                    basketManager.removeItem(item.id!!)
                    val position = adapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        basketItems.removeAt(position)
                        notifyItemRemoved(position)
                        updateBasketSummary()
                        updateEmptyViewVisibility()
                    }
                }
            }
        }
    }
}
