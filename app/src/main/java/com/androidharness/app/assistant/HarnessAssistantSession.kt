package com.androidharness.app.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.androidharness.app.AgentService
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.R
import com.androidharness.app.ui.common.RefreshRate
import com.androidharness.app.ui.common.DialogHost
import com.androidharness.app.ui.common.LocalDialogHost
import android.service.voice.VoiceInteractionSession

/**
 * The floating panel Android shows when the user calls the assistant. The session window
 * is not an Activity, so it carries its own lifecycle, saved state and view model store
 * for Compose.
 */
internal class HarnessAssistantSession(context: Context) : VoiceInteractionSession(context),
    LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    private val container = (context.applicationContext as HarnessApp).container
    private val controller = AssistantController(context.applicationContext, container)
    /** Bumped on every show so the panel replays its entrance. */
    private val showCount = androidx.compose.runtime.mutableIntStateOf(0)
    /** False while Android has the panel hidden, so the microphone is let go. */
    private val visible = androidx.compose.runtime.mutableStateOf(false)

    override fun onCreate() {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        super.onCreate()
        window.window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            // The panel animates itself; the system fade would swallow the first part of it.
            w.setWindowAnimations(0)
            w.decorView.attachOwners()
        }
        keepWindowStill()
    }

    /**
     * Android pans this window above the keyboard on its own, and the panel already lifts
     * itself by the keyboard height, so the two together threw it halfway up the screen.
     * The window has to stay put so the panel alone follows the keyboard.
     *
     * "Adjust nothing" is the normal way to ask for that, but some phones put this window
     * back to "pan". Letting the window lay out without limits also turns the pan off
     * (Android then gives it no visible insets to pan into), and the panel still gets the
     * keyboard and system bar insets it lays itself out with.
     */
    private fun keepWindowStill() {
        val w = window.window ?: return
        val still = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        val mask = WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
        val noLimits = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (w.attributes.softInputMode and mask != still) w.setSoftInputMode((w.attributes.softInputMode and mask.inv()) or still)
        if (w.attributes.flags and noLimits == 0) w.addFlags(noLimits)
        val root = w.peekDecorView() ?: return
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return
        if (params.softInputMode and mask == still && params.flags and noLimits != 0) return
        params.softInputMode = (params.softInputMode and mask.inv()) or still
        params.flags = params.flags or noLimits
        if (root.isAttachedToWindow) runCatching { w.windowManager.updateViewLayout(root, params) }
    }

    /**
     * Without a stated rate this window gets the phone's "normal" rate, 60 Hz on many phones,
     * even on a 120 Hz screen, and every animation in the panel looked choppy next to the app
     * behind it. Ask for the fastest mode, the same as the app's own windows do.
     */
    private fun preferFastestRefresh(v: View) {
        val w = window.window ?: return
        val display = v.display ?: return
        val mode = runCatching { RefreshRate.fastestModeId(display) }.getOrDefault(0)
        if (mode == 0 || w.attributes.preferredDisplayModeId == mode) return
        w.attributes = w.attributes.apply { preferredDisplayModeId = mode }
    }

    override fun onCreateContentView(): View = ComposeView(context).apply {
        attachOwners()
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { keepWindowStill(); preferFastestRefresh(v); v.post { keepWindowStill() } }
            override fun onViewDetachedFromWindow(v: View) = Unit
        })
        // Focusing the text field is the moment Android decides to pan, so check again then.
        viewTreeObserver.addOnGlobalFocusChangeListener { _, _ -> keepWindowStill() }
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@HarnessAssistantSession))
        // Dialogs opened from the answer (tool details, images, tables) attach to this window.
        val dialogs = DialogHost(WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG) { windowToken }
        setContent {
            CompositionLocalProvider(LocalDialogHost provides dialogs) {
                AssistantPanelRoot(container, controller, showCount.intValue, visible.value, onDismiss = ::hide,
                    onContinue = ::continueInApp, onOpenUrl = ::openUrl)
            }
        }
    }

    private fun View.attachOwners() {
        setViewTreeLifecycleOwner(this@HarnessAssistantSession)
        setViewTreeSavedStateRegistryOwner(this@HarnessAssistantSession)
        setViewTreeViewModelStoreOwner(this@HarnessAssistantSession)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        keepWindowStill()
        controller.begin(
            wantsText = showFlags and SHOW_WITH_ASSIST != 0,
            wantsShot = showFlags and SHOW_WITH_SCREENSHOT != 0,
        )
        showCount.intValue++
        visible.value = true
        container.runtimePolicy.setOverlayShown(true)
        registry.currentState = Lifecycle.State.RESUMED
    }

    override fun onHide() {
        visible.value = false
        controller.quiet()
        registry.currentState = Lifecycle.State.CREATED
        container.runtimePolicy.setOverlayShown(false)
        super.onHide()
    }

    override fun onDestroy() {
        container.runtimePolicy.setOverlayShown(false)
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        controller.close()
        super.onDestroy()
    }

    @Deprecated("Android still calls this for the first assist result")
    @Suppress("DEPRECATION")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        // Null structure: the user turned off "Use text from screen" or the app is secure.
        controller.onAssist(structure, data?.getString(Intent.EXTRA_ASSIST_PACKAGE), content)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        // Null when the app is FLAG_SECURE or "Use screenshot" is off.
        controller.onScreenshot(screenshot)
    }

    /** Web links from an answer open in the browser, over which the panel steps aside. */
    private fun openUrl(url: String) {
        val uri = runCatching { Uri.parse(url.trim()) }.getOrNull()
        if (uri?.scheme?.lowercase() !in setOf("http", "https")) return
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { context.startActivity(intent) }.isSuccess ||
            runCatching { startAssistantActivity(intent) }.isSuccess
        if (opened) hide() else Toast.makeText(context, R.string.assistant_open_failed, Toast.LENGTH_SHORT).show()
    }

    private fun continueInApp() {
        val sid = controller.state.value.sessionId
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (sid != null) putExtra(AgentService.EXTRA_SESSION_ID, sid) }
        // The visible panel lets this app start an activity; the assistant stack is only a fallback.
        val opened = runCatching { context.startActivity(intent) }.isSuccess ||
            runCatching { startAssistantActivity(intent) }.isSuccess
        if (opened) hide() else Toast.makeText(context, R.string.assistant_open_failed, Toast.LENGTH_SHORT).show()
    }
}
