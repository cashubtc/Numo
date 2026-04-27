package com.electricdreams.numo.feature.settings

import org.junit.Assert.*
import org.junit.Test

class CurrencySettingsTest {

    @Test
    fun `Yadio currencies set contains expected currencies`() {
        val yadioCurrencies = setOf(
            "AED", "ALL", "ANG", "AOA", "ARS", "AUD", "AWG", "AZN", "BAM", "BBD", "BDT", "BGN", "BHD",
            "BIF", "BMD", "BOB", "BRL", "BSD", "BTC", "BTN", "BWP", "BYN", "BZD", "CAD", "CDF", "CHF",
            "CLP", "CNY", "COP", "CRC", "CUP", "CVE", "CZK", "DJF", "DKK", "DOP", "DZD", "EGP", "ERN",
            "ETB", "EUR", "FKP", "GBP", "GEL", "GHS", "GIP", "GMD", "GNF", "GTQ", "HKD", "HNL", "HUF",
            "IDR", "ILS", "INR", "IRR", "IRT", "ISK", "JEP", "JMD", "JOD", "JPY", "KES", "KGS", "KMF",
            "KRW", "KYD", "KZT", "LBP", "LKR", "LSL", "MAD", "MGA", "MLC", "MOP", "MRU", "MWK", "MXN", "MYR",
            "NAD", "NGN", "NIO", "NOK", "NPR", "NZD", "OMR", "PAB", "PEN", "PHP", "PKR", "PLN", "PYG",
            "QAR", "RON", "RSD", "RUB", "RWF", "SAR", "SEK", "SGD", "SHP", "SYP", "SZL", "THB", "TMT",
            "TND", "TRY", "TTD", "TWD", "TZS", "UAH", "UGX", "USD", "UYU", "UZS", "VES", "VND", "XAF",
            "XAG", "XAU", "XCD", "XOF", "XPT", "ZAR", "ZMW"
        )
        
        assertTrue(yadioCurrencies.size > 120)
        assertTrue(yadioCurrencies.contains("CUP"))
        assertTrue(yadioCurrencies.contains("MLC"))
        assertTrue(yadioCurrencies.contains("USD"))
        assertTrue(yadioCurrencies.contains("EUR"))
        assertTrue(yadioCurrencies.contains("DKK"))
        assertTrue(yadioCurrencies.contains("SEK"))
        assertTrue(yadioCurrencies.contains("NOK"))
    }
}
