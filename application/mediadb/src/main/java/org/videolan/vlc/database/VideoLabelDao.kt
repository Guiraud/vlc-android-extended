package org.videolan.vlc.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import java.text.Normalizer
import java.util.Locale

const val VIDEO_LABEL_MAX_LENGTH = 60

@Entity(tableName = "video_label")
data class VideoLabel(@PrimaryKey val key: String, val name: String)

@Entity(
    tableName = "video_label_assignment",
    primaryKeys = ["mediaUri", "labelKey"],
    foreignKeys = [ForeignKey(
        entity = VideoLabel::class,
        parentColumns = ["key"],
        childColumns = ["labelKey"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("labelKey")]
)
data class VideoLabelAssignment(val mediaUri: String, val labelKey: String)

data class VideoLabelSelection(val labels: List<VideoLabel>, val selectedKeys: List<String>)

internal fun videoLabelFromInput(input: String): VideoLabel? {
    val name = Normalizer.normalize(input.trim(), Normalizer.Form.NFC)
    if (name.isEmpty() || name.length > VIDEO_LABEL_MAX_LENGTH || name.any { it.isISOControl() }) return null
    return VideoLabel(Normalizer.normalize(name.lowercase(Locale.ROOT), Normalizer.Form.NFC), name)
}

@Dao
abstract class VideoLabelDao {
    @Query("SELECT * FROM video_label ORDER BY `key`")
    abstract fun labels(): List<VideoLabel>

    @Query("SELECT labelKey FROM video_label_assignment WHERE mediaUri = :mediaUri")
    protected abstract fun selectedKeys(mediaUri: String): List<String>

    @Query("SELECT mediaUri FROM video_label_assignment WHERE labelKey = :labelKey ORDER BY mediaUri")
    abstract fun mediaForLabel(labelKey: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract fun insertLabel(label: VideoLabel)

    @Insert
    protected abstract fun insertAssignments(assignments: List<VideoLabelAssignment>)

    @Query("DELETE FROM video_label_assignment WHERE mediaUri = :mediaUri")
    protected abstract fun clearAssignments(mediaUri: String)

    @Transaction
    open fun selection(mediaUri: String) = VideoLabelSelection(labels(), selectedKeys(mediaUri))

    @Transaction
    open fun saveSelection(mediaUri: String, keys: Set<String>, newName: String) {
        val newLabel = if (newName.isBlank()) null else requireNotNull(videoLabelFromInput(newName))
        if (newLabel != null) insertLabel(newLabel)
        val selected = if (newLabel == null) keys else keys + newLabel.key
        clearAssignments(mediaUri)
        if (selected.isNotEmpty()) insertAssignments(selected.map { VideoLabelAssignment(mediaUri, it) })
    }
}
