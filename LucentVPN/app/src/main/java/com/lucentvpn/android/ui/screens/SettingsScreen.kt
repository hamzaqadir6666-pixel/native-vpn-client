package com.lucentvpn.android.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.lucentvpn.android.BuildConfig
import com.lucentvpn.android.R
import com.lucentvpn.android.data.SettingsStore
import com.lucentvpn.android.ui.LucentViewModel
import kotlinx.coroutines.delay

/**
 * Settings.
 *
 * Every row here writes straight through to [SettingsStore] and is read back
 * from it, so there is no local copy of the truth to drift. The DNS fields are
 * the one exception: they hold draft text while the user types and only commit
 * once the value is a valid address.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: LucentViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            // ---- Connection ------------------------------------------------
            SectionHeader(text = stringResource(R.string.settings_section_connection))

            SettingCard {
                ToggleRow(
                    title = stringResource(R.string.settings_auto_connect),
                    summary = stringResource(R.string.settings_auto_connect_summary),
                    checked = settings.autoConnectOnLaunch,
                    onCheckedChange = viewModel::setAutoConnect,
                )
                RowDivider()
                ToggleRow(
                    title = stringResource(R.string.settings_auto_reconnect),
                    summary = stringResource(R.string.settings_auto_reconnect_summary),
                    checked = settings.autoReconnect,
                    onCheckedChange = viewModel::setAutoReconnect,
                )
                RowDivider()
                ToggleRow(
                    title = stringResource(R.string.settings_start_on_boot),
                    summary = stringResource(R.string.settings_start_on_boot_summary),
                    checked = settings.startOnBoot,
                    onCheckedChange = viewModel::setStartOnBoot,
                )
            }

            // ---- Network ---------------------------------------------------
            SectionHeader(text = stringResource(R.string.settings_section_network))

            SettingCard {
                ToggleRow(
                    title = stringResource(R.string.settings_custom_dns),
                    summary = stringResource(R.string.settings_custom_dns_summary),
                    checked = settings.useCustomDns,
                    onCheckedChange = viewModel::setUseCustomDns,
                )

                AnimatedVisibility(visible = settings.useCustomDns) {
                    DnsFields(
                        primary = settings.primaryDns,
                        secondary = settings.secondaryDns,
                        onCommit = viewModel::setDns,
                    )
                }
            }

            // ---- Appearance ------------------------------------------------
            SectionHeader(text = stringResource(R.string.settings_section_appearance))

            SettingCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_theme),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeChip(
                            label = stringResource(R.string.settings_theme_system),
                            selected = settings.themeMode == SettingsStore.ThemeMode.SYSTEM,
                            onClick = { viewModel.setThemeMode(SettingsStore.ThemeMode.SYSTEM) },
                        )
                        ThemeChip(
                            label = stringResource(R.string.settings_theme_light),
                            selected = settings.themeMode == SettingsStore.ThemeMode.LIGHT,
                            onClick = { viewModel.setThemeMode(SettingsStore.ThemeMode.LIGHT) },
                        )
                        ThemeChip(
                            label = stringResource(R.string.settings_theme_dark),
                            selected = settings.themeMode == SettingsStore.ThemeMode.DARK,
                            onClick = { viewModel.setThemeMode(SettingsStore.ThemeMode.DARK) },
                        )
                    }
                }
            }

            // ---- About -----------------------------------------------------
            SectionHeader(text = stringResource(R.string.settings_section_about))

            SettingCard {
                InfoRow(
                    title = stringResource(R.string.settings_version),
                    value = BuildConfig.VERSION_NAME,
                )
                RowDivider()
                InfoRow(
                    title = stringResource(R.string.settings_relay_source),
                    value = viewModel.relaySourceName,
                    trailingIcon = true,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, viewModel.relaySourceUrl.toUri())
                            )
                        }
                    },
                )
                RowDivider()
                InfoRow(
                    title = stringResource(R.string.settings_licence),
                    value = stringResource(R.string.settings_licence_summary),
                )
            }

            // The operator trust model, verbatim from the provider. Users of a
            // volunteer relay network deserve to read this before they dial in.
            Text(
                text = viewModel.relayTrustNotice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = 22.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        content()
    }
}

@Composable
private fun RowDivider() {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    )
}

@Composable
private fun ToggleRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The whole row toggles, not just the switch: a 48dp target beats a
            // 20dp one, and the summary text is the part people actually aim at.
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun InfoRow(
    title: String,
    value: String,
    trailingIcon: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (trailingIcon) {
            Icon(
                imageVector = Icons.Rounded.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun ThemeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = label) },
    )
}

/**
 * Draft-then-commit resolver entry.
 *
 * Writing every keystroke to DataStore would persist "1.1.1" on the way to
 * "1.1.1.1" and push a broken resolver into the next tunnel, so an address is
 * only committed once it parses.
 */
@Composable
private fun DnsFields(
    primary: String,
    secondary: String,
    onCommit: (String, String) -> Unit,
) {
    var primaryDraft by remember(primary) { mutableStateOf(primary) }
    var secondaryDraft by remember(secondary) { mutableStateOf(secondary) }

    val primaryValid = primaryDraft.isValidIpv4()
    val secondaryValid = secondaryDraft.isBlank() || secondaryDraft.isValidIpv4()

    // Debounced commit: valid drafts land in DataStore shortly after typing stops.
    LaunchedEffect(primaryDraft, secondaryDraft) {
        if (!primaryValid || !secondaryValid) return@LaunchedEffect
        if (primaryDraft == primary && secondaryDraft == secondary) return@LaunchedEffect
        delay(600)
        onCommit(primaryDraft, secondaryDraft)
    }

    Column(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = primaryDraft,
            onValueChange = { primaryDraft = it.trim() },
            label = { Text(text = stringResource(R.string.settings_dns_primary)) },
            isError = !primaryValid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = secondaryDraft,
            onValueChange = { secondaryDraft = it.trim() },
            label = { Text(text = stringResource(R.string.settings_dns_secondary)) },
            isError = !secondaryValid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Strict dotted-quad check.
 *
 * Deliberately not using [android.net.InetAddresses] or `InetAddress`: the
 * former is API 29+, and the latter would attempt a DNS lookup on a
 * non-numeric string, on the main thread.
 */
private fun String.isValidIpv4(): Boolean {
    val parts = split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() &&
            part.length <= 3 &&
            part.all(Char::isDigit) &&
            part.toInt() in 0..255
    }
}
