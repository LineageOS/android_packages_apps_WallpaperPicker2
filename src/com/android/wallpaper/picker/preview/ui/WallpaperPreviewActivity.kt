/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.wallpaper.picker.preview.ui

import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.OutcomeReceiver
import android.stats.style.StyleEnums
import android.util.Log
import android.view.Window
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.android.wallpaper.R
import com.android.wallpaper.config.BaseFlags
import com.android.wallpaper.model.ImageWallpaperInfo
import com.android.wallpaper.model.WallpaperInfo
import com.android.wallpaper.module.InjectorProvider
import com.android.wallpaper.module.logging.UserEventLogger
import com.android.wallpaper.picker.AppbarFragment
import com.android.wallpaper.picker.BasePreviewActivity
import com.android.wallpaper.picker.category.ui.viewmodel.CategoriesViewModel
import com.android.wallpaper.picker.common.preview.data.repository.PersistentWallpaperModelRepository
import com.android.wallpaper.picker.data.WallpaperModel
import com.android.wallpaper.picker.di.modules.MainDispatcher
import com.android.wallpaper.picker.preview.data.repository.CreativeEffectsRepository
import com.android.wallpaper.picker.preview.data.repository.ImageEffectsRepository
import com.android.wallpaper.picker.preview.data.repository.WallpaperPreviewRepository
import com.android.wallpaper.picker.preview.data.util.LiveWallpaperDownloader
import com.android.wallpaper.picker.preview.ui.fragment.SmallPreviewFragment
import com.android.wallpaper.picker.preview.ui.viewmodel.PreviewActionsViewModel.Companion.getEditActivityIntent
import com.android.wallpaper.picker.preview.ui.viewmodel.PreviewActionsViewModel.Companion.isNewCreativeWallpaper
import com.android.wallpaper.picker.preview.ui.viewmodel.WallpaperPreviewViewModel
import com.android.wallpaper.picker.preview.ui.viewmodel.WallpaperPreviewViewModel.Companion.PreviewScreen
import com.android.wallpaper.util.ActivityUtils
import com.android.wallpaper.util.LaunchSourceUtils.WALLPAPER_LAUNCH_SOURCE
import com.android.wallpaper.util.WallpaperConnection
import com.android.wallpaper.util.converter.WallpaperModelFactory
import com.android.wallpaper.util.wallpaperconnection.LiveWallpaperConnectionUtils
import com.android.wallpaper.util.wallpaperconnection.WallpaperConnectionUtils
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** This activity holds the flow for the preview screen. */
@AndroidEntryPoint(BasePreviewActivity::class)
class WallpaperPreviewActivity :
    Hilt_WallpaperPreviewActivity(), AppbarFragment.AppbarFragmentHost {
    @ApplicationContext @Inject lateinit var appContext: Context
    @Inject lateinit var wallpaperModelFactory: WallpaperModelFactory
    @Inject lateinit var wallpaperPreviewRepository: WallpaperPreviewRepository
    @Inject lateinit var imageEffectsRepository: ImageEffectsRepository
    @Inject lateinit var creativeEffectsRepository: CreativeEffectsRepository
    @Inject lateinit var persistentWallpaperModelRepository: PersistentWallpaperModelRepository
    @Inject lateinit var liveWallpaperDownloader: LiveWallpaperDownloader
    @MainDispatcher @Inject lateinit var mainScope: CoroutineScope
    @Inject lateinit var wallpaperConnectionUtils: WallpaperConnectionUtils
    // Lazily instantiated via dagger.Lazy to ensure LiveWallpaperConnectionUtils is only created if
    // isRefactorWallpaperPreviewScreenEnabled is true.
    @Inject lateinit var liveWallpaperConnectionUtils: dagger.Lazy<LiveWallpaperConnectionUtils>

    private var refreshCreativeCategories: Boolean? = null

    private val wallpaperPreviewViewModel: WallpaperPreviewViewModel by viewModels()
    private val categoriesViewModel: CategoriesViewModel by viewModels()

    private var isFirstRun = false
    private var navigateToExtendedWallpaperEffects: Boolean? = null

    private var isRefactorWallpaperPreviewScreenEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        isFirstRun = savedInstanceState == null

        window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
        super.onCreate(savedInstanceState)
        isRefactorWallpaperPreviewScreenEnabled =
            BaseFlags.get(this).isRefactorWallpaperPreviewScreenEnabled()
        wallpaperPreviewViewModel.updateDisplayConfiguration()
        wallpaperPreviewViewModel.setIsWallpaperColorPreviewEnabled(
            !InjectorProvider.getInjector().isCurrentSelectedColorPreset(appContext)
        )
        window.navigationBarColor = Color.TRANSPARENT
        window.statusBarColor = Color.TRANSPARENT
        setContentView(R.layout.activity_wallpaper_preview)

        navigateToExtendedWallpaperEffects =
            intent.getBooleanExtra(SHOULD_NAVIGATE_TO_EXTENDED_WALLPAPER_EFFECTS, false)

        wallpaperPreviewViewModel.previewActionsViewModel.hideInformationFloatingSheet.value =
            intent.getBooleanExtra(HIDE_INFO_SHEET, false)

        refreshCreativeCategories = intent.getBooleanExtra(SHOULD_CATEGORY_REFRESH, false)

        val wallpaper =
            if (isFirstRun) {
                    (persistentWallpaperModelRepository.wallpaperModel.value
                            ?: intent
                                .getParcelableExtra(EXTRA_WALLPAPER_INFO, WallpaperInfo::class.java)
                                ?.convertToWallpaperModel())
                        ?.also { wallpaperPreviewRepository.setWallpaperModel(it) }
                        ?: run {
                            Log.e(TAG, "No wallpaper for previewing on first launch")
                            showToastAndFinish(R.string.wallpaper_preview_error)
                            return
                        }
                } else {
                    wallpaperPreviewViewModel.wallpaper.value
                }
                .also { persistentWallpaperModelRepository.cleanup() }

        val navController =
            (supportFragmentManager.findFragmentById(R.id.wallpaper_preview_nav_host)
                    as NavHostFragment)
                .navController

        val graph =
            navController.navInflater.inflate(
                if (isRefactorWallpaperPreviewScreenEnabled)
                    R.navigation.wallpaper_preview_nav_graph_compose_refactor
                else R.navigation.wallpaper_preview_nav_graph
            )
        val startDestinationArgs: Bundle =
            Bundle().apply {
                if (navigateToExtendedWallpaperEffects == true) {
                    putBoolean(SHOULD_NAVIGATE_TO_EXTENDED_WALLPAPER_EFFECTS, true)
                    // SmallPreviewFragment is the starting fragment. Hide its surfaces when
                    // entering and exiting to remove activity transition jank.
                    putBoolean(HIDE_SURFACES_FOR_ENTER_TRANSITION, true)
                    putBoolean(HIDE_SURFACES_FOR_EXIT_TRANSITION, true)
                } else if (
                    wallpaper is WallpaperModel.LiveWallpaperModel &&
                        wallpaper.isNewCreativeWallpaper(this@WallpaperPreviewActivity)
                ) {
                    putAll(wallpaper.getNewCreativeWallpaperArgs())
                    // For creating a new creative wallpaper, replace the default start
                    // destination
                    // with CreativeEditPreviewFragment.
                    graph.setStartDestination(R.id.creativeEditPreviewFragment)
                } else {
                    // SmallPreviewFragment is the starting fragment. Hide its surfaces when
                    // entering and exiting to remove activity transition jank.
                    putBoolean(HIDE_SURFACES_FOR_ENTER_TRANSITION, true)
                    putBoolean(HIDE_SURFACES_FOR_EXIT_TRANSITION, true)
                }
            }

        navController.setGraph(graph, startDestinationArgs)
        // Fits screen to navbar and statusbar
        WindowCompat.setDecorFitsSystemWindows(window, ActivityUtils.isSUWMode(this))
        val isAssetIdPresent = intent.getBooleanExtra(IS_ASSET_ID_PRESENT, false)
        wallpaperPreviewViewModel.isNewTask = intent.getBooleanExtra(IS_NEW_TASK, false)
        wallpaperPreviewViewModel.setWallpaperEntryPointValue(
            intent.getIntExtra(
                WALLPAPER_ENTRYPOINT,
                StyleEnums.SET_WALLPAPER_ENTRY_POINT_WALLPAPER_PREVIEW,
            )
        )
        val whichPreview =
            if (isAssetIdPresent) WallpaperConnection.WhichPreview.EDIT_NON_CURRENT
            else WallpaperConnection.WhichPreview.EDIT_CURRENT
        wallpaperPreviewViewModel.setWhichPreview(whichPreview)
        if (wallpaper is WallpaperModel.StaticWallpaperModel) {
            wallpaper.staticWallpaperData.cropHints?.let {
                wallpaperPreviewViewModel.setCropHints(it)
            }
        }
        if (
            (wallpaper as? WallpaperModel.StaticWallpaperModel)?.downloadableWallpaperData != null
        ) {
            liveWallpaperDownloader.initiateDownloadableService(
                this,
                wallpaper,
                registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {},
            )
        }

        val creativeWallpaperEffectData =
            (wallpaper as? WallpaperModel.LiveWallpaperModel)
                ?.creativeWallpaperData
                ?.creativeWallpaperEffectsData
        if (
            creativeWallpaperEffectData != null && !creativeEffectsRepository.isEffectInitialized()
        ) {
            lifecycleScope.launch {
                creativeEffectsRepository.initializeEffect(creativeWallpaperEffectData)
            }
        } else if (
            (wallpaper as? WallpaperModel.StaticWallpaperModel)?.imageWallpaperData != null &&
                imageEffectsRepository.areEffectsAvailable()
        ) {
            lifecycleScope.launch {
                imageEffectsRepository.initializeEffect(
                    staticWallpaperModel = wallpaper,
                    onWallpaperModelUpdated = { wallpaper ->
                        wallpaperPreviewRepository.setWallpaperModel(wallpaper)
                    },
                )
            }
        }

        val baseFlags = BaseFlags.get(this)
        if (baseFlags.isFullscreenPreviewFlowFixEnabled(this)) {
            if (baseFlags.isRefactorWallpaperPreviewScreenEnabled()) {
                lifecycleScope.launch {
                    wallpaperPreviewViewModel.shouldForceDesktopFullscreen.collect {
                        if (it && isInMultiWindowMode) {
                            requestFullscreen(FULLSCREEN_MODE_REQUEST_ENTER)
                        } else if (!it && !isInMultiWindowMode) {
                            requestFullscreen(FULLSCREEN_MODE_REQUEST_EXIT)
                        }
                    }
                }
            } else {
                lifecycleScope.launch {
                    wallpaperPreviewViewModel.previousAndCurrentPreviewScreen.collect { screens ->
                        when (screens.first to screens.second) {
                            null to PreviewScreen.FULL_PREVIEW -> {
                                if (isInMultiWindowMode) {
                                    // Wallpaper preview was dragged from fullscreen FULL_PREVIEW
                                    // back
                                    // to desktop. Go back to SMALL_PREVIEW.
                                    wallpaperPreviewViewModel.handleBackPressed()
                                    navController.popBackStack()
                                }
                            }

                            PreviewScreen.SMALL_PREVIEW to PreviewScreen.FULL_PREVIEW -> {
                                if (isInMultiWindowMode) {
                                    // User started FULL_PREVIEW while in desktop windowing.
                                    requestFullscreen(FULLSCREEN_MODE_REQUEST_ENTER)
                                }
                            }

                            PreviewScreen.FULL_PREVIEW to PreviewScreen.SMALL_PREVIEW -> {
                                if (!isInMultiWindowMode) {
                                    // User finished FULL_PREVIEW and should go back to desktop.
                                    requestFullscreen(FULLSCREEN_MODE_REQUEST_EXIT)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun requestFullscreen(request: Int, onError: ((Throwable) -> Unit)? = null) {
        requestFullscreenMode(
            request,
            object : OutcomeReceiver<Void, Throwable> {
                override fun onResult(result: Void) {
                    Log.v(TAG, "requestFullscreenMode $request success")
                }

                override fun onError(t: Throwable) {
                    Log.e(TAG, "Error requesting fullscreen mode $request", t)
                    onError?.invoke(t)
                }
            },
        )
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.wallpaper_preview_nav_host)
        (navHostFragment?.getChildFragmentManager()?.fragments?.firstOrNull()
                as? SmallPreviewFragment)
            ?.onEnterAnimationComplete()
    }

    override fun onUpArrowPressed() {
        onBackPressedDispatcher.onBackPressed()
    }

    override fun isUpArrowSupported(): Boolean {
        return BaseFlags.get(baseContext).shouldShowDesktopUi(baseContext) ||
            !ActivityUtils.isSUWMode(baseContext)
    }

    override fun onResume() {
        super.onResume()
        if (BaseFlags.get(this).isFullscreenPreviewFlowFixEnabled(this)) {
            return
        }
        if (isInMultiWindowMode) {
            val isWindowingModeFreeform =
                resources.configuration.windowConfiguration.windowingMode == WINDOWING_MODE_FREEFORM
            if (isWindowingModeFreeform && !isFullscreenPreviewEnabled()) {
                // Allow current devices in freeform mode to continue while fullscreen preview rolls
                // out.
                return
            }
            if (isFirstRun && isFullscreenPreviewEnabled()) {
                requestFullscreen(FULLSCREEN_MODE_REQUEST_ENTER) { showToastAndFinish() }
                // Don't dismiss the preview right away while it is still switching to fullscreen.
                return
            }
            // User has returned to freeform mode, so we should dismiss the preview.
            showToastAndFinish()
        }
    }

    override fun onPause() {
        super.onPause()

        // When back to main screen user could launch preview again before it's fully destroyed and
        // it could clean up the repo set by the new launching call, move it earlier to on pause.
        if (isFinishing) {
            persistentWallpaperModelRepository.cleanup()
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            // ImageEffectsRepositoryImpl is Activity-Retained Scoped, and its injected
            // EffectsController is Singleton scoped. Therefore, persist state on config change
            // restart, and only destroy when activity is finishing.
            imageEffectsRepository.destroy()
            // CreativeEffectsRepository is Activity-Retained Scoped, and its injected
            // EffectsController is Singleton scoped. Therefore, persist state on config change
            // restart, and only destroy when activity is finishing.
            creativeEffectsRepository.destroy()

            if (isRefactorWallpaperPreviewScreenEnabled) {
                // liveWallpaperConnectionUtils is Activity-Retained Scoped, however, the associated
                // connections can cause memory leaks if we do not proactively release them.
                // We only disconnect when the activity is finishing, so that we can retain the
                // connections on config change.
                mainScope.launch { liveWallpaperConnectionUtils.get().disconnectAll() }
            } else {
                mainScope.launch { wallpaperConnectionUtils.disconnectAll() }
            }
        }
        liveWallpaperDownloader.cleanup()

        refreshCreativeCategories?.let {
            if (it) {
                categoriesViewModel.refreshCategory()
            }
        }

        super.onDestroy()
    }

    private fun WallpaperInfo.convertToWallpaperModel(): WallpaperModel {
        return wallpaperModelFactory.getWallpaperModel(appContext, this)
    }

    private fun showToastAndFinish(messageResId: Int = R.string.wallpaper_exit_split_screen) {
        // TODO(b/409622144) re-evaluate this string for freeform mode.
        Toast.makeText(this, messageResId, Toast.LENGTH_SHORT).show()
        finishAfterTransition()
    }

    private fun isFullscreenPreviewEnabled() = BaseFlags.get(this).isFullscreenPreviewEnabled(this)

    companion object {
        const val HIDE_SURFACES_FOR_ENTER_TRANSITION = "hide_surfaces_for_enter_transition"
        const val HIDE_SURFACES_FOR_EXIT_TRANSITION = "hide_surfaces_for_exit_transition"
        const val SHOULD_NAVIGATE_TO_EXTENDED_WALLPAPER_EFFECTS =
            "should_navigate_to_extended_wallpaper_effects"
        private const val HIDE_INFO_SHEET = "hide_info_sheet"

        private const val TAG = "WallpaperPreviewActivity"

        private fun newIntent(
            context: Context,
            isAssetIdPresent: Boolean,
            isViewAsHome: Boolean,
            isNewTask: Boolean,
            wallpaperInfo: WallpaperInfo?,
            fromOriginalIntent: Intent?,
            shouldCategoryRefresh: Boolean,
            hideInfoSheet: Boolean,
            shouldNavigateToExtendedWallpaperEffects: Boolean,
            @UserEventLogger.SetWallpaperEntryPoint setWallpaperEntryPoint: Int,
            wallpaperLaunchSource: String?,
        ): Intent {

            val intent = Intent(context.applicationContext, WallpaperPreviewActivity::class.java)

            if (isNewTask) {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }

            // Special handling for propagating permissions from an original Intent.
            fromOriginalIntent?.data?.let {
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.setData(it)
            }

            // Add all extras to the Intent.
            intent.putExtra(IS_ASSET_ID_PRESENT, isAssetIdPresent)
            intent.putExtra(EXTRA_VIEW_AS_HOME, isViewAsHome)
            intent.putExtra(IS_NEW_TASK, isNewTask)
            wallpaperInfo?.let { intent.putExtra(EXTRA_WALLPAPER_INFO, it) }
            intent.putExtra(SHOULD_CATEGORY_REFRESH, shouldCategoryRefresh)
            intent.putExtra(HIDE_INFO_SHEET, hideInfoSheet)
            intent.putExtra(
                SHOULD_NAVIGATE_TO_EXTENDED_WALLPAPER_EFFECTS,
                shouldNavigateToExtendedWallpaperEffects,
            )
            intent.putExtra(WALLPAPER_ENTRYPOINT, setWallpaperEntryPoint)
            intent.putExtra(WALLPAPER_LAUNCH_SOURCE, wallpaperLaunchSource)

            return intent
        }

        /**
         * Creates a new builder for constructing an [Intent] to start [WallpaperPreviewActivity].
         * This is the recommended entry point for both Java and Kotlin callers.
         *
         * @param context The application context.
         * @param isAssetIdPresent Indicates if the asset ID is present. This is a required
         *   parameter.
         * @return A new instance of [IntentBuilder].
         */
        @JvmStatic
        fun intentBuilder(context: Context, isAssetIdPresent: Boolean): IntentBuilder {
            return IntentBuilder(context, isAssetIdPresent)
        }

        /**
         * A builder for creating Intents to launch [WallpaperPreviewActivity]. This pattern
         * provides a clear way to configure the Intent, especially from Java where named arguments
         * are not available.
         */
        class IntentBuilder(private val context: Context, private val isAssetIdPresent: Boolean) {
            private var isViewAsHome: Boolean = false
            private var isNewTask: Boolean = false
            private var wallpaperInfo: WallpaperInfo? = null
            private var fromOriginalIntent: Intent? = null
            private var shouldCategoryRefresh: Boolean = false
            private var hideInfoSheet: Boolean = false
            private var shouldNavigateToExtendedWallpaperEffects: Boolean = false
            private var wallpaperLaunchSource: String? = null
            private var setWallpaperEntryPoint: Int =
                StyleEnums.SET_WALLPAPER_ENTRY_POINT_WALLPAPER_PREVIEW

            fun viewAsHome(isViewAsHome: Boolean) = apply { this.isViewAsHome = isViewAsHome }

            fun newTask(isNewTask: Boolean) = apply { this.isNewTask = isNewTask }

            fun wallpaperInfo(wallpaperInfo: WallpaperInfo) = apply {
                this.wallpaperInfo = wallpaperInfo
            }

            fun fromOriginalIntent(originalIntent: Intent) = apply {
                this.fromOriginalIntent = originalIntent
            }

            fun refreshCategory(shouldRefresh: Boolean) = apply {
                this.shouldCategoryRefresh = shouldRefresh
            }

            fun hideInfoSheet(hide: Boolean) = apply { this.hideInfoSheet = hide }

            fun navigateToExtendedEffects(navigate: Boolean) = apply {
                this.shouldNavigateToExtendedWallpaperEffects = navigate
            }

            fun entryPoint(@UserEventLogger.SetWallpaperEntryPoint entryPoint: Int) = apply {
                this.setWallpaperEntryPoint = entryPoint
            }

            fun wallpaperLaunchSource(source: String?) = apply {
                this.wallpaperLaunchSource = source
            }

            /** Constructs the final [Intent] with the specified configuration. */
            fun build(): Intent {
                // Create ImageWallpaperInfo if the intent is from a content URI,
                // preserving the logic from the original overloaded method.
                // TODO(b/291761856): Use wallpaper model to replace wallpaper info.
                val wallpaperInfo =
                    wallpaperInfo ?: fromOriginalIntent?.data?.let { ImageWallpaperInfo(it) }

                return newIntent(
                    context = context,
                    isAssetIdPresent = isAssetIdPresent,
                    isViewAsHome = isViewAsHome,
                    isNewTask = isNewTask,
                    wallpaperInfo = wallpaperInfo,
                    fromOriginalIntent = fromOriginalIntent,
                    shouldCategoryRefresh = shouldCategoryRefresh,
                    hideInfoSheet = hideInfoSheet,
                    shouldNavigateToExtendedWallpaperEffects =
                        shouldNavigateToExtendedWallpaperEffects,
                    setWallpaperEntryPoint = setWallpaperEntryPoint,
                    wallpaperLaunchSource = wallpaperLaunchSource,
                )
            }
        }

        private fun WallpaperModel.LiveWallpaperModel.getNewCreativeWallpaperArgs() =
            Bundle().apply {
                putParcelable(
                    SmallPreviewFragment.ARG_EDIT_INTENT,
                    liveWallpaperData.getEditActivityIntent(true),
                )
            }
    }
}
