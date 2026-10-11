package md.borisveriga.megapodcastplayer.core.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [DownloadFolders] and [DownloadFoldersCodec].
 *
 * The rules worth pinning are the ones that decide where a user's download ends up: that the
 * built-in *Downloads* folder catches everything a folder lets go of, that a download nobody filed
 * goes where it already was before it goes to the default, and that a name is judged the way a
 * person would judge it — trimmed and regardless of case.
 */
class DownloadFoldersTest {

    private val commute = DownloadFolders.NONE.created(id = "f1", name = "Commute")
    private val two = commute.created(id = "f2", name = "Lectures")

    @Test
    fun `a new folder is added with its name trimmed`() {
        val folders = DownloadFolders.NONE.created(id = "f1", name = "  Commute ")

        assertEquals(listOf(DownloadFolder("f1", "Commute")), folders.folders)
    }

    @Test
    fun `a blank name is refused`() {
        assertEquals(FolderNameProblem.BLANK, commute.nameProblem("   "))
    }

    @Test
    fun `a name longer than a chip is refused`() {
        assertEquals(
            FolderNameProblem.TOO_LONG,
            commute.nameProblem("x".repeat(DownloadFolders.MAX_NAME_LENGTH + 1)),
        )
        assertNull(commute.nameProblem("x".repeat(DownloadFolders.MAX_NAME_LENGTH)))
    }

    @Test
    fun `a name another folder has is refused whatever its case`() {
        assertEquals(FolderNameProblem.TAKEN, commute.nameProblem(" commute"))
    }

