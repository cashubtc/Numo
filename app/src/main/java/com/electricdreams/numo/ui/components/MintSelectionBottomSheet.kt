package com.electricdreams.numo.ui.components

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.MintManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * A beautiful Apple-like bottom sheet for selecting a mint to withdraw from.
 * 
 * Features:
 * - Smooth slide-up animation
 * - Staggered item animations
 * - Beautiful card-based mint items with balance display
 * - Drag handle for dismissal
 * - Material Design 3 styling
 */
class MintSelectionBottomSheet : BottomSheetDialogFragment() {

    interface OnMintSelectedListener {
        fun onMintSelected(mintUrl: String, balance: Long)
    }

    private var listener: OnMintSelectedListener? = null
    private var mintBalances: Map<String, Long> = emptyMap()
    private lateinit var mintManager: MintManager

    companion object {
        private const val TAG = "MintSelectionBottomSheet"

        fun newInstance(
            mintBalances: Map<String, Long>,
            listener: OnMintSelectedListener
        ): MintSelectionBottomSheet {
            return MintSelectionBottomSheet().apply {
                this.mintBalances = mintBalances
                this.listener = listener
            }
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        mintManager = MintManager.getInstance(context)
    }

    override fun getTheme(): Int = R.style.Theme_Numo_BottomSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_mint_selection, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView(view)
        setupBottomSheetBehavior()
    }

    private fun setupRecyclerView(view: View) {
        val recyclerView = view.findViewById<RecyclerView>(R.id.mints_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        
        val mintsWithBalance = mintBalances.filter { it.value > 0 }
        recyclerView.adapter = MintAdapter(mintsWithBalance)
    }

    private fun setupBottomSheetBehavior() {
        dialog?.setOnShowListener { dialogInterface ->
            val bottomSheetDialog = dialogInterface as BottomSheetDialog
            val bottomSheet = bottomSheetDialog.findViewById<FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            
            bottomSheet?.let { sheet ->
                sheet.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.color_bg_white)
                )

                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.isDraggable = true
                
                // Smooth corner animation
                behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                    override fun onStateChanged(bottomSheet: View, newState: Int) {
                        if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                            dismiss()
                        }
                    }
                    override fun onSlide(bottomSheet: View, slideOffset: Float) {
                        // Optional: animate something based on slide
                    }
                })
            }
        }
    }

    /**
     * Premium adapter for displaying mint items with beautiful animations
     */
    private inner class MintAdapter(
        private val mints: Map<String, Long>
    ) : RecyclerView.Adapter<MintAdapter.ViewHolder>() {

        private val mintList = mints.entries.toList()

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val container: View = view.findViewById(R.id.mint_item_container)
            val mintName: TextView = view.findViewById(R.id.mint_name)
            val mintUrl: TextView = view.findViewById(R.id.mint_url)
            val balanceText: TextView = view.findViewById(R.id.balance_text)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_mint_selection, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val (url, balance) = mintList[position]
            
            // Display name and URL
            val displayName = mintManager.getMintDisplayName(url)
            holder.mintName.text = displayName
            holder.mintUrl.text = url.removePrefix("https://").removePrefix("http://")
            
            // Balance with Amount formatting
            val amount = Amount(balance, Amount.Currency.BTC)
            holder.balanceText.text = amount.toString()
            
            // Click handler with ripple feedback
            holder.container.setOnClickListener {
                // Scale animation on press
                holder.container.animate()
                    .scaleX(0.98f)
                    .scaleY(0.98f)
                    .setDuration(100)
                    .withEndAction {
                        holder.container.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(100)
                            .withEndAction {
                                listener?.onMintSelected(url, balance)
                                dismiss()
                            }
                            .start()
                    }
                    .start()
            }
            
            // Staggered entrance animation
            holder.itemView.alpha = 0f
            holder.itemView.translationY = 30f
            holder.itemView.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((position * 60).toLong())
                .setDuration(300)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }

        override fun getItemCount() = mintList.size
    }
}
