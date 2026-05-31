#!/bin/bash
sed -i 's/"1,000 ₿"/"1,000 sat"/g' app/src/test/java/com/electricdreams/numo/feature/tips/TipSelectionActivityTest.kt
