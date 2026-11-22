package com.example.padsampler.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.painter.Painter

/**
 * Sve u izgledu što se razlikuje između Androida i računara. Ekrani ga uzimaju iz LocalUiPlatform.
 * Izbor fajla i foldera vraća tekstualnu referencu (Android: Uri kao tekst, računar: putanja).
 */
interface UiPlatform {
    @Composable fun logoPainter(): Painter

    /** Širina prozora u dp; ispod 1000 se koristi kompaktan raspored (telefon). */
    @Composable fun screenWidthDp(): Int

    /** Tipka/pokret "nazad" dok je ekran prikazan. */
    @Composable fun BackHandler(onBack: () -> Unit)

    /** Vraća funkciju koja otvara izbor jednog audio fajla; rezultat ide u onPicked. */
    @Composable fun rememberFilePicker(onPicked: (String) -> Unit): () -> Unit

    /** Vraća funkciju koja otvara izbor foldera; rezultat ide u onPicked. */
    @Composable fun rememberFolderPicker(onPicked: (String) -> Unit): () -> Unit

    /** Izbor .mid fajla (IMPORT MIDI u Keys). */
    @Composable fun rememberMidiFilePicker(onPicked: (String) -> Unit): () -> Unit

    /** Izbor .padsession fajla za uvoz sesije. */
    @Composable fun rememberSessionFilePicker(onPicked: (String) -> Unit): () -> Unit

    /** "Sačuvaj kao" za .padsession; poziva se sa predloženim imenom fajla (npr. "Pjesma.padsession"). */
    @Composable fun rememberSessionSavePicker(onPicked: (String) -> Unit): (suggestedName: String) -> Unit

    /** Izbor .padmidi fajla (uvoz preseta MIDI mapiranja). */
    @Composable fun rememberMidiPresetFilePicker(onPicked: (String) -> Unit): () -> Unit

    /** "Sačuvaj kao" za .padmidi; poziva se sa predloženim imenom fajla. */
    @Composable fun rememberMidiPresetSavePicker(onPicked: (String) -> Unit): (suggestedName: String) -> Unit

    /** Ima li platforma ekran podešavanja (dugme ⚙ u gornjoj traci). Računar: audio uređaj, MIDI, full screen. */
    val hasSettings: Boolean get() = false

    /** Dijalog podešavanja (prikazuje se samo kad je hasSettings = true). */
    @Composable fun SettingsDialog(onClose: () -> Unit) {}

    /**
     * Android: true = štednja baterije je uključena za PadSampler (sistem može pauzirati zvuk u pozadini),
     * false = isključena. null = platforma to nema (računar). ☰ meni tada ne prikazuje stavku.
     */
    fun batteryRestricted(): Boolean? = null

    /** Otvara sistemska podešavanja aplikacije (baterija), da korisnik sam isključi štednju. */
    fun openBatterySettings() {}

    /** Stanje baterije za gornju traku; null = platforma ne prikazuje bateriju (računar). Zove se povremeno. */
    fun battery(): BatteryInfo? = null
}

/** Baterija uređaja: procenat 0..100 i da li se puni. */
data class BatteryInfo(val percent: Int, val charging: Boolean)

val LocalUiPlatform = staticCompositionLocalOf<UiPlatform> {
    error("UiPlatform nije postavljen (CompositionLocalProvider(LocalUiPlatform provides ...))")
}
