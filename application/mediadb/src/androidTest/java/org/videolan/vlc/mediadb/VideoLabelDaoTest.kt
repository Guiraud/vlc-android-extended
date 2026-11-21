package org.videolan.vlc.mediadb

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.videolan.vlc.database.MediaDatabase
import org.videolan.vlc.database.VideoLabel
import org.videolan.vlc.database.VideoLabelDao

@RunWith(AndroidJUnit4::class)
class VideoLabelDaoTest {
    private lateinit var database: MediaDatabase
    private lateinit var dao: VideoLabelDao
    private val first = "file:///Movies/a.mp4"
    private val second = "file:///Movies/b.mp4"

    @Before
    fun openDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, MediaDatabase::class.java).build()
        dao = database.videoLabelDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun labelsAreReusableAndFilteringIsPerVideo() = runBlocking {
        dao.saveSelection(first, emptySet(), " Référence ")
        dao.saveSelection(second, emptySet(), "RE\u0301FE\u0301RENCE")
        dao.saveSelection(first, setOf("référence"), "À revoir")

        assertEquals(listOf(VideoLabel("référence", "Référence"), VideoLabel("à revoir", "À revoir")), dao.labels())
        assertEquals(setOf("référence", "à revoir"), dao.selection(first).selectedKeys.toSet())
        assertEquals(listOf(first, second), dao.mediaForLabel("référence"))
        assertEquals(listOf(first), dao.mediaForLabel("à revoir"))

        dao.saveSelection(first, emptySet(), "")

        assertTrue(dao.selection(first).selectedKeys.isEmpty())
        assertTrue(dao.mediaForLabel("à revoir").isEmpty())
        assertEquals(listOf(second), dao.mediaForLabel("référence"))
        assertEquals(2, dao.labels().size)
    }

    @Test
    fun invalidLabelDoesNotEraseExistingAssignments() = runBlocking {
        dao.saveSelection(first, emptySet(), "Archive")
        try {
            dao.saveSelection(first, emptySet(), "two\nlines")
            throw AssertionError("Expected an invalid label to be rejected")
        } catch (_: IllegalArgumentException) {
            assertEquals(listOf(first), dao.mediaForLabel("archive"))
        }
    }
}
