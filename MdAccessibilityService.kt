package com.md.assistant
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** Only used for GLOBAL_ACTION_HOME / GLOBAL_ACTION_BACK. Does not read screen content. */
class MdAccessibilityService : AccessibilityService() {
    companion object { @Volatile var instance: MdAccessibilityService? = null }
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
