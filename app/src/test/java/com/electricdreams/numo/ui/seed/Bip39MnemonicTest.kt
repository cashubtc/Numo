package com.electricdreams.numo.ui.seed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Bip39MnemonicTest {
    @get:Rule
    val mnemonicValidation = CdkMnemonicRule()

    private val mnemonic = List(11) { "abandon" }.plus("about").joinToString(" ")

    @Test
    fun `CDK acceptance allows the phrase`() {
        mnemonicValidation.accept(mnemonic)
        assertTrue(Bip39Wordlist.isValidMnemonic(mnemonic))
        mnemonicValidation.verifyValidated(mnemonic)
    }

    @Test
    fun `CDK rejection prevents accepting the phrase`() {
        assertFalse(Bip39Wordlist.isValidMnemonic(mnemonic))
        mnemonicValidation.verifyValidated(mnemonic)
    }

    @Test
    fun `phrase is normalized before CDK validation`() {
        mnemonicValidation.accept(mnemonic)
        assertTrue(Bip39Wordlist.isValidMnemonic("  " + List(11) { "ABANDON" }
            .plus("ABOUT").joinToString("\n ") + "  "))
        mnemonicValidation.verifyValidated(mnemonic)
    }
}
