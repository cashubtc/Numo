package com.electricdreams.numo.ui.seed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Bip39MnemonicTest {
    @Test
    fun `standard entropy vectors have valid checksums`() {
        assertTrue(Bip39Wordlist.isValidMnemonic(List(11) { "abandon" }.plus("about").joinToString(" ")))
        assertTrue(Bip39Wordlist.isValidMnemonic(List(23) { "abandon" }.plus("art").joinToString(" ")))
        assertTrue(Bip39Wordlist.isValidMnemonic(List(11) { "zoo" }.plus("wrong").joinToString(" ")))
    }

    @Test
    fun `known words with incorrect checksum are rejected`() {
        assertFalse(Bip39Wordlist.isValidMnemonic(List(12) { "abandon" }.joinToString(" ")))
    }

    @Test
    fun `unknown words and unsupported lengths are rejected`() {
        assertFalse(Bip39Wordlist.isValidMnemonic(List(11) { "abandon" }.joinToString(" ")))
        assertFalse(Bip39Wordlist.isValidMnemonic(List(11) { "abandon" }.plus("invalidword").joinToString(" ")))
    }

    @Test
    fun `capitalization and whitespace are normalized`() {
        assertTrue(Bip39Wordlist.isValidMnemonic("  " + List(11) { "ABANDON" }
            .plus("ABOUT").joinToString("\n ") + "  "))
    }
}
