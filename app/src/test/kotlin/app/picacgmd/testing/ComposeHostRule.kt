package app.picacgmd.testing

import android.content.ComponentName
import androidx.activity.ComponentActivity
import org.junit.rules.ExternalResource
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Registers the Compose host because the debug manifest deliberately does not declare the test activity.
 * Keeping registration inside Robolectric leaves the APK manifests unchanged.
 */
class ComposeHostRule : ExternalResource() {
    override fun before() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.packageManager)
            .addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java))
    }
}
