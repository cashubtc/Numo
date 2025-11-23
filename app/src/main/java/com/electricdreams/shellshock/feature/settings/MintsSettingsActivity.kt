package com.electricdreams.shellshock.feature.settings

import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.shellshock.R
import com.electricdreams.shellshock.core.util.MintManager
import com.electricdreams.shellshock.ui.adapter.MintsAdapter

class MintsSettingsActivity : AppCompatActivity(), MintsAdapter.MintRemoveListener {

    private lateinit var mintsRecyclerView: RecyclerView
    private lateinit var mintsAdapter: MintsAdapter
    private lateinit var newMintEditText: EditText
    private lateinit var addMintButton: Button
    private lateinit var resetMintsButton: View
    private lateinit var mintManager: MintManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mints_settings)

        findViewById<View?>(R.id.back_button)?.setOnClickListener { finish() }

        mintManager = MintManager.getInstance(this)

        mintsRecyclerView = findViewById(R.id.mints_recycler_view)
        newMintEditText = findViewById(R.id.new_mint_edit_text)
        addMintButton = findViewById(R.id.add_mint_button)
        resetMintsButton = findViewById(R.id.reset_mints_button)

        mintsRecyclerView.layoutManager = LinearLayoutManager(this)
        mintsRecyclerView.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL),
        )

        mintsAdapter = MintsAdapter(mintManager.getAllowedMints(), this)
        mintsRecyclerView.adapter = mintsAdapter

        addMintButton.setOnClickListener { addNewMint() }
        resetMintsButton.setOnClickListener { resetMintsToDefaults() }

        newMintEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addNewMint()
                true
            } else {
                false
            }
        }
    }

    private fun addNewMint() {
        val mintUrl = newMintEditText.text.toString().trim()
        if (TextUtils.isEmpty(mintUrl)) {
            Toast.makeText(this, "Please enter a valid mint URL", Toast.LENGTH_SHORT).show()
            return
        }

        val added = mintManager.addMint(mintUrl)
        if (added) {
            mintsAdapter.updateMints(mintManager.getAllowedMints())
            newMintEditText.setText("")
            Toast.makeText(this, "Mint added", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Mint already in the list", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetMintsToDefaults() {
        mintManager.resetToDefaults()
        mintsAdapter.updateMints(mintManager.getAllowedMints())
        Toast.makeText(this, "Mints reset to defaults", Toast.LENGTH_SHORT).show()
    }

    override fun onMintRemoved(mintUrl: String) {
        if (mintManager.removeMint(mintUrl)) {
            mintsAdapter.updateMints(mintManager.getAllowedMints())
            Toast.makeText(this, "Mint removed", Toast.LENGTH_SHORT).show()
        }
    }
}
