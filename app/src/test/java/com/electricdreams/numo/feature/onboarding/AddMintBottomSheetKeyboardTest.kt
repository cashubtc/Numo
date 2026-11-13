package com.electricdreams.numo.feature.onboarding

import android.graphics.Rect
import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35])
class AddMintBottomSheetKeyboardTest {
    private var animatorDurationScale = 1f

    @Before
    fun disableWelcomeAnimation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        animatorDurationScale = Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @After
    fun restoreWelcomeAnimation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE,
            animatorDurationScale)
    }

    @Test
    fun `onboarding mint actions fit above the keyboard without duplicate insets`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ReflectionHelpers.callInstanceMethod<Void>(activity, "showAddMintBottomSheet")
                activity.supportFragmentManager.executePendingTransactions()
                val sheet = activity.supportFragmentManager
                    .findFragmentByTag("AddMintBottomSheet") as AddMintBottomSheet
                val root = sheet.requireView() as NestedScrollView
                val window = (sheet.requireDialog() as BottomSheetDialog).window
                assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                    window?.attributes?.softInputMode?.and(
                        WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST))
                val originalPadding = root.paddingBottom
                val input = root.findViewById<EditText>(R.id.mint_url_input)
                input.requestFocus()
                input.setText("https://mint.example")

                for (keyboardHeight in listOf(300, 450, 0, 300)) {
                    ViewCompat.dispatchApplyWindowInsets(root, WindowInsetsCompat.Builder()
                        .setInsets(WindowInsetsCompat.Type.ime(),
                            Insets.of(0, 0, 0, keyboardHeight))
                        .setVisible(WindowInsetsCompat.Type.ime(), keyboardHeight > 0)
                        .build())
                    assertEquals(originalPadding, root.paddingBottom)
                    val height = 900 - keyboardHeight
                    root.measure(
                        View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST),
                    )
                    root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                    assertTrue(root.height <= height)
                    for (id in listOf(R.id.add_mint_button, R.id.discover_mints_button,
                        R.id.scan_qr_button)) {
                        val action = root.findViewById<View>(id)
                        val bounds = Rect()
                        action.getDrawingRect(bounds)
                        root.offsetDescendantRectToMyCoords(action, bounds)
                        root.scrollTo(0, bounds.bottom - root.height)
                        assertTrue(action.isShown)
                        assertTrue(bounds.bottom <= root.scrollY + root.height)
                    }
                    assertTrue(root.findViewById<View>(R.id.add_mint_button).isEnabled)
                }
                sheet.dismissNow()
                assertFalse(sheet.isAdded)
            }
        }
    }
}
