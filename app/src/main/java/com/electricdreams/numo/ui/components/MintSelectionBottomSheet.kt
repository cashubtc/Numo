package com.electricdreams.numo.ui.components

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.MintManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Lets the merchant pick which mint a withdrawal is paid from. Only shown when more
 * than one mint holds a balance; with a single funded mint the choice is implicit.
 */
class MintSelectionBottomSheet : BottomSheetDialogFragment() {

    interface OnMintSelectedListener {
        fun onMintSelected(mintUrl: String, balance: Long)
    }

    private var listener: OnMintSelectedListener? = null
    private var mintBalances: Map<String, Long> = emptyMap()
    private var selectedMintUrl: String? = null
    private lateinit var mintManager: MintManager

    companion object {
        private const val TAG = "MintSelectionBottomSheet"

        fun newInstance(
            mintBalances: Map<String, Long>,
            selectedMintUrl: String?,
            listener: OnMintSelectedListener
        ): MintSelectionBottomSheet {
            return MintSelectionBottomSheet().apply {
                this.mintBalances = mintBalances
                this.selectedMintUrl = selectedMintUrl
                this.listener = listener
            }
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        mintManager = MintManager.getInstance(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The listener and balances are not restorable; after a process or
        // configuration recreation the host simply shows its current source again.
        if (savedInstanceState != null) dismissAllowingStateLoss()
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
        val recyclerView = view.findViewById<RecyclerView>(R.id.mints_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        val mints = mintBalances.filter { it.value > 0 }.entries.sortedByDescending { it.value }
        recyclerView.adapter = MintAdapter(mints.map { it.key to it.value })
    }

    private inner class MintAdapter(
        private val mints: List<Pair<String, Long>>
    ) : RecyclerView.Adapter<MintAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val mintName: TextView = view.findViewById(R.id.mint_name)
            val balanceText: TextView = view.findViewById(R.id.balance_text)
            val selectedIcon: ImageView = view.findViewById(R.id.selected_icon)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_mint_selection, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val (url, balance) = mints[position]
            val isSelected = url == selectedMintUrl

            holder.mintName.text = mintManager.getMintDisplayName(url)
            holder.balanceText.text = getString(
                R.string.withdraw_from_available,
                Amount(balance, Amount.Currency.BTC).toString()
            )
            holder.selectedIcon.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            holder.itemView.isSelected = isSelected
            holder.itemView.setOnClickListener {
                listener?.onMintSelected(url, balance)
                dismiss()
            }
        }

        override fun getItemCount() = mints.size
    }
}
