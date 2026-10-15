package dev.mediaexecuter.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFilterTest {

    private val internal = "/storage/emulated/0/Music"
    private val internal2 = "/storage/emulated/0/Download"
    private val sd = "/storage/1A2B-3C4D/Musicas"
    private val sd2 = "/storage/1A2B-3C4D/Backup"

    private fun delete(copies: List<String>, fromSdCard: Boolean) =
        LibraryFilter.copiesToDelete(copies.map { StringBuilder(it) }, fromSdCard) { it.toString() }.map { it.toString() }

    @Test
    fun detectaCartaoSd() {
        assertTrue(LibraryFilter.isSdCard(sd))
        assertFalse(LibraryFilter.isSdCard(internal))
        assertFalse(LibraryFilter.isSdCard("/storage/self/primary/Music"))
    }

    @Test
    fun apagarDoInternoMantemACopiaDoCartao() {
        assertEquals(listOf(internal), delete(listOf(internal, sd), fromSdCard = false))
        assertEquals(listOf(internal, internal2), delete(listOf(internal, internal2, sd), fromSdCard = false))
    }

    @Test
    fun apagarDoCartaoMantemACopiaDoInterno() {
        assertEquals(listOf(sd), delete(listOf(internal, sd), fromSdCard = true))
    }

    @Test
    fun todasNoMesmoLugarMantemAMelhor() {
        // Só no interno: mesmo com "apagar do interno", sobra a primeira (melhor) cópia.
        assertEquals(listOf(internal2), delete(listOf(internal, internal2), fromSdCard = false))
        assertEquals(listOf(sd2), delete(listOf(sd, sd2), fromSdCard = true))
    }

    @Test
    fun duasCopiasNoCartaoEUmaNoInternoSobraUmaSo() {
        // Apagar do interno: fica a melhor do cartão, apaga o interno e a outra do cartão.
        assertEquals(listOf(internal, sd2), delete(listOf(internal, sd, sd2), fromSdCard = false))
    }

    @Test
    fun copiaUnicaNuncaEApagada() {
        assertEquals(emptyList<String>(), delete(listOf(sd), fromSdCard = true))
    }
}
