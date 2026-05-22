#!/bin/bash
sed -i 's/val mockMintManager = mock(com.electricdreams.numo.core.util.MintManager::class.java)/val mockMintManager = mock(com.electricdreams.numo.core.util.MintManager::class.java)\n        mintManagerStaticMock = mockStatic(com.electricdreams.numo.core.util.MintManager::class.java)/g' app/src/test/java/com/electricdreams/numo/payment/LightningMintHandlerTest.kt
sed -i 's/val mintManagerStaticMock = mockStatic(com.electricdreams.numo.core.util.MintManager::class.java)//g' app/src/test/java/com/electricdreams/numo/payment/LightningMintHandlerTest.kt
