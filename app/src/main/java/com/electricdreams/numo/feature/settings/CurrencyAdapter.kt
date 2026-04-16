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

sealed class CurrencyItem {
    abstract val currencyCode: String
    
    data class Standard(val currency: Currency) : CurrencyItem() {
        override val currencyCode: String = currency.currencyCode
    }
    
    data class Custom(
        override val currencyCode: String,
        val displayName: String,
        val symbol: String
    ) : CurrencyItem() {
        companion object {
            private val _currencyMap = mapOf(
                "AED" to Custom("AED", "UAE Dirham", "د.إ"),
                "ALL" to Custom("ALL", "Albanian Lek", "L"),
                "ANG" to Custom("ANG", "Netherlands Antillean Guilder", "ƒ"),
                "AOA" to Custom("AOA", "Angolan Kwanza", "Kz"),
                "ARS" to Custom("ARS", "Argentine Peso", "$"),
                "AUD" to Custom("AUD", "Australian Dollar", "A$"),
                "AWG" to Custom("AWG", "Aruban Florin", "ƒ"),
                "AZN" to Custom("AZN", "Azerbaijani Manat", "₼"),
                "BAM" to Custom("BAM", "Bosnia-Herzegovina Convertible Mark", "KM"),
                "BBD" to Custom("BBD", "Barbadian Dollar", "Bds$"),
                "BDT" to Custom("BDT", "Bangladeshi Taka", "৳"),
                "BGN" to Custom("BGN", "Bulgarian Lev", "лв"),
                "BHD" to Custom("BHD", "Bahraini Dinar", ".د.ب"),
                "BIF" to Custom("BIF", "Burundian Franc", "₣"),
                "BMD" to Custom("BMD", "Bermudan Dollar", "$"),
                "BOB" to Custom("BOB", "Bolivian Boliviano", "Bs"),
                "BRL" to Custom("BRL", "Brazilian Real", "R$"),
                "BSD" to Custom("BSD", "Bahamian Dollar", "B$"),
                "BTC" to Custom("BTC", "Bitcoin", "₿"),
                "BTN" to Custom("BTN", "Bhutanese Ngultrum", "Nu."),
                "BWP" to Custom("BWP", "Botswanan Pula", "P"),
                "BYN" to Custom("BYN", "Belarusian Ruble", "Br"),
                "BZD" to Custom("BZD", "Belize Dollar", "BZ$"),
                "CAD" to Custom("CAD", "Canadian Dollar", "C$"),
                "CDF" to Custom("CDF", "Congolese Franc", "₣"),
                "CHF" to Custom("CHF", "Swiss Franc", "CHF"),
                "CLP" to Custom("CLP", "Chilean Peso", "$"),
                "CNY" to Custom("CNY", "Chinese Yuan", "¥"),
                "COP" to Custom("COP", "Colombian Peso", "$"),
                "CRC" to Custom("CRC", "Costa Rican Colón", "₡"),
                "CUP" to Custom("CUP", "Cuban Peso", "₱"),
                "CVE" to Custom("CVE", "Cape Verdean Escudo", "Esc"),
                "CZK" to Custom("CZK", "Czech Republic Koruna", "Kč"),
                "DJF" to Custom("DJF", "Djiboutian Franc", "₣"),
                "DKK" to Custom("DKK", "Danish Krone", "kr."),
                "DOP" to Custom("DOP", "Dominican Peso", "RD$"),
                "DZD" to Custom("DZD", "Algerian Dinar", "د.ج"),
                "EGP" to Custom("EGP", "Egyptian Pound", "£"),
                "ERN" to Custom("ERN", "Eritrean Nakfa", "Nfk"),
                "ETB" to Custom("ETB", "Ethiopian Birr", "Br"),
                "EUR" to Custom("EUR", "Euro", "€"),
                "FKP" to Custom("FKP", "Falkland Islands Pound", "£"),
                "GBP" to Custom("GBP", "British Pound Sterling", "£"),
                "GEL" to Custom("GEL", "Georgian Lari", "₾"),
                "GHS" to Custom("GHS", "Ghanaian Cedi", "₵"),
                "GIP" to Custom("GIP", "Gibraltar Pound", "£"),
                "GMD" to Custom("GMD", "Gambian Dalasi", "D"),
                "GNF" to Custom("GNF", "Guinean Franc", "₣"),
                "GTQ" to Custom("GTQ", "Guatemalan Quetzal", "Q"),
                "HKD" to Custom("HKD", "Hong Kong Dollar", "HK$"),
                "HNL" to Custom("HNL", "Honduran Lempira", "L"),
                "HUF" to Custom("HUF", "Hungarian Forint", "Ft"),
                "IDR" to Custom("IDR", "Indonesian Rupiah", "Rp"),
                "ILS" to Custom("ILS", "Israeli New Sheqel", "₪"),
                "INR" to Custom("INR", "Indian Rupee", "₹"),
                "IRR" to Custom("IRR", "Iranian Rial", "﷼"),
                "IRT" to Custom("IRT", "Iranian Toman", "تومان"),
                "ISK" to Custom("ISK", "Icelandic Króna", "kr"),
                "JEP" to Custom("JEP", "Jersey Pound", "£"),
                "JMD" to Custom("JMD", "Jamaican Dollar", "J$"),
                "JOD" to Custom("JOD", "Jordanian Dinar", "د.ا"),
                "JPY" to Custom("JPY", "Japanese Yen", "¥"),
                "KES" to Custom("KES", "Kenyan Shilling", "KSh"),
                "KGS" to Custom("KGS", "Kyrgystani Som", "лв"),
                "KMF" to Custom("KMF", "Comorian Franc", "₣"),
                "KRW" to Custom("KRW", "South Korean Won", "₩"),
                "KYD" to Custom("KYD", "Cayman Islands Dollar", "CI$"),
                "KZT" to Custom("KZT", "Kazakhstani Tenge", "₸"),
                "LBP" to Custom("LBP", "Lebanese Pound", "ل.ل"),
                "LKR" to Custom("LKR", "Sri Lankan Rupee", "₨"),
                "LSL" to Custom("LSL", "Lesotho Loti", "L"),
                "MAD" to Custom("MAD", "Moroccan Dirham", "DH"),
                "MGA" to Custom("MGA", "Malagasy Ariary", "Ar"),
                "MLC" to Custom("MLC", "Cuban MLC", "MLC"),
                "MOP" to Custom("MOP", "Macanese Pataca", "MOP$"),
                "MRU" to Custom("MRU", "Mauritanian Ouguiya", "UM"),
                "MWK" to Custom("MWK", "Malawian Kwacha", "MK"),
                "MXN" to Custom("MXN", "Mexican Peso", "$"),
                "MYR" to Custom("MYR", "Malaysian Ringgit", "RM"),
                "NAD" to Custom("NAD", "Namibian Dollar", "N$"),
                "NGN" to Custom("NGN", "Nigerian Naira", "₦"),
                "NIO" to Custom("NIO", "Nicaraguan Córdoba", "C$"),
                "NOK" to Custom("NOK", "Norwegian Krone", "kr"),
                "NPR" to Custom("NPR", "Nepalese Rupee", "₨"),
                "NZD" to Custom("NZD", "New Zealand Dollar", "NZ$"),
                "OMR" to Custom("OMR", "Omani Rial", "﷼"),
                "PAB" to Custom("PAB", "Panamanian Balboa", "B/."),
                "PEN" to Custom("PEN", "Peruvian Nuevo Sol", "S/"),
                "PHP" to Custom("PHP", "Philippine Peso", "₱"),
                "PKR" to Custom("PKR", "Pakistani Rupee", "₨"),
                "PLN" to Custom("PLN", "Polish Zloty", "zł"),
                "PYG" to Custom("PYG", "Paraguayan Guarani", "Gs"),
                "QAR" to Custom("QAR", "Qatari Riyal", "﷼"),
                "RON" to Custom("RON", "Romanian Leu", "lei"),
                "RSD" to Custom("RSD", "Serbian Dinar", "Дин."),
                "RUB" to Custom("RUB", "Russian Ruble", "₽"),
                "RWF" to Custom("RWF", "Rwandan Franc", "R₣"),
                "SAR" to Custom("SAR", "Saudi Riyal", "﷼"),
                "SEK" to Custom("SEK", "Swedish Krona", "kr"),
                "SGD" to Custom("SGD", "Singapore Dollar", "S$"),
                "SHP" to Custom("SHP", "Saint Helena Pound", "£"),
                "SYP" to Custom("SYP", "Syrian Pound", "£"),
                "SZL" to Custom("SZL", "Swazi Lilangeni", "E"),
                "THB" to Custom("THB", "Thai Baht", "฿"),
                "TMT" to Custom("TMT", "Turkmenistani Manat", "T"),
                "TND" to Custom("TND", "Tunisian Dinar", "د.ت"),
                "TRY" to Custom("TRY", "Turkish Lira", "₺"),
                "TTD" to Custom("TTD", "Trinidad and Tobago Dollar", "TT$"),
                "TWD" to Custom("TWD", "New Taiwan Dollar", "NT$"),
                "TZS" to Custom("TZS", "Tanzanian Shilling", "TSh"),
                "UAH" to Custom("UAH", "Ukrainian Hryvnia", "₴"),
                "UGX" to Custom("UGX", "Ugandan Shilling", "USh"),
                "USD" to Custom("USD", "United States Dollar", "$"),
                "UYU" to Custom("UYU", "Uruguayan Peso", "\$U"),
                "UZS" to Custom("UZS", "Uzbekistan Som", "лв"),
                "VES" to Custom("VES", "Venezuelan Bolívar Soberano", "Bs."),
                "VND" to Custom("VND", "Vietnamese Dong", "₫"),
                "XAF" to Custom("XAF", "CFA Franc BEAC", "₣"),
                "XAG" to Custom("XAG", "Silver Ounce", "oz"),
                "XAU" to Custom("XAU", "Gold Ounce", "oz"),
                "XCD" to Custom("XCD", "East Caribbean Dollar", "EC$"),
                "XOF" to Custom("XOF", "CFA Franc BCEAO", "₣"),
                "XPT" to Custom("XPT", "Platinum Ounce", "oz"),
                "ZAR" to Custom("ZAR", "South African Rand", "R"),
                "ZMW" to Custom("ZMW", "Zambian Kwacha", "ZK")
            )
            
            fun getInfo(code: String): Custom? = _currencyMap[code.uppercase()]
            
            fun getAllCurrencies(): Collection<Custom> = _currencyMap.values
            
            fun isSupported(code: String): Boolean = _currencyMap.containsKey(code.uppercase())
        }
    }
}

class CurrencyAdapter(
    private val onCurrencySelected: (CurrencyItem) -> Unit
) : RecyclerView.Adapter<CurrencyAdapter.CurrencyViewHolder>() {

    private var currencies: List<CurrencyItem> = emptyList()
    private var selectedCurrencyCode: String = ""

    @SuppressLint("NotifyDataSetDataSetChanged")
    fun submitList(newCurrencies: List<CurrencyItem>, selectedCode: String) {
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

        fun bind(item: CurrencyItem) {
            val displayName = when (item) {
                is CurrencyItem.Standard -> item.currency.getDisplayName(Locale.getDefault())
                is CurrencyItem.Custom -> item.displayName
            }
            nameText.text = "$displayName (${item.currencyCode})"
            
            val isSelected = item.currencyCode == selectedCurrencyCode
            checkIcon.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            checkIcon.setImageResource(R.drawable.ic_check)

            itemView.setOnClickListener {
                onCurrencySelected(item)
            }
        }
    }
}
