package com.electricdreams.numo.feature.settings

import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.electricdreams.numo.R
import com.electricdreams.numo.ui.seed.SeedWordEditText
import com.google.android.material.button.MaterialButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RestoreWalletActivityTest {

    @Test
    fun `initial state shows seed entry screen`() {
        ActivityScenario.launch(RestoreWalletActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val seedEntryContainer = activity.findViewById<View>(R.id.seed_entry_container)
                val titleText = activity.findViewById<TextView>(R.id.title_text)
                
                assertEquals("Seed entry should be visible", View.VISIBLE, seedEntryContainer.visibility)
                assertEquals("Title should be correct", activity.getString(R.string.restore_progress_title), titleText.text)
                
                val continueButton = activity.findViewById<MaterialButton>(R.id.continue_button)
                assertFalse("Continue button should be disabled initially", continueButton.isEnabled)
            }
        }
    }

    @Test
    fun `entering valid seed enables continue button`() {
        ActivityScenario.launch(RestoreWalletActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Find all seed inputs (we know there are 12 added dynamically)
                // The grid layout is id: seed_input_grid
                // We can also find them by traversing or just use the logic that we know they are SeedWordEditTexts
                
                // Helper to fill inputs
                val validWord = "abandon"
                val inputs = findAllSeedInputs(activity)
                
                assertEquals("Should have 12 inputs", 12, inputs.size)
                
                // Fill first 11
                for (i in 0 until 11) {
                    inputs[i].setText(validWord)
                }
                
                val continueButton = activity.findViewById<MaterialButton>(R.id.continue_button)
                assertFalse("Button should still be disabled with 11 words", continueButton.isEnabled)
                
                // Fill last one
                inputs[11].setText(validWord)
                
                assertTrue("Button should be enabled with 12 valid words", continueButton.isEnabled)
            }
        }
    }

    @Test
    fun `entering invalid seed keeps button disabled and shows error`() {
        ActivityScenario.launch(RestoreWalletActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val inputs = findAllSeedInputs(activity)
                val continueButton = activity.findViewById<MaterialButton>(R.id.continue_button)
                val validationText = activity.findViewById<TextView>(R.id.validation_text)
                
                // Fill all with numbers (invalid for this specific validation logic: ^[a-z]+$)
                for (input in inputs) {
                    input.setText("123")
                }
                
                assertFalse("Button should be disabled for invalid input", continueButton.isEnabled)
                assertEquals("Error message should be shown", 
                    activity.getString(R.string.onboarding_seed_invalid_characters), 
                    validationText.text)
            }
        }
    }

    @Test
    fun `continue button click shows fetching overlay`() {
        ActivityScenario.launch(RestoreWalletActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val inputs = findAllSeedInputs(activity)
                for (input in inputs) {
                    input.setText("abandon")
                }
                
                val continueButton = activity.findViewById<MaterialButton>(R.id.continue_button)
                continueButton.performClick()
                
                val fetchingOverlay = activity.findViewById<View>(R.id.fetching_overlay)
                val titleText = activity.findViewById<TextView>(R.id.title_text)
                
                assertEquals("Fetching overlay should be visible", View.VISIBLE, fetchingOverlay.visibility)
                assertEquals("Title should update", activity.getString(R.string.restore_fetching_title), titleText.text)
            }
        }
    }

    private fun findAllSeedInputs(activity: android.app.Activity): List<SeedWordEditText> {
        val grid = activity.findViewById<androidx.gridlayout.widget.GridLayout>(R.id.seed_input_grid)
        val inputs = mutableListOf<SeedWordEditText>()
        
        // The grid contains container LinearLayouts, which contain the EditTexts
        for (i in 0 until grid.childCount) {
            val container = grid.getChildAt(i) as? android.view.ViewGroup
            if (container != null) {
                for (j in 0 until container.childCount) {
                    val child = container.getChildAt(j)
                    if (child is SeedWordEditText) {
                        inputs.add(child)
                    }
                }
            }
        }
        return inputs
    }
}
