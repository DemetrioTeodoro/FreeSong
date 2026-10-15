package dev.mediaexecuter.player

import android.content.Context

/**
 * Decide o que aparece na biblioteca:
 *  - esconde pastas de áudios que não são música (WhatsApp, gravações, toques...)
 *    e as pastas que o usuário desmarcou;
 *  - esconde áudios muito curtos;
 *  - mostra cada música só uma vez, mesmo que exista em várias pastas / no cartão SD.
 *
 * Nada é apagado: só deixa de aparecer na lista.
 */
class LibraryFilter(context: Context) {

    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    /** Pastas que o usuário escondeu. */
    private val hidden = prefs.getStringSet(KEY_HIDDEN, null).orEmpty().toMutableSet()
    /** Pastas escondidas automaticamente que o usuário pediu para mostrar. */
    private val shown = prefs.getStringSet(KEY_SHOWN, null).orEmpty().toMutableSet()

    data class Folder(val path: String, val label: String, val songCount: Int, val visible: Boolean)

    fun isVisible(folder: String) = folder !in hidden && (folder in shown || !isAutoHidden(folder))

    fun setVisible(folder: String, visible: Boolean) {
        if (visible) {
            hidden -= folder
            if (isAutoHidden(folder)) shown += folder
        } else {
            shown -= folder
            hidden += folder
        }
        prefs.edit().putStringSet(KEY_HIDDEN, HashSet(hidden)).putStringSet(KEY_SHOWN, HashSet(shown)).apply()
    }

    /** Todas as pastas que têm músicas (já ignorando áudios curtos), para a tela de pastas. */
    fun folders(all: List<Song>): List<Folder> =
        all.asSequence()
            .filter { it.durationMs >= MIN_DURATION_MS }
            .groupingBy { it.folder }
            .eachCount()
            .map { (path, count) -> Folder(path, label(path), count, isVisible(path)) }
            .sortedBy { it.label.lowercase() }

    /**
     * @param songs lista final da biblioteca (mantém a ordem de `all`).
     * @param alias cópia escondida -> cópia mostrada da mesma música; usado para que
     *              categorias continuem valendo qualquer que seja a cópia exibida.
     */
    class Library(val songs: List<Song>, val alias: Map<Long, Long>)

    /** Monta a biblioteca. Chamar fora da main thread. */
    fun apply(all: List<Song>): Library {
        val keep = HashSet<Long>()
        val alias = HashMap<Long, Long>()
        for (cluster in copies(all)) {
            val shown = cluster.firstOrNull { isVisible(it.folder) } ?: continue
            keep += shown.id
            for (copy in cluster) if (copy.id != shown.id) alias[copy.id] = shown.id
        }
        return Library(all.filter { it.id in keep }, alias)
    }

    /** Uma música com cópias. [copies] começa pela cópia que aparece na lista ([shown]). */
    class DuplicateGroup(val shown: Song, val copies: List<Song>)

    /** Músicas que existem em mais de um arquivo, na ordem da lista. Chamar fora da main thread. */
    fun duplicateGroups(all: List<Song>): List<DuplicateGroup> =
        copies(all).filter { it.size > 1 }.map { cluster ->
            val shown = cluster.firstOrNull { isVisible(it.folder) } ?: cluster[0]
            DuplicateGroup(shown, listOf(shown) + cluster.filter { it.id != shown.id })
        }

    /**
     * Agrupa os arquivos que são a mesma música: mesmo título (sem "(1)", "- Cópia"...),
     * mesmo artista e duração parecida. Cada grupo vem ordenado do melhor arquivo
     * (maior = melhor qualidade; empate -> o mais antigo) para o pior.
     */
    private fun copies(all: List<Song>): List<List<Song>> {
        val result = ArrayList<List<Song>>()
        val eligible = all.filter { it.durationMs >= MIN_DURATION_MS }
        for (group in eligible.groupBy { duplicateKey(it) }.values) {
            val clusters = ArrayList<MutableList<Song>>()
            for (song in group.sortedWith(compareByDescending<Song> { it.sizeBytes }.thenBy { it.id })) {
                val cluster = clusters.firstOrNull {
                    kotlin.math.abs(it[0].durationMs - song.durationMs) <= DURATION_TOLERANCE_MS
                }
                if (cluster != null) cluster += song else clusters += mutableListOf(song)
            }
            result += clusters
        }
        return result
    }

    private fun duplicateKey(song: Song): String {
        val title = COPY_SUFFIX.replace(normalize(song.title).trim(), "").trim()
        return title + "|" + normalize(song.artist).trim()
    }

    companion object {
        private const val KEY_HIDDEN = "hidden_folders"
        private const val KEY_SHOWN = "shown_folders"
        const val MIN_DURATION_MS = 30_000L
        private const val DURATION_TOLERANCE_MS = 2_000L

        /** " (1)", " - copia", " - copy 2", " copia"... no fim do título (já sem acento/minúsculo). */
        private val COPY_SUFFIX = Regex("""(\s*\(\d+\)|\s*-?\s*(copia|copy)(\s*\d+)?)+$""")

        /** Trechos de caminho de pastas que quase nunca são música. */
        private val AUTO_HIDDEN = listOf(
            "whatsapp", "telegram", "signal", "messenger",
            "record", "gravac", "gravad", "callrec", "call rec",
            "/ringtones/", "/notifications/", "/alarms/", "/ui/", "/audiobooks/", "/podcasts/",
            "/android/data/", "/android/media/",
        )

        fun isAutoHidden(folder: String): Boolean {
            val f = normalize(folder) + "/"
            return AUTO_HIDDEN.any { it in f }
        }

        /** true para arquivos no cartão SD (/storage/1A2B-3C4D/...), false para o armazenamento interno. */
        fun isSdCard(path: String): Boolean =
            path.startsWith("/storage/") && !path.startsWith("/storage/emulated/") && !path.startsWith("/storage/self/")

        /**
         * Quais cópias de UMA música apagar ao "apagar do cartão SD" ([fromSdCard] = true)
         * ou "apagar do interno" (false). Sempre sobra exatamente 1 cópia:
         * de preferência no outro armazenamento; se todas estão no mesmo lugar, fica a primeira
         * (a lista vem ordenada da melhor cópia para a pior).
         */
        fun <T> copiesToDelete(copies: List<T>, fromSdCard: Boolean, folderOf: (T) -> String): List<T> {
            if (copies.size < 2) return emptyList()
            val keep = copies.firstOrNull { isSdCard(folderOf(it)) != fromSdCard } ?: copies.first()
            return copies.filter { it !== keep }
        }

        /** "/storage/emulated/0/Music/Rock" -> "Interno › Music/Rock"; "/storage/1A2B-3C4D/Music" -> "Cartão SD › Music". */
        fun label(path: String): String {
            val internal = Regex("^/storage/emulated/\\d+/?").find(path)
            if (internal != null) return "Interno › " + path.substring(internal.range.last + 1).ifEmpty { "(raiz)" }
            val sd = Regex("^/storage/[^/]+/?").find(path)
            if (sd != null) return "Cartão SD › " + path.substring(sd.range.last + 1).ifEmpty { "(raiz)" }
            return path
        }
    }
}
