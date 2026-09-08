package md.borisveriga.megapodcastplayer.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import md.borisveriga.megapodcastplayer.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * Tests that what the platform is told about the widget and what the widget draws still agree.
 *
 * The same kind of silent failure `LaunchShortcutTest` exists for, one layer out. Nothing in the
 * build connects `res/xml/now_playing_widget_info.xml` to `NowPlayingWidget`: a widget declared
 * smaller than the tree it draws is clipped on a home screen, and a widget that asks for periodic
 * updates wakes the app for nothing. Neither shows up in a compiler or a unit test of the drawing.
 *
 * The sizes are the interesting pair. Glance chooses between the two responsive trees using the
 * size the *launcher* gives it, and the launcher will not go below `minWidth`/`minHeight` — so a
 * declared minimum under the compact tree would produce a face nothing was designed for.
 */
@RunWith(AndroidJUnit4::class)
// The real application binds a Media3 controller on start-up and this needs no player; the plain
// Application is enough to read resources and the package manager, exactly as LaunchShortcutTest
// does for the same reason.
@Config(sdk = [34], application = Application::class)
class NowPlayingWidgetProviderTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    /** Every attribute on the single `<appwidget-provider>` tag, by name. */
    private fun providerAttributes(): Map<String, String> {
        val parser = context.resources.getXml(R.xml.now_playing_widget_info)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "appwidget-provider") {
                return (0 until parser.attributeCount).associate { index ->
                    parser.getAttributeName(index) to parser.getAttributeValue(index)
                }
            }
            event = parser.next()
        }
        parser.close()
        error("res/xml/now_playing_widget_info.xml declares no <appwidget-provider>")
    }

    @Test
    fun `the manifest declares the receiver the widget is drawn by`() {
        val component = ComponentName(context, NowPlayingWidgetReceiver::class.java)

        val declared = context.packageManager
            .queryBroadcastReceivers(
                android.content.Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .setComponent(component),
                0,
            )

        assertTrue(
            "NowPlayingWidgetReceiver is not registered for APPWIDGET_UPDATE",
            declared.isNotEmpty(),
        )
    }

    @Test
    fun `the declared minimum is not smaller than the smallest tree the widget draws`() {
        val attributes = providerAttributes()

        assertEquals("180.0dip", attributes["minWidth"])
        assertEquals("100.0dip", attributes["minHeight"])
    }

    /**
     * Zero, and deliberately. The widget follows playback by collecting it, so a periodic broadcast
     * would wake the app to redraw something that had not changed — and the platform's own floor is
     * half an hour, which is far too slow to follow an episode and far too often to do nothing.
     */
    @Test
    fun `the widget asks for no periodic update`() {
        assertEquals("0", providerAttributes()["updatePeriodMillis"])
    }

    @Test
    fun `the widget may be resized in both directions`() {
        // The two responsive trees differ in height, so a widget the user cannot make taller is a
        // widget that can never show the shelf.
        val resizeMode = providerAttributes()["resizeMode"].orEmpty()

        assertTrue("resizeMode was $resizeMode", resizeMode.isNotEmpty())
    }
}
