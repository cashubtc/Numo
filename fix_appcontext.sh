#!/bin/bash
sed -i 's/com.electricdreams.numo.core.cashu.CashuWalletManager.appContext/context/g' app/src/main/java/com/electricdreams/numo/payment/LightningMintHandler.kt
