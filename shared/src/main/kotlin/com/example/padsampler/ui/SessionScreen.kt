package com.example.padsampler.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.padsampler.audio.NativeAudio
import com.example.padsampler.model.BottomMode
import com.example.padsampler.model.ControlState
import com.example.padsampler.model.Layout
import com.example.padsampler.model.LiveState
import com.example.padsampler.model.PadConfig
import com.example.padsampler.model.TrackColors

private const val GAP = 10

/** Replika Launchpada: 6 redova klipova + scene dugmad, donji red Stop/Solo/Mute. Metronom je u gornjoj traci. */
@Composable
fun SessionScreen(
    pads: Map<Int, PadConfig>,
    live: LiveState,
    control: ControlState,
    editMode: Boolean,
    onClip: (Int) -> Unit,
    onScene: (Int) -> Unit,
    onBottom: (Int) -> Unit,
    onCycleMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val blink by rememberInfiniteTransition(label = "blink").animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(260), RepeatMode.Reverse),
        label = "blinkAlpha"
    )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(GAP.dp)) {
        for (scene in 0 until Layout.SCENES) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(GAP.dp)
            ) {
                var anyClip = false
                for (track in 0 until Layout.TRACKS) {
                    val slot = Layout.slot(scene, track)
                    if (live.slotStates[slot].toInt() != NativeAudio.EMPTY) anyClip = true
                    ClipPad(
                        config = pads[slot],
                        state = live.slotStates[slot].toInt(),
                        track = track,
                        progress = live.progress[track],
                        blink = blink,
                        editMode = editMode,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        onTap = { onClip(slot) }
                    )
                }
                SceneButton(
                    number = scene + 1,
                    lit = anyClip,
                    modifier = Modifier
                        .weight(0.6f)
                        .fillMaxHeight(),
                    onTap = { onScene(scene) }
                )
            }
        }

        // Donji red: Stop / Solo / Mute + dugme moda
        val modeColor = when (control.bottomMode) {
            BottomMode.STOP -> StopRed
            BottomMode.SOLO -> SoloBlue
            BottomMode.MUTE -> MuteYellow
        }
        val modeLabel = when (control.bottomMode) {
            BottomMode.STOP -> "STOP"
            BottomMode.SOLO -> "SOLO"
            BottomMode.MUTE -> "MUTE"
        }
        val phone = LocalUiPlatform.current.screenWidthDp() < 1000
        Row(
            modifier = Modifier
                .weight(0.55f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(GAP.dp)
        ) {
            for (track in 0 until Layout.TRACKS) {
                val trackColor = Color(TrackColors.all[track].argb)
                val active = when (control.bottomMode) {
                    BottomMode.STOP -> false
                    BottomMode.SOLO -> control.solos[track]
                    BottomMode.MUTE -> control.mutes[track]
                }
                val glyph = when (control.bottomMode) {
                    BottomMode.STOP -> "■"
                    BottomMode.SOLO -> "S"
                    BottomMode.MUTE -> "M"
                }
                val shape = rShape(12)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .background(
                            if (active) Brush.verticalGradient(listOf(modeColor, modeColor))
                            else Brush.verticalGradient(listOf(Color(0xFF22262D), Color(0xFF1B1E24)))
                        )
                        .border(1.dp, trackColor.copy(alpha = 0.3f), shape)
                        .clickable { onBottom(track) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(glyph, color = if (active) Ink else trackColor, fontSize = if (phone) 10.sp else 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            val shape = rShape(12)
            Box(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(Brush.verticalGradient(listOf(modeColor.lighten(0.15f), modeColor)))
                    .clickable { onCycleMode() },
                contentAlignment = Alignment.Center
            ) {
                Text(modeLabel, color = Ink, fontSize = if (phone) 9.sp else 12.sp, fontWeight = FontWeight.Bold, letterSpacing = if (phone) 0.sp else 1.sp, textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun SceneButton(number: Int, lit: Boolean, modifier: Modifier, onTap: () -> Unit) {
    val shape = rShape(12)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(BtnTop, BtnBottom)))
            .border(1.dp, Color.White.copy(alpha = 0.06f), shape)
            .clickable { onTap() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            "▶ $number",
            color = if (lit) AccentBright else TextFaint,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ClipPad(
    config: PadConfig?,
    state: Int,
    track: Int,
    progress: Float,
    blink: Float,
    editMode: Boolean,
    modifier: Modifier,
    onTap: () -> Unit
) {
    val base = Color(TrackColors.all[track].argb)
    val shape = rShape(14)
    val playing = state == NativeAudio.PLAYING
    val stopping = state == NativeAudio.QUEUED_STOP
    val queued = state == NativeAudio.QUEUED_START
    val idle = state == NativeAudio.IDLE

    // Pad sa semplom: ravna boja (bez preliva), izdignut sjenkom; prazan: udubljen. Svira: kao prije (preliv + sjaj).
    val idleFace = base.copy(alpha = 0.18f).compositeOver(PadBase)
    var top = when {
        playing || stopping -> base
        idle || queued -> idleFace
        else -> EmptyPadColor
    }
    var bottom = when {
        playing || stopping -> base.darken(0.74f)
        idle || queued -> idleFace
        else -> EmptyPadColor
    }
    val lit = playing || stopping
    val empty = !lit && !idle && !queued
    if (stopping) {
        val dim = (1f - blink) * 0.35f
        top = lerp(top, Color.Black, dim)
        bottom = lerp(bottom, Color.Black, dim)
    }
    val borderColor = when {
        playing || stopping -> Color.White.copy(alpha = 0.2f)
        queued -> base.copy(alpha = blink)
        idle -> base.copy(alpha = 0.30f)
        else -> Color.White.copy(alpha = 0.04f)
    }
    val borderWidth = if (queued) 2.dp else 1.dp
    val textColor = if (playing || stopping) Ink else Color(0xFFDFE3E9)

    Box(
        modifier = modifier
            // pad koji svira svijetli jednako okolo (ne baca sjenku na jednu stranu)
            .then(if (playing) Modifier.glow(shape, base, 1.3f) else Modifier)
            .then(if (!lit && !empty) Modifier.raised(shape, 3.dp) else Modifier)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .then(
                when {
                    lit -> Modifier.bevel(shape, glow = true)
                    empty -> Modifier.border(1.dp, Color.White.copy(alpha = 0.04f), shape)
                    else -> Modifier.border(borderWidth, borderColor, shape)
                }
            )
            .then(if (editMode) Modifier.border(2.dp, EditBorder, shape) else Modifier)
            .clickable { onTap() }
    ) {
        if (config != null) {
            Text(
                text = if (config.loop) "LOOP" else "SHOT",
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = (if (playing || stopping) Ink else base).copy(alpha = 0.75f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 11.dp, top = 8.dp)
            )
            config.key?.let { k ->
                Text(
                    text = k,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = (if (playing || stopping) Ink else base).copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 9.dp, top = 7.dp)
                )
            }
            Text(
                text = config.label,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 11.dp, end = 8.dp, bottom = 10.dp)
            )
        }
        if (playing || stopping) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .padding(0.dp)
                    .background(Ink.copy(alpha = 0.5f))
                    .padding(vertical = 2.dp)
            )
        }
    }
}
