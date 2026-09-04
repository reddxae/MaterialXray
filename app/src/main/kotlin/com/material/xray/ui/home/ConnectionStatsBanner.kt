package com.material.xray.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.material.xray.R
import com.material.xray.core.format.rateUnit
import com.material.xray.core.format.scaleBytes
import com.material.xray.core.format.sizeUnit
import com.material.xray.model.SessionTrafficMetrics
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

/**
 * Live summary of the running tunnel: which server it ended up on, how far away it is, and what it
 * has moved. Every value can be absent while the first samples are still coming in, so each cell
 * keeps its slot and shows a dash rather than collapsing the layout.
 *
 * The live values arrive as flows and are collected here rather than hoisted into the screen's
 * state, so a reading every second invalidates this banner instead of the whole home screen.
 * Collecting them here is also what asks the service to poll at all, so nothing is measured while
 * the banner is off screen.
 */
@Composable
internal fun ConnectionStatsBanner(
    activeBalancerServer: ActiveBalancerServerState?,
    pingMs: StateFlow<Int?>,
    sessionTraffic: StateFlow<SessionTrafficMetrics?>,
    modifier: Modifier = Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    val ping by pingMs.collectAsStateWithLifecycle()
    val traffic by sessionTraffic.collectAsStateWithLifecycle()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            AnimatedContent(
                targetState = activeBalancerServer?.title,
                label = "activeBalancerServer",
            ) { title ->
                if (title != null) {
                    Column(
                        modifier = Modifier.padding(bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ActiveServerRow(title = title)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCell(
                        icon = Icons.Outlined.Bolt,
                        label = stringResource(R.string.home_stats_ping),
                        value = ping?.let { stringResource(R.string.home_stats_ping_value, it) },
                        modifier = Modifier.weight(1f),
                    )
                    StatCell(
                        icon = Icons.Outlined.ArrowDownward,
                        label = stringResource(R.string.home_stats_download),
                        value = traffic?.let { formatRate(it.downlinkBps, locale) },
                        modifier = Modifier.weight(1f),
                    )
                    StatCell(
                        icon = Icons.Outlined.ArrowUpward,
                        label = stringResource(R.string.home_stats_upload),
                        value = traffic?.let { formatRate(it.uplinkBps, locale) },
                        modifier = Modifier.weight(1f),
                    )
                }

                SessionTotalsRow(traffic = traffic, locale = locale)
            }
        }
    }
}

@Composable
private fun ActiveServerRow(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Hub,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.home_balancer_active_server, title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatCell(
    icon: ImageVector,
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
) {
    val reading = value ?: stringResource(R.string.home_stats_unavailable)

    Column(
        // The icon is decorative and the label and value are one fact, so they are announced as a
        // single "Ping, 42 ms" node instead of three fragments.
        modifier = modifier.semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = reading,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SessionTotalsRow(traffic: SessionTrafficMetrics?, locale: Locale) {
    val unavailable = stringResource(R.string.home_stats_unavailable)
    val downloaded = traffic?.let { formatSize(it.downlinkBytes, locale) } ?: unavailable
    val uploaded = traffic?.let { formatSize(it.uplinkBytes, locale) } ?: unavailable

    // The arrows carry the only clue to which figure is which, and a screen reader cannot read
    // them, so the whole row is replaced by one spelled-out description.
    val description = stringResource(R.string.home_stats_session_description, downloaded, uploaded)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.home_stats_session),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.home_stats_session_value, downloaded, uploaded),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun formatRate(bytesPerSecond: Long, locale: Locale): String {
    val scaled = scaleBytes(bytesPerSecond, locale)
    return stringResource(R.string.value_with_unit, scaled.value, stringResource(scaled.magnitude.rateUnit()))
}

@Composable
private fun formatSize(bytes: Long, locale: Locale): String {
    val scaled = scaleBytes(bytes, locale)
    return stringResource(R.string.value_with_unit, scaled.value, stringResource(scaled.magnitude.sizeUnit()))
}
