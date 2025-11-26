package com.electricdreams.shellshock.feature.items

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.electricdreams.shellshock.R
import com.electricdreams.shellshock.core.model.Item
import com.electricdreams.shellshock.core.model.PriceType
import com.electricdreams.shellshock.core.util.CurrencyManager
import com.electricdreams.shellshock.core.util.ItemManager
import com.electricdreams.shellshock.feature.items.handlers.*

/**
 * Activity for adding or editing catalog items.
 * Delegates to specialized handlers for different concerns:
 * - CategoryTagHandler: category tag management
 * - PricingHandler: price type and VAT calculations
 * - InventoryHandler: inventory tracking
 * - ImageHandler: photo capture/selection
 * - SkuHandler: SKU validation
 * - GtinHandler: GTIN validation and barcode scanning
 * - ItemFormValidator: form validation
 * - ItemBuilder: item object construction
 */
class ItemEntryActivity : AppCompatActivity() {

    // UI Elements - Basic Info
    private lateinit var nameInput: EditText
    private lateinit var variationInput: EditText
    private lateinit var categoryInput: EditText
    private lateinit var descriptionInput: EditText

    // Managers
    private lateinit var itemManager: ItemManager
    private lateinit var currencyManager: CurrencyManager

    // Handlers
    private lateinit var categoryTagHandler: CategoryTagHandler
    private lateinit var pricingHandler: PricingHandler
    private lateinit var inventoryHandler: InventoryHandler
    private lateinit var imageHandler: ImageHandler
    private lateinit var skuHandler: SkuHandler
    private lateinit var gtinHandler: GtinHandler
    private lateinit var formValidator: ItemFormValidator
    private val itemBuilder = ItemBuilder()

    // State
    private var editItemId: String? = null
    private var isEditMode: Boolean = false
    private var currentItem: Item? = null

