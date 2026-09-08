package md.borisveriga.megapodcastplayer.navigation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import md.borisveriga.megapodcastplayer.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * Tests that the launcher's shortcut list and the code that reads it still agree.
 *
 * The failure this exists for is silent and only visible on a device: an action renamed on one side
 * leaves the shortcut in the menu, tappable, opening the app at whatever it happened to be showing
 * — no crash, no log, just a menu entry that has stopped meaning anything. So the XML is parsed
 * here rather than restated, and every action in it has to resolve.
 */
@RunWith(AndroidJUnit4::class)
// The real application binds a Media3 controller on start-up, which Robolectric's fake service
// binding completes with a null ComponentName; nothing here needs it.
@Config(sdk = [34], application = Application::class)
class LaunchShortcutTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    /** Every `android:action` on an `<intent>` in `res/xml/shortcuts.xml`, in declaration order. */
    private fun declaredActions(): List<String> {
        val parser = context.resources.getXml(R.xml.shortcuts)
        val actions = mutableListOf<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "intent") {
                actions += parser.getAttributeValue(ANDROID_NAMESPACE, "action").orEmpty()
            }
            event = parser.next()
        }
        parser.close()
        return actions
    }

    /** The resource id of each `<shortcut>`'s `android:icon`, in declaration order. */
    private fun declaredIcons(): List<Int> {
        val parser = context.resources.getXml(R.xml.shortcuts)
        val icons = mutableListOf<Int>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "shortcut") {
                icons += parser.getAttributeResourceValue(ANDROID_NAMESPACE, "icon", 0)
            }
            event = parser.next()
        }
        parser.close()
        return icons
    }

    /** The `android:targetClass` of each `<intent>`, in the same order. */
    private fun declaredTargets(): List<String> {
        val parser = context.resources.getXml(R.xml.shortcuts)
        val targets = mutableListOf<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "intent") {
                targets += parser.getAttributeValue(ANDROID_NAMESPACE, "targetClass").orEmpty()
            }
            event = parser.next()
        }
        parser.close()
        return targets
    }

    @Test
    fun `every shortcut the launcher offers resolves to one this app understands`() {
        val shortcuts = declaredActions().map(LaunchShortcut::fromAction)

        assertEquals(LaunchShortcut.entries.size, shortcuts.size)
        assertEquals(LaunchShortcut.entries.toSet(), shortcuts.toSet())
    }

    @Test
    fun `the shortcuts are declared in the order they are most likely to be wanted`() {
        // A launcher shows the first entries nearest the icon and drops the tail when it has room
        // for fewer than four, so the order is a decision rather than an accident.
        assertEquals(
            listOf(
                LaunchShortcut.RESUME,
                LaunchShortcut.QUEUE,
                LaunchShortcut.DOWNLOADS,
                LaunchShortcut.ADD_SHOW,
            ),
            declaredActions().map(LaunchShortcut::fromAction),
        )
    }

    @Test
    fun `every shortcut names this app's own activity explicitly`() {
        // An explicit intent is what lets these carry a private action with no intent-filter of
        // their own: nothing but the launcher can reach them.
        val targets = declaredTargets()

        assertEquals(LaunchShortcut.entries.size, targets.size)
        assertTrue(targets.all { it == "md.borisveriga.megapodcastplayer.MainActivity" })
    }

    @Test
    fun `an ordinary launch names no shortcut`() {
        // The common case: the launcher icon, a notification, a shared link. None of them is a
        // shortcut, and none of them may be mistaken for one.
        assertNull(LaunchShortcut.fromAction(null))
        assertNull(LaunchShortcut.fromAction("android.intent.action.MAIN"))
        assertNull(LaunchShortcut.fromAction("md.borisveriga.megapodcastplayer.action.UNKNOWN"))
    }

    @Test
    fun `every shortcut icon loads`() {
        // The one part of a shortcut with no other signal when it is wrong: a drawable whose path
        // data or adaptive-icon layering the platform cannot inflate is not a build failure and
        // not a crash, it is an icon that is simply missing from a menu nothing else tests.
        val icons = declaredIcons()

        assertEquals(LaunchShortcut.entries.size, icons.size)
        assertTrue(icons.all { it != 0 })
        icons.forEach { icon -> assertTrue(context.getDrawable(icon) != null) }
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
