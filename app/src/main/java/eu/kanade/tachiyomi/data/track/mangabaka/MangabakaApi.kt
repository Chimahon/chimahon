package eu.kanade.tachiyomi.data.track.mangabaka

import android.net.Uri
import androidx.core.net.toUri
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangaBakaItem
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangaBakaItemResult
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangaBakaListResult
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangaBakaOAuth
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangaBakaSearchResult
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangaBakaUserProfile
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangaBakaUserProfileResponse
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.DELETE
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.PUT
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.util.PkceUtil
import eu.kanade.tachiyomi.util.lang.toLocalDate
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds
import okhttp3.Headers.Companion.headersOf
import okhttp3.Interceptor
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy
import java.math.RoundingMode
import java.security.SecureRandom
import java.util.Base64
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import tachiyomi.domain.track.model.Track as DomainTrack

class MangaBakaApi(
    private val trackId: Long,
    baseClient: OkHttpClient,
    interceptor: MangaBakaInterceptor,
) {

    private val json: Json by injectLazy()

    private val userAgentInterceptor = Interceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .header(
                    "User-Agent",
                    buildString {
                        append("${MR.strings.app_name}/v${BuildConfig.VERSION_NAME} ")
                        append("(${BuildConfig.APPLICATION_ID} ${BuildConfig.COMMIT_SHA}) ")
                        append("(Android) (https://github.com/Chimahon/chimahon)")
                    },
                )
                .build(),
        )
    }

    private val client = baseClient.newBuilder()
        .addInterceptor(userAgentInterceptor)
        .rateLimitHost("https://api.mangabaka.org", permits = 1, period = 2.seconds)
        .build()

    private val authClient = baseClient.newBuilder()
        .addInterceptor(userAgentInterceptor)
        .addInterceptor(interceptor)
        .rateLimitHost("https://api.mangabaka.org", permits = 1, period = 1.seconds)
        .build()

    suspend fun getCurrentUser(): MangaBakaUserProfile {
        return withIOContext {
            with(json) {
                retryOn429 {
                    authClient.newCall(GET("$API_BASE_URL/v1/my/profile"))
                        .awaitSuccess()
                        .parseAs<MangaBakaUserProfileResponse>()
                        .data
                }
            }
        }
    }

    suspend fun getAccessToken(code: String): MangaBakaOAuth {
        return withIOContext {
            with(json) {
                client.newCall(
                    POST(
                        "$OAUTH_URL/token",
                        body = FormBody.Builder()
                            .add("client_id", CLIENT_ID)
                            .add("code", code)
                            .add("code_verifier", codeVerifier)
                            .add("code_challenge_method", "S256")
                            .add("grant_type", "authorization_code")
                            .add("redirect_uri", REDIRECT_URI)
                            .add("scope", SCOPES)
                            .build(),
                    ),
                )
                    .awaitSuccess()
                    .parseAs<MangaBakaOAuth>()
                    .withCalculatedExpiry()
            }
        }
    }

    suspend fun addLibManga(track: Track): Track {
        return withIOContext {
            val url = "$LIBRARY_API_URL/${track.remote_id}"
            val body = buildJsonObject {
                put("is_private", track.private)
                put("state", track.toApiStatus())
                if (track.last_chapter_read > 0.0) {
                    put("progress_chapter", track.last_chapter_read)
                }
                if (track.score > 0) {
                    put("rating", track.score.toInt().coerceIn(0, 100))
                }
                if (track.started_reading_date > 0) {
                    put("start_date", track.started_reading_date.toLocalDate().toString())
                }
                if (track.finished_reading_date > 0) {
                    put("finish_date", track.finished_reading_date.toLocalDate().toString())
                }
            }
                .toString()
                .toRequestBody()

            retryOn429 {
                authClient
                    .newCall(POST(url, body = body, headers = headersOf("Content-Type", APP_JSON)))
                    .awaitSuccess()
            }

            track
        }
    }

    suspend fun deleteLibManga(track: DomainTrack) {
        withIOContext {
            retryOn429 {
                authClient
                    .newCall(DELETE("$LIBRARY_API_URL/${track.remoteId}"))
                    .awaitSuccess()
            }
        }
    }

    suspend fun findLibManga(track: Track): Track? {
        return withIOContext {
            with(json) {
                try {
                    val userData = retryOn429 {
                        authClient.newCall(GET("$LIBRARY_API_URL/${track.remote_id}"))
                            .awaitSuccess()
                            .parseAs<MangaBakaListResult>()
                            .data
                    }

                    val additionalData = retryOn429 {
                        authClient.newCall(GET("$API_BASE_URL/v1/series/${track.remote_id}"))
                            .awaitSuccess()
                            .parseAs<MangaBakaItemResult>()
                            .data
                    }

                    Track.create(TrackerManager.MANGABAKA).apply {
                        remote_id = track.remote_id
                        title = additionalData.title
                        status = userData.getStatus()
                        score = userData.rating?.toDouble() ?: 0.0
                        started_reading_date = parseApiDateMillis(userData.startDate)
                        finished_reading_date = parseApiDateMillis(userData.finishDate)
                        last_chapter_read = userData.progressChapter ?: 0.0
                        private = userData.isPrivate
                    }
                } catch (e: HttpException) {
                    if (e.code == 404) {
                        null
                    } else {
                        throw e
                    }
                }
            }
        }
    }

    suspend fun updateLibManga(track: Track): Track {
        return withIOContext {
            val url = "$LIBRARY_API_URL/${track.remote_id}"
            val body = buildJsonObject {
                put("state", track.toApiStatus())
                put("is_private", track.private)
                if (track.last_chapter_read > 0.0) {
                    put("progress_chapter", track.last_chapter_read)
                }
                if (track.score > 0) {
                    put("rating", track.score.toInt().coerceIn(0, 100))
                }
                if (track.started_reading_date > 0) {
                    put("start_date", track.started_reading_date.toLocalDate().toString())
                }
                if (track.finished_reading_date > 0) {
                    put("finish_date", track.finished_reading_date.toLocalDate().toString())
                }
            }
                .toString()
                .toRequestBody()

            retryOn429 {
                authClient
                    .newCall(PUT(url, body = body, headers = headersOf("Content-Type", APP_JSON)))
                    .awaitSuccess()
            }

            track
        }
    }

    suspend fun search(search: String): List<TrackSearch> {
        return withIOContext {
            val url = "$API_BASE_URL/v1/series/search".toUri().buildUpon()
                .appendQueryParameter("q", search)
                .appendQueryParameter("type_not", "novel")
                .build()
            with(json) {
                retryOn429 {
                    client.newCall(GET(url.toString()))
                        .awaitSuccess()
                        .parseAs<MangaBakaSearchResult>()
                        .data
                        .map { parseSearchItem(it) }
                }
            }
        }
    }

    private fun parseSearchItem(item: MangaBakaItem): TrackSearch {
        return TrackSearch.create(trackId).apply {
            remote_id = item.id
            title = item.chooseBestTitle()
            summary = item.description?.trim().orEmpty()
            score = item.rating?.toBigDecimal()?.setScale(2, RoundingMode.HALF_UP)?.toDouble() ?: -1.0
            cover_url = item.cover.x250.x1.orEmpty()
            tracking_url = "$BASE_URL/${item.id}"
            start_date = item.published.startDate.orEmpty()
            publishing_status = item.status
            publishing_type = item.type.replaceFirstChar { c ->
                if (c.isLowerCase()) c.titlecase(Locale.getDefault()) else c.toString()
            }
            authors = item.authors.orEmpty()
            artists = item.artists.orEmpty()
        }
    }

    suspend fun getMangaDetails(id: Long): TrackSearch? {
        return withIOContext {
            val url = "$API_BASE_URL/v1/series/$id"
            with(json) {
                try {
                    retryOn429 {
                        client.newCall(GET(url))
                            .awaitSuccess()
                            .parseAs<MangaBakaItemResult>()
                            .data
                            .let { parseSearchItem(it) }
                    }
                } catch (e: HttpException) {
                    if (e.code == 404) {
                        return@with null
                    }
                    throw e
                }
            }
        }
    }

    private suspend fun <T> retryOn429(block: suspend () -> T): T {
        var attempts = 0
        while (true) {
            try {
                return block()
            } catch (e: HttpException) {
                if (e.code != 429 || ++attempts >= 3) throw e
                delay(5000L)
            }
        }
    }

    companion object {
        private const val BASE_URL = "https://mangabaka.org"
        private const val API_BASE_URL = "https://api.mangabaka.org"
        private const val LIBRARY_API_URL = "$API_BASE_URL/v1/my/library"
        private const val OAUTH_URL = "$BASE_URL/auth/oauth2"
        private const val SCOPES = "library.read library.write offline_access openid"
        private const val CLIENT_ID = "DCNWrlKfvBgtOJzxquvnozGRZFkWpVGp"
        private const val REDIRECT_URI = "chimahon://mangabaka-auth"
        private const val APP_JSON = "application/json"

        private var codeVerifier = ""
        private var oauthStateParam = ""

        fun authUrl(): Uri = "$OAUTH_URL/authorize".toUri().buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("code_challenge", getPkceS256ChallengeCode())
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("state", getOAuthStateParam())
            .build()

        fun isValidOAuthState(state: String?): Boolean = state != null && state == oauthStateParam

        fun refreshTokenRequest(refreshToken: String) = POST(
            "$OAUTH_URL/token",
            body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", CLIENT_ID)
                .add("refresh_token", refreshToken)
                .add("redirect_uri", REDIRECT_URI)
                .build(),
        )

        private fun getOAuthStateParam(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            oauthStateParam = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            return oauthStateParam
        }

        private fun getPkceS256ChallengeCode(): String {
            val codes = PkceUtil.generateS256Codes()
            codeVerifier = codes.codeVerifier
            return codes.codeChallenge
        }

        private fun parseApiDateMillis(value: String?): Long {
            val text = value?.takeIf { it.isNotBlank() } ?: return 0

            return runCatching {
                LocalDate.parse(text)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
            }.getOrElse {
                runCatching {
                    Instant.parse(text).toEpochMilli()
                }.getOrElse {
                    runCatching {
                        OffsetDateTime.parse(text).toInstant().toEpochMilli()
                    }.getOrDefault(0L)
                }
            }
        }
    }
}
