package com.xander.files.data.cloud

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.xander.files.BuildConfig
import com.xander.files.data.net.HttpClients
import com.xander.files.domain.fs.FsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ClientSecretPost
import net.openid.appauth.ResponseTypeValues
import okhttp3.FormBody
import okhttp3.Request
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

data class OAuthConfig(
    val service: CloudService,
    val authorize: String,
    val token: String,
    val clientId: String,
    val clientSecret: String?,
    val scopes: List<String>,
    val extraParams: Map<String, String> = emptyMap(),
)

object OAuthConfigs {
    const val REDIRECT = "com.xander.files:/oauth2redirect"

    fun of(s: CloudService): OAuthConfig? = when (s) {
        CloudService.DROPBOX -> OAuthConfig(s, "https://www.dropbox.com/oauth2/authorize", "https://api.dropboxapi.com/oauth2/token",
            BuildConfig.DROPBOX_APP_KEY, null, emptyList(), mapOf("token_access_type" to "offline"))
        CloudService.ONEDRIVE -> OAuthConfig(s, "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
            "https://login.microsoftonline.com/common/oauth2/v2.0/token", BuildConfig.ONEDRIVE_CLIENT_ID, null,
            listOf("offline_access", "Files.ReadWrite.All", "User.Read"))
        CloudService.BOX -> OAuthConfig(s, "https://account.box.com/api/oauth2/authorize", "https://api.box.com/oauth2/token",
            BuildConfig.BOX_CLIENT_ID, BuildConfig.BOX_CLIENT_SECRET, emptyList())
        CloudService.YANDEX -> OAuthConfig(s, "https://oauth.yandex.com/authorize", "https://oauth.yandex.com/token",
            BuildConfig.YANDEX_CLIENT_ID, null, listOf("cloud_api:disk.read", "cloud_api:disk.write", "cloud_api:disk.info", "login:email", "login:info"),
            mapOf("force_confirm" to "yes"))
        else -> null
    }

    fun isConfigured(s: CloudService): Boolean = when (s) {
        CloudService.GOOGLE_DRIVE -> true // Android OAuth client is matched by package + signing certificate.
        CloudService.MEGA, CloudService.NEXTCLOUD -> true
        else -> of(s)?.clientId?.isNotBlank() == true
    }
}

/** Builds the AppAuth intent and exchanges the code (PKCE; Box adds its client secret). */
class AppAuthFlow(private val context: Context) {
    private val service = AuthorizationService(context)

    fun intent(cfg: OAuthConfig): Intent {
        val sc = AuthorizationServiceConfiguration(Uri.parse(cfg.authorize), Uri.parse(cfg.token))
        val req = net.openid.appauth.AuthorizationRequest.Builder(sc, cfg.clientId, ResponseTypeValues.CODE, Uri.parse(OAuthConfigs.REDIRECT))
            .apply { if (cfg.scopes.isNotEmpty()) setScopes(cfg.scopes) }
            .setAdditionalParameters(cfg.extraParams)
            .build()
        return service.getAuthorizationRequestIntent(req)
    }

    suspend fun exchange(cfg: OAuthConfig, data: Intent): CloudCredentials {
        val resp = net.openid.appauth.AuthorizationResponse.fromIntent(data)
        val ex = net.openid.appauth.AuthorizationException.fromIntent(data)
        if (resp == null) throw FsException.Generic(ex?.errorDescription ?: ex?.error ?: "Sign-in cancelled")
        val tokenReq = resp.createTokenExchangeRequest()
        return suspendCoroutine { cont ->
            val cb = AuthorizationService.TokenResponseCallback { t, e ->
                if (t?.accessToken != null) cont.resume(CloudCredentials(t.accessToken!!, t.refreshToken.orEmpty(), t.accessTokenExpirationTime ?: (System.currentTimeMillis() + 3_600_000)))
                else cont.resumeWithException(FsException.Generic(e?.errorDescription ?: "Token exchange failed"))
            }
            if (cfg.clientSecret.isNullOrBlank()) service.performTokenRequest(tokenReq, cb)
            else service.performTokenRequest(tokenReq, ClientSecretPost(cfg.clientSecret), cb)
        }
    }

    fun dispose() = service.dispose()
}

/** Refresh-token based token source for Dropbox, OneDrive, Box and Yandex. */
class OAuthTokenSource(
    override val accountId: String,
    private val repo: CloudAccountRepository,
    private val cfg: OAuthConfig,
) : TokenSource {
    private val lock = Mutex()

    override suspend fun token(forceRefresh: Boolean): String = lock.withLock {
        val creds = repo.get(accountId).credentials
        if (!forceRefresh && creds.accessToken.isNotEmpty() && creds.expiresAt - 60_000 > System.currentTimeMillis()) return creds.accessToken
        if (creds.refreshToken.isEmpty()) throw FsException.AuthRequired(accountId)
        val form = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", creds.refreshToken).add("client_id", cfg.clientId)
        if (!cfg.clientSecret.isNullOrBlank()) form.add("client_secret", cfg.clientSecret)
        if (cfg.service == CloudService.ONEDRIVE) form.add("scope", cfg.scopes.joinToString(" "))
        val json = withContext(Dispatchers.IO) {
            HttpClients.shared.newCall(Request.Builder().url(cfg.token).post(form.build()).build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (r.code == 400 || r.code == 401) throw FsException.AuthRequired(accountId)
                if (!r.isSuccessful) throw FsException.NetworkLost(java.io.IOException("Token refresh failed (${r.code})"))
                cloudJson.parseToJsonElement(body).jsonObject
            }
        }
        val updated = creds.copy(
            accessToken = json.str("access_token") ?: throw FsException.AuthRequired(accountId),
            refreshToken = json.str("refresh_token") ?: creds.refreshToken,
            expiresAt = System.currentTimeMillis() + (json.long("expires_in") ?: 3600) * 1000,
        )
        repo.updateCredentials(accountId, updated)
        updated.accessToken
    }
}

/** Google Drive tokens from Google Identity Services' AuthorizationClient (no client secret on device). */
class GoogleTokenSource(
    override val accountId: String,
    private val context: Context,
    private val repo: CloudAccountRepository,
) : TokenSource {
    private val lock = Mutex()
    override suspend fun token(forceRefresh: Boolean): String = lock.withLock {
        val a = repo.get(accountId)
        if (!forceRefresh && a.credentials.accessToken.isNotEmpty() && a.credentials.expiresAt - 60_000 > System.currentTimeMillis()) return a.credentials.accessToken
        val result = GoogleDriveAuth.authorize(context, a.email)
        val tok = result.accessToken
        if (result.hasResolution() || tok == null) throw FsException.AuthRequired(accountId)
        repo.updateCredentials(accountId, a.credentials.copy(accessToken = tok, expiresAt = System.currentTimeMillis() + 50 * 60_000))
        tok
    }
}

object GoogleDriveAuth {
    val scopes = listOf(Scope("https://www.googleapis.com/auth/drive"), Scope("email"), Scope("profile"))

    suspend fun authorize(context: Context, email: String?): AuthorizationResult {
        val b = AuthorizationRequest.builder().setRequestedScopes(scopes)
        if (!email.isNullOrBlank()) b.setAccount(Account(email, "com.google"))
        return Identity.getAuthorizationClient(context).authorize(b.build()).await()
    }

    fun resultFromIntent(activity: Activity, data: Intent?): AuthorizationResult =
        Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
}
