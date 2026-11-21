package org.videolan.vlc.gui.dialogs

import android.app.Dialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.resources.util.waitForML
import org.videolan.tools.KEY_INCOGNITO
import org.videolan.tools.Settings
import org.videolan.tools.dp
import org.videolan.vlc.R
import org.videolan.vlc.database.MediaDatabase
import org.videolan.vlc.database.VIDEO_LABEL_MAX_LENGTH
import org.videolan.vlc.database.VideoLabel
import org.videolan.vlc.media.MediaUtils

class VideoLabelsDialog : DialogFragment() {
    private lateinit var body: FrameLayout
    private var draftName: EditText? = null
    private val checkboxes = linkedMapOf<String, CheckBox>()
    private var activeLabel: VideoLabel? = null
    private var loadJob: Job? = null
    private val dao get() = MediaDatabase.getInstance(requireContext()).videoLabelDao()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val mediaUri = requireArguments().getString(MEDIA_URI)
        val builder = AlertDialog.Builder(requireContext())
        body = FrameLayout(builder.context)
        val dialog = builder
            .setTitle(if (mediaUri == null) R.string.video_labels_filter else R.string.video_labels_edit)
            .setView(body)
            .setNegativeButton(android.R.string.cancel, null)
            .apply { if (mediaUri != null) setPositiveButton(android.R.string.ok, null) }
            .create()
        dialog.setOnShowListener {
            if (!enabled(requireContext())) {
                dismiss()
            } else if (mediaUri != null) {
                loadEditor(dialog, mediaUri, savedInstanceState)
            } else {
                val key = savedInstanceState?.getString(FILTER_KEY)
                val name = savedInstanceState?.getString(FILTER_NAME)
                if (key != null && name != null) showResults(dialog, VideoLabel(key, name))
                else showLabels(dialog)
            }
        }
        return dialog
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        draftName?.let { input ->
            outState.putString(DRAFT_NAME, input.text.toString())
            outState.putStringArrayList(DRAFT_KEYS, ArrayList(checkedKeys()))
        }
        activeLabel?.let {
            outState.putString(FILTER_KEY, it.key)
            outState.putString(FILTER_NAME, it.name)
        }
    }

    private fun loadEditor(dialog: AlertDialog, mediaUri: String, savedState: Bundle?) {
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        save.isEnabled = false
        load {
            val selection = dao.selection(mediaUri)
            val checked = (savedState?.getStringArrayList(DRAFT_KEYS) ?: selection.selectedKeys).toSet()
            val context = body.context
            val panel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24.dp, 8.dp, 24.dp, 8.dp)
            }
            requireArguments().getString(MEDIA_TITLE)?.takeIf { it.isNotBlank() }?.let { title ->
                panel.addView(TextView(context).apply { text = title })
            }
            selection.labels.forEach { label ->
                val checkbox = CheckBox(context).apply {
                    text = label.name
                    isChecked = label.key in checked
                    minHeight = 48.dp
                }
                checkboxes[label.key] = checkbox
                panel.addView(checkbox)
            }
            val input = EditText(context).apply {
                hint = getString(R.string.video_labels_new)
                setSingleLine(true)
                filters = arrayOf(InputFilter.LengthFilter(VIDEO_LABEL_MAX_LENGTH))
                setText(savedState?.getString(DRAFT_NAME).orEmpty())
            }
            draftName = input
            panel.addView(input)
            replaceBody(ScrollView(context).apply { addView(panel) })
            save.isEnabled = true
            save.setOnClickListener {
                if (!enabled(requireContext())) {
                    dismiss()
                    return@setOnClickListener
                }
                val name = input.text.toString()
                val keys = checkedKeys()
                save.isEnabled = false
                lifecycleScope.launch {
                    try {
                        dao.saveSelection(mediaUri, keys, name)
                        dismiss()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: IllegalArgumentException) {
                        input.error = getString(R.string.video_labels_invalid)
                        save.isEnabled = true
                    } catch (_: Exception) {
                        Toast.makeText(requireContext(), R.string.video_labels_error, Toast.LENGTH_SHORT).show()
                        save.isEnabled = true
                    }
                }
            }
        }
    }

    private fun checkedKeys() = checkboxes.filterValues { it.isChecked }.keys

    private fun showLabels(dialog: AlertDialog) {
        activeLabel = null
        dialog.setTitle(R.string.video_labels_filter)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText(android.R.string.cancel)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { dismiss() }
        load {
            val labels = dao.labels()
            if (labels.isEmpty()) showMessage(R.string.video_labels_empty)
            else showList(labels.map { it.name }) { showResults(dialog, labels[it]) }
        }
    }

    private fun showResults(dialog: AlertDialog, label: VideoLabel) {
        activeLabel = label
        dialog.setTitle(label.name)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText(R.string.video_labels_back)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { showLabels(dialog) }
        load {
            val uris = dao.mediaForLabel(label.key)
            val media = if (uris.isEmpty()) emptyList() else {
                waitForML()
                withContext(Dispatchers.IO) {
                    val library = Medialibrary.getInstance()
                    uris.mapNotNull { library.getMedia(Uri.parse(it))?.takeIf { media -> media.isPresent } }
                        .sortedBy { it.title }
                }
            }
            if (media.isEmpty()) showMessage(R.string.video_labels_no_videos)
            else showList(media.map { it.title }) { index ->
                if (enabled(requireContext())) MediaUtils.openMedia(requireActivity(), media[index])
                dismiss()
            }
        }
    }

    private fun load(block: suspend () -> Unit) {
        loadJob?.cancel()
        showMessage(R.string.video_labels_loading)
        loadJob = lifecycleScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                showMessage(R.string.video_labels_error)
            }
        }
    }

    private fun showMessage(message: Int) = replaceBody(TextView(body.context).apply {
        setPadding(24.dp, 16.dp, 24.dp, 16.dp)
        setText(message)
    })

    private fun showList(names: List<String>, onClick: (Int) -> Unit) {
        replaceBody(ListView(body.context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, names)
            setOnItemClickListener { _, _, position, _ -> onClick(position) }
        })
    }

    private fun replaceBody(view: View) {
        body.removeAllViews()
        body.addView(view)
    }

    companion object {
        private const val TAG = "video_labels"
        private const val MEDIA_URI = "media_uri"
        private const val MEDIA_TITLE = "media_title"
        private const val DRAFT_NAME = "draft_name"
        private const val DRAFT_KEYS = "draft_keys"
        private const val FILTER_KEY = "filter_key"
        private const val FILTER_NAME = "filter_name"

        fun enabled(context: Context): Boolean = !Settings.showTvUi && !Settings.safeMode &&
                !Settings.getInstance(context).getBoolean(KEY_INCOGNITO, false)

        fun supports(media: MediaWrapper?): Boolean {
            val uri = media?.uri ?: return false
            return uri.scheme == "file"
        }

        fun edit(activity: FragmentActivity, media: MediaWrapper) {
            if (!supports(media)) return
            open(activity, bundleOf(MEDIA_URI to media.uri.toString(), MEDIA_TITLE to media.title))
        }

        fun filter(activity: FragmentActivity) = open(activity, Bundle())

        private fun open(activity: FragmentActivity, arguments: Bundle) {
            val manager = activity.supportFragmentManager
            if (enabled(activity) && !manager.isStateSaved && manager.findFragmentByTag(TAG) == null) {
                VideoLabelsDialog().apply { this.arguments = arguments }.show(manager, TAG)
            }
        }
    }
}
