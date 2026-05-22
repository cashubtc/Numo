package com.electricdreams.numo.payment

import org.junit.Test
import org.junit.Assert.*
import org.cashudevkit.createBip321Uri

class Bip321Test {
    @Test
    fun testBip321() {
        try {
            val uri = createBip321Uri("creqA123", null, null)
            println("URI: \$uri")
        } catch (e: Throwable) {
            println("ERROR: \${e.message}")
        }
    }
}
