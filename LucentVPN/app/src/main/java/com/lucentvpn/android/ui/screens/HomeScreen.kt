package com.lucentvpn.android.ui.screens

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucentvpn.android.R
import com.lucentvpn.android.data.model.VpnServer
import com.lucentvpn.android.ui.Formatters
import com.lucentvpn.android.ui.LucentViewModel
import com.lucentvpn.android.ui.components.ConnectionRing
import com.lucentvpn.android.ui.components.RingState
import com.lucentvpn.android.ui.messageRes
import com.lucentvpn.android.vpn.ConnectionState
import com.lucentvpn.android.vpn.TunnelStats
import kotlinx.coroutines.delay

/**
 * The home surface.
 *
 * One control, one truth. Everything on this screen is derived from
 * [com.lucentvpn.android.vpn.VpnConnectionManager]'s state, so what the ring
 * shows is what the tunnel is actually doing -- there is no optimistic
 * "connected" anywhere in this file.
 */
@Composable
fun HomeScreen(
    viewModel: LucentViewModel,
    onOpenServers: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.connectionState.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val server by viewModel.displayServer.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()

    Scaffold { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 20.dp),
        ) {
            HomeTopBar(onOpenSettings = onOpenSettings)

            AnimatedVisibility(
                visible = !isOnline,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                OfflineBanner()
            }

            Spacer(modifier = Modifier.weight(1f))

            RingBlock(
                state = state,
                server = server,
                onToggle = viewModel::onToggleConnection,
            )

            Spacer(modifier = Modifier.height(28.dp))

            RelayCard(
                server = server,
                enabled = !state.isBusy,
                onClick = onOpenServers,
            )

            Spacer(modifier = Modifier.height(16.dp))

            StatsBlock(state = state, stats = stats)

            Spacer(modifier = Modifier.weight(1f))

            FailureBlock(
                state = state,
                onRetry = viewModel::onToggleConnection,
                onDismiss = viewModel::onDismissError,
            )

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun HomeTopBar(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )

        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = stringResource(R.string.cd_settings),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OfflineBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(R.string.error_no_network),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** The ring plus the words inside it. */
@Composable
private fun RingBlock(
    state: ConnectionState,
    server: VpnServer?,
    onToggle: () -> Unit,
) {
    val ringState = when (state) {
        is ConnectionState.Connected -> RingState.Connected
        is ConnectionState.Connecting -> RingState.Connecting
        ConnectionState.Disconnecting -> RingState.Connecting
        is ConnectionState.Failed -> RingState.Failed
        ConnectionState.Idle -> RingState.Idle
    }

    val progress = (state as? ConnectionState.Connecting)?.progress ?: 0f

    val actionHint = stringResource(
        if (state.isConnected) R.string.home_tap_to_disconnect
        else R.string.home_tap_to_connect
    )

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        ConnectionRing(
            state = ringState,
            progress = progress,
            onClick = onToggle,
            contentDescription = "${stringResource(state.headlineRes())}. $actionHint",
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(horizontal = 34.dp)
                    .animateContentSize(),
            ) {
                Text(
                    text = stringResource(state.headlineRes()),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    color = if (state.isConnected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onBackground
                    },
                )

                // Which relay we are on, or heading to.
                val subtitle = when {
                    state is ConnectionState.Connected -> state.server.countryName
                    state is ConnectionState.Connecting && state.totalAttempts > 1 ->
                        "Relay ${state.attempt} of ${state.totalAttempts}"

                    server != null -> server.countryName
                    else -> null
                }

                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (state is ConnectionState.Idle) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.home_tap_to_connect),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp,
                    )
                }
            }
        }
    }
}

/** The relay chooser. Disabled mid-handshake so the target cannot move. */
@Composable
private fun RelayCard(
    server: VpnServer?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surface)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = server?.flagEmoji ?: "\uD83C\uDF10",
            style = MaterialTheme.typography.headlineSmall,
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server?.countryName ?: stringResource(R.string.home_auto_relay),
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = server?.hostName ?: stringResource(R.string.servers_auto_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = stringResource(R.string.action_change_server),
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.4f),
        )

        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = if (enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Live counters.
 *
 * Only rendered while a tunnel exists: showing four zeroes when disconnected
 * would be four pieces of furniture that mean nothing.
 */
@Composable
private fun StatsBlock(
    state: ConnectionState,
    stats: TunnelStats,
) {
    AnimatedVisibility(
        visible = state is ConnectionState.Connected,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        val connected = state as? ConnectionState.Connected

        // Ticks once a second while connected, and only while connected.
        var elapsedMs by remember { mutableLongStateOf(0L) }
        LaunchedEffect(connected?.connectedAtElapsedRealtime) {
            val since = connected?.connectedAtElapsedRealtime ?: return@LaunchedEffect
            while (true) {
                elapsedMs = SystemClock.elapsedRealtime() - since
                delay(1_000)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    label = stringResource(R.string.home_stat_download),
                    value = Formatters.rate(stats.downstreamBps),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = stringResource(R.string.home_stat_upload),
                    value = Formatters.rate(stats.upstreamBps),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    label = stringResource(R.string.home_stat_duration),
                    value = Formatters.duration(elapsedMs),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = stringResource(R.string.home_stat_transferred),
                    value = Formatters.bytes(stats.totalBytes),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/**
 * Failure copy and its recovery action.
 *
 * [com.lucentvpn.android.vpn.VpnError.recoverable] decides whether we offer a
 * retry at all -- offering "Try again" for a battery-restricted service would
 * just loop the user through the same failure.
 */
@Composable
private fun FailureBlock(
    state: ConnectionState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val failure = state as? ConnectionState.Failed ?: return
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.error.copy(alpha = 0.12f))
            .padding(14.dp),
    ) {
        Text(
            text = stringResource(failure.error.messageRes()),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onBackground,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (failure.error.recoverable) {
                TextButton(onClick = onRetry) {
                    Text(text = stringResource(R.string.action_retry))
                }
            }
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.action_dismiss),
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The one line of state text, mapped centrally so no composable invents copy. */
private fun ConnectionState.headlineRes(): Int = when (this) {
    ConnectionState.Idle -> R.string.state_ready
    is ConnectionState.Connected -> R.string.state_connected
    ConnectionState.Disconnecting -> R.string.state_disconnecting
    is ConnectionState.Failed -> R.string.state_ready
    is ConnectionState.Connecting -> when (stage) {
        ConnectionState.Connecting.Stage.SELECTING_SERVER -> R.string.state_selecting
        ConnectionState.Connecting.Stage.AWAITING_PERMISSION -> R.string.state_awaiting_permission
        ConnectionState.Connecting.Stage.STARTING_ENGINE -> R.string.state_starting_engine
        ConnectionState.Connecting.Stage.CONTACTING_SERVER -> R.string.state_contacting
        ConnectionState.Connecting.Stage.AUTHENTICATING -> R.string.state_authenticating
        ConnectionState.Connecting.Stage.ESTABLISHING_TUNNEL -> R.string.state_establishing
    }
}
