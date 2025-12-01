package com.electricdreams.numo.payment

import android.content.Intent
import android.nfc.Tag
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.numo.R
import com.electricdreams.numo.ndef.NdefHostCardEmulationService
import com.electricdreams.numo.SatocashNfcClient
import com.electricdreams.numo.SatocashWallet

/**
 * Handles NFC payment processing, including PIN dialogs and card communication.
 */
class NfcPaymentProcessor(
    private val activity: AppCompatActivity,
    private val onPaymentSuccess: (String) -> Unit,
    private val onPaymentError: (String) -> Unit
) {

    private var satocashClient: SatocashNfcClient? = null
    private var satocashWallet: SatocashWallet? = null
    private var savedPin: String? = null
    private var waitingForRescan: Boolean = false
    private var rescanDialog: AlertDialog? = null
    private var processingDialog: AlertDialog? = null

    /** Handle NFC tag for payment */
    fun handleNfcPayment(tag: Tag, requestedAmount: Long) {
        if (requestedAmount <= 0) {
            Toast.makeText(
                activity,
                activity.getString(R.string.nfc_payment_error_enter_amount_first),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        
        if (waitingForRescan && savedPin != null) {
            // TODO: Re-implement full PIN-based rescan flow
            Toast.makeText(
                activity,
                activity.getString(R.string.nfc_payment_error_rescan_not_supported),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        
        waitingForRescan = false
        
        Thread {
            try {
                val client = SatocashNfcClient(tag).also { it.connect() }
                satocashClient = client

                satocashWallet = SatocashWallet(client)

                client.selectApplet(SatocashNfcClient.SATOCASH_AID)
                client.initSecureChannel()
                
                try {
                    val wallet = satocashWallet
                    if (wallet == null) {
                        onPaymentError(activity.getString(R.string.nfc_payment_error_generic, "Wallet not initialized"))
                        return@Thread
                    }

                    val token = wallet.getPayment(requestedAmount, "SAT").join()
                    onPaymentSuccess(token)
                    return@Thread
                } catch (e: RuntimeException) {
                    if (e.message?.contains("not enough funds") == true) {
                        onPaymentError(
                            activity.getString(R.string.nfc_payment_error_insufficient_funds)
                        )
                        return@Thread
                    }
                    
                    val cause = e.cause
                    if (cause is SatocashNfcClient.SatocashException) {
                        val statusWord = cause.sw
                        if (statusWord == SW.UNAUTHORIZED) {
                            // TODO: Restore PIN entry + rescan UX
                            onPaymentError(
                                activity.getString(R.string.nfc_payment_error_pin_flow_not_implemented)
                            )
                        } else {
                            onPaymentError(
                                activity.getString(
                                    R.string.nfc_payment_error_card_sw,
                                    statusWord
                                )
                            )
                        }
                    } else {
                        onPaymentError(
                            activity.getString(
                                R.string.nfc_payment_error_generic,
                                e.message ?: ""
                            )
                        )
                    }
                }
            } catch (e: java.io.IOException) {
                onPaymentError(
                    activity.getString(R.string.nfc_payment_error_nfc_comm, e.message ?: "")
                )
            } catch (e: SatocashNfcClient.SatocashException) {
                onPaymentError(
                    activity.getString(
                        R.string.nfc_payment_error_satocash,
                        e.message ?: "",
                        e.sw
                    )
                )
            } catch (e: Exception) {
                onPaymentError(
                    activity.getString(R.string.nfc_payment_error_unexpected, e.message ?: "")
                )
            } finally {
                try {
                    satocashClient?.close()
                    satocashClient = null
                } catch (_: java.io.IOException) {}
            }
        }.start()
    }

    /** Show rescan dialog */
    fun showRescanDialog() {
        val builder = AlertDialog.Builder(activity, R.style.Theme_Numo)
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_nfc_modern_simplified, null)
        builder.setView(dialogView)
        builder.setCancelable(true)
        builder.setOnCancelListener {
            savedPin = null
            waitingForRescan = false
        }
        
        dialogView.findViewById<Button?>(R.id.nfc_cancel_button)?.setOnClickListener {
            rescanDialog?.dismiss()
            savedPin = null
            waitingForRescan = false
        }
        
        rescanDialog = builder.create().apply {
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            show()
        }
    }

    /** Show processing dialog */
    fun showProcessingDialog() {
        val builder = AlertDialog.Builder(activity, R.style.Theme_Numo)
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_nfc_modern_simplified, null)
        builder.setView(dialogView)
        dialogView.findViewById<Button?>(R.id.nfc_cancel_button)?.visibility = android.view.View.GONE
        builder.setCancelable(false)
        
        processingDialog = builder.create().apply {
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            show()
        }
    }

    /** Show PIN entry dialog */
    fun showPinDialog(callback: (String?) -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val builder = AlertDialog.Builder(activity)
            builder.setTitle(R.string.dialog_title_enter_pin)
            
            val layout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                val density = activity.resources.displayMetrics.density
                val padding = (50 * density).toInt()
                val paddingVertical = (20 * density).toInt()
                setPadding(padding, paddingVertical, padding, paddingVertical)
            }
            
            val input = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                hint = activity.getString(R.string.dialog_pin_hint)
            }
            layout.addView(input)
            
            val keypadLayout = createKeypadLayout(input)
            val buttonLayout = createDialogButtons { pin -> callback(pin) }
            
            layout.addView(keypadLayout)
            layout.addView(buttonLayout)
            builder.setView(layout)
            
            val dialog = builder.create()
            
            val cancelButton = buttonLayout.getChildAt(0) as Button
            val okButton = buttonLayout.getChildAt(1) as Button
            
            cancelButton.setOnClickListener { 
                dialog.cancel()
                callback(null) 
            }
            okButton.setOnClickListener { 
                val pin = input.text.toString()
                dialog.dismiss()
                callback(pin) 
            }
            dialog.setOnCancelListener { callback(null) }
            dialog.show()
        }
    }

    /** Create keypad layout for PIN dialog */
    private fun createKeypadLayout(input: EditText): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            
            val buttons = arrayOf(
                arrayOf("1", "2", "3"), 
                arrayOf("4", "5", "6"), 
                arrayOf("7", "8", "9"), 
                arrayOf("", "0", "DEL")
            )
            
            for (row in buttons) {
                val rowLayout = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { weight = 1f }
                }
                
                for (text in row) {
                    val button = Button(activity).apply {
                        setText(text)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { 
                            weight = 1f 
                        }
                        setOnClickListener {
                            when (text) {
                                "DEL" -> if (input.length() > 0) {
                                    input.text.delete(input.length() - 1, input.length())
                                }
                                "" -> {}
                                else -> input.append(text)
                            }
                        }
                    }
                    rowLayout.addView(button)
                }
                addView(rowLayout)
            }
        }
    }

    /** Create dialog buttons layout */
    private fun createDialogButtons(onOk: (String) -> Unit): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { 
                topMargin = (20 * activity.resources.displayMetrics.density).toInt() 
            }
            
            val cancelButton = Button(activity).apply {
                text = activity.getString(R.string.common_cancel)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { 
                    rightMargin = (8 * activity.resources.displayMetrics.density).toInt() 
                }
            }

            val okButton = Button(activity).apply {
                text = activity.getString(R.string.common_ok)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { 
                    leftMargin = (8 * activity.resources.displayMetrics.density).toInt() 
                }
            }
            
            addView(cancelButton)
            addView(okButton)
        }
    }

    /** Reset HCE service */
    fun resetHceService() {
        try {
            val service = NdefHostCardEmulationService.getInstance()
            if (service != null) {
                service.clearPaymentRequest()
                service.setPaymentCallback(null)
            }
        } catch (_: Exception) {}
    }

    /** Stop HCE service */
    fun stopHceService() {
        try {
            resetHceService()
            val service = NdefHostCardEmulationService.getInstance()
            if (service != null) {
                activity.stopService(Intent(activity, NdefHostCardEmulationService::class.java))
            }
        } catch (_: Exception) {}
    }

    /** Dismiss dialogs */
    fun dismissDialogs() {
        rescanDialog?.dismiss()
        processingDialog?.dismiss()
    }

    private object SW {
        const val UNAUTHORIZED = 0x9C06
    }
}
