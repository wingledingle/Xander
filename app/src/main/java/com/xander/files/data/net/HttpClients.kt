package com.xander.files.data.net

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object HttpClients {
    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val trustAll: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun forConnection(user: String, password: String, trustSelfSigned: Boolean, timeoutSec: Int = 20): OkHttpClient {
        val b = shared.newBuilder()
            .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSec * 3L, TimeUnit.SECONDS)
            .writeTimeout(timeoutSec * 3L, TimeUnit.SECONDS)
        if (user.isNotEmpty()) {
            val auth = HttpAuth(user, password)
            b.addInterceptor(auth).authenticator(auth)
        }
        if (trustSelfSigned) {
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), SecureRandom()) }
            b.sslSocketFactory(ctx.socketFactory, trustAll).hostnameVerifier { _, _ -> true }
        }
        return b.build()
    }
}

/**
 * Basic + Digest (RFC 7616, MD5, qop=auth). After the first challenge the scheme is remembered and every
 * request is authorised up front, so one-shot streaming uploads are never replayed after a 401.
 */
class HttpAuth(private val user: String, private val password: String) : Interceptor, Authenticator {
    @Volatile private var digest: Map<String, String>? = null
    @Volatile private var basic = false
    private var nc = 0

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.header("Authorization") != null) return chain.proceed(req)
        val header = when {
            digest != null -> digestHeader(digest!!, req)
            basic -> Credentials.basic(user, password, Charsets.UTF_8)
            else -> null
        }
        return chain.proceed(if (header != null) req.newBuilder().header("Authorization", header).build() else req)
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 3) return null
        val challenges = response.headers("WWW-Authenticate")
        val d = challenges.firstOrNull { it.startsWith("Digest", true) }
        if (d != null) {
            val params = Regex("(\\w+)=(?:\"([^\"]*)\"|([^,\\s]*))").findAll(d.substringAfter(' '))
                .associate { it.groupValues[1].lowercase() to it.groupValues[2].ifEmpty { it.groupValues[3] } }
            if (params["realm"] == null || params["nonce"] == null) return null
            // A stale nonce re-challenge is fine; a repeated challenge with the same nonce means bad credentials.
            if (digest?.get("nonce") == params["nonce"] && params["stale"] != "true") return null
            digest = params; nc = 0
            return response.request.newBuilder().header("Authorization", digestHeader(params, response.request)).build()
        }
        if (challenges.any { it.startsWith("Basic", true) } || challenges.isEmpty()) {
            if (response.request.header("Authorization") != null) return null
            basic = true
            return response.request.newBuilder().header("Authorization", Credentials.basic(user, password, Charsets.UTF_8)).build()
        }
        return null
    }

    @Synchronized
    private fun digestHeader(p: Map<String, String>, req: Request): String {
        val realm = p["realm"]!!; val nonce = p["nonce"]!!
        val qop = p["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
        val uri = req.url.encodedPath + (req.url.encodedQuery?.let { "?$it" } ?: "")
        fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        val ha1 = md5("$user:$realm:$password")
        val ha2 = md5("${req.method}:$uri")
        val cnonce = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val ncs = String.format("%08x", ++nc)
        val resp = if (qop != null) md5("$ha1:$nonce:$ncs:$cnonce:$qop:$ha2") else md5("$ha1:$nonce:$ha2")
        return buildString {
            append("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$resp\"")
            p["opaque"]?.let { append(", opaque=\"$it\"") }
            if (qop != null) append(", qop=$qop, nc=$ncs, cnonce=\"$cnonce\"")
            append(", algorithm=MD5")
        }
    }

    private fun responseCount(r: Response): Int { var n = 1; var p = r.priorResponse; while (p != null) { n++; p = p.priorResponse }; return n }
}
