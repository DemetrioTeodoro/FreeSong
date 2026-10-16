package dev.mediaexecuter.player

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/** Mostra as músicas que existem em mais de um arquivo e permite apagar as cópias. */
class DuplicatesActivity : DeletingActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var summary: TextView
    private lateinit var status: TextView
    private lateinit var bottom: View
    private lateinit var deleteButton: TextView
    private lateinit var presets: View
    private lateinit var presetInternal: TextView
    private lateinit var presetSd: TextView
    private val prefs by lazy { getSharedPreferences("library", MODE_PRIVATE) }
    /** Atalho escolhido: true = apagar do cartão SD, false = apagar do interno, null = seleção manual. */
    private var deleteFromSd: Boolean? = null

    private var groups: List<LibraryFilter.DuplicateGroup> = emptyList()
    /** Ids dos arquivos marcados para apagar. */
    private val marked = HashSet<Long>()
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_duplicates)

        summary = findViewById(R.id.summary)
        status = findViewById(R.id.status)
        bottom = findViewById(R.id.bottom)
        deleteButton = findViewById(R.id.deleteButton)
        presets = findViewById(R.id.presets)
        presetInternal = findViewById(R.id.presetInternal)
        presetSd = findViewById(R.id.presetSd)
        presetInternal.setOnClickListener { applyPreset(fromSdCard = false, remember = true) }
        presetSd.setOnClickListener { applyPreset(fromSdCard = true, remember = true) }
        val list = findViewById<RecyclerView>(R.id.list)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            val d = resources.displayMetrics.density
            bottom.setPadding(bottom.paddingLeft, bottom.paddingTop, bottom.paddingRight, (16 * d).toInt() + bars.bottom)
            list.setPadding(0, 0, 0, (8 * d).toInt() + bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        deleteButton.setOnClickListener { confirmDelete() }

        load()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun load() {
        status.setText(R.string.loading)
        status.visibility = View.VISIBLE
        val appContext = applicationContext
        io.execute {
            val raw = SongRepository.query(appContext)
            val result = LibraryFilter(appContext).duplicateGroups(raw)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                groups = result
                adapter.rebuild()
                // Padrão: apaga do interno e mantém a cópia do cartão SD (ou a última opção usada).
                applyPreset(prefs.getBoolean(KEY_DELETE_FROM_SD, false), remember = false)
            }
        }
    }

    /** Marca as cópias a apagar de todas as músicas de uma vez; sempre sobra 1 cópia de cada. */
    private fun applyPreset(fromSdCard: Boolean, remember: Boolean) {
        deleteFromSd = fromSdCard
        if (remember) prefs.edit().putBoolean(KEY_DELETE_FROM_SD, fromSdCard).apply()
        marked.clear()
        for (g in groups) {
            LibraryFilter.copiesToDelete(g.copies, fromSdCard) { it.folder }.mapTo(marked) { it.id }
        }
        adapter.refreshAll()
        updateUi()
    }

    private fun updateUi() {
        if (groups.isEmpty()) {
            status.setText(R.string.duplicates_none)
            status.visibility = View.VISIBLE
            summary.visibility = View.GONE
            bottom.visibility = View.GONE
            return
        }
        status.visibility = View.GONE
        summary.visibility = View.VISIBLE
        // Os atalhos só fazem sentido se há cópias nos dois lugares.
        val all = groups.flatMap { it.copies }
        val hasSd = all.any { LibraryFilter.isSdCard(it.folder) }
        val hasInternal = all.any { !LibraryFilter.isSdCard(it.folder) }
        presets.visibility = if (hasSd && hasInternal) View.VISIBLE else View.GONE
        presetInternal.isSelected = deleteFromSd == false
        presetSd.isSelected = deleteFromSd == true
        bottom.visibility = View.VISIBLE

        val extraBytes = groups.sumOf { g -> g.copies.drop(1).sumOf { it.sizeBytes } }
        summary.text = getString(
            R.string.duplicates_summary,
            resources.getQuantityString(R.plurals.song_count, groups.size, groups.size),
            Formatter.formatShortFileSize(this, extraBytes),
        )

        val selected = markedSongs()
        deleteButton.isEnabled = selected.isNotEmpty()
        deleteButton.alpha = if (selected.isEmpty()) 0.5f else 1f
        deleteButton.text = resources.getQuantityString(
            R.plurals.delete_copies_button, selected.size, selected.size,
            Formatter.formatShortFileSize(this, selected.sumOf { it.sizeBytes }),
        )
    }

    private fun markedSongs(): List<Song> = groups.flatMap { g -> g.copies.filter { it.id in marked } }

    private fun confirmDelete() {
        val selected = markedSongs()
        if (selected.isEmpty()) return
        var message = getString(
            R.string.delete_copies_q, selected.size,
            Formatter.formatShortFileSize(this, selected.sumOf { it.sizeBytes }),
        )
        val wholeSongs = groups.count { g -> g.copies.all { it.id in marked } }
        if (wholeSongs > 0) message += getString(R.string.delete_whole_song_warning, wholeSongs)

        AlertDialog.Builder(this)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteSongs(selected.map { it.uri }) }
            .show()
    }

    override fun onSongsDeleted(deleted: List<Uri>) {
        if (deleted.isEmpty()) return
        Toast.makeText(this, getString(R.string.deleted_n, deleted.size), Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK) // avisa a tela principal para recarregar
        load()
    }

    private companion object {
        const val KEY_DELETE_FROM_SD = "duplicates_delete_from_sd"
    }

    // ---------- Lista: cabeçalho por música + uma linha por arquivo ----------

    private sealed class Row {
        class Header(val group: LibraryFilter.DuplicateGroup) : Row()
        class Copy(val song: Song, val shown: Boolean) : Row()
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = emptyList()

        @Suppress("NotifyDataSetChanged")
        fun rebuild() {
            rows = groups.flatMap { g -> listOf(Row.Header(g)) + g.copies.map { Row.Copy(it, it.id == g.shown.id) } }
            notifyDataSetChanged()
        }

        fun refreshAll() = notifyItemRangeChanged(0, rows.size)

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                HeaderHolder(inflater.inflate(R.layout.item_duplicate_header, parent, false))
            } else {
                CopyHolder(inflater.inflate(R.layout.item_duplicate_copy, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderHolder).bind(row.group)
                is Row.Copy -> (holder as CopyHolder).bind(row)
            }
        }

        inner class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val art: ImageView = view.findViewById(R.id.art)
            private val title: TextView = view.findViewById(R.id.title)
            private val subtitle: TextView = view.findViewById(R.id.subtitle)

            init {
                art.clipToOutline = true
            }

            fun bind(group: LibraryFilter.DuplicateGroup) {
                title.text = group.shown.title
                subtitle.text = "${group.shown.artist} • ${formatTime(group.shown.durationMs)} • ${group.copies.size} arquivos"
                ArtLoader.load(art, group.shown)
            }
        }

        inner class CopyHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val check: ImageView = view.findViewById(R.id.check)
            private val location: TextView = view.findViewById(R.id.location)
            private val details: TextView = view.findViewById(R.id.details)
            private val badge: View = view.findViewById(R.id.badge)

            init {
                view.setOnClickListener {
                    val pos = bindingAdapterPosition
                    val row = rows.getOrNull(pos) as? Row.Copy ?: return@setOnClickListener
                    if (!marked.add(row.song.id)) marked.remove(row.song.id)
                    deleteFromSd = null // virou seleção manual
                    notifyItemChanged(pos)
                    updateUi()
                }
            }

            fun bind(row: Row.Copy) {
                val isMarked = row.song.id in marked
                itemView.isActivated = isMarked
                check.setImageResource(if (isMarked) R.drawable.ic_check_circle else R.drawable.ic_circle_outline)
                location.text = LibraryFilter.label(row.song.folder)
                details.text = "${row.song.fileName} • ${Formatter.formatShortFileSize(itemView.context, row.song.sizeBytes)}"
                badge.visibility = if (row.shown) View.VISIBLE else View.GONE
            }
        }
    }
}
