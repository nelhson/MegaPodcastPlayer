package md.borisveriga.megapodcastplayer.feature.downloads

import md.borisveriga.megapodcastplayer.core.model.DownloadFolders
import md.borisveriga.megapodcastplayer.core.model.FolderNameProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [folderNameProblem], which is what the folder-name dialog decides as a name is typed.
 *
 * The folders' own rules are tested in `:core:model`; the one added here is the built-in folder's
 * name, which no stored folder has and which still must not be taken.
 */
class FolderNameProblemTest {

    private val folders = DownloadFolders.NONE.created(id = "f1", name = "Commute")

    @Test
    fun `a name another folder has is refused`() {
        assertEquals(FolderNameProblem.TAKEN, folderNameProblem(" commute", folders, null, BUILT_IN))
    }

    @Test
    fun `the built-in folder's name is refused whatever its case`() {
        assertEquals(FolderNameProblem.TAKEN, folderNameProblem("downloads ", folders, null, BUILT_IN))
    }

    @Test
    fun `a folder may keep its own name when renamed`() {
        assertNull(folderNameProblem("COMMUTE", folders, renamingId = "f1", builtInName = BUILT_IN))
    }

    @Test
    fun `a new name is accepted`() {
        assertNull(folderNameProblem("Lectures", folders, null, BUILT_IN))
    }

    @Test
    fun `blank is reported, which the dialog shows as a disabled button rather than an error`() {
        assertEquals(FolderNameProblem.BLANK, folderNameProblem("  ", folders, null, BUILT_IN))
    }

    private companion object {
        /** The built-in folder's name, as the English screen shows it. */
        const val BUILT_IN = "Downloads"
    }
}
