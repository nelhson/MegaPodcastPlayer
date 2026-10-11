package md.borisveriga.megapodcastplayer.core.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.datastore.UserPreferencesDataSource
import md.borisveriga.megapodcastplayer.core.model.FolderNameProblem
import md.borisveriga.megapodcastplayer.core.testing.InMemoryPreferencesDataStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [DefaultDownloadFolderRepository].
 *
 * The rules themselves are tested on `DownloadFolders` in `:core:model`; what is pinned here is the
 * repository's own job — that a refused name reaches the caller as a value and leaves the stored
 * folders alone, and that each operation is written through.
 */
class DefaultDownloadFolderRepositoryTest {

    private val repository = DefaultDownloadFolderRepository(
        UserPreferencesDataSource(InMemoryPreferencesDataStore()),
    )

    private suspend fun stored() = repository.observeFolders().first()

    @Test
    fun `a made folder is stored and returned`() = runTest {
        val made = repository.createFolder(" Commute ") as FolderEdit.Done

        assertEquals("Commute", made.folder.name)
        assertEquals(listOf(made.folder), stored().folders)
    }

    @Test
    fun `each folder gets an id of its own`() = runTest {
        val first = repository.createFolder("Commute") as FolderEdit.Done
        val second = repository.createFolder("Lectures") as FolderEdit.Done

        assertTrue(first.folder.id != second.folder.id)
    }

    @Test
    fun `a refused name says why and stores nothing`() = runTest {
        repository.createFolder("Commute")

        assertEquals(FolderEdit.Refused(FolderNameProblem.TAKEN), repository.createFolder("commute"))
        assertEquals(FolderEdit.Refused(FolderNameProblem.BLANK), repository.createFolder(" "))
        assertEquals(1, stored().folders.size)
    }

    @Test
    fun `a rename is stored`() = runTest {
        val id = (repository.createFolder("Commute") as FolderEdit.Done).folder.id

        val renamed = repository.renameFolder(id, "Train") as FolderEdit.Done

        assertEquals("Train", renamed.folder.name)
        assertEquals("Train", stored().folders.single().name)
    }

    @Test
    fun `renaming to another folder's name is refused`() = runTest {
        repository.createFolder("Commute")
        val id = (repository.createFolder("Lectures") as FolderEdit.Done).folder.id

        assertEquals(FolderEdit.Refused(FolderNameProblem.TAKEN), repository.renameFolder(id, "COMMUTE"))
        assertEquals("Lectures", stored().folders.last().name)
    }

    @Test
    fun `renaming a folder that has gone says so`() = runTest {
        assertEquals(FolderEdit.Gone, repository.renameFolder("gone", "Train"))
    }

    @Test
    fun `deleting a folder sends its downloads back to Downloads`() = runTest {
        val id = (repository.createFolder("Commute") as FolderEdit.Done).folder.id
        repository.moveToFolder(listOf("e1"), id)
        repository.setDefaultFolder(id)

        repository.deleteFolder(id)

        val folders = stored()
        assertTrue(folders.folders.isEmpty())
        assertNull(folders.folderOf("e1"))
        assertNull(folders.defaultFolderId)
    }

    @Test
    fun `forgetting downloads leaves the others filed`() = runTest {
        val id = (repository.createFolder("Commute") as FolderEdit.Done).folder.id
        repository.moveToFolder(listOf("e1", "e2"), id)

        repository.forget(listOf("e1"))

        assertNull(stored().folderOf("e1"))
        assertEquals(id, stored().folderOf("e2"))
    }
}
