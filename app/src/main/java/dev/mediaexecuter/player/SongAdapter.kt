package dev.mediaexecuter.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SongAdapter(private val listener: Listener) : RecyclerView.Adapter<SongAdapter.Holder>() {

    interface Listener {
        fun onSongClick(position: Int)
        fun onSongLongClick(position: Int)
        fun onSelectionChanged(count: Int)
    }

    private var all: List<Song> = emptyList()
    private var query = ""
    private var categorySongs: Set<Long>? = null
    private var currentUri: String? = null

    /** Ids das músicas selecionadas (modo de seleção ativo quando não está vazio). */
    val selected = LinkedHashSet<Long>()
    val inSelection get() = selected.isNotEmpty()

    /** Lista visível (filtrada por categoria e busca). */
    var items: List<Song> = emptyList()
        private set

    init {
        setHasStableIds(true)
    }

    fun submit(songs: List<Song>) {
        all = songs
        val present = songs.mapTo(HashSet()) { it.id }
        if (selected.retainAll(present)) listener.onSelectionChanged(selected.size)
        applyFilter()
    }

    fun filter(text: CharSequence) {
        val q = normalize(text.trim())
        if (q == query) return
        query = q
        applyFilter()
    }

    /** null = todas as músicas. */
    fun setCategory(songIds: Set<Long>?) {
        categorySongs = songIds
        applyFilter()
    }

    @Suppress("NotifyDataSetChanged")
    fun applyFilter() {
        val cat = categorySongs
        items = if (cat == null && query.isEmpty()) all else all.filter {
            (cat == null || it.id in cat) && (query.isEmpty() || it.searchKey.contains(query))
        }
        notifyDataSetChanged()
    }

    fun setCurrent(uri: String?) {
        if (uri == currentUri) return
        val old = currentUri
        currentUri = uri
        items.forEachIndexed { i, s ->
            val key = s.uri.toString()
            if (key == old || key == uri) notifyItemChanged(i)
        }
    }

    fun toggle(position: Int) {
        val id = items[position].id
        val wasSelecting = inSelection
        if (!selected.add(id)) selected.remove(id)
        // Entrar/sair do modo de seleção muda o visual de todas as linhas.
        if (wasSelecting != inSelection) notifyItemRangeChanged(0, items.size) else notifyItemChanged(position)
        listener.onSelectionChanged(selected.size)
    }

    fun selectAllVisible() {
        items.mapTo(selected) { it.id }
        notifyItemRangeChanged(0, items.size)
        listener.onSelectionChanged(selected.size)
    }

    fun clearSelection() {
        if (!inSelection) return
        selected.clear()
        notifyItemRangeChanged(0, items.size)
        listener.onSelectionChanged(0)
    }

    override fun getItemCount() = items.size

    override fun getItemId(position: Int) = items[position].id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val song = items[position]
        val playing = song.uri.toString() == currentUri
        val isSelected = song.id in selected
        val ctx = holder.itemView.context
        holder.itemView.isActivated = isSelected
        holder.title.text = song.title
        holder.title.setTextColor(ctx.getColor(if (playing) R.color.accent else R.color.text))
        holder.subtitle.text = "${song.artist} • ${formatTime(song.durationMs)}"
        holder.playing.visibility = if (playing && !inSelection) View.VISIBLE else View.GONE
        if (inSelection) {
            holder.check.visibility = View.VISIBLE
            holder.check.setImageResource(if (isSelected) R.drawable.ic_check_circle else R.drawable.ic_circle_outline)
        } else {
            holder.check.visibility = View.GONE
        }
        ArtLoader.load(holder.art, song)
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val art: ImageView = view.findViewById(R.id.art)
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val playing: ImageView = view.findViewById(R.id.playing)
        val check: ImageView = view.findViewById(R.id.check)

        init {
            art.clipToOutline = true
            view.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) listener.onSongClick(pos)
            }
            view.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) listener.onSongLongClick(pos)
                true
            }
        }
    }
}
