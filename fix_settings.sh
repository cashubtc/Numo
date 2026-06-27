#!/bin/bash
sed -i 's/unit = methodObj.optString("unit", ""),/unit = methodObj.optString("unit", "").let { if (it.contains("CurrencyUnit$Sat", ignoreCase = true)) "sat" else it },/g' app/src/main/java/com/electricdreams/numo/core/cashu/CashuWalletManager.kt
