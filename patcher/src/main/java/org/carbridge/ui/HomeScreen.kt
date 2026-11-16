package org.carbridge.ui

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.carbridge.AddChoice
import org.carbridge.CarCopy
import org.carbridge.core.CarOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.Role
import org.carbridge.Caution
import org.carbridge.CheckItem
import org.carbridge.CheckState
import org.carbridge.CopyChecks
import org.carbridge.HomeModel
import org.carbridge.Job
import org.carbridge.YOUTUBE
import org.carbridge.patch.Stage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.font.FontFamily
import org.carbridge.Setup
import org.carbridge.SourceApp
import org.carbridge.UserMessage
import org.carbridge.Version

class HomeActions(
    val allowInstalls: () -> Unit = {},
    val openAndroidAuto: () -> Unit = {},
    val markDeveloperMode: () -> Unit = {},
    val markUnknownSources: () -> Unit = {},
    val add: (SourceApp) -> Unit = {},
    val update: (CarCopy) -> Unit = {},
    val updateAll: (List<CarCopy>) -> Unit = {},
    val openMorphe: () -> Unit = {},
    val setFillScreen: (CarCopy, Boolean) -> Unit = { _, _ -> },
    val setKeepMedia: (CarCopy, Boolean) -> Unit = { _, _ -> },
    val setParkedOnly: (CarCopy, Boolean) -> Unit = { _, _ -> },
    val remove: (CarCopy) -> Unit = {},
    /** Opens a car copy's checks, by its package name. */
    val openChecks: (String) -> Unit = {},
    /** Opens Android's app info page for a package, e.g. to allow location for a copy. */
    val openAppInfo: (String) -> Unit = {},
)