    @Test
    fun `a folder may be renamed to its own name in another case`() {
        assertNull(commute.nameProblem("COMMUTE", ignoringId = "f1"))
        assertEquals("COMMUTE", commute.renamed("f1", "COMMUTE").folders.single().name)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `creating with a refused name throws`() {
        commute.created(id = "f2", name = "Commute")
    }

    @Test
    fun `a rename keeps the folder's episodes`() {
        val folders = commute.moved(listOf("e1"), "f1").renamed("f1", "Train")

        assertEquals("f1", folders.folderOf("e1"))
        assertEquals("Train", folders.folders.single().name)
    }

    @Test
    fun `renaming a folder that does not exist changes nothing`() {
        assertSame(commute, commute.renamed("gone", "Train"))
    }

    @Test
    fun `deleting a folder puts its episodes back in Downloads and resets the default`() {
        val folders = two.moved(listOf("e1"), "f1").moved(listOf("e2"), "f2").withDefault("f1")
            .deleted("f1")

        assertEquals(listOf("f2"), folders.folders.map { it.id })
        assertNull(folders.folderOf("e1"))
        assertEquals("f2", folders.folderOf("e2"))
        assertNull(folders.defaultFolderId)
    }

    @Test
    fun `an episode is in Downloads until moved, and back in it when moved to null`() {
        assertNull(commute.folderOf("e1"))

        val moved = commute.moved(listOf("e1"), "f1")
        assertEquals("f1", moved.folderOf("e1"))
        assertNull(moved.moved(listOf("e1"), null).folderOf("e1"))
    }

    @Test
    fun `moving into a folder that does not exist changes nothing`() {
        assertSame(commute, commute.moved(listOf("e1"), "gone"))
    }

    @Test
    fun `a membership naming a missing folder reads as Downloads`() {
        val stale = DownloadFolders(membership = mapOf("e1" to "gone"))

        assertNull(stale.folderOf("e1"))
    }

    @Test
    fun `the default can only be a folder that exists`() {
        assertEquals("f1", commute.withDefault("f1").defaultFolderId)
        assertSame(commute, commute.withDefault("gone"))
        assertNull(commute.withDefault("f1").withDefault(null).defaultFolderId)
    }

    @Test
    fun `a chosen folder wins over everything`() {
        val folders = two.moved(listOf("e1"), "f1").withDefault("f1")

        assertEquals("f2", folders.folderForDownload("e1", DownloadDestination.Folder("f2")))
    }

    @Test
    fun `choosing Downloads on purpose wins over the folder the episode was in`() {
        val folders = commute.moved(listOf("e1"), "f1")

        assertNull(folders.folderForDownload("e1", DownloadDestination.Downloads))
    }

    @Test
    fun `with no choice an episode stays in its folder before going to the default`() {
        val folders = two.moved(listOf("e1"), "f1").withDefault("f2")

        assertEquals("f1", folders.folderForDownload("e1", DownloadDestination.Unspecified))
        assertEquals("f2", folders.folderForDownload("e9", DownloadDestination.Unspecified))
    }

    @Test
    fun `with no choice and no default a download goes to Downloads`() {
        assertNull(commute.folderForDownload("e1", DownloadDestination.Unspecified))
    }

    @Test
    fun `a chosen folder that has gone falls back as if nothing was chosen`() {
        val folders = commute.withDefault("f1")

        assertEquals("f1", folders.folderForDownload("e1", DownloadDestination.Folder("gone")))
    }

    @Test
    fun `assigning files the episode where the rules say`() {
        val folders = two.withDefault("f2").assigned("e1", DownloadDestination.Unspecified)

        assertEquals("f2", folders.folderOf("e1"))
        assertNull(folders.assigned("e1", DownloadDestination.Downloads).folderOf("e1"))
    }

    @Test
    fun `forgetting removed downloads drops their memberships only`() {
        val folders = commute.moved(listOf("e1", "e2"), "f1").forgotten(listOf("e1"))

        assertNull(folders.folderOf("e1"))
        assertEquals("f1", folders.folderOf("e2"))
    }

    @Test
    fun `forgetting episodes in no folder changes nothing`() {
        assertSame(commute, commute.forgotten(listOf("e1")))
    }

    @Test
    fun `folders survive the codec`() {
        val folders = two.moved(listOf("e1"), "f2").withDefault("f1")

        assertEquals(folders, DownloadFoldersCodec.decode(DownloadFoldersCodec.encode(folders)))
    }

    @Test
    fun `nothing stored decodes to no folders`() {
        assertEquals(DownloadFolders.NONE, DownloadFoldersCodec.decode(null))
        assertEquals(DownloadFolders.NONE, DownloadFoldersCodec.decode(""))
    }

    @Test
    fun `an unknown field is corruption, not a newer peer`() {
        assertEquals(
            DownloadFolders.NONE,
            DownloadFoldersCodec.decode("""{"folders":[],"membership":{},"defaultFolderId":null,"colour":"red"}"""),
        )
    }

    private fun download(id: String) = EpisodeWithShow(
        episode = Episode(
            id = id,
            podcastId = "podcast-1",
            guid = "guid-$id",
            title = "Episode $id",
            description = "",
            audioUrl = "https://cdn.example.com/$id.mp3",
            artworkUrl = null,
            durationMs = 60_000L,
            publishedAt = Instant.EPOCH,
            sizeBytes = null,
            downloadState = DownloadState.COMPLETED,
        ),
        showTitle = "Podlodka Podcast",
        showArtworkUrl = null,
    )

    private val filed = two.moved(listOf("a"), "f1").moved(listOf("b"), "f2")
    private val rows = listOf(download("a"), download("b"), download("c"))

    @Test
    fun `all folders shows every download`() {
        assertSame(rows, rows.inFolder(FolderView.AllFolders, filed))
    }

    @Test
    fun `the Downloads view shows only what is filed nowhere else`() {
        assertEquals(listOf("c"), rows.inFolder(FolderView.Downloads, filed).map { it.episode.id })
    }

    @Test
    fun `a folder view shows only that folder`() {
        assertEquals(listOf("a"), rows.inFolder(FolderView.Folder("f1"), filed).map { it.episode.id })
    }

    @Test
    fun `a view of a deleted folder falls back to every folder`() {
        assertEquals(FolderView.AllFolders, FolderView.Folder("gone").orAllFoldersIfGone(filed))
        assertEquals(FolderView.Folder("f1"), FolderView.Folder("f1").orAllFoldersIfGone(filed))
        assertEquals(FolderView.Downloads, FolderView.Downloads.orAllFoldersIfGone(filed))
    }

    @Test
    fun `counts are per folder with Downloads under null and empty folders absent`() {
        val counts = listOf(download("a"), download("c"), download("d")).countByFolder(filed)

        assertEquals(mapOf("f1" to 1, null to 2), counts)
    }

    @Test
    fun `a view contains a folder id exactly when it shows it`() {
        assertTrue(FolderView.AllFolders.contains("f1"))
        assertTrue(FolderView.Downloads.contains(null))
        assertFalse(FolderView.Downloads.contains("f1"))
        assertTrue(FolderView.Folder("f1").contains("f1"))
        assertFalse(FolderView.Folder("f1").contains(null))
    }
}
