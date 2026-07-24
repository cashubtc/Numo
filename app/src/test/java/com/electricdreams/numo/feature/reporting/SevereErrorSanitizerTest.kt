package com.electricdreams.numo.feature.reporting

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SevereErrorSanitizerTest {
    @Test
    fun `sanitizer excludes messages and non-app frames`() {
        val throwable = IllegalStateException(
            "secret wallet message",
            IllegalArgumentException("secret cause")
        ).apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "com.electricdreams.numo.feature.payment.PaymentHandler",
                    "processPayment",
                    "PaymentHandler.kt",
                    42
                ),
                StackTraceElement("okhttp3.RealCall", "execute", "RealCall.kt", 100)
            )
            cause?.stackTrace = arrayOf(
                StackTraceElement(
                    "com.electricdreams.numo.ModernPOSActivity",
                    "onCreate",
                    "ModernPOSActivity.kt",
                    15
                )
            )
        }

        val report = SevereErrorSanitizer.sanitize(throwable, 123L)
        val serialized = Gson().toJson(report)

        assertEquals(listOf("IllegalStateException", "IllegalArgumentException"), report.exceptionTypes)
        assertEquals(2, report.appFrames.size)
        assertTrue(report.appFrames.all { it.className.startsWith("com.electricdreams.numo.") })
        assertFalse(serialized.contains("secret wallet message"))
        assertFalse(serialized.contains("secret cause"))
        assertFalse(serialized.contains("okhttp3"))
        assertFalse(serialized.contains("PaymentHandler.kt"))
    }

    @Test
    fun `sanitizer limits app frames`() {
        val throwable = RuntimeException().apply {
            stackTrace = Array(30) { index ->
                StackTraceElement(
                    "com.electricdreams.numo.Class$index",
                    "method$index",
                    "Class.kt",
                    index + 1
                )
            }
        }

        val report = SevereErrorSanitizer.sanitize(throwable)

        assertEquals(12, report.appFrames.size)
    }
}
