package app.stratos.host.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.stratos.host.R
import app.stratos.host.ui.theme.StratosColors
import app.stratos.host.ui.theme.StratosDisplayFamily
import app.stratos.host.ui.theme.glassEdgeBrush
import app.stratos.host.ui.theme.glassFill
import app.stratos.host.ui.theme.stratosIsDark

/** Cloudflare Zero Trust Tunnels console — where the named-tunnel token is minted. */
private const val CF_ZERO_TRUST_URL = "https://one.dash.cloudflare.com/?tab=tunnels"

/** Official dashboard guide with the embedded video walkthrough of the exact flow. */
private const val CF_DOC_VIDEO_URL =
    "https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/get-started/create-remote-tunnel/"

/** Official 5-minute quick start. */
private const val CF_DOC_QUICKSTART_URL = "https://developers.cloudflare.com/tunnel/get-started/"

/** Official Cloudflare Tunnel documentation overview. */
private const val CF_DOC_OVERVIEW_URL =
    "https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/"

private const val INSTALL_COMMAND_SAMPLE = "cloudflared service install eyJ…"

/**
 * Expandable, trilingual walkthrough for extracting a Cloudflare named-tunnel
 * token. Mounted directly above every token field (custom-domain wizard step 2
 * and the named-tunnel token dialog) so the user never has to leave the flow
 * to find out where the token lives.
 *
 * Contents: the exact console path (Networks → Tunnels → Create a tunnel →
 * Cloudflared), the anatomy of the install command, a one-tap deep link into
 * the Cloudflare Zero Trust console, and a privacy note spelling out what the
 * token contains and why only the public tunnel ID ever leaves the envelope.
 */
@Composable
fun TokenGuideCard(modifier: Modifier = Modifier) {
    val dark = stratosIsDark()
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val chevron by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = tween(240),
        label = "tokenGuideChevron"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(glassFill(dark))
            .border(1.dp, glassEdgeBrush(dark), RoundedCornerShape(14.dp))
    ) {
        // ---- header / toggle ------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Help,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = StratosColors.Accent
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.token_guide_title),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    stringResource(if (open) R.string.token_guide_hide else R.string.token_guide_show),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(chevron),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ---- steps ----------------------------------------------------------
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(tween(240)) + fadeIn(tween(240)),
            exit = shrinkVertically(tween(200)) + fadeOut(tween(160))
        ) {
            Column(
                Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GuideStep(1, stringResource(R.string.token_guide_s1))
                GuideStep(2, stringResource(R.string.token_guide_s2))
                GuideStep(3, stringResource(R.string.token_guide_s3))
                GuideStep(4, stringResource(R.string.token_guide_s4))
                GuideStep(5, stringResource(R.string.token_guide_s5))
                GuideStep(6, stringResource(R.string.token_guide_s6))

                // The anatomy of the command the user will see in the console.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) {
                    MonoText(INSTALL_COMMAND_SAMPLE)
                }

                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(CF_ZERO_TRUST_URL))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Filled.OpenInBrowser,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.token_guide_open))
                }

                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Filled.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = StratosColors.Success
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.token_guide_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // ---- illustrated guides ---------------------------------------
                Text(
                    stringResource(R.string.token_guide_media_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                GuideLink(
                    text = stringResource(R.string.token_guide_link_video),
                    url = CF_DOC_VIDEO_URL,
                    icon = Icons.Filled.PlayCircle
                )
                GuideLink(
                    text = stringResource(R.string.token_guide_link_quickstart),
                    url = CF_DOC_QUICKSTART_URL,
                    icon = Icons.Filled.Bolt
                )
                GuideLink(
                    text = stringResource(R.string.token_guide_link_docs),
                    url = CF_DOC_OVERVIEW_URL,
                    icon = Icons.Filled.MenuBook
                )
            }
        }
    }
}

/** Numbered walkthrough row: glowing index chip + step text. */
@Composable
private fun GuideStep(index: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(21.dp)
                .background(StratosColors.AccentSoft, CircleShape)
                .border(1.dp, StratosColors.Accent.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                index.toString(),
                fontFamily = StratosDisplayFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = StratosColors.Accent
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Tappable illustrated-guide link row that opens the official Cloudflare page. */
@Composable
private fun GuideLink(text: String, url: String, icon: ImageVector) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = StratosColors.Accent
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.Filled.OpenInBrowser,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
