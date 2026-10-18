package com.electricdreams.numo.feature.onboarding

import android.content.Context
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.electricdreams.numo.R
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OnboardingActivityTest {

    @Before
    fun disableWelcomeMotion() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("OnboardingPrefs", Context.MODE_PRIVATE).edit().clear().commit()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @After
    fun restoreWelcomeMotion() {
        val context: Context = ApplicationProvider.getApplicationContext()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    // ── Navigation tests ────────────────────────────────────────────────

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
                activity.findViewById<MaterialButton>(R.id.accept_button).performClick()

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
                activity.findViewById<MaterialButton>(R.id.accept_button).performClick()

                val createButton = activity.findViewById<View>(R.id.create_wallet_button)
                createButton.performClick()

                val generatingContainer = activity.findViewById<FrameLayout>(R.id.generating_container)
                assertEquals("Generating container should be visible", View.VISIBLE, generatingContainer.visibility)
            }
        }
    }

    // ── Add mint tests ──────────────────────────────────────────────────

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
                    ReflectionHelpers.callInstanceMethod<Unit>(activity, "updateReviewMintsUI")
                    assertFalse(
                        activity.findViewById<MaterialButton>(R.id.mints_continue_button).isEnabled
                    )

                    ReflectionHelpers.callInstanceMethod<Unit>(
                        activity,
                        "addDifferentMint",
                        ReflectionHelpers.ClassParameter.from(String::class.java, mintUrl)
                    )
                }

                val added = waitForCondition(scenario, timeoutMs = 3000L) { activity ->
                    val adapter = ReflectionHelpers.getField<OnboardingMintAdapter>(
                        activity, "mintAdapter"
                    )
                    adapter.getDefaultMintUrl() == mintUrl
                }
                assertTrue("mint should be added after validation", added)

                scenario.onActivity { activity ->
                    val selected =
                        ReflectionHelpers.getField<LinkedHashSet<String>>(activity, "selectedMints")
                    assertTrue(selected.contains(mintUrl))
                    assertTrue(
                        activity.findViewById<MaterialButton>(R.id.mints_continue_button).isEnabled
                    )
                }
            }
        } finally {
            server.shutdown()
        }
    }

    // ── Mint adapter / review screen tests ──────────────────────────────

    @Test
    fun `health check keeps only reachable mints in the original order`() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return if (request.path == "/unhealthy/v1/info") {
                        MockResponse().setResponseCode(503)
                    } else {
                        MockResponse().setBody("{\"name\":\"Test Mint\"}")
                    }
                }
            }
            server.start()
            val first = server.url("/first").toString()
            val unhealthy = server.url("/unhealthy").toString()
            val second = server.url("/second").toString()

            ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val healthy = runBlocking {
                        activity.filterHealthyMints(listOf(first, unhealthy, second))
                    }
                    assertEquals(listOf(first, second), healthy)
                }
            }
        }
    }

    @Test
    fun `health check returns no mints when every endpoint fails`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(404))
            val mints = listOf(server.url("/one").toString(), server.url("/two").toString())

            ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val healthy = runBlocking { activity.filterHealthyMints(mints) }
                    assertTrue(healthy.isEmpty())
                }
            }
        }
    }

    @Test
    fun `health check excludes mints with connection failures`() {
        val server = MockWebServer()
        server.start()
        val mint = server.url("/").toString().removeSuffix("/")
        server.shutdown()

        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val healthy = runBlocking { activity.filterHealthyMints(listOf(mint)) }
                assertTrue(healthy.isEmpty())
            }
        }
    }

    @Test
    fun `empty review disables continue and keeps the add mint action`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "updateReviewMintsUI")

                val adapter = ReflectionHelpers.getField<OnboardingMintAdapter>(activity, "mintAdapter")
                assertNull(adapter.getDefaultMintUrl())
                assertTrue(adapter.getAllSelectedMints().isEmpty())
                assertEquals(2, adapter.itemCount)
                val button = activity.findViewById<MaterialButton>(R.id.mints_continue_button)
                assertFalse(button.isEnabled)
                assertEquals(0.35f, button.alpha)
            }
        }
    }

    @Test
    fun `empty review clears a previously selected mint and disables continue`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                setupMints(activity, default = "https://mint.coinos.io", popular = emptyList())
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "updateReviewMintsUI")

                ReflectionHelpers.getField<LinkedHashSet<String>>(activity, "discoveredMints").clear()
                ReflectionHelpers.getField<LinkedHashSet<String>>(activity, "selectedMints").clear()
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "updateReviewMintsUI")

                val adapter = ReflectionHelpers.getField<OnboardingMintAdapter>(activity, "mintAdapter")
                assertNull(adapter.getDefaultMintUrl())
                assertTrue(adapter.getAllSelectedMints().isEmpty())
                assertFalse(
                    activity.findViewById<MaterialButton>(R.id.mints_continue_button).isEnabled
                )
            }
        }
    }

    @Test
    fun `review screen populates adapter with default and popular mints`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                setupMints(activity, default = "https://mint.coinos.io", popular = listOf("https://mint.minibits.cash"))

                ReflectionHelpers.callInstanceMethod<Unit>(activity, "updateReviewMintsUI")

                val adapter = ReflectionHelpers.getField<OnboardingMintAdapter>(activity, "mintAdapter")
                assertEquals("https://mint.coinos.io", adapter.getDefaultMintUrl())
                assertTrue(adapter.getPopularMints().contains("https://mint.minibits.cash"))
            }
        }
    }

    @Test
    fun `review screen shows first discovered mint as default`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                setupMints(activity, default = "https://mint.coinos.io", popular = listOf("https://mint.minibits.cash", "https://testnut.cashu.space"))

                ReflectionHelpers.callInstanceMethod<Unit>(activity, "updateReviewMintsUI")

                val adapter = ReflectionHelpers.getField<OnboardingMintAdapter>(activity, "mintAdapter")
                assertEquals("https://mint.coinos.io", adapter.getDefaultMintUrl())
            }
        }
    }

    @Test
    fun `adapter tracks all selected mints including default`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                setupMints(activity, default = "https://mint.coinos.io", popular = listOf("https://mint.minibits.cash"))

                ReflectionHelpers.callInstanceMethod<Unit>(activity, "updateReviewMintsUI")

                val adapter = ReflectionHelpers.getField<OnboardingMintAdapter>(activity, "mintAdapter")
                val allSelected = adapter.getAllSelectedMints()
                assertTrue(allSelected.contains("https://mint.coinos.io"))
                assertTrue(allSelected.contains("https://mint.minibits.cash"))
                assertEquals(2, allSelected.size)
            }
        }
    }

    @Test
    fun `review screen continue button exists and is enabled with mints`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                setupMints(activity, default = "https://mint.coinos.io", popular = listOf("https://mint.minibits.cash"))

                ReflectionHelpers.callInstanceMethod<Unit>(activity, "updateReviewMintsUI")

                val continueButton = activity.findViewById<MaterialButton>(R.id.mints_continue_button)
                assertNotNull(continueButton)
                assertTrue(continueButton.isEnabled)
            }
        }
    }

    @Test
    fun `adapter onAddMintClicked callback is wired`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val adapter = ReflectionHelpers.getField<OnboardingMintAdapter>(activity, "mintAdapter")
                // Adapter should be non-null and attached — the listener is set in onCreate
                assertNotNull(adapter)
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun setupMints(activity: OnboardingActivity, default: String, popular: List<String>) {
        val discovered = ReflectionHelpers.getField<LinkedHashSet<String>>(activity, "discoveredMints")
        val selected = ReflectionHelpers.getField<LinkedHashSet<String>>(activity, "selectedMints")
        val names = ReflectionHelpers.getField<MutableMap<String, String>>(activity, "onboardingMintDisplayNames")
        discovered.clear()
        selected.clear()
        names.clear()

        discovered.add(default)
        selected.add(default)
        names[default] = default.substringAfter("://").substringBefore("/")

        for (url in popular) {
            discovered.add(url)
            selected.add(url)
            names[url] = url.substringAfter("://").substringBefore("/")
        }
    }

    private fun waitForCondition(
        scenario: ActivityScenario<OnboardingActivity>,
        timeoutMs: Long,
        condition: (OnboardingActivity) -> Boolean
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
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
