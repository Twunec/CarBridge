package org.carbridge

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.carbridge.core.CarOptions
import org.carbridge.patch.PatchPipeline
import org.carbridge.patch.Stage
import org.carbridge.patch.StageFailure

private const val TAG = "CarBridge"

/** A car copy being made, currently at [stage]; [detail] says which part, e.g. "split 2 of 4". */
data class Job(
    val label: String,
    val icon: ImageBitmap,
    val packageName: String,
    val update: Boolean,
    val stage: Stage,
    val detail: String? = null,
)

data class Setup(val canInstall: Boolean, val developerMode: Boolean, val unknownSources: Boolean) {
    val complete get() = canInstall && developerMode && unknownSources
}

/** An add waiting for the user's choices. */
data class AddChoice(
    val app: SourceApp,
    val portraitLocked: Boolean,
    val hasMediaService: Boolean,
    /** YouTube gets an explanation of what to expect before patching. */
    val isYouTube: Boolean,
)

/** A message for the snackbar; [stage] is where it failed, [checksFor] offers the copy's checks. */
data class UserMessage(
    val text: String,
    val details: String? = null,
    val stage: Stage? = null,
    val checksFor: String? = null,
)

const val YOUTUBE = "com.google.android.youtube"

class HomeModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("setup", 0)

    var catalog by mutableStateOf<Catalog?>(null)
        private set
    var job by mutableStateOf<Job?>(null)
        private set
    var setup by mutableStateOf(readSetup())
        private set
    var showAutoApps by mutableStateOf(prefs.getBoolean("show_auto_apps", false))
        private set

    /** The checks sheet being shown, if any. */
    var checks by mutableStateOf<CopyChecks?>(null)
        private set

    private val _messages = Channel<UserMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private val log = mutableListOf<String>()
    private val updateQueue = ArrayDeque<SourceApp>()

    /** Copies still waiting in an Update all run, including the one being built. */
    var queued by mutableStateOf(0)
        private set

    /** Fires once, after the first copy installs, so the permission ask has an obvious reason. */
    private val _notificationPrompt = Channel<Unit>(Channel.CONFLATED)
    val notificationPrompt = _notificationPrompt.receiveAsFlow()

    init {
        viewModelScope.launch {
            Installer.events.collect { result ->
                val current = job
                if (current != null && (result.packageName ?: current.packageName) == current.packageName) {
                    if (result.success) {
                        job = current.copy(stage = Stage.Inspect, detail = null)
                        val inspected = withContext(Dispatchers.IO) {
                            CopyChecks.run(getApplication(), prefs, current.packageName)
                        }
                        job = null
                        if (!prefs.getBoolean("asked_notifications", false)) {
                            prefs.edit().putBoolean("asked_notifications", true).apply()
                            _notificationPrompt.send(Unit)
                        }
                        _messages.send(
                            UserMessage(
                                if (inspected == null) {
                                    "${current.label} installed, but CarTube couldn't inspect it."
                                } else {
                                    "${current.label} installed. ${inspected.summary}."
                                },
                                checksFor = current.packageName,
                            ),
                        )
                        // Location must be granted before the car screen unlocks; show what's missing now
                        if (inspected != null && (inspected.failed > 0 || inspected.needsLocation)) checks = inspected
                    } else {
                        job = null
                        _messages.send(
                            if (result.cancelled) {
                                UserMessage("Install cancelled.")
                            } else {
                                UserMessage(
                                    "Couldn't install ${current.label}.",
                                    details(result.message),
                                    stage = Stage.Install,
                                )
                            },
                        )
                    }
                }
                refresh()
                if (job == null) nextQueued(continueRun = result.success)
            }
        }
    }

    /** Rebuilds every outdated copy in turn; each still needs its own install confirmation. */
    fun updateAll(copies: List<CarCopy>) {
        if (job != null) return
        updateQueue.clear()
        updateQueue.addAll(copies.filter { it.outdated }.mapNotNull { it.original })
        nextQueued(continueRun = true)
    }

    private fun nextQueued(continueRun: Boolean) {
        // A cancelled or failed install stops the run, so the user isn't trapped in prompts
        if (!continueRun) updateQueue.clear()
        val next = updateQueue.removeFirstOrNull()
        queued = if (next == null) 0 else updateQueue.size + 1
        next?.let { add(it, update = true) }
    }

    var pendingAdd by mutableStateOf<AddChoice?>(null)
        private set

    /** Asks for choices first when they matter, so the copy isn't patched twice. */
    fun requestAdd(app: SourceApp) {
        if (job != null) return
        viewModelScope.launch {
            val (portrait, media) = withContext(Dispatchers.IO) {
                AppCatalog.traits(getApplication(), app.info.packageName)
            }
            val youtube = app.info.packageName == YOUTUBE
            if (portrait || media || youtube) {
                pendingAdd = AddChoice(app, portrait, media, youtube)
            } else {
                add(app, update = false)
            }
        }
    }

    fun confirmAdd(choice: AddChoice, options: CarOptions, parkedOnly: Boolean) {
        val pkg = choice.app.info.packageName
        prefs.edit()
            .putBoolean("keep_portrait:$pkg", !options.fillScreen)
            .putBoolean("keep_media:$pkg", options.keepMediaServices)
            .putBoolean("parked_only:$pkg", parkedOnly || pkg == YOUTUBE)
            .apply()
        pendingAdd = null
        add(choice.app, update = false)
    }

    fun cancelAdd() {
        pendingAdd = null
    }

    /** Set from the update notification; null package means every outdated copy. */
    private var pendingUpdate: String? = null
    private var pendingUpdateAll = false

    fun updateFromNotification(originalPackage: String?) {
        if (originalPackage == null) pendingUpdateAll = true else pendingUpdate = originalPackage
        refresh()
    }

    private fun runPendingUpdate(catalog: Catalog) {
        if (job != null) return
        val all = pendingUpdateAll
        val pkg = pendingUpdate
        pendingUpdateAll = false
        pendingUpdate = null
        when {
            all -> updateAll(catalog.copies)
            pkg != null -> catalog.copies.firstOrNull { it.original?.info?.packageName == pkg }?.original
                ?.let { add(it, update = true) }
        }
    }

    /** Fixed sample data for store screenshots; never set in normal use. */
    private var sample: Catalog? = null

    fun showSample(catalog: Catalog) {
        sample = catalog
        refresh()
    }

    fun refresh() {
        sample?.let { setup = Setup(canInstall = true, developerMode = true, unknownSources = true); catalog = it; return }
        setup = readSetup()
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { AppCatalog.load(getApplication(), ::carOptions, ::parkedOnly) }
            catalog = loaded
            runPendingUpdate(loaded)
        }
        checks?.let { openChecks(it.packageName) }
    }

    fun toggleAutoApps() {
        showAutoApps = !showAutoApps
        prefs.edit().putBoolean("show_auto_apps", showAutoApps).apply()
    }

    /** Per-app choices; by default copies fill the screen and drop media services. */
    private fun carOptions(originalPackage: String) = CarOptions(
        keepMediaServices = prefs.getBoolean("keep_media:$originalPackage", false),
        fillScreen = !prefs.getBoolean("keep_portrait:$originalPackage", false),
    )

    /** The parked-only lock is on by default, and always on for YouTube. */
    private fun parkedOnly(originalPackage: String) =
        originalPackage == YOUTUBE || prefs.getBoolean("parked_only:$originalPackage", true)

    /** Saves the layout choice and rebuilds the copy with it. */
    fun setFillScreen(copy: CarCopy, fill: Boolean) {
        val original = copy.original ?: return
        prefs.edit().putBoolean("keep_portrait:${original.info.packageName}", !fill).apply()
        add(original, update = true)
    }

    /** Saves the audio-player choice and rebuilds the copy with it. */
    fun setKeepMedia(copy: CarCopy, keep: Boolean) {
        val original = copy.original ?: return
        prefs.edit().putBoolean("keep_media:${original.info.packageName}", keep).apply()
        add(original, update = true)
    }

    /** Saves the parked-only choice and rebuilds the copy with it; YouTube can't turn it off. */
    fun setParkedOnly(copy: CarCopy, on: Boolean) {
        val original = copy.original ?: return
        if (original.info.packageName == YOUTUBE && !on) return
        prefs.edit().putBoolean("parked_only:${original.info.packageName}", on).apply()
        add(original, update = true)
    }

    fun openChecks(carPackage: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { CopyChecks.run(getApplication(), prefs, carPackage) }
            if (result == null) {
                checks = null
                _messages.send(UserMessage("That car copy isn't installed any more."))
            } else {
                checks = result
            }
        }
    }

    fun closeChecks() {
        checks = null
    }

    fun setManualCheck(key: String, done: Boolean) {
        val current = checks ?: return
        prefs.edit().putBoolean(CopyChecks.manualPrefKey(current.packageName, current.versionCode, key), done).apply()
        openChecks(current.packageName)
    }

    fun markDeveloperMode() = mark("developer_mode")
    fun markUnknownSources() = mark("unknown_sources")

    private fun mark(key: String) {
        prefs.edit().putBoolean(key, true).apply()
        setup = readSetup()
    }

    private fun readSetup() = Setup(
        canInstall = getApplication<Application>().packageManager.canRequestPackageInstalls(),
        developerMode = prefs.getBoolean("developer_mode", false),
        unknownSources = prefs.getBoolean("unknown_sources", false),
    )

    fun add(source: SourceApp, update: Boolean) {
        if (job != null) return
        val context = getApplication<Application>()
        val originalPkg = source.info.packageName
        val carPackage = PatchPipeline.carPackageName(originalPkg)
        synchronized(log) { log.clear() }
        job = Job(source.label, source.icon, carPackage, update, Stage.Check)
        viewModelScope.launch {
            var stage = Stage.Check
            try {
                val result = withContext(Dispatchers.IO) {
                    PatchPipeline(
                        context,
                        onStage = { s, detail ->
                            stage = s
                            onLog("▶ ${s.title}" + (detail?.let { " ($it)" } ?: ""))
                            viewModelScope.launch { job = job?.copy(stage = s, detail = detail) }
                        },
                        log = ::onLog,
                    ).patch(source.info, carOptions(originalPkg), parkedOnly(originalPkg))
                }
                prefs.edit().putString(CopyChecks.verifiedPrefKey(carPackage), result.verification.summary).apply()

                stage = Stage.Install
                job = job?.copy(stage = Stage.Install, detail = "${result.apks.size} APK(s)")
                withContext(Dispatchers.IO) { Installer.install(context, result.apks, carPackage) }
                job = job?.copy(stage = Stage.Confirm, detail = null)
            } catch (e: Throwable) {
                Log.e(TAG, "patch failed", e)
                job = null
                nextQueued(continueRun = false)
                val failed = (e as? StageFailure)?.stage ?: stage
                val reason = (e as? StageFailure)?.reason ?: "${e.javaClass.simpleName}: ${e.message}"
                _messages.send(
                    UserMessage(
                        "Couldn't add ${source.label}: “${failed.title}” failed.",
                        details(reason),
                        stage = failed,
                    ),
                )
            }
        }
    }

    private fun onLog(line: String) {
        Log.i(TAG, line)
        synchronized(log) { log.add(line) }
    }

    /** The reason first, then the full log of this run, for the details dialog. */
    private fun details(error: String?) = synchronized(log) {
        (listOfNotNull(error) + listOf("", "Log:") + log).joinToString("\n")
    }
}
