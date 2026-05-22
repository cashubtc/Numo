package com.electricdreams.numo.payment

import org.junit.Test
import org.cashudevkit.createBip321Uri

class Bip321Test {
    @Test
    fun testBip321() {
        val uri = createBip321Uri("addr", "lnbc123", "creq123")
        println("URI: \$uri")
    }
}
