package com.electricdreams.numo.payment

import android.content.Context
import androidx.core.content.edit
import com.electricdreams.numo.payment.PaymentTabManager.PaymentTab

class DefaultPaymentMethodManager private constructor(context: Context) {
    
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getDefaultPaymentMethod(): PaymentTab {
        val saved = prefs.getString(KEY_DEFAULT_METHOD, PaymentTab.UNIFIED.name)
        return try {
            PaymentTab.valueOf(saved ?: PaymentTab.UNIFIED.name)
        } catch (e: IllegalArgumentException) {
            PaymentTab.UNIFIED
        }
    }

    fun setDefaultPaymentMethod(method: PaymentTab) {
        prefs.edit {
            putString(KEY_DEFAULT_METHOD, method.name)
        }
    }

    companion object {
        private const val PREFS_NAME = "default_payment_method_prefs"
        private const val KEY_DEFAULT_METHOD = "default_method"
        
        @Volatile
        private var instance: DefaultPaymentMethodManager? = null

        fun getInstance(context: Context): DefaultPaymentMethodManager {
            return instance ?: synchronized(this) {
                instance ?: DefaultPaymentMethodManager(context).also { instance = it }
            }
        }
    }
}