package com.electricdreams.numo.feature.baskets

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.SavedBasket
import com.electricdreams.numo.core.util.CurrencyManager
import com.electricdreams.numo.core.util.SavedBasketManager
import com.electricdreams.numo.feature.history.PaymentsHistoryActivity
import com.electricdreams.numo.feature.history.TransactionDetailActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Activity for viewing archived (paid) baskets.
 * Shows a beautiful list of completed orders with expandable details
 * and links to payment information.
 */
class BasketArchiveActivity : AppCompatActivity() {

    private lateinit var savedBasketManager: SavedBasketManager
    private lateinit var currencyManager: CurrencyManager

    private lateinit var archiveRecyclerView: RecyclerView
    private lateinit var emptyView: LinearLayout
    private lateinit var adapter: BasketArchiveAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_basket_archive)

        initializeManagers()
        initializeViews()
        setupRecyclerView()
        loadArchivedBaskets()
    }

    override fun onResume() {
        super.onResume()
        loadArchivedBaskets()
    }

    private fun initializeManagers() {
        savedBasketManager = SavedBasketManager.getInstance(this)
        currencyManager = CurrencyManager.getInstance(this)
    }

    private fun initializeViews() {
        findViewById<View>(R.id.back_button).setOnClickListener { finish() }

        archiveRecyclerView = findViewById(R.id.archive_recycler_view)
        emptyView = findViewById(R.id.empty_view)
    }

    private fun setupRecyclerView() {
        adapter = BasketArchiveAdapter(
            currencyManager = currencyManager,
            onBasketClick = { basket -> showBasketDetails(basket) },
            onPaymentClick = { basket -> openPaymentDetails(basket) },
            onDeleteClick = { basket -> showDeleteConfirmation(basket) }
        )

        archiveRecyclerView.layoutManager = LinearLayoutManager(this)
        archiveRecyclerView.adapter = adapter
    }

    private fun loadArchivedBaskets() {
        val baskets = savedBasketManager.getArchivedBaskets()
        adapter.updateBaskets(baskets)

        if (baskets.isEmpty()) {
            archiveRecyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            archiveRecyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
        }
    }

    private fun showBasketDetails(basket: SavedBasket) {
        // Show expandable details in a bottom sheet or dialog
        val dialog = BasketDetailDialog(this, basket, currencyManager) {
            // On payment click
            openPaymentDetails(basket)
        }
        dialog.show()
    }

    private fun openPaymentDetails(basket: SavedBasket) {
        val paymentId = basket.paymentId ?: return

        // Find payment in history
        val payments = PaymentsHistoryActivity.getPaymentHistory(this)
        val payment = payments.find { it.id == paymentId }

        if (payment != null) {
            val intent = Intent(this, TransactionDetailActivity::class.java).apply {
                putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_TOKEN, payment.token)
                putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_AMOUNT, payment.amount)
                putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_DATE, payment.date.time)
                putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_UNIT, payment.getUnit())
                putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_ENTRY_UNIT, payment.getEntryUnit())
                putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_ENTERED_AMOUNT, payment.enteredAmount)
                payment.bitcoinPrice?.let { 
                    putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_BITCOIN_PRICE, it) 
                }
                putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_MINT_URL, payment.mintUrl)
                putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_PAYMENT_TYPE, payment.paymentType)
                putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_LIGHTNING_INVOICE, payment.lightningInvoice)
                putExtra(TransactionDetailActivity.EXTRA_CHECKOUT_BASKET_JSON, payment.checkoutBasketJson)
                putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_TIP_AMOUNT, payment.tipAmountSats)
                putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_TIP_PERCENTAGE, payment.tipPercentage)
            }
            startActivity(intent)
        }
    }

    private fun showDeleteConfirmation(basket: SavedBasket) {
        val index = savedBasketManager.getArchivedBasketIndex(basket.id)
        val displayName = basket.getDisplayName(index)

        AlertDialog.Builder(this)
            .setTitle(R.string.basket_archive_delete_title)
            .setMessage(getString(R.string.basket_archive_delete_message, displayName))
            .setPositiveButton(R.string.common_delete) { _, _ ->
                savedBasketManager.deleteArchivedBasket(basket.id)
                loadArchivedBaskets()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }
}

