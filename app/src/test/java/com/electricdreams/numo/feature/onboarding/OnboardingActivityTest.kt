package com.electricdreams.numo.feature.onboarding

import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.electricdreams.numo.R
import com.electricdreams.numo.ui.components.AddMintInputCard
import com.google.android.material.button.MaterialButton
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OnboardingActivityTest {

    @Test
    fun `activity launches and shows welcome screen`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val welcomeContainer = activity.findViewById<FrameLayout>(R.id.welcome_container)
                assertEquals("Welcome container should be visible", View.VISIBLE, welcomeContainer.visibility)
                
                val choosePathContainer = activity.findViewById<FrameLayout>(R.id.choose_path_container)
                assertEquals("Choose path container should be gone", View.GONE, choosePathContainer.visibility)
            }
        }
    }

    @Test
    fun `accept button clicks through to choose path screen`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val acceptButton = activity.findViewById<MaterialButton>(R.id.accept_button)
                acceptButton.performClick()
                
                val welcomeContainer = activity.findViewById<FrameLayout>(R.id.welcome_container)
                assertEquals("Welcome container should be gone", View.GONE, welcomeContainer.visibility)
                
                val choosePathContainer = activity.findViewById<FrameLayout>(R.id.choose_path_container)
                assertEquals("Choose path container should be visible", View.VISIBLE, choosePathContainer.visibility)
            }
        }
    }

    @Test
    fun `restore wallet button shows enter seed screen`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Navigate to choose path
                activity.findViewById<MaterialButton>(R.id.accept_button).performClick()
                
                // Click restore
                val restoreButton = activity.findViewById<View>(R.id.restore_wallet_button)
                restoreButton.performClick()
                
                val enterSeedContainer = activity.findViewById<FrameLayout>(R.id.enter_seed_container)
                assertEquals("Enter seed container should be visible", View.VISIBLE, enterSeedContainer.visibility)
            }
        }
    }

    @Test
    fun `create wallet button shows generating screen`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Navigate to choose path
                activity.findViewById<MaterialButton>(R.id.accept_button).performClick()
                
                // Click create
                val createButton = activity.findViewById<View>(R.id.create_wallet_button)
                createButton.performClick()
                
                val generatingContainer = activity.findViewById<FrameLayout>(R.id.generating_container)
                assertEquals("Generating container should be visible", View.VISIBLE, generatingContainer.visibility)
            }
        }
    }

    @Test
    fun `review mint row renders name only with no visible URL subtitle`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val discovered =
                    ReflectionHelpers.getField<LinkedHashSet<String>>(activity, "discoveredMints")
                val selected =
                    ReflectionHelpers.getField<LinkedHashSet<String>>(activity, "selectedMints")
                val names = ReflectionHelpers.getField<MutableMap<String, String>>(
                    activity,
                    "onboardingMintDisplayNames"
                )
                discovered.clear()
                selected.clear()
                names.clear()

                val mintUrl = "https://mint.coinos.io"
                discovered.add(mintUrl)
                selected.add(mintUrl)
                names[mintUrl] = "Coinos"

                ReflectionHelpers.callInstanceMethod<Unit>(activity, "updateReviewMintsUI")

                val list = activity.findViewById<LinearLayout>(R.id.mints_list_container)
                assertEquals(1, list.childCount)

                val rowTexts = extractTextValues(list.getChildAt(0))
                assertTrue(rowTexts.contains("Coinos"))
                assertFalse(rowTexts.any { it.contains("coinos.io", ignoreCase = true) })
            }
        }
    }

    @Test
    fun `review screen includes add different mint card`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val addCard = activity.findViewById<AddMintInputCard>(R.id.add_different_mint_card)
                assertNotNull(addCard)
            }
        }
    }

    @Test
    fun `review subtitle uses updated bitcoin custody copy`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val subtitle = activity.findViewById<TextView>(R.id.mints_subtitle)
                assertEquals(
                    "These mints will hold your bitcoin. You can withdraw to your own wallet at any time, or set a payout threshold to do it automatically.",
                    subtitle.text.toString()
                )
            }
        }
    }

    @Test
    fun `onboarding add mint card uses onboarding presentation mode`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val addCard = activity.findViewById<AddMintInputCard>(R.id.add_different_mint_card)

                val helperText = addCard.findViewById<TextView>(R.id.helper_text)
                val urlInput = addCard.findViewById<EditText>(R.id.url_input)
                val inlineScan = addCard.findViewById<ImageButton>(R.id.scan_button)
                val scanRow = addCard.findViewById<View>(R.id.scan_row_container)
                val scanRowTitle = addCard.findViewById<TextView>(R.id.scan_row_title)
                val scanRowSubtitle = addCard.findViewById<TextView>(R.id.scan_row_subtitle)
                val addButton = addCard.findViewById<TextView>(R.id.add_button)

                assertEquals(View.GONE, helperText.visibility)
                assertEquals("Enter mint address", urlInput.hint.toString())
                assertEquals(View.GONE, inlineScan.visibility)
                assertEquals(View.VISIBLE, scanRow.visibility)
                assertEquals("Scan QR Code", scanRowTitle.text.toString())
                assertEquals("Tap to scan an address", scanRowSubtitle.text.toString())
                assertEquals(activity.getColor(R.color.color_text_primary), addButton.currentTextColor)
            }
        }
    }

    @Test
    fun `mints count reflects single selected mint in review`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val discovered =
                    ReflectionHelpers.getField<LinkedHashSet<String>>(activity, "discoveredMints")
                val selected =
                    ReflectionHelpers.getField<LinkedHashSet<String>>(activity, "selectedMints")
                val names = ReflectionHelpers.getField<MutableMap<String, String>>(
                    activity,
                    "onboardingMintDisplayNames"
                )
                discovered.clear()
                selected.clear()
                names.clear()

                val mintUrl = "https://mint.coinos.io"
                discovered.add(mintUrl)
                selected.add(mintUrl)
                names[mintUrl] = "Coinos"

                ReflectionHelpers.callInstanceMethod<Unit>(activity, "updateReviewMintsUI")

                val countText = activity.findViewById<TextView>(R.id.mints_count_text).text.toString()
                assertTrue(countText.contains("1 mint"))
            }
        }
    }

    @Test
    fun `add different mint with invalid URL does not modify list`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val discovered =
                    ReflectionHelpers.getField<LinkedHashSet<String>>(activity, "discoveredMints")
                discovered.clear()
                discovered.add("https://mint.coinos.io")

                ReflectionHelpers.callInstanceMethod<Unit>(
                    activity,
                    "addDifferentMint",
                    ReflectionHelpers.ClassParameter.from(String::class.java, "not-a-url")
                )
            }

            scenario.onActivity { activity ->
                val discovered =
                    ReflectionHelpers.getField<LinkedHashSet<String>>(activity, "discoveredMints")
                assertEquals(1, discovered.size)
                assertTrue(discovered.contains("https://mint.coinos.io"))
            }
        }
    }

    @Test
    fun `add different mint ignores duplicates`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val discovered =
                    ReflectionHelpers.getField<LinkedHashSet<String>>(activity, "discoveredMints")
                discovered.clear()
                discovered.add("https://mint.coinos.io")

                ReflectionHelpers.callInstanceMethod<Unit>(
                    activity,
                    "addDifferentMint",
                    ReflectionHelpers.ClassParameter.from(String::class.java, "mint.coinos.io")
                )
            }

            scenario.onActivity { activity ->
                val discovered =
                    ReflectionHelpers.getField<LinkedHashSet<String>>(activity, "discoveredMints")
                assertEquals(1, discovered.size)
            }
        }
    }

    @Test
    fun `add different mint adds validated mint and selects it`() {
        val server = MockWebServer()
        server.start()
        try {
            val mintUrl = server.url("/").toString().removeSuffix("/")
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"name\":\"Test Mint\"}"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"name\":\"Test Mint\"}"))

            ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val discovered =
                        ReflectionHelpers.getField<LinkedHashSet<String>>(activity, "discoveredMints")
                    val selected =
                        ReflectionHelpers.getField<LinkedHashSet<String>>(activity, "selectedMints")
                    val names = ReflectionHelpers.getField<MutableMap<String, String>>(
                        activity,
                        "onboardingMintDisplayNames"
                    )
                    discovered.clear()
                    selected.clear()
                    names.clear()

                    ReflectionHelpers.callInstanceMethod<Unit>(
                        activity,
                        "addDifferentMint",
                        ReflectionHelpers.ClassParameter.from(String::class.java, mintUrl)
                    )
                }

                val added = waitForCondition(scenario, timeoutMs = 3000L) { activity ->
                    val discovered =
                        ReflectionHelpers.getField<LinkedHashSet<String>>(activity, "discoveredMints")
                    discovered.contains(mintUrl)
                }
                assertTrue("mint should be added after validation", added)

                scenario.onActivity { activity ->
                    val selected =
                        ReflectionHelpers.getField<LinkedHashSet<String>>(activity, "selectedMints")
                    assertTrue(selected.contains(mintUrl))
                }
            }
        } finally {
            server.shutdown()
        }
    }

    private fun extractTextValues(view: View): List<String> {
        val values = mutableListOf<String>()
        when (view) {
            is TextView -> values.add(view.text.toString())
            is LinearLayout -> {
                for (i in 0 until view.childCount) {
                    values.addAll(extractTextValues(view.getChildAt(i)))
                }
            }
            is FrameLayout -> {
                for (i in 0 until view.childCount) {
                    values.addAll(extractTextValues(view.getChildAt(i)))
                }
            }
        }
        return values
    }

    private fun waitForCondition(
        scenario: ActivityScenario<OnboardingActivity>,
        timeoutMs: Long,
        condition: (OnboardingActivity) -> Boolean
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var met = false
            scenario.onActivity { activity ->
                met = condition(activity)
            }
            if (met) {
                return true
            }
            Thread.sleep(50)
        }
        return false
    }
}
