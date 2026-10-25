package com.xander.files.data.lock

import android.util.Base64
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.xander.files.data.crypto.FileCrypto
import com.xander.files.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/** PIN / biometric app lock. Locks when the app goes to the background for longer than [graceMs]. */
@Singleton
class AppLock @Inject constructor(private val settings: SettingsRepository) : DefaultLifecycleObserver {
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked
    private var backgroundAt = 0L
    private val graceMs = 5_000L

    fun install() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        _locked.value = runBlocking { settings.settings.first().appLockEnabled }
    }

    override fun onStop(owner: LifecycleOwner) { backgroundAt = System.currentTimeMillis() }

    override fun onStart(owner: LifecycleOwner) {
        val enabled = runBlocking { settings.settings.first().appLockEnabled }
        if (enabled && backgroundAt > 0 && System.currentTimeMillis() - backgroundAt > graceMs) _locked.value = true
        if (!enabled) _locked.value = false
    }

    suspend fun verifyPin(pin: String): Boolean {
        val stored = settings.settings.first().appLockPinHash
        val ok = check(pin, stored)
        if (ok) _locked.value = false
        return ok
    }

    fun unlockedByBiometric() { _locked.value = false }

    suspend fun setPin(pin: String?) {
        settings.update { if (pin == null) it.copy(appLockEnabled = false, appLockPinHash = "", appLockBiometric = false) else it.copy(appLockEnabled = true, appLockPinHash = hash(pin)) }
        _locked.value = false
    }

    companion object {
        fun hash(pin: String): String {
            val salt = ByteArray(16).also(SecureRandom()::nextBytes)
            val dk = FileCrypto.deriveKey(pin.toCharArray(), salt, 50_000)
            return Base64.encodeToString(salt, Base64.NO_WRAP) + ":" + Base64.encodeToString(dk, Base64.NO_WRAP)
        }

        fun check(pin: String, stored: String): Boolean {
            val parts = stored.split(':')
            if (parts.size != 2) return false
            val salt = Base64.decode(parts[0], Base64.NO_WRAP)
            val expected = Base64.decode(parts[1], Base64.NO_WRAP)
            return MessageDigest.isEqual(expected, FileCrypto.deriveKey(pin.toCharArray(), salt, 50_000))
        }
    }
}
