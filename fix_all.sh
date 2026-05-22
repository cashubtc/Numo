#!/bin/bash
sed -i 's/context(/com.electricdreams.numo.core.cashu.CashuWalletManager.getCurrencyUnit(/g' app/src/main/java/com/electricdreams/numo/ndef/CashuPaymentHelper.kt
sed -i 's/context(/com.electricdreams.numo.core.cashu.CashuWalletManager.getCurrencyUnit(/g' app/src/main/java/com/electricdreams/numo/payment/SwapToLightningMintManager.kt
sed -i 's/context(/com.electricdreams.numo.core.cashu.CashuWalletManager.getCurrencyUnit(/g' app/src/main/java/com/electricdreams/numo/ui/adapter/PaymentsHistoryAdapter.kt
