package com.electricdreams.numo.feature.onboarding

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.electricdreams.numo.R
import com.google.android.material.button.MaterialButton
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

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
}
