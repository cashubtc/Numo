#!/bin/bash
sed -i 's/10,000 sat/10,000 sat/g' app/src/test/java/com/electricdreams/numo/core/util/ReceiptPrinterTest.kt
sed -i 's/10,000/10,000 sat/g' app/src/test/java/com/electricdreams/numo/feature/items/handlers/VatCalculatorTest.kt
