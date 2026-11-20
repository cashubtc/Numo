package com.electricdreams.numo.ui.seed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Bip39UnambiguousWordTest {

    @Test
    fun `valid word that prefixes no other word is unambiguous`() {
        assertTrue(Bip39Wordlist.isUnambiguousWord("abandon"))
        assertTrue(Bip39Wordlist.isUnambiguousWord("action"))
        assertTrue(Bip39Wordlist.isUnambiguousWord("zoo"))
    }

    @Test
    fun `valid word that prefixes other words is ambiguous`() {
        // "act" -> "action", "actor", ...; "add" -> "addict", "address"
        assertFalse(Bip39Wordlist.isUnambiguousWord("act"))
        assertFalse(Bip39Wordlist.isUnambiguousWord("add"))
        assertFalse(Bip39Wordlist.isUnambiguousWord("win"))
    }

    @Test
    fun `partial or invalid words are not complete`() {
        assertFalse(Bip39Wordlist.isUnambiguousWord("aban"))
        assertFalse(Bip39Wordlist.isUnambiguousWord("notaword"))
        assertFalse(Bip39Wordlist.isUnambiguousWord(""))
    }

    @Test
    fun `uppercase input is normalized`() {
        assertTrue(Bip39Wordlist.isUnambiguousWord("Abandon"))
    }
}
