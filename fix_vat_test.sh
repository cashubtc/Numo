#!/bin/bash
sed -i 's/"sat1,000"/"1,000 sat"/g' app/src/test/java/com/electricdreams/numo/feature/items/handlers/VatCalculatorTest.kt
sed -i 's/"sat100"/"100 sat"/g' app/src/test/java/com/electricdreams/numo/feature/items/handlers/VatCalculatorTest.kt
sed -i 's/"sat1,100"/"1,100 sat"/g' app/src/test/java/com/electricdreams/numo/feature/items/handlers/VatCalculatorTest.kt
