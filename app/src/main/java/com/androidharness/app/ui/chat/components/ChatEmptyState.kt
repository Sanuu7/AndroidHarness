package com.androidharness.app.ui.chat.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.ChatAgentProfile
import com.androidharness.app.ui.theme.expressiveDefaultSpatialSpec
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import com.androidharness.app.ui.theme.fastEffectsSpec
import com.androidharness.app.ui.theme.harnessGlass
import kotlinx.coroutines.delay
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.rememberCoroutineScope
import com.androidharness.app.ui.common.HarnessLogoMorph
import com.androidharness.app.ui.common.LogoMorph
import kotlinx.coroutines.launch

/** A starter either sends right away (agent prompts are complete) or fills the composer. */
private data class ChatStarter(val icon: ImageVector, val label: String, val text: String, val send: Boolean)

private data class StarterAccent(val container: Color, val content: Color, val shape: Shape)

/**
 * New Chat screen. Identity sits in the upper part (mark, headline, agent
 * choice, support links); starters sit at the bottom next to the composer
 * where the thumb already is. With the keyboard up the grid becomes one
 * scrolling row of chips, and it steps aside once the user starts typing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatEmptyState(
    agent: ChatAgentProfile?,
    agentSetup: (@Composable () -> Unit)?,
    startersVisible: Boolean,
    onSend: (String) -> Unit,
    onPrefill: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Code uses the same layout with build-oriented copy, mark and starters that send right away. */
    code: Boolean = false,
) {
    val starters = if (code) listOf(
        ChatStarter(Icons.Outlined.Terminal, "Create a Python hello world and run it", "Create a Python hello world and run it", true),
        ChatStarter(Icons.Outlined.Folder, "Explore my workspace and summarize it", "Explore my workspace and summarize it", true),
        ChatStarter(Icons.Outlined.Code, "Build a simple HTML landing page", "Build a simple HTML landing page", true),
        ChatStarter(Icons.Outlined.CreateNewFolder, "Set up a new project with a README", "Set up a new project with a README", true),
    ) else if (agent != null && agent.starters.isNotEmpty()) {
        agent.starters.map { ChatStarter(agentIcon(agent.avatar), it, it, send = true) }
    } else listOf(
        ChatStarter(Icons.Outlined.School, stringResource(R.string.chat_suggestion_explain), stringResource(R.string.chat_prefill_explain), false),
        ChatStarter(Icons.Outlined.Lightbulb, stringResource(R.string.chat_suggestion_ideas), stringResource(R.string.chat_prefill_ideas), false),
        ChatStarter(Icons.Outlined.EditNote, stringResource(R.string.chat_suggestion_draft), stringResource(R.string.chat_prefill_draft), false),
        ChatStarter(Icons.Outlined.Forum, stringResource(R.string.chat_suggestion_think), stringResource(R.string.chat_prefill_think), false),
    )
    val tap: (ChatStarter) -> Unit = { if (it.send) onSend(it.text) else onPrefill(it.text + " ") }

    // One counter drives the whole entrance so every piece keeps its layout
    // slot from the first frame and only fades, rises and scales in.
    var step by remember(agent?.id) { mutableIntStateOf(0) }
    LaunchedEffect(agent?.id) {
        step = 0
        delay(40)
        repeat(4 + starters.size) { step = it + 1; delay(55) }
    }
    val imeVisible = WindowInsets.isImeVisible

    BoxWithConstraints(modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
        val viewport = maxHeight
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewport)
                .widthIn(max = 560.dp)
                .padding(horizontal = 8.dp, vertical = 16.dp),
            // The whole block centers in the visible area; extra room splits evenly above and below.
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ChatHeroMark(
                    icon = if (!code && agent != null) agentIcon(agent.avatar) else null,
                    morph = if (code) LogoMorph.Code else LogoMorph.Discover,
                    visible = step > 0,
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    if (code) stringResource(R.string.ui_what_do_you_want_to_build) else agent?.name ?: stringResource(R.string.chat_empty_title),
                    style = MaterialTheme.typography.headlineMediumEmphasized,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.enterRise(step > 1),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (code) stringResource(R.string.ui_start_with_an_idea_or_create_a)
                    else agent?.description?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_empty_description),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp).enterRise(step > 2),
                )
                Column(Modifier.fillMaxWidth().enterRise(step > 3), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!code && agent == null && agentSetup != null) {
                        Spacer(Modifier.height(16.dp))
                        agentSetup()
                    }
                    Spacer(Modifier.height(12.dp))
                    ChatSupportLinks()
                }
            }
            AnimatedVisibility(
                visible = startersVisible,
                enter = fadeIn(expressiveEffectsSpec()) + scaleIn(expressiveSpatialSpec(), initialScale = 0.94f),
                exit = fadeOut(fastEffectsSpec()) + scaleOut(fastEffectsSpec(), targetScale = 0.96f),
                modifier = Modifier.padding(top = 32.dp),
            ) {
                AnimatedContent(
                    targetState = imeVisible,
                    transitionSpec = { fadeIn(expressiveEffectsSpec()) togetherWith fadeOut(fastEffectsSpec()) },
                    label = "chatStarterLayout",
                ) { compact ->
                    if (compact) StarterChips(starters, tap)
                    else StarterGrid(starters, step, firstStep = 4, onTap = tap)
                }
            }
        }
    }
}

