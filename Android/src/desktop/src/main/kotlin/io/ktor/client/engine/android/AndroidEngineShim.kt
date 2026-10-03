package io.ktor.client.engine.android

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.CIOEngineConfig

val Android: HttpClientEngineFactory<CIOEngineConfig> = CIO
