package md.borisveriga.bpodcat.wearsync

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.asImage
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for the artwork the phone sends to the watch.
 *
 * Runs under Robolectric because everything here is Android: a `Bitmap` to compress and an `Asset`
 * to wrap it in. The behaviour worth pinning is not the encoding — it is that the cache saves a
 * decode on the many republishes that do not change the image, and that a failure never becomes the
 * publisher's problem.
 */
@RunWith(AndroidJUnit4::class)
class ArtworkAssetsTest {

    private val imageLoader = mockk<ImageLoader>()
    private lateinit var artworkAssets: ArtworkAssets

    /** How many times the loader was actually asked for an image. */
    private var loads = 0

    @Before
    fun setUp() {
        artworkAssets = ArtworkAssets(ApplicationProvider.getApplicationContext(), imageLoader)
        loads = 0
    }

    /** Makes the loader answer every request with a small real bitmap. */
    private fun loaderSucceeds() {
        coEvery { imageLoader.execute(any()) } answers {
            loads++
            val request = firstArg<ImageRequest>()
            SuccessResult(
                image = android.graphics.Bitmap
                    .createBitmap(BITMAP_SIZE, BITMAP_SIZE, android.graphics.Bitmap.Config.ARGB_8888)
                    .asImage(),
                request = request,
            )
        }
    }

    @Test
    fun `an episode with no artwork sends none`() = runTest {
        loaderSucceeds()

        assertNull(artworkAssets.assetFor(null))
        assertNull(artworkAssets.assetFor(""))
        assertEquals(0, loads)
    }

    @Test
    fun `artwork is encoded into an asset`() = runTest {
        loaderSucceeds()

        val asset = artworkAssets.assetFor("https://example.com/cover.jpg")

        assertNotNull(asset)
        assertEquals(1, loads)
    }

    @Test
    fun `the same artwork is not encoded twice`() = runTest {
        loaderSucceeds()
        val url = "https://example.com/cover.jpg"

        val first = artworkAssets.assetFor(url)
        val second = artworkAssets.assetFor(url)

        // The publisher republishes on every seek and queue change; each of those must not cost a
        // decode and a recompress of an image that has not changed.
        assertSame(first, second)
        assertEquals(1, loads)
    }

    @Test
    fun `a new episode's artwork replaces the cached one`() = runTest {
        loaderSucceeds()

        artworkAssets.assetFor("https://example.com/one.jpg")
        artworkAssets.assetFor("https://example.com/two.jpg")

        assertEquals(2, loads)
    }

    @Test
    fun `artwork that will not load yields null rather than throwing`() = runTest {
        coEvery { imageLoader.execute(any()) } answers {
            loads++
            ErrorResult(image = null, request = firstArg(), throwable = RuntimeException("offline"))
        }

        // The publisher must still get its snapshot out; artwork is decoration.
        assertNull(artworkAssets.assetFor("https://example.com/cover.jpg"))
    }

    @Test
    fun `a loader that throws is contained`() = runTest {
        coEvery { imageLoader.execute(any()) } throws IllegalStateException("boom")

        assertNull(artworkAssets.assetFor("https://example.com/cover.jpg"))
    }

    private companion object {
        /** Any size will do; the point is a bitmap with readable pixels, not a realistic cover. */
        const val BITMAP_SIZE = 8
    }
}