private val SignShape = RoundedCornerShape(20.dp)
private val SignBorderShape = RoundedCornerShape(15.dp)
private val Gutter = 16.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(model: HomeModel, actions: HomeActions) {
    val snackbar = remember { SnackbarHostState() }
    var details by remember { mutableStateOf<UserMessage?>(null) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showSetup by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var cautionFor by remember { mutableStateOf<SourceApp?>(null) }

    LaunchedEffect(model) {
        model.messages.collect { message ->
            val action = when {
                message.checksFor != null -> "Checks"
                message.details != null -> "Details"
                else -> null
            }
            val result = snackbar.showSnackbar(
                message.text,
                actionLabel = action,
                withDismissAction = action == null,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                message.checksFor?.let(actions.openChecks) ?: run { details = message }
            }
        }
    }

    val catalog = model.catalog
    val job = model.job
    val setup = model.setup
    val busy = job != null

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            AnimatedVisibility(job != null, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                job?.let { JobBar(it, waiting = (model.queued - 1).coerceAtLeast(0)) }
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { inner ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    bottom = inner.calculateBottomPadding() + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
                ),
            ) {
                item("header") { SignHeader(onAbout = { showAbout = true }) }

                item("setup") {
                    if (!setup.complete || showSetup) {
                        SetupCard(setup, actions, onHide = if (setup.complete) ({ showSetup = false }) else null)
                    } else {
                        SetupDone(onShow = { showSetup = true })
                    }
                }

                if (catalog != null) {
                    val youtube = catalog.available.firstOrNull { it.info.packageName == YOUTUBE }
                    val youtubeCopy = catalog.copies.firstOrNull { it.original?.info?.packageName == YOUTUBE }
                    item("youtube") {
                        YouTubeCard(
                            source = youtube ?: youtubeCopy?.original,
                            copy = youtubeCopy,
                            busy = busy,
                            onCreate = { app -> actions.add(app) },
                            onUpdate = { copy -> actions.update(copy) },
                            onChecks = { copy -> actions.openChecks(copy.packageName) },
                            onRemove = { copy -> actions.remove(copy) },
                        )
                    }
                }

                item("car-title") { SectionTitle("On your car") }
                when {
                    catalog == null -> item("loading") { Loading() }
                    catalog.copies.isEmpty() -> item("car-empty") {
                        Hint("Nothing yet. Add an app below, then reconnect your phone to Android Auto.")
                    }
                    else -> {
                        val outdated = catalog.copies.filter { it.outdated && it.original != null }
                        if (outdated.size > 1) {
                            item("update-all") { UpdateAllRow(outdated.size, enabled = !busy) { actions.updateAll(outdated) } }
                        }
                        items(catalog.copies, key = { "car:" + it.packageName }) { copy ->
                        CarSign(
                            copy,
                            busy,
                            onUpdate = { actions.update(copy) },
                            onRemove = { actions.remove(copy) },
                            onFillScreen = { fill -> actions.setFillScreen(copy, fill) },
                            onKeepMedia = { keep -> actions.setKeepMedia(copy, keep) },
                            onParkedOnly = { on -> actions.setParkedOnly(copy, on) },
                            onChecks = { actions.openChecks(copy.packageName) },
                        )
                    }
                    }
                }

                item("add-title") { SectionTitle("Add an app") }
                if (catalog != null) {
                    if (catalog.available.size > 6) {
                        item("search") { SearchField(query) { query = it } }
                    }
                    val search = query.trim()
                    // A search is explicit, so it also finds apps that already have Android Auto
                    val shown = catalog.available.filter {
                        if (search.isNotEmpty()) {
                            it.label.contains(search, ignoreCase = true) || it.info.packageName.contains(search, ignoreCase = true)
                        }
                        else model.showAutoApps || !it.hasAndroidAuto
                    }
                    val autoCount = catalog.available.count { it.hasAndroidAuto }
                    if (search.isEmpty() && autoCount > 0) {
                        item("auto-toggle") { AutoAppsToggle(autoCount, model.showAutoApps, model::toggleAutoApps) }
                    }
                    if (shown.isEmpty()) {
                        item("add-empty") {
                            Hint(if (search.isEmpty()) "Every app you can add is already on your car." else "No apps match “$search”.")
                        }
                    }
                    items(shown, key = { "src:" + it.info.packageName }) { app ->
                        SourceRow(
                            app,
                            enabled = !busy,
                            onAdd = { if (app.caution != null) cautionFor = app else actions.add(app) },
                        )
                    }
                }

                item("footer") { Footer(onAbout = { showAbout = true }) }
            }

            // Keeps light status bar icons readable once the header scrolls away
            Box(
                Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(LocalSigns.current.guide),
            )
        }
    }

    model.pendingAdd?.let { choice ->
        AddChoiceDialog(
            choice,
            onConfirm = { options, parkedOnly -> model.confirmAdd(choice, options, parkedOnly) },
            onDismiss = model::cancelAdd,
        )
    }
    cautionFor?.let { app ->
        CautionDialog(
            app,
            onDismiss = { cautionFor = null },
            onAddAnyway = { cautionFor = null; actions.add(app) },
            onOpenMorphe = { cautionFor = null; actions.openMorphe() },
        )
    }
    model.checks?.let { checks ->
        ModalBottomSheet(onDismissRequest = model::closeChecks) {
            ChecksSheet(
                checks,
                onManual = model::setManualCheck,
                onAllowLocation = { actions.openAppInfo(checks.packageName) },
                onOpenAndroidAuto = actions.openAndroidAuto,
                onRerun = { model.openChecks(checks.packageName) },
            )
        }
    }
    if (showAbout) {
        ModalBottomSheet(onDismissRequest = { showAbout = false }) { About() }
    }
    details?.let { message ->
        AlertDialog(
            onDismissRequest = { details = null },
            confirmButton = { TextButton(onClick = { details = null }) { Text("Close") } },
            title = { Text(message.text) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    message.stage?.let { stage ->
                        Text(
                            "Failed at step ${stage.ordinal + 1} of ${Stage.entries.size}: ${stage.title}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(stageHelp(stage), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                    }
                    SelectionContainer {
                        Text(
                            message.details.orEmpty(),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            },
        )
    }
}

/** A guide-sign blank: green field, inset white border. */
@Composable
private fun GuidePanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val signs = LocalSigns.current
    Surface(modifier, shape = SignShape, color = signs.guide, contentColor = signs.onGuide, shadowElevation = 2.dp) {
        Column(
            Modifier.padding(5.dp).border(2.dp, signs.onGuide, SignBorderShape)
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            content = content,
        )
    }
}

@Composable
private fun SignHeader(onAbout: () -> Unit) {
    val signs = LocalSigns.current
    Column(
        Modifier.fillMaxWidth().background(signs.guide)
            .padding(WindowInsets.statusBars.asPaddingValues())
            .padding(start = Gutter, end = 4.dp, top = 12.dp, bottom = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(BridgeMark, contentDescription = null, tint = signs.onGuide, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "CarBridge",
                style = MaterialTheme.typography.displaySmall,
                color = signs.onGuide,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            IconButton(onClick = onAbout) {
                Icon(Icons.Rounded.Info, contentDescription = "About CarBridge", tint = signs.onGuide)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Put the apps on your phone on your car's screen.",
            style = MaterialTheme.typography.titleMedium,
            color = signs.onGuideMuted,
            modifier = Modifier.padding(end = Gutter),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(start = Gutter, end = Gutter, top = 32.dp, bottom = 12.dp).semantics { heading() },
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Gutter, vertical = 4.dp),
    )
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun SetupCard(setup: Setup, actions: HomeActions, onHide: (() -> Unit)?) {
    val signs = LocalSigns.current
    val steps = listOf(
        SetupStep(
            "Let CarBridge install apps",
            "Android asks once. CarBridge only installs the car copies you choose.",
            setup.canInstall,
        ) { Button(onClick = actions.allowInstalls, colors = cautionButton()) { Text("Allow") } },
        SetupStep(
            "Turn on developer mode in Android Auto",
            "Open Android Auto settings and tap Version 10 times until it says developer mode is on.",
            setup.developerMode,
        ) {
            Button(onClick = actions.openAndroidAuto, colors = cautionButton()) { Text("Open Android Auto") }
            TextButton(onClick = actions.markDeveloperMode, colors = ButtonDefaults.textButtonColors(contentColor = signs.onCaution)) {
                Text("It's on")
            }
        },
        SetupStep(
            "Allow unknown sources",
            "In Android Auto settings, open the menu at the top right, choose Developer settings and turn on Unknown sources.",
            setup.unknownSources,
        ) {
            Button(onClick = actions.openAndroidAuto, colors = cautionButton()) { Text("Open Android Auto") }
            TextButton(onClick = actions.markUnknownSources, colors = ButtonDefaults.textButtonColors(contentColor = signs.onCaution)) {
                Text("It's on")
            }
        },
    )
    val current = steps.indexOfFirst { !it.done }

    Surface(
        Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 20.dp),
        shape = SignShape,
        color = signs.caution,
        contentColor = signs.onCaution,
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (current == -1) "You're set up" else "Set up once",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                if (onHide != null) {
                    IconButton(onClick = onHide) { Icon(Icons.Rounded.Close, contentDescription = "Hide setup") }
                }
            }
            Text(
                "Android Auto only lists apps from outside the Play Store after these three steps.",
                style = MaterialTheme.typography.bodyMedium,
                color = signs.onCautionMuted,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            steps.forEachIndexed { i, step ->
                StepRow(number = i + 1, step = step, active = i == current)
            }
        }
    }
}

private class SetupStep(
    val title: String,
    val body: String,
    val done: Boolean,
    val actions: @Composable () -> Unit,
)

@Composable
private fun cautionButton() = LocalSigns.current.let {
    ButtonDefaults.buttonColors(containerColor = it.onCaution, contentColor = it.caution)
}

@Composable
private fun StepRow(number: Int, step: SetupStep, active: Boolean) {
    val signs = LocalSigns.current
    Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Box(
            Modifier.size(32.dp).clip(CircleShape)
                .then(if (step.done) Modifier.background(signs.onCaution) else Modifier.border(2.dp, signs.onCaution, CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            if (step.done) {
                Icon(Icons.Rounded.Check, contentDescription = "Done", tint = signs.caution, modifier = Modifier.size(20.dp))
            } else {
                Text("$number", style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).alpha(if (step.done || active) 1f else 0.72f)) {
            Text(step.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
            if (active) {
                Text(step.body, style = MaterialTheme.typography.bodyMedium, color = signs.onCautionMuted, modifier = Modifier.padding(top = 2.dp))
                FlowRow(
                    Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) { step.actions() }
            }
        }
    }
}

@Composable
private fun SetupDone(onShow: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = Gutter, end = 4.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text("Android Auto is set up", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onShow) { Text("Setup steps") }
    }
}

@Composable
private fun CarSign(
    copy: CarCopy,
    busy: Boolean,
    onUpdate: () -> Unit,
    onRemove: () -> Unit,
    onFillScreen: (Boolean) -> Unit,
    onKeepMedia: (Boolean) -> Unit,
    onParkedOnly: (Boolean) -> Unit,
    onChecks: () -> Unit,
) {
    val signs = LocalSigns.current
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp)) {
        if (copy.outdated) UpdateTab()
        GuidePanel(Modifier.fillMaxWidth().clip(SignShape).clickable(onClick = onChecks)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(copy.icon, 40)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(copy.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            copy.original == null -> "Original removed"
                            copy.outdated -> "${versionText(copy.version!!, copy.originalVersion!!)} → " +
                                versionText(copy.originalVersion, copy.version)
                            else -> copy.version?.name ?: "In Android Auto"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = signs.onGuideMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    PackageName(copy.packageName, signs.onGuideMuted)
                }
                if (copy.parkedOnly) {
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = "Video only while parked",
                        tint = signs.onGuideMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (copy.outdated) {
                    Button(
                        onClick = onUpdate,
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = signs.caution, contentColor = signs.onCaution),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) { Text("Update") }
                }
                Box {
                    IconButton(onClick = { menu = true }, enabled = !busy) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "More for ${copy.label}", tint = signs.onGuide)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Checks") },
                            leadingIcon = { Icon(Icons.Rounded.CheckCircle, contentDescription = null) },
                            onClick = { menu = false; onChecks() },
                        )
                        if (copy.original != null) {
                            DropdownMenuItem(
                                text = { Text("Update copy") },
                                leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                                onClick = { menu = false; onUpdate() },
                            )
                        }
                        if (copy.original != null && copy.portraitLocked) {
                            DropdownMenuItem(
                                text = { Text(if (copy.options.fillScreen) "Keep portrait layout" else "Fill the car screen") },
                                leadingIcon = { Icon(LandscapeGlyph, contentDescription = null) },
                                onClick = { menu = false; onFillScreen(!copy.options.fillScreen) },
                            )
                        }
                        if (copy.original != null && copy.hasMediaService) {
                            val keep = copy.options.keepMediaServices
                            DropdownMenuItem(
                                // Kept media services let Android Auto show its audio player for the copy
                                text = { Text(if (keep) "Always show the full app" else "Use Android Auto's audio player") },
                                leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
                                onClick = { menu = false; onKeepMedia(!keep) },
                            )
                        }
                        if (copy.original != null && copy.original.info.packageName != YOUTUBE) {
                            DropdownMenuItem(
                                text = { Text(if (copy.parkedOnly) "Allow while driving" else "Only while parked") },
                                leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                                onClick = { menu = false; onParkedOnly(!copy.parkedOnly) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Uninstall copy") },
                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                            onClick = { menu = false; onRemove() },
                        )
                    }
                }
            }
        }
    }
}

/** A sign-yellow tab on top of the panel, like the exit tab on a guide sign. */
@Composable
private fun UpdateTab() {
    val signs = LocalSigns.current
    Surface(
        Modifier.padding(start = 20.dp),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        color = signs.caution,
        contentColor = signs.onCaution,
    ) {
        Text(
            "UPDATE READY",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/** Version name, or the build number when both sides share a name (e.g. a same-name rebuild). */
private fun versionText(version: Version, other: Version): String =
    version.name?.takeIf { it != other.name } ?: "build ${version.code}"

@Composable
private fun AppIcon(icon: ImageBitmap, sizeDp: Int) {
    Image(icon, contentDescription = null, modifier = Modifier.size(sizeDp.dp))
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Search your apps") },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            { IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") } }
        } else null,
        singleLine = true,
        shape = CircleShape,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        modifier = Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, bottom = 8.dp),
    )
}

