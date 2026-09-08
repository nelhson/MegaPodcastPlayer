package md.borisveriga.megapodcastplayer.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * The broadcast receiver the platform knows the widget by.
 *
 * All it does is name the widget; everything else — the update cadence, the sizes, the preview —
 * is declared in `res/xml/now_playing_widget_info.xml`, and everything the widget draws comes from
 * [NowPlayingWidget].
 *
 * Not `@AndroidEntryPoint`. The receiver injects nothing: the widget and its action callbacks are
 * constructed by Glance and reach the graph through `WidgetEntryPoint` from the context they are
 * handed, which is the only route open to an `ActionCallback` in any case. One route rather than
 * two is also one thing to get right.
 */
class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}
