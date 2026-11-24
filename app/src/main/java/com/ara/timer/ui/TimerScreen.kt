package com.ara.timer.ui

import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ara.timer.R
import com.ara.timer.TimerConfig
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val CardShape = RoundedCornerShape(20.dp)
private val PillShape = RoundedCornerShape(percent = 50)
private const val TABULAR_FIGURES = "tnum"

@Composable
fun TimerScreen(state: TimerUiState, actions: TimerActions, modifier: Modifier = Modifier) {
    val accent by animateColorAsState(
        targetValue = if (state.ringing) AraColors.Amber else AraColors.Green,
        animationSpec = tween(durationMillis = 500),
        label = "accent",
    )
    val glow by animateFloatAsState(
        targetValue = if (state.running) 0.20f else 0.10f,
        animationSpec = tween(durationMillis = 600),
        label = "glow",
    )
    Box(modifier.fillMaxSize().background(AraColors.Background)) {
        AmbientGlow(accent, glow)
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (maxWidth > maxHeight) {
                LandscapeLayout(state, actions, accent, maxWidth, maxHeight)
            } else {
                PortraitLayout(state, actions, accent, maxWidth, maxHeight)
            }
        }
    }
}

@Composable
private fun PortraitLayout(
    state: TimerUiState,
    actions: TimerActions,
    accent: Color,
    maxWidth: Dp,
    maxHeight: Dp,
) {
    val dialSize = minOf(maxWidth - 72.dp, 300.dp, maxHeight * 0.40f).coerceAtLeast(180.dp)
    Column(Modifier.fillMaxSize()) {
        // Everything but the button scrolls if it doesn't fit (small screens, large text,
        // warning cards); the Start/Stop button always stays in reach at the bottom.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = this.maxHeight)
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Header(state, accent)
                Column(
                    modifier = Modifier.padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Dial(state, accent, dialSize)
                    Spacer(Modifier.height(28.dp))
                    Details(state, actions)
                }
                Spacer(Modifier.height(0.dp))
            }
            BottomFade(Modifier.align(Alignment.BottomCenter))
        }
        Box(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            StartStopButton(state.running, actions)
        }
    }
}

@Composable
private fun LandscapeLayout(
    state: TimerUiState,
    actions: TimerActions,
    accent: Color,
    maxWidth: Dp,
    maxHeight: Dp,
) {
    val dialSize = minOf(maxHeight - 40.dp, maxWidth * 0.42f, 300.dp).coerceAtLeast(160.dp)
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Dial(state, accent, dialSize)
        }
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1.1f).fillMaxHeight()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = this.maxHeight)
                        .padding(top = 16.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                ) {
                    Header(state, accent)
                    Details(state, actions)
                }
                BottomFade(Modifier.align(Alignment.BottomCenter))
            }
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                StartStopButton(state.running, actions)
            }
        }
    }
}

@Composable
private fun Details(state: TimerUiState, actions: TimerActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Warnings first, so e.g. "phone is on vibrate" is visible without scrolling.
        state.warnings.forEach { WarningCard(it, state.vibrate, actions) }
        StatsRow(state)
        SettingsCard(state, actions)
    }
}

/** Softens the edge where scrolling content slides under the Start/Stop button. */
@Composable
private fun BottomFade(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(20.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, AraColors.Background))),
    )
}

@Composable
private fun AmbientGlow(accent: Color, intensity: Float) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = intensity), Color.Transparent),
                center = Offset(size.width / 2f, size.height * 0.36f),
                radius = size.maxDimension * 0.6f,
            ),
        )
    }
}

@Composable
private fun Header(state: TimerUiState, accent: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Ara") }
                    withStyle(SpanStyle(fontWeight = FontWeight.Light, color = AraColors.TextSecondary)) {
                        append(" Timer")
                    }
                },
                color = AraColors.TextPrimary,
                fontSize = 26.sp,
            )
            Text(
                text = stringResource(R.string.tagline),
                color = AraColors.TextSecondary,
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        StatusPill(state, accent)
    }
}