/**
 * Google-style dialog to show basket details with clean layout.
 * Features order summary, items in rounded card, and action button.
 */
class BasketDetailDialog(
    context: android.content.Context,
    private val basket: SavedBasket,
    private val currencyManager: CurrencyManager,
    private val onPaymentClick: () -> Unit
) : AlertDialog(context, R.style.Theme_Numo_Dialog) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_basket_detail)

        // Make dialog centered with rounded corners
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        setupHeader()
        setupItemsList()
        setupActions()
    }

    private fun setupHeader() {
        // Get index for display name
        val savedBasketManager = SavedBasketManager.getInstance(context)
        val index = savedBasketManager.getArchivedBasketIndex(basket.id)

        // Order name
        findViewById<TextView>(R.id.basket_name)?.text = basket.getDisplayName(index)
        
        // Date
        val dateFormat = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
        findViewById<TextView>(R.id.basket_date)?.text = 
            dateFormat.format(Date(basket.paidAt ?: basket.updatedAt))

        // Total amount (large, hero style)
        val totalText = formatTotal()
        findViewById<TextView>(R.id.basket_total)?.text = totalText
    }

    private fun setupItemsList() {
        // Items header with count
        val itemCount = basket.items.sumOf { it.quantity }
        val headerText = if (itemCount == 1) "1 ITEM" else "$itemCount ITEMS"
        findViewById<TextView>(R.id.items_header)?.text = headerText

        // Items list
        val itemsContainer = findViewById<LinearLayout>(R.id.items_container)
        val inflater = LayoutInflater.from(context)
        
        basket.items.forEach { item ->
            val itemView = inflater.inflate(R.layout.item_basket_detail_line, itemsContainer, false)
            
            // Quantity badge
            itemView.findViewById<TextView>(R.id.item_quantity)?.text = item.quantity.toString()
            
            // Item name
            itemView.findViewById<TextView>(R.id.item_name)?.text = item.item.name ?: "Item"
            
            // Subtitle - show unit price if quantity > 1
            val subtitleView = itemView.findViewById<TextView>(R.id.item_subtitle)
            if (item.quantity > 1) {
                val unitPrice = if (item.isSatsPrice()) {
                    "₿${item.item.priceSats} each"
                } else {
                    "${currencyManager.formatCurrencyAmount(item.item.getGrossPrice())} each"
                }
                subtitleView?.text = unitPrice
                subtitleView?.visibility = View.VISIBLE
            } else {
                subtitleView?.visibility = View.GONE
            }
            
            // Line total
            val priceText = if (item.isSatsPrice()) {
                "₿${item.getTotalSats()}"
            } else {
                currencyManager.formatCurrencyAmount(item.getTotalPrice())
            }
            itemView.findViewById<TextView>(R.id.item_price)?.text = priceText
            
            itemsContainer?.addView(itemView)
        }
    }

    private fun setupActions() {
        // View Payment button
        findViewById<View>(R.id.view_payment_button)?.setOnClickListener {
            dismiss()
            onPaymentClick()
        }

        // Close button (now a TextView)
        findViewById<View>(R.id.close_button)?.setOnClickListener {
            dismiss()
        }
    }

    private fun formatTotal(): String {
        return if (basket.hasMixedPriceTypes()) {
            val fiat = currencyManager.formatCurrencyAmount(basket.getTotalFiatPrice())
            val sats = "₿${basket.getTotalSatsPrice()}"
            "$fiat + $sats"
        } else if (basket.getTotalSatsPrice() > 0) {
            "₿${basket.getTotalSatsPrice()}"
        } else {
            currencyManager.formatCurrencyAmount(basket.getTotalFiatPrice())
        }
    }
}
