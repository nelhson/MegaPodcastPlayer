package md.borisveriga.megapodcastplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [DownloadsView] and [FolderViewCodec].
 *
 * The codec is what stands between the stored string and the folder the screen opens on, so each
 * view has to survive the round trip and anything else has to fall back to the widest view rather
 * than to a folder nobody chose.
 */
class DownloadsViewTest {

    @Test
    fun `every folder view survives a round trip`() {
        val views = listOf(
            FolderView.AllFolders,
            FolderView.Downloads,
            FolderView.Folder("8f0e4a2c-1d0b-4d7e-9c1a-2b3c4d5e6f70"),
        )

        for (view in views) {
            assertEquals(view, FolderViewCodec.decode(FolderViewCodec.encode(view)))
        }
    }

    @Test
    fun `nothing stored reads as every folder`() {
        assertEquals(FolderView.AllFolders, FolderViewCodec.decode(null))
    }

    @Test
    fun `a string this build did not write reads as every folder`() {
        assertEquals(FolderView.AllFolders, FolderViewCodec.decode("recent"))
        assertEquals(FolderView.AllFolders, FolderViewCodec.decode(""))
        // The prefix with no id after it names no folder.
        assertEquals(FolderView.AllFolders, FolderViewCodec.decode("folder:"))
    }

    @Test
    fun `the default view is the widest one`() {
        assertEquals(DownloadKindFilter.ALL, DownloadsView.DEFAULT.kind)
        assertEquals(FolderView.AllFolders, DownloadsView.DEFAULT.folder)
    }
}
