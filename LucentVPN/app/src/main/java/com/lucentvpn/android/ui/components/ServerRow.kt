package com.lucentvpn.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lucentvpn.android.R
import com.lucentvpn.android.data.model.VpnServer
import com.lucentvpn.android.ui.Formatters

/**
 * One relay in the list.
 *
 * Note what is deliberately *not* aqua here: selecting a relay is not the same
 * as having a tunnel to it, so the selected state is drawn with weight and a
 * neutral check rather than the accent. Aqua staying exclusive to a live tunnel
 * is what keeps it meaningful on the home screen.
 */
@Composable
fun ServerRow(
    server: VpnServer,
    isSelected: Boolean,
    isFavourite: Boolean,
    onClick: () -> Unit,
    onToggleFavourite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) scheme.surfaceVariant else scheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = server.flagEmoji,
            style = MaterialTheme.typography.titleLarge,
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = server.countryName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (isSelected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = scheme.onSurface,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            Text(
                text = server.hostName,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(7.dp))

            // Load, as a hairline meter. Kept thin so it reads as instrumentation
            // rather than a progress bar the user is meant to wait on.
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(scheme.onSurfaceVariant.copy(alpha = 0.18f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(server.load.coerceIn(0.03f, 1f))
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(
                            if (server.load > 0.8f) scheme.error
                            else scheme.onSurfaceVariant.copy(alpha = 0.55f)
                        ),
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = Formatters.ping(server.effectivePingMs.takeIf { it > 0 }),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurface,
            )
            Text(
                text = Formatters.advertisedSpeed(server.speedBps),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            SignalBars(quality = server.quality)
        }

        IconButton(onClick = onToggleFavourite) {
            Icon(
                imageVector = if (isFavourite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = stringResource(
                    if (isFavourite) R.string.servers_favourite_remove
                    else R.string.servers_favourite_add
                ),
                tint = if (isFavourite) scheme.onSurface else scheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Three ascending bars; how many are lit encodes [VpnServer.Quality]. */
@Composable
private fun SignalBars(quality: VpnServer.Quality) {
    val scheme = MaterialTheme.colorScheme

    val lit = when (quality) {
        VpnServer.Quality.EXCELLENT -> 3
        VpnServer.Quality.GOOD -> 2
        VpnServer.Quality.FAIR -> 1
    }

    val tint = if (quality == VpnServer.Quality.FAIR) scheme.error else scheme.onSurfaceVariant

    // The bars are the only place quality is expressed, so they carry the label
    // for screen readers rather than being purely decorative.
    val label = stringResource(R.string.cd_signal_quality, quality.name.lowercase())

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        listOf(5.dp, 8.dp, 11.dp).forEachIndexed { index, barHeight ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(barHeight)
                    .clip(RoundedCornerShape(1.dp))
                    .background(
                        if (index < lit) tint else tint.copy(alpha = 0.20f)
                    ),
            )
        }
    }
}
