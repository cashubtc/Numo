package com.electricdreams.numo.ui.seed

import org.cashudevkit.FfiException
import org.cashudevkit.mnemonicToEntropy
import org.junit.rules.ExternalResource
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any

class CdkMnemonicRule : ExternalResource() {
    private lateinit var functions: MockedStatic<*>

    override fun before() {
        // Android CDK native code is unavailable in JVM unit tests.
        functions = mockStatic(Class.forName("org.cashudevkit.Cdk_ffiKt"))
        functions.`when`<ByteArray> { mnemonicToEntropy(any()) }
            .thenThrow(FfiException.Internal("Invalid mnemonic"))
    }

    fun accept(mnemonic: String) {
        functions.`when`<ByteArray> { mnemonicToEntropy(mnemonic) }
            .thenAnswer { ByteArray(16) }
    }

    fun verifyValidated(mnemonic: String) {
        functions.verify { mnemonicToEntropy(mnemonic) }
    }

    override fun after() {
        functions.close()
    }
}
