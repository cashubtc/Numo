#!/bin/bash
cat app/src/main/java/com/electricdreams/numo/feature/history/TransactionDetailActivity.kt | sed 's/basket.getFiatGrossTotalCents() + val btcPrice = entry.bitcoinPrice/val btcPriceForFiat = entry.bitcoinPrice\n                basket.getFiatGrossTotalCents() + /g' | sed 's/if (btcPrice != null \&\& btcPrice > 0) {/if (btcPriceForFiat != null \&\& btcPriceForFiat > 0) {/g' > temp.kt
mv temp.kt app/src/main/java/com/electricdreams/numo/feature/history/TransactionDetailActivity.kt
