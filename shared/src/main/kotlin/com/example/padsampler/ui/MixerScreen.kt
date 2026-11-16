package com.example.padsampler.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.padsampler.model.ControlState
import com.example.padsampler.model.Fader
import com.example.padsampler.model.Layout
import com.example.padsampler.model.LiveState
import com.example.padsampler.model.PadConfig
import com.example.padsampler.model.TrackColors

/** 8 kanala: ime (folder prvog sempla u koloni), LED meter, fader, dB, M i S. */
@Composable
fun MixerScreen(
    pads: Map<Int, PadConfig>,
    live: LiveState,
    control: ControlState,
    onFader: (Int, Float) -> Unit,
    onMute: (Int) -> Unit,
    onSolo: (Int) -> Unit,
    onPan: (Int, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val compact = LocalUiPlatform.current.screenWidthDp() < 1000
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp)) {
        for (track in 0 until Layout.TRACKS) {
            val color = Color(TrackColors.all[track].argb)
            val firstLabel = (0 until Layout.SCENES).firstNotNullOfOrNull { pads[Layout.slot(it, track)]?.label }
            ChannelStrip(
                name = firstLabel ?: "Track ${track + 1}",
                color = color,
                value = control.faders[track],
                meter = Fader.meter(live.levels[track]),
                muted = control.mutes[track],
                soloed = control.solos[track],
                showSolo = true,
                onChange = { onFader(track, it) },
                onMute = { onMute(track) },
                onSolo = { onSolo(track) },
                pan = control.pans[track],
                onPan = { onPan(track, it) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
    }
}
