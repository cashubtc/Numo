package com.electricdreams.numo.feature.settings

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R
import java.util.Currency
import java.util.Locale

/**
 * Simple wrapper class to handle both valid Java Currencies and custom/non-ISO ones.
 * For example, MLC (Cuban Freely Convertible Currency) is not a standard ISO 4217 code 
 * and will crash standard java.util.Currency initializers.
 */
class CurrencyWrapper(val code: String, val javaCurrency: Currency?) {
    val displayName: String
        get() = javaCurrency?.getDisplayName(Locale.getDefault()) ?: code
}

class CurrencyAdapter(
    private val onCurrencySelected: (CurrencyWrapper) -> Unit
) : RecyclerView.Adapter<CurrencyAdapter.CurrencyViewHolder>() {

    private var currencies: List<CurrencyWrapper> = emptyList()
    private var selectedCurrencyCode: String = ""

    @SuppressLint("NotifyDataSetDataSetChanged")
    fun submitList(newCurrencies: List<CurrencyWrapper>, selectedCode: String) {
        currencies = newCurrencies
        selectedCurrencyCode = selectedCode
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CurrencyViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_currency, parent, false)
        return CurrencyViewHolder(view)
    }

    override fun onBindViewHolder(holder: CurrencyViewHolder, position: Int) {
        holder.bind(currencies[position])
    }

    override fun getItemCount(): Int = currencies.size

    inner class CurrencyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.currency_name_text)
        private val checkIcon: ImageView = itemView.findViewById(R.id.currency_check_icon)

        fun bind(currency: CurrencyWrapper) {
            val displayName = currency.displayName
            nameText.text = "${displayName} (${currency.code})"
            
            val isSelected = currency.code == selectedCurrencyCode
            checkIcon.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            checkIcon.setImageResource(R.drawable.ic_check)

            itemView.setOnClickListener {
                onCurrencySelected(currency)
            }
        }
    }
}