    // Activity Result Launchers
    private val selectGalleryLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            imageHandler.handleGalleryResult(uri)
        }

    private val takePictureLauncher: ActivityResultLauncher<Uri> =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            imageHandler.handleCameraResult(success)
        }

    private val barcodeScanLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val barcodeValue = result.data?.getStringExtra(BarcodeScannerActivity.EXTRA_BARCODE_VALUE)
                gtinHandler.handleBarcodeScanResult(barcodeValue)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_item_entry)

        initializeManagers()
        initializeViews()
        initializeHandlers()
        setupClickListeners()

        editItemId = intent.getStringExtra(EXTRA_ITEM_ID)
        isEditMode = !editItemId.isNullOrEmpty()

        if (isEditMode) {
            setupEditMode()
            loadItemData()
        }
    }

    private fun initializeManagers() {
        itemManager = ItemManager.getInstance(this)
        currencyManager = CurrencyManager.getInstance(this)
    }

    private fun initializeViews() {
        nameInput = findViewById(R.id.item_name_input)
        variationInput = findViewById(R.id.item_variation_input)
        categoryInput = findViewById(R.id.item_category_input)
        descriptionInput = findViewById(R.id.item_description_input)
    }

    private fun initializeHandlers() {
        initializeCategoryHandler()
        initializePricingHandler()
        initializeInventoryHandler()
        initializeImageHandler()
        initializeGtinHandler()
        initializeSkuHandler()
        initializeFormValidator()
    }

    private fun initializeCategoryHandler() {
        categoryTagHandler = CategoryTagHandler(
            context = this,
            categoryTagsContainer = findViewById(R.id.category_tags_container),
            newCategoryContainer = findViewById(R.id.new_category_container),
            newCategoryInput = findViewById(R.id.new_category_input),
            btnConfirmCategory = findViewById(R.id.btn_confirm_category),
            btnCancelCategory = findViewById(R.id.btn_cancel_category),
            categoryInput = categoryInput,
            itemManager = itemManager
        )
        categoryTagHandler.initialize()
    }

    private fun initializePricingHandler() {
        pricingHandler = PricingHandler(
            priceTypeToggle = findViewById(R.id.price_type_toggle),
            btnPriceFiat = findViewById(R.id.btn_price_fiat),
            btnPriceBitcoin = findViewById(R.id.btn_price_bitcoin),
            fiatPriceContainer = findViewById(R.id.fiat_price_container),
            satsPriceContainer = findViewById(R.id.sats_price_container),
            priceInput = findViewById(R.id.item_price_input),
            satsInput = findViewById(R.id.item_sats_input),
            currencySymbol = findViewById(R.id.currency_symbol),
            currencyCode = findViewById(R.id.currency_code),
            vatSectionCard = findViewById(R.id.vat_section_card),
            switchVatEnabled = findViewById(R.id.switch_vat_enabled),
            vatFieldsContainer = findViewById(R.id.vat_fields_container),
            switchPriceIncludesVat = findViewById(R.id.switch_price_includes_vat),
            vatRateInput = findViewById(R.id.vat_rate_input),
            priceBreakdownContainer = findViewById(R.id.price_breakdown_container),
            textNetPrice = findViewById(R.id.text_net_price),
            textVatLabel = findViewById(R.id.text_vat_label),
            textVatAmount = findViewById(R.id.text_vat_amount),
            textGrossPrice = findViewById(R.id.text_gross_price),
            currencyManager = currencyManager
        )
        pricingHandler.initialize()
    }

    private fun initializeInventoryHandler() {
        inventoryHandler = InventoryHandler(
            switchTrackInventory = findViewById(R.id.switch_track_inventory),
            inventoryFieldsContainer = findViewById(R.id.inventory_fields_container),
            quantityInput = findViewById(R.id.item_quantity_input),
            alertCheckbox = findViewById(R.id.item_alert_checkbox),
            alertThresholdContainer = findViewById(R.id.alert_threshold_container),
            alertThresholdInput = findViewById(R.id.item_alert_threshold_input)
        )
        inventoryHandler.initialize()
    }

    private fun initializeImageHandler() {
        imageHandler = ImageHandler(
            activity = this,
            itemImageView = findViewById(R.id.item_image_view),
            imagePlaceholder = findViewById(R.id.item_image_placeholder),
            addImageButton = findViewById(R.id.item_add_image_button),
            removeImageButton = findViewById(R.id.item_remove_image_button),
            itemManager = itemManager,
            selectGalleryLauncher = selectGalleryLauncher,
            takePictureLauncher = takePictureLauncher
        )
        imageHandler.initialize()
    }

    private fun initializeGtinHandler() {
        gtinHandler = GtinHandler(
            activity = this,
            gtinInput = findViewById(R.id.item_gtin_input),
            gtinContainer = findViewById(R.id.gtin_container),
            gtinErrorText = findViewById(R.id.gtin_error_text),
            scanBarcodeButton = findViewById(R.id.btn_scan_barcode),
            itemManager = itemManager,
            barcodeScanLauncher = barcodeScanLauncher
        )
        gtinHandler.setEditItemId(editItemId)
        gtinHandler.initialize()
    }

    private fun initializeSkuHandler() {
        skuHandler = SkuHandler(
            skuInput = findViewById(R.id.item_sku_input),
            skuContainer = findViewById(R.id.sku_container),
            skuErrorText = findViewById(R.id.sku_error_text),
            itemManager = itemManager
        )
        skuHandler.setEditItemId(editItemId)
        skuHandler.initialize()
    }

    private fun initializeFormValidator() {
        formValidator = ItemFormValidator(
            activity = this,
            nameInput = nameInput,
            pricingHandler = pricingHandler,
            inventoryHandler = inventoryHandler,
            skuHandler = skuHandler,
            gtinHandler = gtinHandler
        )
    }

    private fun setupClickListeners() {
        findViewById<View>(R.id.back_button)?.setOnClickListener { finish() }
        findViewById<Button>(R.id.item_save_button).setOnClickListener { saveItem() }
        findViewById<Button>(R.id.item_cancel_button).setOnClickListener {
            if (isEditMode) showDeleteConfirmationDialog() else finish()
        }
    }

    private fun setupEditMode() {
        findViewById<TextView>(R.id.toolbar_title)?.text = "Edit Item"
        findViewById<Button>(R.id.item_cancel_button).apply {
            text = "Delete Item"
            setTextColor(ContextCompat.getColor(this@ItemEntryActivity, R.color.color_warning_red))
        }
    }

    private fun loadItemData() {
        val item = itemManager.getAllItems().find { it.id == editItemId } ?: return
        currentItem = item
        imageHandler.setCurrentItem(item)

        // Basic info
        nameInput.setText(item.name)
        variationInput.setText(item.variationName)
        descriptionInput.setText(item.description)

        // Delegated loading
        categoryTagHandler.setSelectedCategory(item.category)
        skuHandler.setSku(item.sku)
        gtinHandler.setGtin(item.gtin)
        loadPricingData(item)
        loadInventoryData(item)
        imageHandler.loadItemImage(item)
    }

    private fun loadPricingData(item: Item) {
        pricingHandler.setCurrentPriceType(item.priceType)
        when (item.priceType) {
            PriceType.FIAT -> {
                val displayPrice = if (item.vatEnabled) item.getGrossPrice() else item.price
                pricingHandler.setFiatPrice(displayPrice)
            }
            PriceType.SATS -> pricingHandler.setSatsPrice(item.priceSats)
        }
        pricingHandler.setVatFields(item.vatEnabled, item.vatRate, true)
    }

    private fun loadInventoryData(item: Item) {
        inventoryHandler.setTrackingEnabled(item.trackInventory)
        inventoryHandler.setQuantity(item.quantity)
        inventoryHandler.setAlertSettings(item.alertEnabled, item.alertThreshold)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == ImageHandler.REQUEST_IMAGE_CAPTURE) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            imageHandler.handlePermissionResult(granted)
        }
    }

    private fun showDeleteConfirmationDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_delete_confirmation, null)
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        dialogView.findViewById<Button>(R.id.dialog_cancel_button).setOnClickListener { 
            dialog.dismiss() 
        }
        dialogView.findViewById<Button>(R.id.dialog_confirm_button).setOnClickListener {
            currentItem?.let { item ->
                itemManager.removeItem(item.id!!)
                setResult(RESULT_OK)
                dialog.dismiss()
                finish()
            }
        }
        dialog.show()
    }

    private fun saveItem() {
        val validationResult = formValidator.validate()
        if (!validationResult.isValid) return

        val item = itemBuilder.build(
            validationResult = validationResult,
            isEditMode = isEditMode,
            editItemId = editItemId,
            currentItem = currentItem,
            variationName = variationInput.text.toString().trim(),
            category = categoryTagHandler.getSelectedCategory(),
            description = descriptionInput.text.toString().trim(),
            sku = skuHandler.getSku(),
            gtin = gtinHandler.getGtin(),
            priceType = pricingHandler.getCurrentPriceType(),
            currency = currencyManager.getCurrentCurrency(),
            vatEnabled = pricingHandler.isVatEnabled(),
            vatRate = pricingHandler.getVatRate(),
            trackInventory = inventoryHandler.isTrackingEnabled(),
            alertEnabled = inventoryHandler.isAlertEnabled(),
            hasNewImage = imageHandler.selectedImageUri != null
        )

        val success = if (isEditMode) itemManager.updateItem(item) else itemManager.addItem(item)
        if (!success) {
            Toast.makeText(this, "Failed to save item", Toast.LENGTH_SHORT).show()
            return
        }

        saveImageIfNeeded(item)
        setResult(RESULT_OK)
        finish()
    }

    private fun saveImageIfNeeded(item: Item) {
        imageHandler.selectedImageUri?.let { uri ->
            if (!itemManager.saveItemImage(item, uri)) {
                Toast.makeText(this, "Item saved but image could not be saved", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val EXTRA_ITEM_ID = "extra_item_id"
    }
}
