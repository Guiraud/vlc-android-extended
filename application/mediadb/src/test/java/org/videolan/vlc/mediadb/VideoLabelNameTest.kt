package org.videolan.vlc.mediadb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.videolan.vlc.database.VIDEO_LABEL_MAX_LENGTH
import org.videolan.vlc.database.VideoLabel
import org.videolan.vlc.database.videoLabelFromInput

class VideoLabelNameTest {
    @Test
    fun trimsAndNormalizesAccentsAndCase() {
        assertEquals(
            VideoLabel("référence", "RÉFÉRENCE"),
            videoLabelFromInput("  RE\u0301FE\u0301RENCE  ")
        )
        assertEquals(videoLabelFromInput("Référence")?.key, videoLabelFromInput("RÉFÉRENCE")?.key)
    }

    @Test
    fun rejectsBlankMultilineAndOverlongLabels() {
        assertNull(videoLabelFromInput(" "))
        assertNull(videoLabelFromInput("a\nb"))
        assertNull(videoLabelFromInput("a".repeat(VIDEO_LABEL_MAX_LENGTH + 1)))
    }
}
