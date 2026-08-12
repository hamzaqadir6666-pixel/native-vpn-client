package com.lucentvpn.android.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The one signature element of the app.
 *
 * Reads as a piece of instrumentation rather than a button: a thin static track
 * with a live arc drawn over it. The arc's behaviour encodes state without any
 * text --
 *
 *  - idle: track only, no arc,
 *  - connecting: a short arc sweeping continuously, plus a determinate arc
 *    showing handshake progress,
 *  - connected: a full ring with a slow breathing glow,
 *  - failed: a broken ring in the warning colour.
 *
 * The glow is the only place in the app that emits light, which is what makes
 * "connected" readable at a glance.
 */
@Composable
fun ConnectionRing(
    state: RingState,
    /** Handshake progress, 0f..1f. Only consulted while [RingState.Connecting]. */
    progress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 240.dp,
    contentDescription: String,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    val activeColor by animateColorAsState(
        targetValue = when (state) {
            RingState.Connected -> scheme.primary
            RingState.Failed -> scheme.error
            RingState.Connecting -> scheme.primary
            RingState.Idle -> scheme.onSurfaceVariant
        },
        animationSpec = tween(400),
        label = "ringColor",
    )

    // Continuous rotation for the indeterminate sweep.
    val transition = rememberInfiniteTransition(label = "ring")

    val sweepRotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )

    // Slow breath while connected. Deliberately subtle: a fast pulse on a
    // permanent state becomes irritating within minutes.
    val breath by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2_600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )

    val determinate by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(500),
        label = "progress",
    )

    val glowAlpha = when (state) {
        RingState.Connected -> breath * 0.30f
        RingState.Connecting -> 0.12f
        else -> 0f
    }

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = false, color = activeColor),
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = 6.dp.toPx()
            val inset = stroke / 2 + 10.dp.toPx()
            val diameter = this.size.minDimension - inset * 2
            val topLeft = Offset(inset, inset)
            val arcSize = Size(diameter, diameter)
            val centre = Offset(this.size.width / 2, this.size.height / 2)
            val radius = diameter / 2

            // Outward glow, drawn first so the ring sits on top of it.
            if (glowAlpha > 0f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            activeColor.copy(alpha = glowAlpha),
                            Color.Transparent,
                        ),
                        center = centre,
                        radius = radius * 1.45f,
                    ),
                    radius = radius * 1.45f,
                    center = centre,
                )
            }

            // Static track: always present, so the control never disappears.
            drawArc(
                color = activeColor.copy(alpha = 0.14f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )

            when (state) {
                RingState.Connected -> {
                    drawArc(
                        color = activeColor,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }

                RingState.Connecting -> {
                    // Determinate portion: how far the handshake has actually got.
                    drawArc(
                        color = activeColor.copy(alpha = 0.45f),
                        startAngle = -90f,
                        sweepAngle = 360f * determinate,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )

                    // Indeterminate sweep: proves the app is still working even
                    // when a stage takes a while.
                    drawArc(
                        color = activeColor,
                        startAngle = sweepRotation - 90f,
                        sweepAngle = 46f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }

                RingState.Failed -> {
                    // A deliberately incomplete ring: the shape itself says
                    // "not closed".
                    drawArc(
                        color = activeColor,
                        startAngle = -90f,
                        sweepAngle = 260f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }

                RingState.Idle -> Unit
            }
        }

        content()
    }
}

enum class RingState { Idle, Connecting, Connected, Failed }
