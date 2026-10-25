package com.xander.files.ui.cloud

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xander.files.data.cloud.AppAuthFlow
import com.xander.files.data.cloud.Box
import com.xander.files.data.cloud.CloudAccount
import com.xander.files.data.cloud.CloudAccountRepository
import com.xander.files.data.cloud.CloudCredentials
import com.xander.files.data.cloud.CloudFileSystemProvider
import com.xander.files.data.cloud.CloudService
import com.xander.files.data.cloud.Dropbox
import com.xander.files.data.cloud.GoogleDrive
import com.xander.files.data.cloud.GoogleDriveAuth
import com.xander.files.data.cloud.Mega
import com.xander.files.data.cloud.Nextcloud
import com.xander.files.data.cloud.OAuthConfigs
import com.xander.files.data.cloud.OneDrive
import com.xander.files.data.cloud.YandexDisk
import com.xander.files.data.net.HttpClients
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.SpaceInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface CloudEvent {
    data class LaunchIntent(val intent: Intent, val service: CloudService) : CloudEvent
    data class LaunchSender(val sender: android.content.IntentSender) : CloudEvent
    data class OpenUrl(val url: String) : CloudEvent
    data class Message(val text: String) : CloudEvent
    data class Browse(val root: FsPath) : CloudEvent
}

@HiltViewModel
class CloudViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: CloudAccountRepository,
    private val provider: CloudFileSystemProvider,
) : ViewModel() {
    val accounts = repo.all.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val quotas = MutableStateFlow<Map<String, SpaceInfo>>(emptyMap())
    val busy = MutableStateFlow(false)
    val events = Channel<CloudEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()
    private val oauth = AppAuthFlow(context)
    private var nextcloudPoll: Job? = null

    fun loadQuotas() = viewModelScope.launch {
        accounts.value.forEach { a -> provider.spaceInfo(FsPath(FsPath.SCHEME_CLOUD, a.id, "/"))?.let { q -> quotas.value = quotas.value + (a.id to q) } }
    }

    fun start(service: CloudService) = viewModelScope.launch {
        if (!OAuthConfigs.isConfigured(service)) { events.send(CloudEvent.Message("${service.label} needs an API key configured in the build (see README)")); return@launch }
        when (service) {
            CloudService.GOOGLE_DRIVE -> runCatching {
                val r = GoogleDriveAuth.authorize(context, null)
                if (r.hasResolution()) events.send(CloudEvent.LaunchSender(r.pendingIntent!!.intentSender)) else finishGoogle(r.accessToken)
            }.onFailure { events.send(CloudEvent.Message(it.message ?: "Google sign-in failed")) }
            CloudService.DROPBOX, CloudService.ONEDRIVE, CloudService.BOX, CloudService.YANDEX ->
                events.send(CloudEvent.LaunchIntent(oauth.intent(OAuthConfigs.of(service)!!), service))
            else -> Unit // MEGA / Nextcloud use dialogs
        }
    }

    fun onGoogleResult(activity: Activity, data: Intent?) = viewModelScope.launch {
        runCatching { finishGoogle(GoogleDriveAuth.resultFromIntent(activity, data).accessToken) }
            .onFailure { events.send(CloudEvent.Message(it.message ?: "Google sign-in failed")) }
    }

    private suspend fun finishGoogle(token: String?) {
        if (token == null) { events.send(CloudEvent.Message("Google sign-in was cancelled")); return }
        busy.value = true
        try {
            val (name, email) = GoogleDrive.userInfo(HttpClients.shared, token)
            val a = repo.add(CloudService.GOOGLE_DRIVE, name, email, CloudCredentials(token, "", System.currentTimeMillis() + 50 * 60_000))
            events.send(CloudEvent.Browse(FsPath(FsPath.SCHEME_CLOUD, a.id, "/")))
        } finally { busy.value = false }
    }

    fun onOAuthResult(service: CloudService, data: Intent?) = viewModelScope.launch {
        if (data == null) { events.send(CloudEvent.Message("Sign-in cancelled")); return@launch }
        busy.value = true
        try {
            val cfg = OAuthConfigs.of(service)!!
            val creds = oauth.exchange(cfg, data)
            val http = HttpClients.shared
            val (name, email) = when (service) {
                CloudService.DROPBOX -> Dropbox.userInfo(http, creds.accessToken)
                CloudService.ONEDRIVE -> OneDrive.userInfo(http, creds.accessToken)
                CloudService.BOX -> Box.userInfo(http, creds.accessToken)
                else -> YandexDisk.userInfo(http, creds.accessToken)
            }
            val a = repo.add(service, name, email, creds)
            events.send(CloudEvent.Browse(FsPath(FsPath.SCHEME_CLOUD, a.id, "/")))
        } catch (e: Exception) {
            events.send(CloudEvent.Message(e.message ?: "Sign-in failed"))
        } finally { busy.value = false }
    }

    fun signInMega(email: String, password: String) = viewModelScope.launch {
        busy.value = true
        try {
            val http = HttpClients.shared
            val (sid, master) = Mega.login(http, email, password)
            val (mail, handle) = Mega.userInfo(http, sid)
            val a = repo.add(CloudService.MEGA, mail.ifEmpty { email }, mail.ifEmpty { email },
                CloudCredentials(accessToken = sid, server = handle, user = email, password = password, masterKey = com.xander.files.data.cloud.MegaCrypto.b64(master)))
            events.send(CloudEvent.Browse(FsPath(FsPath.SCHEME_CLOUD, a.id, "/")))
        } catch (e: Exception) {
            events.send(CloudEvent.Message(e.message ?: "MEGA sign-in failed"))
        } finally { busy.value = false }
    }

    fun signInNextcloudBrowser(server: String) {
        nextcloudPoll?.cancel()
        nextcloudPoll = viewModelScope.launch {
            busy.value = true
            try {
                val flow = Nextcloud.startLogin(server)
                events.send(CloudEvent.OpenUrl(flow.loginUrl))
                val (srv, login, appPw) = Nextcloud.poll(flow)
                addNextcloud(srv, login, appPw)
            } catch (e: Exception) {
                events.send(CloudEvent.Message(e.message ?: "Nextcloud sign-in failed"))
            } finally { busy.value = false }
        }
    }

    fun signInNextcloudManual(server: String, user: String, appPassword: String) = viewModelScope.launch {
        busy.value = true
        try { addNextcloud(Nextcloud.normalize(server), user, appPassword) }
        catch (e: Exception) { events.send(CloudEvent.Message(e.message ?: "Nextcloud sign-in failed")) }
        finally { busy.value = false }
    }

    private suspend fun addNextcloud(server: String, user: String, password: String) {
        // Verify by listing the root before saving.
        Nextcloud(server, user, password).list(FsPath(FsPath.SCHEME_CLOUD, "", "/"))
        val host = Uri.parse(server).host ?: server
        val a = repo.add(CloudService.NEXTCLOUD, "$user@$host", "$user@$host", CloudCredentials(server = server, user = user, password = password))
        events.send(CloudEvent.Browse(FsPath(FsPath.SCHEME_CLOUD, a.id, "/")))
    }

    fun remove(a: CloudAccount) = viewModelScope.launch { repo.remove(a.id) }

    override fun onCleared() { oauth.dispose() }
}