@Composable
private fun StatusPill(state: TimerUiState, accent: Color) {
    val label = when {
        state.ringing -> R.string.status_ringing
        state.running -> R.string.status_running
        else -> R.string.status_ready
    }
    val color = if (state.running) accent else AraColors.TextSecondary
    Row(
        modifier = Modifier
            .clip(PillShape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), PillShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(label), color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Dial(state: TimerUiState, accent: Color, size: Dp) {
    val waves: State<Float>? = if (state.ringing) {
        rememberInfiniteTransition(label = "ringing").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1400, easing = LinearEasing)),
            label = "waves",
        )
    } else {
        null
    }
    // While ringing the timer is paused at the end of its countdown, so the ring stays full.
    val progress = if (state.running) state.cycleFraction.coerceIn(0f, 1f) else 0f

    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val half = this.size.minDimension / 2f
            val strokeWidth = half * 0.096f
            val radius = half * 0.80f
            val c = center

            waves?.let { anim ->
                for (k in 0 until 2) {
                    val p = (anim.value + k * 0.5f) % 1f
                    drawCircle(
                        color = accent.copy(alpha = 0.35f * (1f - p)),
                        radius = radius + p * radius * 0.18f,
                        center = c,
                        style = Stroke(width = strokeWidth * 0.5f),
                    )
                }
            }

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(AraColors.SurfaceHigh, AraColors.Background),
                    center = c,
                    radius = radius,
                ),
                radius = radius - strokeWidth * 0.5f,
                center = c,
            )

            val tickOuter = radius - strokeWidth * 1.4f
            for (i in 0 until 60) {
                val major = i % 5 == 0
                val length = if (major) strokeWidth * 0.9f else strokeWidth * 0.45f
                val angle = Math.toRadians(i * 6.0 - 90.0)
                val dx = cos(angle).toFloat()
                val dy = sin(angle).toFloat()
                val lit = state.running && i / 60f < progress
                drawLine(
                    color = if (lit) accent.copy(alpha = 0.8f) else AraColors.Outline,
                    start = Offset(c.x + dx * (tickOuter - length), c.y + dy * (tickOuter - length)),
                    end = Offset(c.x + dx * tickOuter, c.y + dy * tickOuter),
                    strokeWidth = if (major) 2.dp.toPx() else 1.2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            drawCircle(color = AraColors.Track, radius = radius, center = c, style = Stroke(strokeWidth))

            if (state.running) {
                val sweep = 360f * progress
                val topLeft = Offset(c.x - radius, c.y - radius)
                val arcSize = Size(radius * 2f, radius * 2f)
                drawArc(
                    color = accent.copy(alpha = 0.22f),
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(strokeWidth * 2.2f, cap = StrokeCap.Round),
                )
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(strokeWidth, cap = StrokeCap.Round),
                )
                if (!state.ringing) { // no moving tip while the timer is paused
                    val headAngle = Math.toRadians(-90.0 + sweep)
                    drawCircle(
                        color = Color.White,
                        radius = strokeWidth * 0.30f,
                        center = Offset(
                            c.x + cos(headAngle).toFloat() * radius,
                            c.y + sin(headAngle).toFloat() * radius,
                        ),
                    )
                }
            }
        }
        DialCenter(state, accent, size)
    }
}

