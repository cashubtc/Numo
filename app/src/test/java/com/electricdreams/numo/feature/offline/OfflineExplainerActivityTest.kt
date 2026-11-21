package com.electricdreams.numo.feature.offline

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import androidx.core.view.children
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.R
import com.electricdreams.numo.core.network.ConnectionProblem
import com.electricdreams.numo.core.network.MintReachabilityMonitor
import com.electricdreams.numo.core.prefs.PreferenceStore
import com.electricdreams.numo.core.util.CurrencyManager
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.core.worker.BitcoinPriceWorker
import com.electricdreams.numo.databinding.ActivityOfflineExplainerBinding
import com.electricdreams.numo.databinding.ItemOfflineCapabilityBinding
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawSettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineExplainerActivityTest {

    private lateinit var context: Context
    private val problem = MutableStateFlow<ConnectionProblem?>(ConnectionProblem.OFFLINE)
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        val monitor = mock<MintReachabilityMonitor>()
        whenever(monitor.problem).thenReturn(problem)
        setSingleton(MintReachabilityMonitor::class.java, monitor)
        setSingleton(BitcoinPriceWorker::class.java, null)
        setSingleton(CurrencyManager::class.java, null)
        BitcoinPriceWorker.isTesting = true
        CurrencyManager.getInstance(context).setPreferredCurrency(CurrencyManager.CURRENCY_USD)
        context.getSharedPreferences("BitcoinPricePrefs", Context.MODE_PRIVATE).edit()
            .clear()
            .putFloat("btcPrice_USD", 50_000f)
            .putLong("lastUpdateTime_USD", 1_700_000_000_000L)
            .apply()
        PreferenceStore.app(context).putBoolean("btcpay_enabled", true)
        PreferenceStore.app(context).putString("btcpay_pos_app_id", "catalog")
        AutoWithdrawSettingsManager.getInstance(context).setGloballyEnabled(true)
        val accessibility = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as AccessibilityManager
        shadowOf(accessibility).setTouchExplorationEnabled(true)
    }

    @After
    fun tearDown() {
        BitcoinPriceWorker.getInstance(context).stop()
        BitcoinPriceWorker.isTesting = false
        setSingleton(BitcoinPriceWorker::class.java, null)
        setSingleton(MintReachabilityMonitor::class.java, null)
        CurrencyManager.getInstance(context).setCurrencyChangeListener(null)
        Dispatchers.resetMain()
    }

    @Test
    fun `TalkBack explainer restores offline rows and can celebrate the next reconnect`() {
        val intent = Intent(context, OfflineExplainerActivity::class.java)
            .putExtra("payment_on_screen", true)
        Robolectric.buildActivity(OfflineExplainerActivity::class.java, intent)
            .setup().visible().use { controller ->
                val activity = controller.get()
                val binding = binding(activity)
                assertOffline(activity, binding)

                problem.value = null
                finishAnimations()
                assertOnline(activity, binding)
                dispatcher.scheduler.advanceTimeBy(2_000L)
                dispatcher.scheduler.runCurrent()
                assertFalse(activity.isFinishing)

                problem.value = ConnectionProblem.OFFLINE
                finishAnimations()
                assertOffline(activity, binding)
                assertFalse(activity.isFinishing)

                problem.value = null
                finishAnimations()
                assertOnline(activity, binding)
                assertFalse(activity.isFinishing)
            }
    }

    @Test
    fun `TalkBack explainer celebrates reconnect after returning from network settings`() {
        Robolectric.buildActivity(OfflineExplainerActivity::class.java)
            .setup().visible().use { controller ->
                val activity = controller.get()
                assertOffline(activity, binding(activity))

                controller.pause().stop()
                problem.value = null
                controller.start().resume()
                finishAnimations()

                assertOnline(activity, binding(activity))
                dispatcher.scheduler.advanceTimeBy(2_000L)
                dispatcher.scheduler.runCurrent()
                assertFalse(activity.isFinishing)

                controller.pause().stop().start().resume()
                assertFalse(activity.isFinishing)
                problem.value = ConnectionProblem.OFFLINE
                finishAnimations()
                assertOffline(activity, binding(activity))
                problem.value = null
                finishAnimations()
                assertOnline(activity, binding(activity))
                assertFalse(activity.isFinishing)
            }
    }

    @Test
    fun `explainer opened while already online still closes with TalkBack enabled`() {
        problem.value = null
        Robolectric.buildActivity(OfflineExplainerActivity::class.java)
            .setup().use { controller ->
                assertTrue(controller.get().isFinishing)
            }
    }

    @Test
    fun `disconnect cancels pending online animations before restoring the explainer`() {
        Robolectric.buildActivity(OfflineExplainerActivity::class.java)
            .setup().visible().use { controller ->
                val activity = controller.get()
                val binding = binding(activity)

                problem.value = null
                problem.value = ConnectionProblem.OFFLINE
                finishAnimations()

                assertOffline(activity, binding)
                assertEquals(1f, binding.title.alpha)
                assertEquals(1f, binding.heroBadge.scaleX)
                assertFalse(activity.isFinishing)
            }
    }

    @Test
    fun `disconnect cancels automatic closing when TalkBack is off`() {
        val accessibility = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as AccessibilityManager
        shadowOf(accessibility).setTouchExplorationEnabled(false)
        Robolectric.buildActivity(OfflineExplainerActivity::class.java)
            .setup().visible().use { controller ->
                val activity = controller.get()
                problem.value = null
                problem.value = ConnectionProblem.OFFLINE
                dispatcher.scheduler.advanceTimeBy(2_000L)
                dispatcher.scheduler.runCurrent()
                finishAnimations()

                assertOffline(activity, binding(activity))
                assertFalse(activity.isFinishing)
            }
    }

    @Test
    fun `mint explainer names the mint and what needs it, then celebrates when it answers`() {
        problem.value = ConnectionProblem.MINT_UNREACHABLE
        Robolectric.buildActivity(OfflineExplainerActivity::class.java)
            .setup().visible().use { controller ->
                val activity = controller.get()
                val binding = binding(activity)
                val host = Uri.parse(
                    MintManager.getInstance(context).getPreferredLightningMint()
                ).host

                assertEquals(activity.getString(R.string.mint_explainer_title), binding.title.text)
                assertEquals(
                    activity.getString(R.string.mint_explainer_subtitle, host),
                    binding.subtitle.text
                )
                assertEquals(
                    listOf(
                        activity.getString(R.string.mint_explainer_group_needs_mint),
                        activity.getString(R.string.mint_explainer_group_still_works),
                    ),
                    binding.capabilityGroups.children
                        .map { it.findViewById<TextView>(R.id.group_title).text.toString() }
                        .toList()
                )
                val rows = rows(binding)
                assertRow(activity, rows.getValue(activity.getString(R.string.offline_feature_new_charges)),
                    R.string.offline_status_unavailable, R.drawable.ic_status_unavailable)
                assertRow(activity, rows.getValue(activity.getString(R.string.offline_feature_catalog)),
                    R.string.offline_status_available, R.drawable.ic_status_available)
                assertFalse(activity.getString(R.string.offline_feature_exchange_rate) in rows)

                problem.value = null
                finishAnimations()

                assertEquals(
                    activity.getString(R.string.mint_explainer_reconnected_title),
                    binding.title.text
                )
                assertFalse(activity.isFinishing)
            }
    }

    @Test
    fun `explainer switches to the offline explanation when the phone drops off the network`() {
        problem.value = ConnectionProblem.MINT_UNREACHABLE
        Robolectric.buildActivity(OfflineExplainerActivity::class.java)
            .setup().visible().use { controller ->
                val activity = controller.get()

                problem.value = ConnectionProblem.OFFLINE
                finishAnimations()

                assertOffline(activity, binding(activity))
            }
    }

    private fun assertOffline(
        activity: OfflineExplainerActivity,
        binding: ActivityOfflineExplainerBinding,
    ) {
        assertEquals(activity.getString(R.string.offline_explainer_title), binding.title.text)
        assertEquals(activity.getString(R.string.offline_explainer_subtitle), binding.subtitle.text)
        assertEquals(R.drawable.ic_status_degraded, shadowOf(binding.heroBadge.drawable).createdFromResId)
        val rows = rows(binding)
        listOf(
            R.string.offline_feature_new_charges,
            R.string.offline_feature_catalog,
            R.string.offline_feature_withdrawals,
        ).forEach { title ->
            assertRow(activity, rows.getValue(activity.getString(title)),
                R.string.offline_status_unavailable, R.drawable.ic_status_unavailable)
        }
        assertRow(activity, rows.getValue(activity.getString(R.string.offline_feature_auto_withdraw)),
            R.string.offline_status_paused, R.drawable.ic_status_unavailable)
        assertRow(activity, rows.getValue(activity.getString(R.string.offline_feature_history)),
            R.string.offline_status_available, R.drawable.ic_status_available)
        rows[activity.getString(R.string.offline_feature_payment_on_screen)]?.let { row ->
            assertRow(activity, row,
                R.string.offline_status_confirms_later, R.drawable.ic_status_degraded)
        }
        val rate = rows.getValue(activity.getString(R.string.offline_feature_exchange_rate))
        assertEquals(R.drawable.ic_status_degraded, shadowOf(rate.capabilityIcon.drawable).createdFromResId)
        assertEquals(
            activity.getString(R.string.offline_row_content_description_outdated,
                rate.capabilityTitle.text, rate.capabilityStatus.text),
            rate.root.contentDescription
        )
    }

    private fun assertOnline(
        activity: OfflineExplainerActivity,
        binding: ActivityOfflineExplainerBinding,
    ) {
        assertEquals(activity.getString(R.string.offline_explainer_back_online_title), binding.title.text)
        assertEquals(activity.getString(R.string.offline_explainer_back_online_subtitle), binding.subtitle.text)
        assertEquals(R.drawable.ic_status_available, shadowOf(binding.heroBadge.drawable).createdFromResId)
        rows(binding).values.forEach { row ->
            val title = row.capabilityTitle.text.toString()
            val updating = title == activity.getString(R.string.offline_feature_exchange_rate) ||
                title == activity.getString(R.string.offline_feature_payment_on_screen)
            assertRow(activity, row,
                if (updating) R.string.offline_status_updating else R.string.offline_status_available,
                R.drawable.ic_status_available)
        }
    }

    private fun assertRow(
        activity: OfflineExplainerActivity,
        row: ItemOfflineCapabilityBinding,
        status: Int,
        icon: Int,
    ) {
        assertEquals(activity.getString(status), row.capabilityStatus.text)
        assertEquals(icon, shadowOf(row.capabilityIcon.drawable).createdFromResId)
        assertEquals(
            activity.getString(R.string.offline_row_content_description,
                row.capabilityTitle.text, row.capabilityStatus.text),
            row.root.contentDescription
        )
    }

    private fun rows(binding: ActivityOfflineExplainerBinding): Map<String, ItemOfflineCapabilityBinding> =
        binding.capabilityGroups.children.flatMap { group ->
            group.findViewById<ViewGroup>(R.id.group_rows).children
        }.map { ItemOfflineCapabilityBinding.bind(it) }
            .associateBy { it.capabilityTitle.text.toString() }

    private fun binding(activity: OfflineExplainerActivity): ActivityOfflineExplainerBinding =
        ActivityOfflineExplainerBinding.bind(
            activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        )

    private fun finishAnimations() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
    }

    private fun setSingleton(type: Class<*>, value: Any?) {
        type.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, value)
        }
    }
}
