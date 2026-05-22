#!/bin/bash
sed -i 's/val satsInFiat = ((basket.getSatsDirectTotal().toDouble() \/ 100_000_000.0) \* entry.bitcoinPrice!! \* 100).toLong()/val satsInFiat = ((basket.getSatsDirectTotal().toDouble() \/ 100_000_000.0) \* entry.bitcoinPrice \* 100).toLong()/g' app/src/main/java/com/electricdreams/numo/feature/history/TransactionDetailActivity.kt
