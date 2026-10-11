package dev.pocketmuse

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** Keep the first-run system permission dialog from covering app UI tests. */
class NotificationPermissionRule : ExternalResource() {
    override fun before() {
        if (Build.VERSION.SDK_INT < 33) return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "pm grant $packageName android.permission.POST_NOTIFICATIONS"
        )).use { it.readBytes() }
    }
}