@Composable
private fun AutoAppsToggle(count: Int, showing: Boolean, onToggle: () -> Unit) {
    val apps = if (count == 1) "1 app" else "$count apps"
    Row(
        Modifier.fillMaxWidth().padding(start = Gutter, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (showing) "Showing $apps that already work in Android Auto." else "$apps already work in Android Auto.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onToggle) { Text(if (showing) "Hide them" else "Show them") }
    }
}

@Composable
private fun SourceRow(app: SourceApp, enabled: Boolean, onAdd: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.icon, 44)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            PackageName(app.info.packageName, MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                listOfNotNull(
                    when (app.caution) {
                        Caution.LicenseCheck -> "Copy protected"
                        Caution.Morphe -> "Patched with Morphe"
                        null -> null
                    },
                    "Has Android Auto".takeIf { app.hasAndroidAuto },
                    app.version?.name,
                    Formatter.formatShortFileSize(context, app.sizeBytes),
                    splitText(app.splitCount),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(onClick = onAdd, enabled = enabled, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add")
        }
    }
}

@Composable
private fun UpdateAllRow(count: Int, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$count copies are out of date",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(onClick = onClick, enabled = enabled) { Text("Update all") }
    }
}

@Composable
private fun AddChoiceDialog(choice: AddChoice, onConfirm: (CarOptions, Boolean) -> Unit, onDismiss: () -> Unit) {
    var fill by rememberSaveable { mutableStateOf(true) }
    var player by rememberSaveable { mutableStateOf(false) }
    var parkedOnly by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (choice.isYouTube) "Create YouTube (Car)" else "Add ${choice.app.label}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (choice.isYouTube) {
                    YouTubeExpectations(choice.app)
                } else {
                    Text(
                        "You can change these later from the copy's ⋮ menu.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ChoiceRow(
                        "Only while parked",
                        "Covers the app on the car screen and pauses playback unless GPS shows the car is still. Needs location for the copy.",
                        parkedOnly,
                    ) { parkedOnly = it }
                }
                if (choice.portraitLocked) {
                    ChoiceRow(
                        "Fill the car screen",
                        "Some screens only work upright. Turn off to keep them narrow but unchanged.",
                        fill,
                    ) { fill = it }
                }
                if (choice.hasMediaService) {
                    ChoiceRow(
                        "Use Android Auto's audio player",
                        "Android Auto shows its own player for this app instead of the full app.",
                        player,
                    ) { player = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(CarOptions(keepMediaServices = player, fillScreen = fill), parkedOnly) }) {
                Text(if (choice.isYouTube) "Create car version" else "Add to car")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChoiceRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun CautionDialog(app: SourceApp, onDismiss: () -> Unit, onAddAnyway: () -> Unit, onOpenMorphe: () -> Unit) {
    when (app.caution) {
        Caution.Morphe -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Add ${app.label} in Morphe instead") },
            text = {
                Text(
                    "${app.label} was patched with Morphe. Add the CarBridge Patches source in Morphe and " +
                        "repatch there: you keep one app with your settings, and it avoids a branding error.",
                )
            },
            confirmButton = { TextButton(onClick = onOpenMorphe) { Text("Open in Morphe") } },
            dismissButton = { TextButton(onClick = onAddAnyway) { Text("Make a copy anyway") } },
        )
        Caution.LicenseCheck -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("${app.label} probably won't open") },
            text = {
                Text(
                    "${app.label} uses Google Play's copy protection, which usually closes copies as soon " +
                        "as they start. You can still try.",
                )
            },
            confirmButton = { TextButton(onClick = onAddAnyway) { Text("Add anyway") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        null -> Unit
    }
}

@Composable
private fun Footer(onAbout: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 32.dp)) {
        Text(
            "Car copies lock video on the car screen unless the car is parked. Never watch while driving.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onAbout, modifier = Modifier.padding(top = 12.dp)) { Text("How CarBridge works") }
    }
}

