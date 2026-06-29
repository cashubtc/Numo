#!/bin/bash
sed -i 's/unit = methodObj.optString("unit", "").let { if (it.contains("CurrencyUnit$Sat", ignoreCase = true)) "sat" else it },/unit = methodObj.optString("unit", "").let { if (it.contains("CurrencyUnit$")) it.substringAfter("CurrencyUnit$").substringBefore("@").lowercase() else it },/g' app/src/main/java/com/electricdreams/numo/core/cashu/CashuWalletManager.kt
