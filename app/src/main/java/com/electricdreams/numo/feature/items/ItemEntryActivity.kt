package com.electricdreams.numo.feature.items

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged

import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Item
import com.electricdreams.numo.core.model.PriceType
import com.electricdreams.numo.core.util.CurrencyManager
import com.electricdreams.numo.core.util.ItemManager
import com.electricdreams.numo.databinding.ActivityItemEntryBinding
import com.electricdreams.numo.feature.items.handlers.CategoryTagHandler
import com.electricdreams.numo.feature.items.handlers.GtinHandler
import com.electricdreams.numo.feature.items.handlers.ImageHandler
import com.electricdreams.numo.feature.items.handlers.InventoryHandler
import com.electricdreams.numo.feature.items.handlers.ItemBuilder
import com.electricdreams.numo.feature.items.handlers.ItemFormValidator
import com.electricdreams.numo.feature.items.handlers.PricingHandler
import com.electricdreams.numo.feature.items.handlers.SkuHandler
import com.electricdreams.numo.ui.util.applySettingsWindowInsets

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

    private lateinit var binding: ActivityItemEntryBinding

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
    private var moreDetailsExpanded: Boolean = false

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
        binding = ActivityItemEntryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySettingsWindowInsets(this, binding.root)

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
        nameInput = binding.itemNameInput
        variationInput = binding.itemVariationInput
        categoryInput = binding.itemCategoryInput
        descriptionInput = binding.itemDescriptionInput
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
            categoryTagsContainer = binding.categoryTagsContainer,
            newCategoryContainer = binding.newCategoryContainer,
            newCategoryInput = binding.newCategoryInput,
            btnConfirmCategory = binding.btnConfirmCategory,
            btnCancelCategory = binding.btnCancelCategory,
            categoryInput = categoryInput,
            itemManager = itemManager
        )
        categoryTagHandler.initialize()
    }

    private fun initializePricingHandler() {
        pricingHandler = PricingHandler(
            priceTypeToggle = binding.priceTypeToggle,
            btnPriceFiat = binding.btnPriceFiat,
            btnPriceBitcoin = binding.btnPriceBitcoin,
            fiatPriceLayout = binding.fiatPriceLayout,
            satsPriceLayout = binding.satsPriceLayout,
            priceInput = binding.itemPriceInput,
            satsInput = binding.itemSatsInput,
            vatSectionCard = binding.vatSectionCard,
            switchVatEnabled = binding.switchVatEnabled,
            vatFieldsContainer = binding.vatFieldsContainer,
            switchPriceIncludesVat = binding.switchPriceIncludesVat,
            vatRateInput = binding.vatRateInput,
            priceBreakdownContainer = binding.priceBreakdownContainer,
            textNetPrice = binding.textNetPrice,
            textVatLabel = binding.textVatLabel,
            textVatAmount = binding.textVatAmount,
            textGrossPrice = binding.textGrossPrice,
            currencyManager = currencyManager
        )
        pricingHandler.initialize()
    }

    private fun initializeInventoryHandler() {
        inventoryHandler = InventoryHandler(
            switchTrackInventory = binding.switchTrackInventory,
            inventoryFieldsContainer = binding.inventoryFieldsContainer,
            quantityInput = binding.itemQuantityInput,
            alertCheckbox = binding.itemAlertCheckbox,
            alertThresholdContainer = binding.alertThresholdContainer,
            alertThresholdInput = binding.itemAlertThresholdInput
        )
        inventoryHandler.initialize()
    }

    private fun initializeImageHandler() {
        imageHandler = ImageHandler(
            activity = this,
            itemImageView = binding.itemImageView,
            imagePlaceholder = binding.itemImagePlaceholder,
            addImageButton = binding.itemAddImageButton,
            removeImageButton = binding.itemRemoveImageButton,
            itemManager = itemManager,
            selectGalleryLauncher = selectGalleryLauncher,
            takePictureLauncher = takePictureLauncher
        )
        imageHandler.initialize()
    }

    private fun initializeGtinHandler() {
        gtinHandler = GtinHandler(
            activity = this,
            gtinInput = binding.itemGtinInput,
            gtinContainer = binding.gtinContainer,
            gtinErrorText = binding.gtinErrorText,
            scanBarcodeButton = binding.btnScanBarcode,
            itemManager = itemManager,
            barcodeScanLauncher = barcodeScanLauncher
        )
        gtinHandler.setEditItemId(editItemId)
        gtinHandler.initialize()
    }

    private fun initializeSkuHandler() {
        skuHandler = SkuHandler(
            skuInput = binding.itemSkuInput,
            skuContainer = binding.skuContainer,
            skuErrorText = binding.skuErrorText,
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
        binding.topBar.onNavClick { finish() }
        binding.itemSaveButton.setOnClickListener { saveItem() }
        binding.itemCancelButton.setOnClickListener {
            showDeleteConfirmationDialog()
        }

        // More Details expand/collapse
        binding.moreDetailsToggle.setOnClickListener {
            toggleMoreDetails()
        }

        // Inline validation on focus change
        setupInlineValidation()
    }

    private fun toggleMoreDetails() {
        moreDetailsExpanded = !moreDetailsExpanded
        val container = binding.moreDetailsContainer
        val chevron = binding.moreDetailsChevron

        if (moreDetailsExpanded) {
            container.visibility = View.VISIBLE
            chevron.animate().rotation(180f).setDuration(200).start()
        } else {
            container.visibility = View.GONE
            chevron.animate().rotation(0f).setDuration(200).start()
        }
    }

    private fun setupInlineValidation() {
        nameInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && nameInput.text.toString().trim().isEmpty()) {
                nameInput.error = getString(R.string.item_entry_error_name_required)
            }
        }
        nameInput.doAfterTextChanged { text ->
            if (!text.isNullOrBlank()) nameInput.error = null
        }
    }

    private fun setupEditMode() {
        binding.topBar.setTitle(getString(R.string.item_entry_title_edit))
        // Show the dedicated delete button
        binding.itemCancelButton.visibility = View.VISIBLE
        // Auto-expand more details in edit mode so all fields are visible
        if (!moreDetailsExpanded) {
            toggleMoreDetails()
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("name", nameInput.text.toString())
        outState.putString("variation", variationInput.text.toString())
        outState.putString("description", descriptionInput.text.toString())
        outState.putBoolean("moreDetailsExpanded", moreDetailsExpanded)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        nameInput.setText(savedInstanceState.getString("name", ""))
        variationInput.setText(savedInstanceState.getString("variation", ""))
        descriptionInput.setText(savedInstanceState.getString("description", ""))
        if (savedInstanceState.getBoolean("moreDetailsExpanded", false) && !moreDetailsExpanded) {
            toggleMoreDetails()
        }
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

        dialogView.findViewById<View>(R.id.close_button).setOnClickListener {
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
            vatEnabled = pricingHandler.isVatEnabled(),
            vatRate = pricingHandler.getVatRate(),
            trackInventory = inventoryHandler.isTrackingEnabled(),
            alertEnabled = inventoryHandler.isAlertEnabled(),
            hasNewImage = imageHandler.selectedImageUri != null
        )

        val success = if (isEditMode) itemManager.updateItem(item) else itemManager.addItem(item)
        if (!success) {
            Toast.makeText(this, R.string.item_list_toast_failed_save_item, Toast.LENGTH_SHORT).show()
            return
        }

        saveImageIfNeeded(item)
        val toastRes = if (isEditMode) R.string.item_entry_toast_item_updated else R.string.item_entry_toast_item_saved
        Toast.makeText(this, toastRes, Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun saveImageIfNeeded(item: Item) {
        // Use the corrected bitmap (with proper rotation) if available, otherwise fall back to URI
        val bitmap = imageHandler.correctedBitmap
        if (bitmap != null) {
            if (!itemManager.saveItemImageBitmap(item, bitmap)) {
                Toast.makeText(this, R.string.item_list_toast_saved_without_image, Toast.LENGTH_LONG).show()
            }
        } else {
            imageHandler.selectedImageUri?.let { uri ->
                if (!itemManager.saveItemImage(item, uri)) {
                    Toast.makeText(this, R.string.item_list_toast_saved_without_image, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    companion object {
        const val EXTRA_ITEM_ID = "extra_item_id"
    }
}
