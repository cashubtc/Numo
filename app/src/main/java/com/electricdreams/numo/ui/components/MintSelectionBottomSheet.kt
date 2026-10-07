package com.electricdreams.numo.ui.components

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.databinding.BottomSheetMintSelectionBinding
import com.electricdreams.numo.databinding.ItemMintSelectionBinding
import com.electricdreams.numo.feature.settings.WithdrawUi
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
    ): View {
        val binding = BottomSheetMintSelectionBinding.inflate(inflater, container, false)
        binding.mintsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        val mints = mintBalances.filter { it.value > 0 }.entries.sortedByDescending { it.value }
        binding.mintsRecyclerView.adapter = MintAdapter(mints.map { it.key to it.value })
        return binding.root
    }

    private inner class MintAdapter(
        private val mints: List<Pair<String, Long>>
    ) : RecyclerView.Adapter<MintAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemMintSelectionBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(ItemMintSelectionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val (url, balance) = mints[position]
            val isSelected = url == selectedMintUrl

            holder.binding.mintName.text = mintManager.getMintDisplayName(url)
            holder.binding.balanceText.text =
                WithdrawUi.sourceCaption(requireContext(), url, balance, mints.map { it.first })
            holder.binding.selectedIcon.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            holder.itemView.isSelected = isSelected
            holder.itemView.setOnClickListener {
                listener?.onMintSelected(url, balance)
                dismiss()
            }
        }

        override fun getItemCount() = mints.size
    }

    companion object {
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
}
