#!/bin/bash
sed -i 's/verify(secondaryAmountDisplay).text = "₿10"/verify(secondaryAmountDisplay).text = "10 sat"/g' app/src/test/java/com/electricdreams/numo/ui/components/AmountDisplayManagerTest.kt