@Composable
private fun JobBar(job: Job, waiting: Int) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(job.icon, 40)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            waiting > 0 -> "Updating ${job.label}, $waiting more after"
                            job.update -> "Updating ${job.label}"
                            else -> "Adding ${job.label} to your car"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Step ${job.stage.ordinal + 1} of ${Stage.entries.size} · ${job.stage.title}" +
                            (job.detail?.let { " ($it)" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LaneProgress(job.stage.ordinal, Stage.entries.size, Modifier.padding(top = 14.dp))
        }
    }
}

/** Progress drawn as lane markings: finished dashes solid, the current one pulsing. */
@Composable
private fun LaneProgress(current: Int, total: Int, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "lane").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse",
    )
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outlineVariant
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(total) { i ->
            Box(
                Modifier.weight(1f).height(6.dp).clip(CircleShape).background(
                    when {
                        i < current -> on
                        i == current -> on.copy(alpha = pulse)
                        else -> off
                    },
                ),
            )
        }
    }
}

@Composable
private fun About() {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("How CarBridge works", style = MaterialTheme.typography.headlineSmall)
        AboutItem(
            "A separate copy",
            "CarBridge makes a copy of an app that Android Auto will show on the car screen. " +
                "Your original app and its data stay as they are; the copy has its own data and sign-in.",
        )
        AboutItem(
            "Some apps won't open",
            "Apps that check their own signature, a license or Play Integrity may refuse to run as a copy.",
        )
        AboutItem(
            "Keep CarBridge installed",
            "Copies are signed with a key stored only on this phone. If you uninstall CarBridge or clear its data, " +
                "remove your copies and add them again.",
        )
        AboutItem(
            "Use it safely and fairly",
            "Use car copies only while parked or for passengers. Only copy apps whose license allows changes, " +
                "and don't share the copies.",
        )
        AboutItem(
            "Parked-only video",
            "Each copy carries a small lock from CarBridge. On the car screen it covers the app and pauses playback " +
                "unless the phone's GPS shows the car has been still for a few seconds. Without location permission " +
                "or a GPS fix it stays locked. It doesn't affect the copy on the phone's own screen.",
        )
        AboutItem(
            "Free software",
            "GPL-3.0. No accounts, ads or tracking, and no internet permission. Everything happens on this phone.",
        )
        AboutItem(
            "Licence and credits",
            "This is a modified version of CarBridge by jmwerk (github.com/jmwerk/CarBridge), changed in November 2026 " +
                "to add the YouTube workflow, the parked-only lock, step-by-step progress and copy checks. It is " +
                "distributed under the GNU General Public License v3.0, without warranty; the full licence is in the " +
                "LICENSE file of the source. Car-visibility entries come from AABrowser (GPL-3.0). Uses ARSCLib " +
                "(Apache-2.0) and apksig (Apache-2.0, Android Open Source Project), and Jetpack Compose (Apache-2.0).",
        )
        AboutItem(
            "Other apps' licences",
            "YouTube and other apps you copy keep their own terms. Copies are for your own use: don't share them.",
        )
    }
}

