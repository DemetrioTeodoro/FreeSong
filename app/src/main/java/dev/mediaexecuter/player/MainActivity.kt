package dev.mediaexecuter.player

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class MainActivity : DeletingActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val mainExecutorCompat = Executor { handler.post(it) }
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private lateinit var count: TextView
    private lateinit var status: TextView
    private lateinit var player: View
    private lateinit var playerArt: ImageView
    private lateinit var playerTitle: TextView
    private lateinit var playerArtist: TextView
    private lateinit var seek: SeekBar
    private lateinit var position: TextView
    private lateinit var duration: TextView
    private lateinit var playPause: ImageButton
    private lateinit var shuffle: ImageButton
    private lateinit var repeat: ImageButton

    private lateinit var header: View
    private lateinit var selectionBar: View
    private lateinit var selectionCount: TextView
    private lateinit var chips: LinearLayout
    private lateinit var actions: View
    private lateinit var listInfo: TextView

    private val adapter: SongAdapter = SongAdapter(object : SongAdapter.Listener {
        override fun onSongClick(position: Int) {
            if (adapter.inSelection) adapter.toggle(position) else playFrom(position)
        }
        override fun onSongLongClick(position: Int) = adapter.toggle(position)
        override fun onSelectionChanged(count: Int) = updateSelectionBar()
    })
    private lateinit var categories: CategoryStore
    /** Categoria escolhida nos chips; null = Todas. */
    private var activeCategory: Long? = null
    private var songsByUri: Map<String, Song> = emptyMap()
    private var presentIds: Set<Long> = emptySet()
    /** Tudo que o Android encontrou, antes de esconder pastas e duplicadas. */
    private var rawSongs: List<Song> = emptyList()
    private lateinit var libraryFilter: LibraryFilter
    /** Cópia escondida -> cópia mostrada (ver [LibraryFilter.Library.alias]). */
    private var songAlias: Map<Long, Long> = emptyMap()

    /** Músicas da categoria, traduzidas para a cópia que aparece na lista. */
    private fun categorySongs(id: Long): Set<Long> =
        categories.songIds(id).mapTo(HashSet()) { songAlias[it] ?: it }
    private var permissionDenied = false

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var userSeeking = false
    private var bottomInset = 0
    private lateinit var list: RecyclerView

    private val progressTicker = object : Runnable {
        override fun run() {
            updateProgress()
            if (controller?.isPlaying == true) handler.postDelayed(this, 500)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = updatePlayer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            bottomInset = bars.bottom
            applyBottomInset()
            WindowInsetsCompat.CONSUMED
        }

        count = findViewById(R.id.count)
        status = findViewById(R.id.status)
        player = findViewById(R.id.player)
        playerArt = findViewById(R.id.playerArt)
        playerTitle = findViewById(R.id.playerTitle)
        playerArtist = findViewById(R.id.playerArtist)
        seek = findViewById(R.id.seek)
        position = findViewById(R.id.position)
        duration = findViewById(R.id.duration)
        playPause = findViewById(R.id.playPause)
        shuffle = findViewById(R.id.shuffle)
        repeat = findViewById(R.id.repeat)

        playerArt.clipToOutline = true
        playerTitle.isSelected = true // ativa o letreiro (marquee) em títulos longos

        header = findViewById(R.id.header)
        selectionBar = findViewById(R.id.selectionBar)
        selectionCount = findViewById(R.id.selectionCount)
        chips = findViewById(R.id.chips)
        actions = findViewById(R.id.actions)
        listInfo = findViewById(R.id.listInfo)
        categories = CategoryStore(this)
        libraryFilter = LibraryFilter(this)
        rebuildChips()

        list = findViewById(R.id.list)
        list.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            setHasFixedSize(true)
            setItemViewCacheSize(20)
        }

        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                adapter.filter(s)
                updateStatus()
            }
        })

        playPause.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else {
                if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0)
                c.play()
            }
        }
        findViewById<View>(R.id.next).setOnClickListener { controller?.seekToNext() }
        findViewById<View>(R.id.prev).setOnClickListener { controller?.seekToPrevious() }
        shuffle.setOnClickListener { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
        repeat.setOnClickListener {
            controller?.let {
                it.repeatMode = when (it.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) position.text = formatTime(progress.toLong())
            }
            override fun onStartTrackingTouch(bar: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(bar: SeekBar) {
                userSeeking = false
                controller?.seekTo(bar.progress.toLong())
            }
        })
        status.setOnClickListener { if (permissionDenied) requestOrOpenSettings() }

        findViewById<View>(R.id.playAll).setOnClickListener { playVisible(shuffled = false) }
        findViewById<View>(R.id.shuffleAll).setOnClickListener { playVisible(shuffled = true) }
        findViewById<View>(R.id.selectionClose).setOnClickListener { adapter.clearSelection() }
        findViewById<View>(R.id.selectAll).setOnClickListener { adapter.selectAllVisible() }
        findViewById<View>(R.id.categorize).setOnClickListener { showCategorizeDialog() }
        findViewById<View>(R.id.folders).setOnClickListener { showFoldersDialog() }
        findViewById<View>(R.id.deleteSelected).setOnClickListener { confirmDeleteSelected() }
        findViewById<View>(R.id.duplicates).setOnClickListener {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this, DuplicatesActivity::class.java), REQ_DUPLICATES)
        }

        if (hasAudioPermission()) loadSongs() else requestPermissions(permissionsToRequest(), REQ_PERMISSIONS)
    }

    override fun onStart() {
        super.onStart()
        if (!permissionDenied && songsByUri.isEmpty() && hasAudioPermission()) loadSongs()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future) return@addListener
            controller = future.get().also { it.addListener(playerListener) }
            updatePlayer()
        }, mainExecutorCompat)
    }

    override fun onStop() {
        handler.removeCallbacks(progressTicker)
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onStop()
    }

    override fun onDestroy() {
        ioExecutor.shutdown()
        super.onDestroy()
    }

    // ---------- Permissões ----------

    private val audioPermission =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasAudioPermission() =
        checkSelfPermission(audioPermission) == PackageManager.PERMISSION_GRANTED

    private fun permissionsToRequest(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(audioPermission, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(audioPermission)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        if (hasAudioPermission()) {
            permissionDenied = false
            loadSongs()
        } else {
            permissionDenied = true
            updateStatus()
        }
    }

    private fun requestOrOpenSettings() {
        if (shouldShowRequestPermissionRationale(audioPermission)) {
            requestPermissions(permissionsToRequest(), REQ_PERMISSIONS)
        } else {
            // Negado permanentemente: abre as configurações do app.
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            )
            permissionDenied = false // reavalia ao voltar (onStart)
        }
    }

    // ---------- Biblioteca ----------

    private fun loadSongs() {
        status.setText(R.string.loading)
        status.visibility = View.VISIBLE
        val appContext = applicationContext
        val store = categories
        val filter = libraryFilter
        ioExecutor.execute {
            val raw = SongRepository.query(appContext)
            val library = filter.apply(raw)
            val saved = store.loadFromDisk()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                rawSongs = raw
                songsByUri = raw.associateBy { it.uri.toString() }
                store.apply(saved)
                showLibrary(library)
                pruneQueue()
            }
        }
    }

    /** Reaplica pastas/duplicadas sobre a lista já lida, sem consultar o Android de novo. */
    private fun refilterLibrary() {
        val raw = rawSongs
        val filter = libraryFilter
        ioExecutor.execute {
            val library = filter.apply(raw)
            runOnUiThread { if (!isDestroyed) showLibrary(library) }
        }
    }

    private fun showLibrary(library: LibraryFilter.Library) {
        val songs = library.songs
        songAlias = library.alias
        presentIds = songs.mapTo(HashSet()) { it.id }
        if (activeCategory?.let { categories.name(it) } == null) activeCategory = null
        adapter.submit(songs)
        selectCategory(activeCategory)
        count.text = resources.getQuantityString(R.plurals.song_count, songs.size, songs.size)
        updatePlayer()
    }

    // ---------- Excluir ----------

    private fun confirmDeleteSelected() {
        val selected = adapter.selected.toSet()
        if (selected.isEmpty()) return
        // Inclui as cópias escondidas; senão outra cópia apareceria no lugar da música apagada.
        val files = rawSongs.filter { it.id in selected || songAlias[it.id] in selected }
        val songsText = resources.getQuantityString(R.plurals.song_count, selected.size, selected.size)
        val message = if (files.size > selected.size) {
            getString(R.string.delete_songs_with_copies_q, songsText, files.size)
        } else {
            getString(R.string.delete_songs_q, songsText)
        }
        AlertDialog.Builder(this)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteSongs(files.map { it.uri }) }
            .show()
    }

    override fun onSongsDeleted(deleted: List<Uri>) {
        if (deleted.isEmpty()) return
        Toast.makeText(this, getString(R.string.deleted_n, deleted.size), Toast.LENGTH_SHORT).show()
        adapter.clearSelection()
        loadSongs()
    }

    @Deprecated("API de Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DUPLICATES && resultCode == RESULT_OK) loadSongs()
    }

    /** Tira da fila de reprodução arquivos que não existem mais. */
    private fun pruneQueue() {
        val c = controller ?: return
        if (songsByUri.isEmpty()) return
        for (i in c.mediaItemCount - 1 downTo 0) {
            if (c.getMediaItemAt(i).mediaId !in songsByUri) c.removeMediaItem(i)
        }
    }

    private fun showFoldersDialog() {
        val folders = libraryFilter.folders(rawSongs)
        if (folders.isEmpty()) {
            AlertDialog.Builder(this).setMessage(R.string.folders_empty).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val checked = BooleanArray(folders.size) { folders[it].visible }
        val labels = folders.map { "${it.label}  (${it.songCount})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.folders_title)
            .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                folders.forEachIndexed { i, f ->
                    if (checked[i] != f.visible) libraryFilter.setVisible(f.path, checked[i])
                }
                refilterLibrary()
            }
            .show()
    }

    private fun updateStatus() {
        val text = when {
            permissionDenied -> R.string.need_permission
            presentIds.isEmpty() -> R.string.empty
            adapter.itemCount > 0 -> null
            activeCategory != null && categorySongs(activeCategory!!).none { it in presentIds } -> R.string.empty_category
            else -> R.string.no_results
        }
        if (text == null) {
            status.visibility = View.GONE
        } else {
            status.setText(text)
            status.visibility = View.VISIBLE
        }

        val n = adapter.itemCount
        actions.visibility = if (n > 0) View.VISIBLE else View.GONE
        val songsText = resources.getQuantityString(R.plurals.song_count, n, n)
        listInfo.text = activeCategory?.let { "${categories.name(it)} · $songsText" } ?: songsText
    }

    private fun playFrom(index: Int) = playQueue(adapter.items, index)

    /** Toca o que está na tela: a categoria escolhida (ou tudo), respeitando a busca. */
    private fun playVisible(shuffled: Boolean) {
        val queue = adapter.items
        if (queue.isEmpty()) return
        controller?.shuffleModeEnabled = shuffled
        playQueue(queue, if (shuffled) queue.indices.random() else 0)
    }

    private fun playQueue(queue: List<Song>, index: Int) {
        val c = controller ?: return
        c.setMediaItems(queue.map { it.toMediaItem() }, index, 0L)
        c.prepare()
        c.play()
    }

    // ---------- Categorias ----------

    private fun selectCategory(id: Long?) {
        activeCategory = id
        adapter.setCategory(id?.let { categorySongs(it) })
        rebuildChips()
        updateStatus()
        updateBackHandling()
        list.scrollToPosition(0)
    }

    private fun rebuildChips() {
        chips.removeAllViews()
        chips.addView(makeChip(getString(R.string.all), activeCategory == null) { selectCategory(null) })
        for (cat in categories.categories()) {
            val n = categorySongs(cat.id).count { it in presentIds }
            val chip = makeChip("${cat.name}  $n", activeCategory == cat.id) { selectCategory(cat.id) }
            chip.setOnLongClickListener { showCategoryOptions(cat); true }
            chips.addView(chip)
        }
        val add = makeChip("+ " + getString(R.string.new_category), false) { promptCategoryName(null) { selectCategory(it) } }
        add.setBackgroundResource(R.drawable.bg_chip_add)
        add.setTextColor(getColor(R.color.text_secondary))
        chips.addView(add)
    }

    private fun makeChip(text: String, selected: Boolean, onClick: () -> Unit): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(getColorStateList(R.color.chip_text))
            setBackgroundResource(R.drawable.bg_chip)
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
            isSelected = selected
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (36 * d).toInt())
                .apply { marginEnd = (8 * d).toInt() }
            setOnClickListener { onClick() }
        }
    }

    private fun showCategoryOptions(cat: Category) {
        AlertDialog.Builder(this)
            .setTitle(cat.name)
            .setItems(arrayOf(getString(R.string.rename), getString(R.string.delete))) { _, which ->
                if (which == 0) {
                    promptCategoryName(cat) { selectCategory(activeCategory) }
                } else {
                    AlertDialog.Builder(this)
                        .setMessage(getString(R.string.delete_category_q, cat.name))
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.delete) { _, _ ->
                            categories.delete(cat.id)
                            selectCategory(if (activeCategory == cat.id) null else activeCategory)
                        }
                        .show()
                }
            }
            .show()
    }

    /** Cria (existing == null) ou renomeia uma categoria. */
    private fun promptCategoryName(existing: Category?, onDone: (Long) -> Unit) {
        val d = resources.displayMetrics.density
        val input = EditText(this).apply {
            hint = getString(R.string.category_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
            existing?.let { setText(it.name); setSelection(it.name.length) }
        }
        val box = FrameLayout(this).apply {
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.new_category_title else R.string.rename)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(if (existing == null) R.string.create else R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val id = if (existing == null) categories.create(name) else existing.id.also { categories.rename(it, name) }
                onDone(id)
            }
            .create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    /** Diálogo com caixas de seleção: marca/desmarca as categorias das músicas selecionadas. */
    private fun showCategorizeDialog(preChecked: Long? = null) {
        val songs = adapter.selected.toList()
        if (songs.isEmpty()) return
        val cats = categories.categories()
        if (cats.isEmpty()) {
            promptCategoryName(null) { showCategorizeDialog(it) }
            return
        }
        // Marcada = todas as selecionadas já estão nela.
        val initial = BooleanArray(cats.size) { i -> categorySongs(cats[i].id).containsAll(songs) }
        val checked = initial.copyOf()
        cats.indexOfFirst { it.id == preChecked }.takeIf { it >= 0 }?.let { checked[it] = true }

        AlertDialog.Builder(this)
            .setTitle(R.string.categorize_title)
            .setMultiChoiceItems(cats.map { it.name }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setNeutralButton(R.string.new_category_title) { _, _ ->
                promptCategoryName(null) { showCategorizeDialog(it) }
            }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                cats.forEachIndexed { i, cat ->
                    if (checked[i] && !initial[i]) categories.add(cat.id, songs)
                    if (!checked[i] && initial[i]) {
                        // Remove também se a categoria guardou outra cópia da mesma música.
                        val selectedSet = songs.toSet()
                        categories.remove(cat.id, categories.songIds(cat.id).filter { (songAlias[it] ?: it) in selectedSet })
                    }
                }
                adapter.clearSelection()
                selectCategory(activeCategory) // atualiza filtro e contadores
            }
            .show()
    }

    private fun updateSelectionBar() {
        val n = adapter.selected.size
        val selecting = n > 0
        selectionBar.visibility = if (selecting) View.VISIBLE else View.GONE
        header.visibility = if (selecting) View.INVISIBLE else View.VISIBLE
        if (selecting) {
            selectionCount.text = n.toString()
            selectionCount.contentDescription = resources.getQuantityString(R.plurals.selected_count, n, n)
        }
        updateBackHandling()
    }

    // ---------- Voltar: sai da seleção, depois volta para "Todas" ----------

    private fun handleBack(): Boolean = when {
        adapter.inSelection -> { adapter.clearSelection(); true }
        activeCategory != null -> { selectCategory(null); true }
        else -> false
    }

    private var backCallback: Any? = null

    private fun updateBackHandling() {
        if (Build.VERSION.SDK_INT < 33) return
        val needed = adapter.inSelection || activeCategory != null
        val dispatcher = onBackInvokedDispatcher
        if (needed && backCallback == null) {
            val cb = OnBackInvokedCallback { handleBack() }
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        } else if (!needed && backCallback != null) {
            dispatcher.unregisterOnBackInvokedCallback(backCallback as OnBackInvokedCallback)
            backCallback = null
        }
    }

    @Deprecated("Usado só no Android 12 ou anterior")
    override fun onBackPressed() {
        if (!handleBack()) @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---------- Player ----------

    private fun updatePlayer() {
        val c = controller
        val item = c?.currentMediaItem
        if (c == null || item == null) {
            player.visibility = View.GONE
            adapter.setCurrent(null)
            applyBottomInset()
            return
        }
        if (player.visibility != View.VISIBLE) {
            player.visibility = View.VISIBLE
            applyBottomInset()
        }

        val meta = item.mediaMetadata
        if (playerTitle.text != meta.title) playerTitle.text = meta.title
        playerArtist.text = meta.artist
        val song = songsByUri[item.mediaId]
        if (playerArt.tag != song?.id || song == null) ArtLoader.load(playerArt, song)
        adapter.setCurrent(item.mediaId)

        playPause.setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        shuffle.imageTintList = getColorStateList(if (c.shuffleModeEnabled) R.color.accent else R.color.icon_inactive)
        repeat.setImageResource(if (c.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        repeat.imageTintList = getColorStateList(
            if (c.repeatMode == Player.REPEAT_MODE_OFF) R.color.icon_inactive else R.color.accent
        )

        handler.removeCallbacks(progressTicker)
        progressTicker.run()
    }

    /** O painel do player se estende até atrás da barra de navegação; sem player, a lista é que ganha o espaço. */
    private fun applyBottomInset() {
        val base = (resources.displayMetrics.density * 8).toInt()
        val playerVisible = player.visibility == View.VISIBLE
        list.setPadding(0, 0, 0, base + if (playerVisible) 0 else bottomInset)
        player.setPadding(player.paddingLeft, player.paddingTop, player.paddingRight, base + bottomInset)
    }

    private fun updateProgress() {
        val c = controller ?: return
        val dur = c.duration.takeIf { it > 0 } ?: 0L
        seek.max = dur.toInt()
        duration.text = formatTime(dur)
        if (!userSeeking) {
            seek.progress = c.currentPosition.toInt()
            position.text = formatTime(c.currentPosition)
        }
    }

    companion object {
        private const val REQ_PERMISSIONS = 1
        private const val REQ_DUPLICATES = 2
    }
}
