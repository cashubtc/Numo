package com.electricdreams.numo.feature.settings

import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.viewpager2.widget.ViewPager2
import com.electricdreams.numo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StripeCardTutorialActivityTest {

    @Test
    fun `card settings opens the setup tutorial`() {
        ActivityScenario.launch(StripeCardSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.stripe_tutorial_row).performClick()
                val intent = shadowOf(activity).nextStartedActivity
                assertEquals(
                    StripeCardTutorialActivity::class.java.name,
                    intent.component?.className,
                )
            }
        }
    }

    @Test
    fun `tutorial pages forward backward and finishes from the last slide`() {
        ActivityScenario.launch(StripeCardTutorialActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val pager = activity.findViewById<ViewPager2>(R.id.tutorial_pager)
                val back = activity.findViewById<Button>(R.id.tutorial_back_button)
                val next = activity.findViewById<Button>(R.id.tutorial_next_button)
                val progress = activity.findViewById<TextView>(R.id.tutorial_progress)
                assertEquals(3, pager.adapter?.itemCount)
                assertEquals(0, pager.currentItem)
                assertEquals("1 of 3", progress.text.toString())

                next.performClick()
                next.performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(2, pager.currentItem)
                assertEquals("3 of 3", progress.text.toString())

                back.performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(1, pager.currentItem)

                pager.currentItem = 2
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(activity.getString(R.string.stripe_tutorial_done), next.text.toString())
                next.performClick()
                assertTrue(activity.isFinishing)
            }
        }
    }
}
