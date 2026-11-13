package com.electricdreams.numo.feature.history

import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.R
import com.electricdreams.numo.databinding.ActivityHistoryBinding
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryEmptyStateLayoutTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.getSharedPreferences("PaymentHistory", Context.MODE_PRIVATE).edit().clear().commit()
        PaymentsHistoryActivity.ioDispatcher = Dispatchers.Unconfined
    }

    @After
    fun tearDown() {
        PaymentsHistoryActivity.ioDispatcher = Dispatchers.IO
    }

    @Test
    fun `empty and filtered history center in the visible viewport and restore scrolling`() {
        val controller = Robolectric.buildActivity(PaymentsHistoryActivity::class.java).setup()
        try {
            val activity = controller.get()
            val binding = ActivityHistoryBinding.bind(
                activity.findViewById<View>(R.id.history_coordinator).parent as View)

            fun layout() {
                shadowOf(Looper.getMainLooper()).idle()
                repeat(2) {
                    binding.root.measure(
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
                    )
                    binding.root.layout(0, 0, 1080, 2000)
                }
            }

            fun checkEmptyCenter() {
                layout()
                assertEquals(View.VISIBLE, binding.emptyView.root.visibility)
                val bounds = Rect()
                binding.emptyView.root.getDrawingRect(bounds)
                binding.historyCoordinator.offsetDescendantRectToMyCoords(
                    binding.emptyView.root, bounds)
                val visibleCenter = (binding.historyAppBar.bottom +
                    binding.historyCoordinator.height) / 2f
                assertEquals(visibleCenter, bounds.exactCenterY(), 1f)
                assertTrue(bounds.bottom <= binding.historyCoordinator.height)
            }

            checkEmptyCenter()
            PaymentsHistoryActivity.addPendingPayment(
                context, 100, "sat", 100, null, null, null)
            activity.applyHistoryFilter(HistoryFilter())
            layout()
            assertEquals(View.GONE, binding.emptyView.root.visibility)
            assertTrue(binding.historyAppBar.totalScrollRange > 0)

            activity.applyHistoryFilter(HistoryFilter(status = HistoryStatusFilter.PAID))
            checkEmptyCenter()
            assertEquals(activity.getString(R.string.history_empty_filtered_title),
                binding.emptyView.emptyStateTitle.text.toString())

            activity.applyHistoryFilter(HistoryFilter())
            layout()
            assertTrue(binding.historyAppBar.totalScrollRange > 0)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
