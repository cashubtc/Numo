#!/bin/bash
sed -i 's/"${formatter.format(value)} ${currency.symbol}"/"${currency.symbol}${formatter.format(value)}"/g' app/src/main/java/com/electricdreams/numo/core/model/Amount.kt
sed -i 's/"${formatAbbreviated(sats, currency.getLocale())} ${currency.symbol}"/"${currency.symbol}${formatAbbreviated(sats, currency.getLocale())}"/g' app/src/main/java/com/electricdreams/numo/core/model/Amount.kt