@Composable
private fun AboutItem(title: String, body: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun PackageName(packageName: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        packageName,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Play installs most apps as an App Bundle: a base APK plus config splits. */
private fun splitText(splits: Int) = when (splits) {
    0 -> "single APK"
    1 -> "base + 1 split"
    else -> "base + $splits splits"
}

/** What a failure at each stage usually means, and what to try. */
private fun stageHelp(stage: Stage) = when (stage) {
    Stage.Check -> "CarBridge couldn't start: usually free space, or Android not letting it read the app."
    Stage.Read -> "The app's APK files couldn't be read. Very large apps can run out of memory here."
    Stage.Patch -> "The app's manifest couldn't be changed for Android Auto. This app may not support being copied."
    Stage.Lock -> "The parked-only lock couldn't be added to the copy."
    Stage.Write -> "Writing the copy's APK files failed, often for lack of space."
    Stage.Sign -> "Signing with this phone's key failed. Restarting the phone sometimes helps the Keystore."
    Stage.Verify -> "The signed copy didn't pass the same signature checks Android's installer runs, so it wasn't installed."
    Stage.Install, Stage.Confirm -> "Android's installer refused the copy. Its own message is below."
    Stage.Inspect -> "The copy installed, but checking it failed."
}

/** The main job: making and managing YouTube's car version. */
@Composable
private fun YouTubeCard(
    source: SourceApp?,
    copy: CarCopy?,
    busy: Boolean,
    onCreate: (SourceApp) -> Unit,
    onUpdate: (CarCopy) -> Unit,
    onChecks: (CarCopy) -> Unit,
    onRemove: (CarCopy) -> Unit,
) {
    val context = LocalContext.current
    Surface(
        Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 20.dp),
        shape = SignShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("YouTube for the car", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            if (source == null) {
                Text(
                    "YouTube isn't installed on this phone. Install it from the Play Store, then come back.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                return@Column
            }
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(copy?.icon ?: source.icon, 48)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (copy != null) "YouTube (Car)" else source.label, style = MaterialTheme.typography.titleMedium)
                    PackageName(copy?.packageName ?: source.info.packageName, MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        listOfNotNull(
                            (copy?.version ?: source.version)?.name,
                            splitText(source.splitCount),
                            Formatter.formatShortFileSize(context, source.sizeBytes),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                if (copy == null) {
                    "Creates a separate app, YouTube (Car), that Android Auto can show. Your YouTube app stays as it is. " +
                        "Video on the car screen only plays while parked."
                } else if (copy.outdated) {
                    "YouTube was updated. Update the car version to match."
                } else {
                    "Installed next to YouTube. Run the checks after each update and in the car."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            FlowRow(
                Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (copy == null) {
                    Button(onClick = { onCreate(source) }, enabled = !busy) { Text("Create YouTube (Car)") }
                } else {
                    Button(onClick = { onChecks(copy) }) { Text("Checks") }
                    FilledTonalButton(onClick = { onUpdate(copy) }, enabled = !busy) {
                        Text(if (copy.outdated) "Update" else "Rebuild")
                    }
                    OutlinedButton(onClick = { onRemove(copy) }, enabled = !busy) { Text("Uninstall") }
                }
            }
        }
    }
}

@Composable
private fun YouTubeExpectations(app: SourceApp) {
    val points = listOf(
        "Your YouTube app, its data and its Play Store updates are untouched. The car version is a separate app with its own data.",
        "The ${splitText(app.splitCount)} from the Play Store are copied, renamed and signed with a key that stays on this phone.",
        "Google sign-in will probably not work in a re-signed copy. Expect to use it signed out.",
        "YouTube may refuse to run, or misbehave, as a copy. Patching succeeding doesn't prove it works: use Checks and test in the car.",
        "On the car screen, video is covered and paused unless GPS shows the car is parked. Allow location for the copy afterwards.",
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        points.forEach { point ->
            Row {
                Text("•  ", style = MaterialTheme.typography.bodyMedium)
                Text(point, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ChecksSheet(
    checks: CopyChecks,
    onManual: (String, Boolean) -> Unit,
    onAllowLocation: () -> Unit,
    onOpenAndroidAuto: () -> Unit,
    onRerun: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
    ) {
        Text("Checks: ${checks.label}", style = MaterialTheme.typography.headlineSmall)
        PackageName(checks.packageName, MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            checks.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        FlowRow(
            Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (checks.needsLocation) Button(onClick = onAllowLocation) { Text("Allow location") }
            FilledTonalButton(onClick = onRerun) { Text("Check again") }
            OutlinedButton(onClick = onOpenAndroidAuto) { Text("Android Auto settings") }
        }
        checks.sections.forEach { section ->
            Text(
                section.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 20.dp, bottom = 4.dp).semantics { heading() },
            )
            HorizontalDivider()
            section.items.forEach { item -> CheckRow(item, onManual) }
        }
    }
}

@Composable
private fun CheckRow(item: CheckItem, onManual: (String, Boolean) -> Unit) {
    val signs = LocalSigns.current
    val manual = item.manualKey
    Row(
        Modifier.fillMaxWidth()
            .then(
                if (manual != null) {
                    Modifier.toggleable(value = item.done, role = Role.Checkbox) { onManual(manual, it) }
                } else Modifier,
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when (item.state) {
                CheckState.Pass -> Icon(Icons.Rounded.CheckCircle, "Passed", tint = MaterialTheme.colorScheme.primary)
                CheckState.Warn -> Icon(Icons.Rounded.Warning, "Warning", tint = signs.caution)
                CheckState.Fail -> Icon(Icons.Rounded.Close, "Failed", tint = MaterialTheme.colorScheme.error)
                CheckState.Manual -> Checkbox(checked = item.done, onCheckedChange = null)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall)
            Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
