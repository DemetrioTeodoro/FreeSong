package dev.mediaexecuter.player

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executors

data class Category(val id: Long, val name: String)

/**
 * Categorias criadas pelo usuário (ex.: "Rock", "Anos 80").
 * Uma música pode estar em várias. Tudo fica em memória para a UI ser instantânea;
 * o SQLite só persiste em segundo plano.
 *
 * Acessar os métodos públicos apenas da main thread (exceto [loadFromDisk]).
 */
class CategoryStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "categories.db", null, 1) {

    private val io = Executors.newSingleThreadExecutor()
    private val names = HashMap<Long, String>()
    private val members = HashMap<Long, MutableSet<Long>>()
    private val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE category (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE song_category (category_id INTEGER NOT NULL, song_id INTEGER NOT NULL, " +
                "PRIMARY KEY (category_id, song_id)) WITHOUT ROWID"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** Lê tudo do disco. Chamar fora da main thread; aplicar o resultado com [apply]. */
    fun loadFromDisk(): Snapshot {
        val n = HashMap<Long, String>()
        val m = HashMap<Long, MutableSet<Long>>()
        val db = readableDatabase
        db.rawQuery("SELECT id, name FROM category", null).use {
            while (it.moveToNext()) {
                n[it.getLong(0)] = it.getString(1)
                m[it.getLong(0)] = HashSet()
            }
        }
        db.rawQuery("SELECT category_id, song_id FROM song_category", null).use {
            while (it.moveToNext()) m[it.getLong(0)]?.add(it.getLong(1))
        }
        return Snapshot(n, m)
    }

    fun apply(snapshot: Snapshot) {
        names.clear(); names.putAll(snapshot.names)
        members.clear(); members.putAll(snapshot.members)
    }

    class Snapshot(val names: Map<Long, String>, val members: Map<Long, MutableSet<Long>>)

    fun categories(): List<Category> =
        names.map { Category(it.key, it.value) }.sortedWith { a, b -> collator.compare(a.name, b.name) }

    fun name(id: Long): String? = names[id]

    fun songIds(categoryId: Long): Set<Long> = members[categoryId] ?: emptySet()

    /** Cria (ou reaproveita, se já existir com o mesmo nome) e devolve o id. */
    fun create(name: String): Long {
        names.entries.firstOrNull { collator.compare(it.value, name) == 0 }?.let { return it.key }
        val id = writableDatabase.insert("category", null, ContentValues().apply { put("name", name) })
        names[id] = name
        members[id] = HashSet()
        return id
    }

    fun rename(id: Long, name: String) {
        names[id] = name
        io.execute {
            writableDatabase.update("category", ContentValues().apply { put("name", name) }, "id = ?", arrayOf("$id"))
        }
    }

    fun delete(id: Long) {
        names.remove(id)
        members.remove(id)
        io.execute {
            writableDatabase.transaction {
                delete("song_category", "category_id = ?", arrayOf("$id"))
                delete("category", "id = ?", arrayOf("$id"))
            }
        }
    }

    fun add(categoryId: Long, songIds: Collection<Long>) {
        val set = members.getOrPut(categoryId) { HashSet() }
        val added = songIds.filter { set.add(it) }
        if (added.isEmpty()) return
        io.execute {
            writableDatabase.transaction {
                val stmt = compileStatement("INSERT OR IGNORE INTO song_category (category_id, song_id) VALUES (?, ?)")
                for (song in added) {
                    stmt.bindLong(1, categoryId)
                    stmt.bindLong(2, song)
                    stmt.executeInsert()
                }
            }
        }
    }

    fun remove(categoryId: Long, songIds: Collection<Long>) {
        val set = members[categoryId] ?: return
        val removed = songIds.filter { set.remove(it) }
        if (removed.isEmpty()) return
        io.execute {
            writableDatabase.transaction {
                val stmt = compileStatement("DELETE FROM song_category WHERE category_id = ? AND song_id = ?")
                for (song in removed) {
                    stmt.bindLong(1, categoryId)
                    stmt.bindLong(2, song)
                    stmt.executeUpdateDelete()
                }
            }
        }
    }

    private inline fun SQLiteDatabase.transaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }
}
