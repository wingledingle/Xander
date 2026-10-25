package com.xander.files.data.tv.companion

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.xander.files.data.tv.TvInputBackend
import com.xander.files.data.tv.TvKey

/**
 * Accessibility service on the TV that turns phone remote-control commands into D-pad navigation,
 * global actions (Back/Home/Recents) and text entry. The user enables it once in TV Accessibility settings.
 */
class TvRemoteService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun key(k: TvKey): Boolean {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun media(code: Int): Boolean {
            val t = SystemClock.uptimeMillis()
            audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
            audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0)); return true
        }
        return when (k) {
            TvKey.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            TvKey.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            TvKey.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            TvKey.NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            TvKey.POWER_DIALOG -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            TvKey.PLAY_PAUSE -> media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            TvKey.NEXT -> media(KeyEvent.KEYCODE_MEDIA_NEXT)
            TvKey.PREVIOUS -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            TvKey.VOLUME_UP -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); true }
            TvKey.VOLUME_DOWN -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); true }
            TvKey.MUTE -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI); true }
            TvKey.UP, TvKey.DOWN, TvKey.LEFT, TvKey.RIGHT, TvKey.CENTER -> dpad(k)
        }
    }

    private fun dpad(k: TvKey): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            val a = when (k) {
                TvKey.UP -> GLOBAL_ACTION_DPAD_UP; TvKey.DOWN -> GLOBAL_ACTION_DPAD_DOWN; TvKey.LEFT -> GLOBAL_ACTION_DPAD_LEFT
                TvKey.RIGHT -> GLOBAL_ACTION_DPAD_RIGHT; else -> GLOBAL_ACTION_DPAD_CENTER
            }
            if (performGlobalAction(a)) return true
        }
        // Older TVs: move input focus through the accessibility tree.
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (k == TvKey.CENTER) return focused?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        val dir = when (k) { TvKey.UP -> View.FOCUS_UP; TvKey.DOWN -> View.FOCUS_DOWN; TvKey.LEFT -> View.FOCUS_LEFT; else -> View.FOCUS_RIGHT }
        val next = (focused ?: root).focusSearch(dir) ?: return false
        return next.performAction(AccessibilityNodeInfo.ACTION_FOCUS) || next.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
    }

    fun text(t: String): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!node.isEditable) return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    companion object {
        @Volatile var instance: TvRemoteService? = null
            private set

        val backend = object : TvInputBackend {
            override val available get() = instance != null
            override fun key(key: TvKey) = instance?.key(key) ?: false
            override fun text(text: String) = instance?.text(text) ?: false
        }
    }
}
