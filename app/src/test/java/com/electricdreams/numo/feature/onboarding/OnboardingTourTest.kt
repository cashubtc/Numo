package com.electricdreams.numo.feature.onboarding

import android.content.Context
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.ModernPOSActivity
import com.electricdreams.numo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingTourTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun resetPreferences() {
        context.getSharedPreferences("OnboardingPrefs", Context.MODE_PRIVATE).edit().clear().commit()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @After
    fun restoreAnimationScale() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        context.getSharedPreferences("OnboardingPrefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `all four pages are available with motion disabled and get started stays accessible`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val tour = activity.findViewById<OnboardingTourView>(R.id.welcome_tour)
                for (page in 0..3) {
                    assertEquals(page, tour.currentPage)
                    assertFalse(tour.isPlaybackRunning)
                    assertTrue(activity.findViewById<View>(R.id.accept_button).isShown)
                    if (page < 3) tour.advancePage()
                }
                tour.advancePage()
                assertEquals(0, tour.currentPage)
                activity.findViewById<View>(R.id.accept_button).performClick()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.choose_path_container).visibility)
            }
        }
    }

    @Test
    fun `page and elapsed time survive recreation`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<OnboardingTourView>(R.id.welcome_tour).restorePlaybackState(Bundle().apply {
                    putInt("tour_page", 2)
                    putLong("tour_elapsed", 2800)
                })
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val state = Bundle()
                activity.findViewById<OnboardingTourView>(R.id.welcome_tour).savePlaybackState(state)
                assertEquals(2, state.getInt("tour_page"))
                assertEquals(2800L, state.getLong("tour_elapsed"))
            }
        }
    }

    @Test
    fun `preview exits without changing onboarding completion or entering setup`() {
        OnboardingActivity.setOnboardingComplete(context, true)
        val intent = Intent(context, OnboardingActivity::class.java).putExtra("preview_onboarding", true)
        ActivityScenario.launch<OnboardingActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.welcome_container).visibility)
                activity.findViewById<View>(R.id.accept_button).performClick()
                assertTrue(activity.isFinishing)
                assertTrue(OnboardingActivity.isOnboardingComplete(context))
            }
        }
    }

    @Test
    fun `fresh install continues into wallet setup even with the preview extra`() {
        val intent = Intent(context, OnboardingActivity::class.java)
            .putExtra("preview_onboarding", true)
        ActivityScenario.launch<OnboardingActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.accept_button).performClick()

                assertFalse(activity.isFinishing)
                assertTrue(activity.findViewById<View>(R.id.create_wallet_button).isShown)
                assertTrue(activity.findViewById<View>(R.id.restore_wallet_button).isShown)
                assertFalse(OnboardingActivity.isOnboardingComplete(context))
            }
        }
    }

    @Test
    fun `get started enters existing setup from any scene without completing onboarding`() {
        for (page in 0..3) {
            ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val tour = activity.findViewById<OnboardingTourView>(R.id.welcome_tour)
                    repeat(page) { tour.advancePage() }
                    activity.findViewById<View>(R.id.accept_button).performClick()

                    assertTrue(activity.findViewById<View>(R.id.create_wallet_button).isShown)
                    assertTrue(activity.findViewById<View>(R.id.restore_wallet_button).isShown)
                    assertFalse(tour.isPlaybackRunning)
                    assertFalse(OnboardingActivity.isOnboardingComplete(context))
                }
            }
        }
    }

    @Test
    fun `reopening unfinished setup returns to the welcome screen`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.accept_button).performClick()
                activity.findViewById<View>(R.id.restore_wallet_button).performClick()
                assertTrue(activity.findViewById<View>(R.id.enter_seed_container).isShown)
            }
        }

        assertFalse(OnboardingActivity.isOnboardingComplete(context))
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.welcome_container).isShown)
                assertFalse(activity.findViewById<View>(R.id.enter_seed_container).isShown)
                assertEquals(0, activity.findViewById<OnboardingTourView>(R.id.welcome_tour).currentPage)
            }
        }
    }

    @Test
    fun `completed setup skips the welcome and opens checkout on normal launch`() {
        OnboardingActivity.setOnboardingComplete(context, true)
        val controller = Robolectric.buildActivity(OnboardingActivity::class.java).create()
        try {
            val activity = controller.get()
            val destination = shadowOf(activity).nextStartedActivity

            assertTrue(activity.isFinishing)
            assertNull(activity.findViewById<View>(R.id.welcome_container))
            assertEquals(ModernPOSActivity::class.java.name, destination?.component?.className)
            assertEquals(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                destination?.flags,
            )
            assertTrue(OnboardingActivity.isOnboardingComplete(context))
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `back navigates to previous tour page before leaving onboarding`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val tour = activity.findViewById<OnboardingTourView>(R.id.welcome_tour)
                tour.advancePage()
                assertTrue(tour.previousPage())
                assertEquals(0, tour.currentPage)
                assertFalse(tour.previousPage())
            }
        }
    }

    @Test
    fun `automatic playback wraps and stops while the welcome screen is inactive`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val tour = activity.findViewById<OnboardingTourView>(R.id.welcome_tour)
                ShadowChoreographer.setPaused(true)
                Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
                tour.setActive(true)
                assertTrue(tour.isPlaybackRunning)
                for (expectedPage in listOf(1, 2, 3, 0, 1)) {
                    ReflectionHelpers.getField<ValueAnimator>(tour, "clock").end()
                    assertEquals(expectedPage, tour.currentPage)
                    assertTrue(tour.isPlaybackRunning)
                }
                tour.setActive(false)
                val before = Bundle().also(tour::savePlaybackState)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
                val after = Bundle().also(tour::savePlaybackState)
                assertFalse(tour.isPlaybackRunning)
                assertEquals(before.getInt("tour_page"), after.getInt("tour_page"))
                assertEquals(before.getLong("tour_elapsed"), after.getLong("tour_elapsed"))
            }
        }
    }
}
