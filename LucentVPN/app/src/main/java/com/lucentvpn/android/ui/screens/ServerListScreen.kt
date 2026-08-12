package com.lucentvpn.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucentvpn.android.R
import com.lucentvpn.android.data.model.VpnServer
import com.lucentvpn.android.ui.Formatters
import com.lucentvpn.android.ui.LucentViewModel
import com.lucentvpn.android.ui.components.ServerRow
import com.lucentvpn.android.ui.messageRes

/**
 * Relay picker.
 *
 * Order comes from the view model (which delegates to the repository's ranking),
 * not from this file -- the list must not re-sort itself as recompositions
 * happen or the row under the user's finger would move.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    viewModel: LucentViewModel,
    onBack: () -> Unit,
) {
    val servers by viewModel.rankedServers.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val lastRefreshAt by viewModel.lastRefreshAt.collectAsState()
    val listError by viewModel.listError.collectAsState()

    var query by remember { mutableStateOf("") }

    val filtered = remember(servers, query) {
        if (query.isBlank()) {
            servers
        } else {
            val needle = query.trim().lowercase()
            servers.filter {
                it.countryName.lowercase().contains(needle) ||
                    it.countryCode.lowercase().contains(needle) ||
                    it.hostName.lowercase().contains(needle)
            }
        }
    }

    val favouriteIds = settings.favouriteServerIds
    val searching = query.isNotBlank()

    // Sections only make sense on the unfiltered list; while searching, a flat
    // list of matches is what the user is actually asking for.
    val favourites = if (searching) emptyList() else filtered.filter { it.id in favouriteIds }
    val rest = if (searching) filtered else filtered.filterNot { it.id in favouriteIds }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.servers_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::onRefreshServers,
                        enabled = !isRefreshing,
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.action_refresh),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(text = stringResource(R.string.servers_search_hint)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            )

            Spacer(modifier = Modifier.height(10.dp))

            ListMeta(
                count = servers.size,
                lastRefreshAt = lastRefreshAt,
            )

            AnimatedVisibility(visible = listError != null) {
                listError?.let { error ->
                    ListErrorBanner(
                        messageRes = error.messageRes(),
                        onRetry = viewModel::onRefreshServers,
                        onDismiss = viewModel::onDismissListError,
                    )
                }
            }

            if (servers.isEmpty()) {
                EmptyState(isRefreshing = isRefreshing)
                return@Column
            }

            LazyColumn(
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 4.dp,
                    bottom = 28.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!searching) {
                    item(key = "auto") {
                        AutomaticRow(
                            isSelected = settings.pinnedServerId == null,
                            onClick = { viewModel.onSelectServer(null) },
                        )
                    }
                }

                if (favourites.isNotEmpty()) {
                    item(key = "favourites-header") {
                        SectionLabel(text = stringResource(R.string.servers_favourites))
                    }
                    items(favourites, key = { "fav-${it.id}" }) { server ->
                        ServerRow(
                            server = server,
                            isSelected = settings.pinnedServerId == server.id,
                            isFavourite = true,
                            onClick = { viewModel.onSelectServer(server.id) },
                            onToggleFavourite = { viewModel.onToggleFavourite(server.id) },
                        )
                    }
                }

                if (rest.isNotEmpty()) {
                    item(key = "all-header") {
                        SectionLabel(
                            text = if (searching) {
                                stringResource(R.string.servers_count, rest.size)
                            } else {
                                stringResource(R.string.servers_all)
                            }
                        )
                    }
                    items(rest, key = { it.id }) { server ->
                        ServerRow(
                            server = server,
                            isSelected = settings.pinnedServerId == server.id,
                            isFavourite = server.id in favouriteIds,
                            onClick = { viewModel.onSelectServer(server.id) },
                            onToggleFavourite = { viewModel.onToggleFavourite(server.id) },
                        )
                    }
                }

                if (filtered.isEmpty()) {
                    item(key = "no-matches") {
                        Text(
                            text = stringResource(R.string.servers_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ListMeta(count: Int, lastRefreshAt: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.servers_count, count),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                R.string.servers_updated,
                Formatters.relativeTime(lastRefreshAt),
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Automatic mode: the default, and deliberately the first thing in the list. */
@Composable
private fun AutomaticRow(
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) scheme.surfaceVariant else scheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.servers_auto_title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = scheme.onSurface,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.servers_auto_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }

        if (isSelected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = scheme.onSurface,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
}

@Composable
private fun ListErrorBanner(
    messageRes: Int,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.error.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(messageRes),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onBackground,
        )
        Row {
            TextButton(onClick = onRetry) {
                Text(text = stringResource(R.string.action_retry))
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

@Composable
private fun EmptyState(isRefreshing: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            if (isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.servers_empty),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.servers_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
