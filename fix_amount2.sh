#!/bin/bash
sed -i 's/basket.getFiatGrossTotalCents() + val btcPrice = entry.bitcoinPrice\n            if (btcPrice != null \&\& btcPrice > 0) {/val btcPriceForFiat = entry.bitcoinPrice\n                basket.getFiatGrossTotalCents() + if (btcPriceForFiat != null \&\& btcPriceForFiat > 0) {/g' app/src/main/java/com/electricdreams/numo/feature/history/TransactionDetailActivity.kt
