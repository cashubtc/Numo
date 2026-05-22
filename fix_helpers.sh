#!/bin/bash
sed -i 's/com.electricdreams.numo.core.cashu.CashuWalletManager.toCurrencyUnit/com.electricdreams.numo.core.cashu.CashuWalletManager.getCurrencyUnit/g' app/src/main/java/com/electricdreams/numo/ndef/CashuPaymentHelper.kt
sed -i 's/com.electricdreams.numo.core.cashu.CashuWalletManager.toCurrencyUnit/com.electricdreams.numo.core.cashu.CashuWalletManager.getCurrencyUnit/g' app/src/main/java/com/electricdreams/numo/payment/SwapToLightningMintManager.kt
