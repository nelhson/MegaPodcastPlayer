package md.borisveriga.bpodcat.wear.complication

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import md.borisveriga.bpodcat.wear.MainActivity
import md.borisveriga.bpodcat.wear.R
import md.borisveriga.bpodcat.wear.data.PhonePlayerClient
import md.borisveriga.bpodcat.wear.data.extrapolatedPositionMs

/**
 * What the phone is playing, as a complication on the watch face itself.
 *
 * The tile is one swipe away; this is none. It cannot take a command — a complication is a readout,
 * and its single tap is spoken for by "open the app" — so it answers the two questions worth
 * answering without any interaction at all: what is playing, and how much of it is left.
 *
 * It reads the cached data item like every other surface here, so the face stays correct with the
 * phone asleep. Updates are pushed by
 * [md.borisveriga.bpodcat.wear.ongoing.NowPlayingChipService] whenever the phone publishes; the
 * manifest asks for no periodic refresh at all, because a timer that woke this up every few minutes
 * would cost battery on both devices to redraw something that had not changed.
 *
 * @property client the connection to the phone; only the cached snapshot is read.
 */
@AndroidEntryPoint
class NowPlayingComplicationService : SuspendingComplicationDataSourceService() {

    @Inject
    lateinit var client: PhonePlayerClient

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val snapshot = client.cachedSnapshot()
        val copy = complicationCopy(
            snapshot = snapshot,
            positionMs = snapshot?.let { extrapolatedPositionMs(it, System.currentTimeMillis()) }
                ?: 0L,
            strings = complicationStrings(this),
        )
        return complicationData(request.complicationType, copy)
    }

    /**
     * What the complication picker shows before the user commits to it.
     *
     * Deliberately a plausible episode rather than the real one: preview data is requested while the
     * user is browsing a list of every complication on the watch, and reaching over Bluetooth for
     * each of them would make that list crawl.
     */
    override fun getPreviewData(type: ComplicationType): ComplicationData? = complicationData(
        type = type,
        copy = ComplicationCopy(
            shortText = "48m",
            longText = getString(R.string.watch_complication_preview_episode),
            title = getString(R.string.watch_complication_preview_show),
            description = getString(R.string.watch_complication_preview_episode),
            progress = PREVIEW_PROGRESS,
            isPlaying = true,
        ),
    )

    /**
     * Builds the complication in whichever shape the face asked for.
     *
     * Every shape gets the tap that opens the app, and every shape gets the same spoken description:
     * a face may render four characters, but TalkBack must still say which episode they belong to.
     *
     * @param type the shape asked for; an unsupported one yields null, which the face treats as "no
     *   data" rather than as a failure.
     * @param copy what to say.
     */
    private fun complicationData(type: ComplicationType, copy: ComplicationCopy): ComplicationData? {
        val description = PlainComplicationText.Builder(copy.description).build()
        val icon = MonochromaticImage.Builder(
            Icon.createWithResource(
                this,
                if (copy.isPlaying) R.drawable.ic_tile_play else R.drawable.ic_tile_pause,
            ),
        ).build()

        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(copy.shortText).build(),
                contentDescription = description,
            )
                .setMonochromaticImage(icon)
                .setTapAction(openApp())
                .build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder(copy.longText).build(),
                contentDescription = description,
            )
                .apply { copy.title?.let { setTitle(PlainComplicationText.Builder(it).build()) } }
                .setMonochromaticImage(icon)
                .setTapAction(openApp())
                .build()

            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = copy.progress,
                min = 0f,
                max = 1f,
                contentDescription = description,
            )
                .setText(PlainComplicationText.Builder(copy.shortText).build())
                .setMonochromaticImage(icon)
                .setTapAction(openApp())
                .build()

            ComplicationType.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(
                monochromaticImage = icon,
                contentDescription = description,
            )
                .setTapAction(openApp())
                .build()

            else -> null
        }
    }

    /**
     * Opens the watch app.
     *
     * Carries no intent flags, for the reason spelled out in
     * [md.borisveriga.bpodcat.wear.ongoing.NowPlayingNotifications]: the reflexive
     * `NEW_TASK or CLEAR_TOP` breaks how Wear's recents treats the app, and a `PendingIntent` starts
     * the activity through the system regardless.
     */
    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        /** A believable fraction for the picker: plainly under way, plainly not nearly done. */
        const val PREVIEW_PROGRESS = 0.35f
    }
}

/**
 * The complication's copy, read from this module's resources.
 *
 * @param context any context; only resources are taken from it.
 */
internal fun complicationStrings(context: Context): ComplicationStrings = ComplicationStrings(
    nothingPlaying = context.getString(R.string.watch_nothing_playing_title),
    empty = context.getString(R.string.watch_complication_empty),
    describeFormat = context.getString(R.string.watch_complication_description),
)