@Composable
private fun DialCenter(state: TimerUiState, accent: Color, dialSize: Dp) {
    val density = LocalDensity.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.ringing) {
            val wobble by rememberInfiniteTransition(label = "bell").animateFloat(
                initialValue = -14f,
                targetValue = 14f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 180), RepeatMode.Reverse),
                label = "wobble",
            )
            Icon(
                painter = painterResource(R.drawable.ic_bell),
                contentDescription = null,
                tint = accent,
                modifier = Modifier
                    .size(dialSize * 0.15f)
                    .graphicsLayer { rotationZ = wobble },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.ringing),
                color = accent,
                fontSize = with(density) { (dialSize * 0.11f).toSp() },
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.timer_paused),
                color = AraColors.TextPrimary,
                fontSize = with(density) { (dialSize * 0.05f).toSp() },
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.resumes_in_seconds, state.ringSecondsLeft),
                color = AraColors.TextSecondary,
                fontSize = with(density) { (dialSize * 0.05f).toSp() },
                maxLines = 1,
                style = TextStyle(fontFeatureSettings = TABULAR_FIGURES),
            )
        } else {
            Text(
                text = state.countdownText,
                color = AraColors.TextPrimary,
                fontSize = with(density) { (dialSize * 0.21f).toSp() },
                fontWeight = FontWeight.Light,
                letterSpacing = (-1).sp,
                maxLines = 1,
                style = TextStyle(fontFeatureSettings = TABULAR_FIGURES),
            )
            Text(
                text = stringResource(if (state.running) R.string.until_next_ring else R.string.every_five_minutes),
                color = AraColors.TextSecondary,
                // Sized from the dial so it always fits inside the ring, whatever the font size.
                fontSize = with(density) { (dialSize * 0.052f).toSp() },
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun StatsRow(state: TimerUiState) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatCard(stringResource(R.string.stat_rings), state.ringCount.toString(), Modifier.weight(1f).fillMaxHeight())
        StatCard(
            label = stringResource(R.string.stat_elapsed),
            value = state.elapsedText,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            // Amber while ringing: the elapsed time is paused.
            valueColor = if (state.ringing) AraColors.Amber else AraColors.TextPrimary,
        )
        StatCard(stringResource(R.string.stat_next), state.nextAtText ?: "—", Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = AraColors.TextPrimary,
) {
    Column(
        modifier = modifier
            .clip(CardShape)
            .background(AraColors.Surface)
            .border(1.dp, AraColors.Outline, CardShape)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        BasicText(
            text = label.uppercase(),
            style = TextStyle(
                color = AraColors.TextSecondary,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = 11.sp),
        )
        Spacer(Modifier.height(4.dp))
        BasicText(
            text = value,
            style = TextStyle(
                color = valueColor,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = TABULAR_FIGURES,
            ),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 20.sp),
        )
    }
}

@Composable
private fun SettingsCard(state: TimerUiState, actions: TimerActions) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(AraColors.Surface)
            .border(1.dp, AraColors.Outline, CardShape),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(R.drawable.ic_music)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.sound), color = AraColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = state.ringtoneText,
                    color = AraColors.TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.sound_detail),
                    color = AraColors.TextSecondary.copy(alpha = 0.75f),
                    fontSize = 12.sp,
                )
            }
            TextButton(onClick = actions.onTogglePreview, enabled = !state.ringing) {
                Text(
                    text = stringResource(if (state.previewing) R.string.test_stop else R.string.test),
                    color = if (state.ringing) AraColors.TextSecondary else AraColors.Green,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        VolumeRow(state.ringVolume, actions.onVolumeChange)
        HorizontalDivider(color = AraColors.Outline, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = state.vibrate, role = Role.Switch, onValueChange = actions.onVibrateChange)
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(R.drawable.ic_vibration)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.vibrate), color = AraColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.vibrate_subtitle), color = AraColors.TextSecondary, fontSize = 13.sp)
            }
            Switch(
                checked = state.vibrate,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AraColors.OnGreen,
                    checkedTrackColor = AraColors.Green,
                    checkedBorderColor = AraColors.Green,
                    uncheckedThumbColor = AraColors.TextSecondary,
                    uncheckedTrackColor = AraColors.Track,
                    uncheckedBorderColor = AraColors.Outline,
                ),
            )
        }
    }
}

