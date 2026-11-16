package com.example.padsampler.platform

import com.example.padsampler.midi.MidiListener
import com.example.padsampler.midi.MidiOut
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Sve što se razlikuje između Androida i računara. Zajednički kod (SamplerViewModel, Library, ...)
 * zna samo za ovaj interfejs; Android implementacija je AndroidPlatform.
 */

/** Dekodirani zvuk: interleaved stereo float. */
class DecodedAudio(val data: FloatArray, val frames: Int, val sampleRate: Int)

/** Jedan unos u folderu (stablu). size = 0 ako nije poznata. */
class DocChild(val id: String, val name: String, val isDir: Boolean, val size: Long = 0L)

/**
 * Fajlovi i folderi koje korisnik izabere. "ref" i "tree" su obični tekstovi
 * (Android: Uri kao tekst, računar: putanja), pa ih zajednički kod ne mora poznavati.
 */
interface DocFiles {
    /** Ime fajla za prikaz; null ako se ne može pročitati. */
    fun displayName(ref: String): String?

    /** Putanja za prepoznavanje BPM-a/tonaliteta i imena foldera; null ako je nema. */
    fun documentPath(ref: String): String?

    fun openInput(ref: String): InputStream?

    /** Izlazni tok u fajl koji je korisnik izabrao za spremanje ("Save as"); prepisuje sadržaj. */
    fun openOutputFile(ref: String): OutputStream?

    // ---- folder (stablo) i njegovi dokumenti
    /** Id korijena izabranog foldera; null ako folder nije ispravan. */
    fun treeRootId(tree: String): String?

    fun children(tree: String, docId: String): List<DocChild>

    /** "ref" dokumenta unutar stabla (za openInput). */
    fun childRef(tree: String, docId: String): String

    /** Pravi fajl ili folder; vraća id novog dokumenta ili null. */
    fun createChild(tree: String, parentId: String, name: String, dir: Boolean): String?

    /** Izlazni tok u postojeći dokument (prepisuje sadržaj). */
    fun openOutput(tree: String, docId: String): OutputStream?

    /** Trajno pamti dozvolu za folder (Android SAF); na računaru ne radi ništa. */
    fun rememberTree(tree: String)
    fun forgetTree(tree: String)
}

interface Platform {
    /** Privatni folder aplikacije (pads.json, samples/, sesije, indeks biblioteke). */
    val filesDir: File
    val cacheDir: File
    val docs: DocFiles

    /** Jednostavne postavke (broj). */
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)

    /** Postavke kao tekst (npr. izabrana MIDI klavijatura). */
    fun getString(key: String, default: String = ""): String
    fun putString(key: String, value: String)

    /** Mrežna dozvola za Ableton Link (Android: multicast lock). close() je otpušta. Null ako ne treba. */
    fun acquireLinkLock(): AutoCloseable?

    /** Dekodiranje formata koje C++ ne zna (mp3...). Null ako ne ide. */
    fun decodeAudio(path: String): DecodedAudio?

    /** Launchpad MIDI veza (Android: android.media.midi, računar: Java MIDI). */
    fun createMidi(listener: MidiListener): MidiOut

    /** MIDI klavijatura (ulaz za Keys): svi MIDI ulazi osim Launchpada. */
    fun createKeyboard(listener: com.example.padsampler.midi.KeyboardListener): com.example.padsampler.midi.MidiKeyboard
}