/** Alpha, rise and scale on the graphics layer only, so nothing below jumps as it enters. */
@Composable
private fun Modifier.enterRise(visible: Boolean, rise: Dp = 12.dp, fromScale: Float = 0.96f): Modifier {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, expressiveEffectsSpec(), label = "enterAlpha")
    val progress by animateFloatAsState(if (visible) 1f else 0f, expressiveSpatialSpec(), label = "enterRise")
    val risePx = with(LocalDensity.current) { rise.toPx() }
    return graphicsLayer {
        this.alpha = alpha.coerceIn(0f, 1f)
        translationY = (1f - progress) * risePx
        val scale = fromScale + (1f - fromScale) * progress
        scaleX = scale
        scaleY = scale
    }
}

/**
 * The cookie hero. Chat and Code show the AH monogram, which morphs into
 * sparkles (Chat) or "</>" (Code) and back: once after the entrance, then every
 * [LOGO_LOOP_MS]. A tap turns the cookie and replays the morph right away.
 * A chat agent keeps its own avatar instead.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChatHeroMark(icon: ImageVector?, morph: LogoMorph, visible: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val spin by animateFloatAsState(if (visible) 0f else -90f, expressiveDefaultSpatialSpec(), label = "heroSpin")
    val grow by animateFloatAsState(if (visible) 1f else 0.5f, expressiveDefaultSpatialSpec(), label = "heroGrow")
    val alpha by animateFloatAsState(if (visible) 1f else 0f, expressiveEffectsSpec(), label = "heroAlpha")
    val turn = remember { Animatable(0f) }
    val progress = remember(morph) { Animatable(0f) }
    var plays by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val showLogo = icon == null
    // Each tap bumps [plays], which cancels the running cycle and starts a fresh one
    // from wherever the glyph is, so rapid taps never stack or jump.
    LaunchedEffect(morph, plays, visible, showLogo) {
        if (!visible || !showLogo) {
            progress.snapTo(0f)
            return@LaunchedEffect
        }
        if (plays == 0) delay(LOGO_FIRST_DELAY_MS)
        while (true) {
            progress.animateTo(1f, tween(LOGO_MORPH_MS, easing = FastOutSlowInEasing))
            delay(LOGO_HOLD_MS)
            progress.animateTo(0f, tween(LOGO_MORPH_MS, easing = FastOutSlowInEasing))
            delay(LOGO_LOOP_MS)
        }
    }
    val shape = MaterialShapes.Cookie9Sided.toShape()
    Box(
        Modifier
            .size(80.dp)
            .graphicsLayer {
                rotationZ = spin + turn.value
                scaleX = grow
                scaleY = grow
                this.alpha = alpha.coerceIn(0f, 1f)
            }
            .clip(shape)
            .background(scheme.primaryContainer, shape)
            .then(
                if (showLogo) Modifier.clickable(
                    interactionSource = null,
                    indication = ripple(),
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.chat_logo_replay),
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    // Nine-sided cookie: any multiple of 40 degrees lands back on the same outline.
                    scope.launch { turn.animateTo(turn.targetValue + 120f, expressiveDefaultSpatialSpec()) }
                    plays++
                } else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = icon, animationSpec = expressiveEffectsSpec(), label = "heroIcon") { current ->
            // Counter-rotate so the glyph stays upright while the cookie turns.
            val upright = Modifier.graphicsLayer { rotationZ = -(spin + turn.value) }
            if (current == null) {
                HarnessLogoMorph(
                    morph = morph,
                    color = scheme.onPrimaryContainer,
                    progress = { progress.value },
                    modifier = Modifier.size(40.dp).then(upright),
                )
            } else {
                Icon(current, null, Modifier.size(36.dp).then(upright), tint = scheme.onPrimaryContainer)
            }
        }
    }
}

private const val LOGO_FIRST_DELAY_MS = 600L
private const val LOGO_MORPH_MS = 650
private const val LOGO_HOLD_MS = 1400L
private const val LOGO_LOOP_MS = 10_000L

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun starterAccent(index: Int): StarterAccent {
    val scheme = MaterialTheme.colorScheme
    val shape = when (index % 4) {
        0 -> MaterialShapes.Cookie4Sided
        1 -> MaterialShapes.Sunny
        2 -> MaterialShapes.Clover4Leaf
        else -> MaterialShapes.Cookie6Sided
    }.toShape()
    return when (index % 3) {
        0 -> StarterAccent(scheme.primaryContainer, scheme.onPrimaryContainer, shape)
        1 -> StarterAccent(scheme.tertiaryContainer, scheme.onTertiaryContainer, shape)
        else -> StarterAccent(scheme.secondaryContainer, scheme.onSecondaryContainer, shape)
    }
}

@Composable
private fun StarterGrid(starters: List<ChatStarter>, step: Int, firstStep: Int, onTap: (ChatStarter) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        starters.chunked(2).forEachIndexed { row, pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEachIndexed { column, starter ->
                    val index = row * 2 + column
                    StarterCard(
                        starter, starterAccent(index),
                        Modifier.weight(1f).fillMaxHeight().enterRise(step > firstStep + index, rise = 24.dp, fromScale = 0.9f),
                    ) { onTap(starter) }
                }
            }
        }
    }
}

/** Press squeezes the card and tightens its corners, the Expressive shape-morph response. */
@Composable
private fun StarterCard(starter: ChatStarter, accent: StarterAccent, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, expressiveSpatialSpec(), label = "starterScale")
    val corner by animateDpAsState(if (pressed) 14.dp else 28.dp, expressiveSpatialSpec(), label = "starterCorner")
    val shape = RoundedCornerShape(corner.coerceAtLeast(0.dp))
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .harnessGlass(scheme.surfaceContainerHigh, shape)
            .clickable(interactionSource = interaction, indication = ripple(), role = Role.Button) {
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                onClick()
            }
            .heightIn(min = 108.dp)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).background(accent.container, accent.shape), contentAlignment = Alignment.Center) {
            Icon(starter.icon, null, Modifier.size(20.dp), tint = accent.content)
        }
        Text(starter.label, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** Keyboard is up: one row of compact chips so the headline keeps its room. */
@Composable
private fun StarterChips(starters: List<ChatStarter>, onTap: (ChatStarter) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        starters.forEachIndexed { index, starter ->
            val accent = starterAccent(index)
            val shape = RoundedCornerShape(50)
            Row(
                Modifier
                    .clip(shape)
                    .harnessGlass(scheme.surfaceContainerHigh, shape)
                    .clickable(role = Role.Button) {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onTap(starter)
                    }
                    .heightIn(min = 44.dp)
                    .padding(start = 6.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(32.dp).background(accent.container, accent.shape), contentAlignment = Alignment.Center) {
                    Icon(starter.icon, null, Modifier.size(16.dp), tint = accent.content)
                }
                Spacer(Modifier.width(8.dp))
                Text(starter.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

/** Donation and community stay available but quiet, away from the starters. */
@Composable
private fun ChatSupportLinks() {
    val context = LocalContext.current
    val noBrowserCoffee = stringResource(R.string.ui_no_browser_available_visit_buymeacoffee_com_sanuj7)
    val noBrowserDiscord = stringResource(R.string.discord_no_browser)
    fun open(url: String, fallback: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, fallback, Toast.LENGTH_LONG).show()
        }
    }
    Column(Modifier.fillMaxWidth().widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        DonationFundingNotice()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
            SupportChip(
                painterResource(R.drawable.ic_buy_me_a_coffee), Color(0xFFFFDD00), null,
                stringResource(R.string.ui_donate), stringResource(R.string.ui_open_buy_me_a_coffee),
            ) { open(DONATION_URL, noBrowserCoffee) }
            SupportChip(
                painterResource(R.drawable.ic_discord), DiscordBlurple, Color.White,
                stringResource(R.string.chat_support_discord), stringResource(R.string.discord_open_invite),
            ) { open(DISCORD_INVITE_URL, noBrowserDiscord) }
        }
    }
}

@Composable
private fun SupportChip(icon: Painter, badge: Color, tint: Color?, label: String, clickLabel: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .clickable(role = Role.Button, onClickLabel = clickLabel, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(start = 8.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp).background(badge, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
            if (tint != null) Icon(icon, null, Modifier.size(14.dp), tint = tint)
            else Image(icon, null, Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
    }
}
