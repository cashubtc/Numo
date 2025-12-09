package com.electricdreams.numo.feature.items

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Item
import com.electricdreams.numo.core.util.ItemManager
import com.electricdreams.numo.feature.items.CsvImportHelper
import com.electricdreams.numo.ui.util.DialogHelper
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections

class ItemListActivity : AppCompatActivity() {

    private lateinit var itemManager: ItemManager
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var itemsContent: View
    private lateinit var bottomActions: LinearLayout
    private lateinit var fabAddItem: ImageButton
    private lateinit var doneReorderButton: ImageButton
    private lateinit var headerDivider: View
    private lateinit var topBar: View
    private lateinit var adapter: ItemAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    
    // Empty state animator
    private var emptyStateAnimator: EmptyStateAnimator? = null

    // Reordering mode state
    private var isReorderingMode = false

    private val addItemLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                refreshItems()
                setResult(Activity.RESULT_OK) // Propagate result back
            }
        }

    private val csvPickerLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                CsvImportHelper.importItemsFromCsvUri(
                    context = this,
                    itemManager = itemManager,
                    uri = uri,
                    clearExisting = true
                ) { importedCount ->
                    if (importedCount > 0) {
                        refreshItems()
                        setResult(Activity.RESULT_OK)
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_item_list)

        // Set up back button
        findViewById<View?>(R.id.back_button)?.setOnClickListener {
            if (isReorderingMode) {
                exitReorderingMode()
            } else {
                finish()
            }
        }

        recyclerView = findViewById(R.id.items_recycler_view)
        emptyView = findViewById(R.id.empty_view)
        itemsContent = findViewById(R.id.items_content)
        bottomActions = findViewById(R.id.bottom_actions)
        fabAddItem = findViewById(R.id.fab_add_item)
        doneReorderButton = findViewById(R.id.done_reorder_button)
        headerDivider = findViewById(R.id.header_divider)
        topBar = findViewById(R.id.top_bar)
        val importCsvButton: Button = findViewById(R.id.import_csv_button)
        val clearItemsButton: TextView = findViewById(R.id.clear_items_button)

        itemManager = ItemManager.getInstance(this)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = ItemAdapter(itemManager.getAllItems())
        recyclerView.adapter = adapter

        // Set up drag-and-drop reordering
        setupDragAndDrop()
        
        // Set up empty state buttons
        setupEmptyStateButtons()

        updateEmptyViewVisibility()

        fabAddItem.setOnClickListener {
            val intent = Intent(this, ItemEntryActivity::class.java)
            addItemLauncher.launch(intent)
        }

        doneReorderButton.setOnClickListener {
            exitReorderingMode()
        }

        importCsvButton.setOnClickListener {
            csvPickerLauncher.launch("text/csv")
        }

        clearItemsButton.setOnClickListener {
            showClearAllDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshItems()
        // Start animation if empty state is visible
        if (emptyView.visibility == View.VISIBLE) {
            emptyStateAnimator?.start()
        }
    }

    override fun onPause() {
        super.onPause()
        emptyStateAnimator?.stop()
    }

    private fun setupEmptyStateButtons() {
        // Find buttons in the included empty state layout
        val addButton = emptyView.findViewById<Button>(R.id.empty_state_add_button)
        val importButton = emptyView.findViewById<View>(R.id.empty_state_import_button)
        val closeButton = emptyView.findViewById<ImageButton>(R.id.empty_state_close_button)
        val backButton = emptyView.findViewById<ImageButton>(R.id.empty_state_back_button)
        val ribbonContainer = emptyView.findViewById<View>(R.id.ribbon_container)

        addButton?.setOnClickListener {
            val intent = Intent(this, ItemEntryActivity::class.java)
            addItemLauncher.launch(intent)
        }

        importButton?.setOnClickListener {
            csvPickerLauncher.launch("text/csv")
        }
        
        // Settings → Items: Show back arrow, hide close button
        closeButton?.visibility = View.GONE
        backButton?.visibility = View.VISIBLE
        backButton?.setOnClickListener {
            finish()
        }

        // Initialize the animator
        ribbonContainer?.let {
            emptyStateAnimator = EmptyStateAnimator(this, it)
        }
    }

    private fun refreshItems() {
        adapter.updateItems(itemManager.getAllItems())
        updateEmptyViewVisibility()
    }

    private fun updateEmptyViewVisibility() {
        val hasItems = adapter.itemCount > 0
        if (hasItems) {
            // Show items list, hide empty state
            emptyView.visibility = View.GONE
            itemsContent.visibility = View.VISIBLE
            fabAddItem.visibility = View.VISIBLE
            headerDivider.visibility = View.VISIBLE
            topBar.visibility = View.VISIBLE
            topBar.setBackgroundResource(R.color.color_bg_white)
            
            // Reset navigation bar to normal
            setNavigationBarStyle(isDarkBackground = false)
            
            // Stop animation
            emptyStateAnimator?.stop()
        } else {
            // Show empty state, hide items list
            emptyView.visibility = View.VISIBLE
            itemsContent.visibility = View.GONE
            fabAddItem.visibility = View.GONE
            headerDivider.visibility = View.GONE
            topBar.visibility = View.GONE
            
            // Set navigation bar to match empty state background
            setNavigationBarStyle(isDarkBackground = true)
            
            // Start animation
            emptyStateAnimator?.start()
        }
    }
    
    /**
     * Set the navigation bar style to match the current screen background.
     */
    private fun setNavigationBarStyle(isDarkBackground: Boolean) {
        window.navigationBarColor = if (isDarkBackground) {
            ContextCompat.getColor(this, R.color.empty_state_background)
        } else {
            ContextCompat.getColor(this, R.color.color_bg_white)
        }
        
        // Set light/dark icons in navigation bar
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightNavigationBars = !isDarkBackground
        }
        
        // Also update status bar
        window.statusBarColor = if (isDarkBackground) {
            ContextCompat.getColor(this, R.color.empty_state_background)
        } else {
            ContextCompat.getColor(this, R.color.color_bg_white)
        }
        
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDarkBackground
        }
    }

    private fun showClearAllDialog() {
        DialogHelper.showConfirmation(
            context = this,
            config = DialogHelper.ConfirmationConfig(
                title = getString(R.string.item_list_dialog_clear_all_title),
                message = getString(R.string.item_list_dialog_clear_all_message),
                confirmText = getString(R.string.item_list_dialog_clear_all_positive),
                cancelText = getString(R.string.common_cancel),
                isDestructive = true,
                onConfirm = {
                    itemManager.clearItems()
                    refreshItems()
                    setResult(Activity.RESULT_OK)
                    Toast.makeText(this, getString(R.string.item_list_toast_all_items_cleared), Toast.LENGTH_SHORT).show()
                }
            )
        )
    }

    

    private fun setupDragAndDrop() {
        val callback = object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
                return makeMovementFlags(dragFlags, 0)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPosition = viewHolder.adapterPosition
                val toPosition = target.adapterPosition
                adapter.moveItem(fromPosition, toPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // Not used - swipe disabled
            }

            override fun isLongPressDragEnabled(): Boolean = false

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.alpha = 0.9f
                    viewHolder?.itemView?.elevation = 8f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1.0f
                viewHolder.itemView.elevation = 0f
                // Persist the new order when drag ends
                adapter.commitReorder()
            }
        }

        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(recyclerView)
    }

    fun startDragging(viewHolder: RecyclerView.ViewHolder) {
        itemTouchHelper.startDrag(viewHolder)
    }

    // ----- Reordering Mode -----

    /**
     * Enter reordering mode - shows drag handles and replaces add button with done button.
     */
    fun enterReorderingMode() {
        if (isReorderingMode) return
        isReorderingMode = true

        // Swap buttons with cross-fade animation
        fabAddItem.animate()
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                fabAddItem.visibility = View.GONE
                doneReorderButton.visibility = View.VISIBLE
                doneReorderButton.alpha = 0f
                doneReorderButton.animate()
                    .alpha(1f)
                    .setDuration(150)
                    .start()
            }
            .start()

        // Hide bottom actions during reordering
        bottomActions.animate()
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                bottomActions.visibility = View.GONE
            }
            .start()

        // Refresh adapter to show drag handles
        adapter.setReorderingMode(true)
    }

    /**
     * Exit reordering mode - hides drag handles and restores add button.
     */
    private fun exitReorderingMode() {
        if (!isReorderingMode) return
        isReorderingMode = false

        // Swap buttons with cross-fade animation
        doneReorderButton.animate()
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                doneReorderButton.visibility = View.GONE
                fabAddItem.visibility = View.VISIBLE
                fabAddItem.alpha = 0f
                fabAddItem.animate()
                    .alpha(1f)
                    .setDuration(150)
                    .start()
            }
            .start()

        // Show bottom actions again
        bottomActions.visibility = View.VISIBLE
        bottomActions.alpha = 0f
        bottomActions.animate()
            .alpha(1f)
            .setDuration(150)
            .start()

        // Refresh adapter to hide drag handles
        adapter.setReorderingMode(false)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isReorderingMode) {
            exitReorderingMode()
        } else {
            super.onBackPressed()
        }
    }

    private inner class ItemAdapter(items: List<Item>) :
        RecyclerView.Adapter<ItemAdapter.ItemViewHolder>() {

        private val itemsList: MutableList<Item> = items.toMutableList()
        private var pendingFromPosition: Int = -1
        private var pendingToPosition: Int = -1
        private var inReorderingMode: Boolean = false

        fun updateItems(newItems: List<Item>) {
            itemsList.clear()
            itemsList.addAll(newItems)
            pendingFromPosition = -1
            pendingToPosition = -1
            notifyDataSetChanged()
        }

        fun setReorderingMode(enabled: Boolean) {
            inReorderingMode = enabled
            notifyDataSetChanged()
        }

        fun moveItem(fromPosition: Int, toPosition: Int) {
            if (fromPosition < 0 || fromPosition >= itemsList.size ||
                toPosition < 0 || toPosition >= itemsList.size) {
                return
            }
            // Track the overall drag operation (from start to end)
            if (pendingFromPosition == -1) {
                pendingFromPosition = fromPosition
            }
            pendingToPosition = toPosition

            // Move item in local list for visual feedback
            Collections.swap(itemsList, fromPosition, toPosition)
            notifyItemMoved(fromPosition, toPosition)
        }

        fun commitReorder() {
            if (pendingFromPosition != -1 && pendingToPosition != -1 &&
                pendingFromPosition != pendingToPosition) {
                // Persist the reorder to storage
                itemManager.reorderItems(pendingFromPosition, pendingToPosition)
                setResult(Activity.RESULT_OK)
            }
            pendingFromPosition = -1
            pendingToPosition = -1
            // Refresh dividers after reorder
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_product, parent, false)
            return ItemViewHolder(view)
        }

        override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
            val item = itemsList[position]
            holder.bind(item, position == itemsList.size - 1, inReorderingMode)
        }

        override fun getItemCount(): Int = itemsList.size

        inner class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val nameView: TextView = itemView.findViewById(R.id.item_name)
            private val variationView: TextView = itemView.findViewById(R.id.item_variation)
            private val priceView: TextView = itemView.findViewById(R.id.item_price)
            private val quantityView: TextView = itemView.findViewById(R.id.item_quantity)
            private val itemImageView: ImageView = itemView.findViewById(R.id.item_image)
            private val imagePlaceholder: ImageView = itemView.findViewById(R.id.item_image_placeholder)
            private val divider: View = itemView.findViewById(R.id.divider)
            private val dragHandle: ImageView = itemView.findViewById(R.id.drag_handle)

            @SuppressLint("ClickableViewAccessibility")
            fun bind(item: Item, isLast: Boolean, isReordering: Boolean) {
                // Item name
                nameView.text = item.name ?: ""

                // Variation (grey text)
                if (!item.variationName.isNullOrEmpty()) {
                    variationView.text = item.variationName
                    variationView.visibility = View.VISIBLE
                } else {
                    variationView.visibility = View.GONE
                }

                // Price
                val currencyCode = com.electricdreams.numo.core.util.CurrencyManager
                    .getInstance(itemView.context)
                    .getCurrentCurrency()
                priceView.text = item.getFormattedPrice(currencyCode)

                // Stock quantity (only if tracking inventory)
                if (item.trackInventory) {
                    quantityView.text = "${item.quantity} in stock"
                    quantityView.visibility = View.VISIBLE
                } else {
                    quantityView.visibility = View.GONE
                }

                // Image
                if (!item.imagePath.isNullOrEmpty()) {
                    val bitmap = itemManager.loadItemImage(item)
                    if (bitmap != null) {
                        itemImageView.setImageBitmap(bitmap)
                        imagePlaceholder.visibility = View.GONE
                    } else {
                        itemImageView.setImageBitmap(null)
                        imagePlaceholder.visibility = View.VISIBLE
                    }
                } else {
                    itemImageView.setImageBitmap(null)
                    imagePlaceholder.visibility = View.VISIBLE
                }

                // Hide divider on last item
                divider.visibility = if (isLast) View.GONE else View.VISIBLE

                // Drag handle visibility based on reordering mode
                dragHandle.visibility = if (isReordering) View.VISIBLE else View.GONE

                // Drag handle - start dragging on touch (only when visible)
                if (isReordering) {
                    dragHandle.setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            startDragging(this)
                        }
                        false
                    }
                } else {
                    dragHandle.setOnTouchListener(null)
                }

                // Click behavior depends on mode
                if (isReordering) {
                    // In reordering mode, clicking does nothing (only drag works)
                    itemView.setOnClickListener(null)
                    itemView.setOnLongClickListener(null)
                } else {
                    // Normal mode: click to edit, long-press to enter reordering mode
                    itemView.setOnClickListener {
                        val intent = Intent(this@ItemListActivity, ItemEntryActivity::class.java)
                        intent.putExtra(ItemEntryActivity.EXTRA_ITEM_ID, item.id)
                        addItemLauncher.launch(intent)
                    }

                    itemView.setOnLongClickListener {
                        enterReorderingMode()
                        true
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "ItemListActivity"
    }
}