/** The app's own ring volume, on top of the phone's ringtone volume. */
@Composable
private fun VolumeRow(volume: Float, onVolumeChange: (Float) -> Unit) {
    val percent = (volume * 100).roundToInt()
    val description = stringResource(R.string.ring_volume)
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_volume_down),
            contentDescription = null,
            tint = AraColors.TextSecondary,
            modifier = Modifier.size(20.dp),
        )
        Slider(
            value = volume,
            onValueChange = onVolumeChange,
            valueRange = TimerConfig.MIN_RING_VOLUME..1f,
            steps = VOLUME_STEPS,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp)
                .semantics { contentDescription = description },
            colors = SliderDefaults.colors(
                thumbColor = AraColors.Green,
                activeTrackColor = AraColors.Green,
                inactiveTrackColor = AraColors.Track,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
        Icon(
            painter = painterResource(R.drawable.ic_volume_up),
            contentDescription = null,
            tint = AraColors.TextSecondary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = stringResource(R.string.volume_percent, percent),
            color = AraColors.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(44.dp),
            style = TextStyle(fontFeatureSettings = TABULAR_FIGURES),
        )
    }
}

@Composable
private fun IconBadge(@DrawableRes icon: Int) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AraColors.Green.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = AraColors.Green, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun WarningCard(warning: Warning, vibrate: Boolean, actions: TimerActions) {
    val (message, action) = when (warning) {
        Warning.PhoneOnVibrate ->
            (if (vibrate) R.string.warn_vibrate_buzz else R.string.warn_vibrate_silent) to R.string.sound_on
        Warning.PhoneOnMute ->
            (if (vibrate) R.string.warn_mute_buzz else R.string.warn_mute_silent) to R.string.sound_on
        Warning.RingtoneVolumeOff -> R.string.warn_ringtone_volume_off to R.string.turn_up
        Warning.DoNotDisturb -> R.string.warn_dnd to R.string.settings
        Warning.Notifications -> R.string.warn_notifications to R.string.allow
        Warning.ExactAlarms -> R.string.warn_exact to R.string.allow
        Warning.Battery -> R.string.warn_battery to R.string.allow
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(AraColors.Amber.copy(alpha = 0.07f))
            .border(1.dp, AraColors.Amber.copy(alpha = 0.30f), CardShape)
            .padding(start = 14.dp, end = 6.dp, top = 14.dp, bottom = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                painter = painterResource(R.drawable.ic_warning),
                contentDescription = null,
                tint = AraColors.Amber,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(message),
                color = AraColors.TextPrimary,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (warning == Warning.Battery) {
                TextButton(onClick = { actions.onWarningDismiss(warning) }) {
                    Text(stringResource(R.string.not_now), color = AraColors.TextSecondary)
                }
            }
            TextButton(onClick = { actions.onWarningAction(warning) }) {
                Text(stringResource(action), color = AraColors.Amber, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun StartStopButton(running: Boolean, actions: TimerActions) {
    val top by animateColorAsState(if (running) AraColors.RedLight else AraColors.GreenLight, tween(350), label = "buttonTop")
    val bottom by animateColorAsState(if (running) AraColors.Red else AraColors.Green, tween(350), label = "buttonBottom")
    val content by animateColorAsState(if (running) AraColors.OnRed else AraColors.OnGreen, tween(350), label = "buttonContent")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "buttonScale")
    val haptics = LocalHapticFeedback.current
    var lastClick by remember { mutableLongStateOf(-1L) }
    val label = stringResource(if (running) R.string.stop else R.string.start)
    val description = stringResource(if (running) R.string.stop_description else R.string.start_description)

    Box(
        modifier = Modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .height(68.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(elevation = 18.dp, shape = PillShape, ambientColor = bottom, spotColor = bottom)
            .clip(PillShape)
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = content),
                role = Role.Button,
            ) {
                // Ignore an accidental double tap that would start and instantly stop.
                val now = SystemClock.uptimeMillis()
                if (lastClick >= 0L && now - lastClick < DOUBLE_TAP_GUARD_MS) return@clickable
                lastClick = now
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                if (running) actions.onStop() else actions.onStart()
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(if (running) R.drawable.ic_stop else R.drawable.ic_play),
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(label, color = content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
        }
    }
}

private const val DOUBLE_TAP_GUARD_MS = 700L

/** Slider stops between 10% and 100%: 20%, 30% ... 90%. */
private const val VOLUME_STEPS = 8
