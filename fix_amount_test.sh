#!/bin/bash
sed -i 's/verify(amountDisplay).text = "¥1"/verify(amountDisplay).text = "¥1"/g' app/src/test/java/com/electricdreams/numo/ui/components/AmountDisplayManagerTest.kt
