package chimahon.novel.opds

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.network.awaitSuccess
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Keep private OPDS feed keys out of optional HTTP request logging. */
internal object OpdsNetwork {
    val client: OkHttpClient by lazy {
        Injekt.get<NetworkHelper>().client.newBuilder().apply {
            interceptors().removeAll { it is HttpLoggingInterceptor }
            networkInterceptors().removeAll { it is HttpLoggingInterceptor }
        }.build()
    }

    suspend fun <T> read(call: Call, block: suspend (Response) -> T): T = coroutineScope {
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.awaitSuccess().use { block(it) }
        } finally {
            cancellation.cancel()
        }
    }

    suspend fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "OPDS response is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
