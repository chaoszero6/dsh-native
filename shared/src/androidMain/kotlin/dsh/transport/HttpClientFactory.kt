package dsh.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.util.concurrent.TimeUnit

actual fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
    followRedirects = false
    expectSuccess = false
    install(WebSockets)
    engine {
        config {
            connectTimeout(20, TimeUnit.SECONDS)
            readTimeout(0, TimeUnit.SECONDS) // streams and long-running RPCs (e.g. plugin installs)
            writeTimeout(120, TimeUnit.SECONDS)
            followRedirects(false)
            followSslRedirects(false)
            retryOnConnectionFailure(true)
        }
    }
}
