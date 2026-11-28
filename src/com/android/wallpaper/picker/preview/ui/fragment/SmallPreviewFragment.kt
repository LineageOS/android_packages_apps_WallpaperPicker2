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
package com.android.wallpaper.picker.preview.ui.fragment

import android.app.Activity.RESULT_OK
import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.transition.Slide
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isGone
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.transition.Transition
import com.android.wallpaper.R
import com.android.wallpaper.config.BaseFlags
import com.android.wallpaper.model.Screen
import com.android.wallpaper.module.PackageStatusNotifier
import com.android.wallpaper.module.WallpaperPreferences
import com.android.wallpaper.module.logging.UserEventLogger
import com.android.wallpaper.picker.AppbarFragment
import com.android.wallpaper.picker.TrampolinePickerActivity
import com.android.wallpaper.picker.customization.ui.util.EmptyTransitionListener
import com.android.wallpaper.picker.di.modules.MainDispatcher
import com.android.wallpaper.picker.preview.ui.WallpaperPreviewActivity
import com.android.wallpaper.picker.preview.ui.binder.ApplyWallpaperScreenBinder
import com.android.wallpaper.picker.preview.ui.binder.PreviewActionsBinder
import com.android.wallpaper.picker.preview.ui.binder.PreviewPagerBinder2
import com.android.wallpaper.picker.preview.ui.binder.SetWallpaperProgressDialogBinder
import com.android.wallpaper.picker.preview.ui.binder.SmallPreviewScreenBinder
import com.android.wallpaper.picker.preview.ui.util.AnimationUtil
import com.android.wallpaper.picker.preview.ui.util.ImageEffectDialogUtil
import com.android.wallpaper.picker.preview.ui.view.ClickableMotionLayout
import com.android.wallpaper.picker.preview.ui.view.PreviewActionFloatingSheet
import com.android.wallpaper.picker.preview.ui.view.PreviewActionGroup
import com.android.wallpaper.picker.preview.ui.viewmodel.Action
import com.android.wallpaper.picker.preview.ui.viewmodel.SmallPreviewAlphaAnimationBinder
import com.android.wallpaper.picker.preview.ui.viewmodel.WallpaperPreviewViewModel
import com.android.wallpaper.picker.wallpapers.data.repository.CategoryWallpapersRepository
import com.android.wallpaper.util.DisplayUtils
import com.android.wallpaper.util.ExtendedWallpaperEffectsUtils
import com.android.wallpaper.util.LaunchSourceUtils.LAUNCH_SOURCE_SETTINGS_HOMEPAGE
import com.android.wallpaper.util.LaunchSourceUtils.WALLPAPER_LAUNCH_SOURCE
import com.android.wallpaper.util.wallpaperconnection.WallpaperConnectionUtils
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * This fragment displays the preview of the selected wallpaper on all available workspaces and
 * device displays.
 */
@AndroidEntryPoint(AppbarFragment::class)
class SmallPreviewFragment : Hilt_SmallPreviewFragment() {

    @Inject @ApplicationContext lateinit var appContext: Context
    @Inject @MainDispatcher lateinit var mainScope: CoroutineScope
    @Inject lateinit var displayUtils: DisplayUtils
    @Inject lateinit var logger: UserEventLogger
    @Inject lateinit var imageEffectDialogUtil: ImageEffectDialogUtil
    @Inject lateinit var wallpaperConnectionUtils: WallpaperConnectionUtils
    @Inject lateinit var packageStatusNotifier: PackageStatusNotifier
    @Inject lateinit var categoryWallpapersRepository: CategoryWallpapersRepository
    @Inject lateinit var wallpaperPreferences: WallpaperPreferences

    private lateinit var currentView: View
    private lateinit var shareActivityResult: ActivityResultLauncher<Intent>

    private val wallpaperPreviewViewModel by activityViewModels<WallpaperPreviewViewModel>()
    private val isFirstBindingDeferred = CompletableDeferred<Boolean>()

    /**
     * True if the view of this fragment is destroyed from the current or previous lifecycle.
     *
     * Null if it's the first life cycle, and false if the view has not been destroyed.
     *
     * Read-only during the first half of the lifecycle (when starting a fragment).
     */
    private var isViewDestroyed: Boolean? = null
    private var surfacesBinding: PreviewPagerBinder2.Binding? = null

    private var setWallpaperProgressDialog: AlertDialog? = null
    private var launchExtendedEffectWallpaperJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exitTransition = AnimationUtil.getFastFadeOutTransition()
        reenterTransition = AnimationUtil.getFastFadeInTransition()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        postponeEnterTransition()
        val isFoldable = displayUtils.hasMultiInternalDisplays()
        currentView =
            inflater.inflate(
                if (isFoldable) {
                    R.layout.fragment_small_preview_foldable
                } else if (resources.getBoolean(R.bool.is_picker_side_by_side)) {
                    R.layout.fragment_small_preview_handheld_land
                } else {
                    R.layout.fragment_small_preview_handheld
                },
                container,
                /* attachToRoot= */ false,
            )
        val smallPreview = currentView.requireViewById<MotionLayout>(R.id.small_preview_container)
        val previewPager = currentView.requireViewById<ClickableMotionLayout>(R.id.preview_pager)

        var lockPreviewShades: List<View>? = null
        var homePreviewShades: List<View>? = null
        previewPager.let {
            val lockPreviewContainers =
                previewPager.requireViewById<ViewGroup>(R.id.lock_preview).let { previewCard ->
                    if (isFoldable) {
                        listOf<ViewGroup>(
                            previewCard.requireViewById(R.id.small_preview_folded_preview),
                            previewCard.requireViewById(R.id.small_preview_unfolded_preview),
                        )
                    } else {
                        listOf(previewCard.requireViewById(R.id.wallpaper_preview_crop))
                    }
                }
            lockPreviewShades =
                lockPreviewContainers.map { container ->
                    layoutInflater.inflate(R.layout.preview_shade, container, false).also { shade ->
                        container.addView(shade)
                    }
                }

            val homePreviewContainers =
                previewPager.requireViewById<ViewGroup>(R.id.home_preview).let { previewCard ->
                    if (isFoldable) {
                        listOf<ViewGroup>(
                            previewCard.requireViewById(R.id.small_preview_folded_preview),
                            previewCard.requireViewById(R.id.small_preview_unfolded_preview),
                        )
                    } else {
                        listOf(previewCard.requireViewById(R.id.wallpaper_preview_crop))
                    }
                }
            homePreviewShades =
                homePreviewContainers.map { container ->
                    layoutInflater.inflate(R.layout.preview_shade, container, false).also { shade ->
                        container.addView(shade)
                    }
                }

            SmallPreviewAlphaAnimationBinder.bind(
                lockPreviewShades = lockPreviewShades,
                homePreviewShades = homePreviewShades,
                viewModel = wallpaperPreviewViewModel,
                lifecycleOwner = viewLifecycleOwner,
            )
            setUpTransitionListener(it)

            // Sets up focus listeners for the lock preview and home preview to handle accessibility
            // focus events.
            if (BaseFlags.get(it.context).shouldShowDesktopUi(it.context)) {
                val lockPreview = it.requireViewById<View>(R.id.lock_preview)
                val homePreview = it.requireViewById<View>(R.id.home_preview)
                setUpPreviewCardFocusListener(lockPreview, Screen.LOCK_SCREEN)
                setUpPreviewCardFocusListener(homePreview, Screen.HOME_SCREEN)
            }
        }
        setUpToolbar(currentView, /* upArrow= */ true, /* transparentToolbar= */ true)
        bindScreenPreview(
            currentView,
            isFirstBindingDeferred,
            isFoldable,
            lockPreviewShades,
            homePreviewShades,
            onPreviewReady = { previewScreen ->
                wallpaperPreviewViewModel.setPreviewReady(previewScreen, true)
            },
            onStartTransition = { startPostponedEnterTransition() },
            onPreviewSurfaceDestroyed = { previewScreen ->
                wallpaperPreviewViewModel.setPreviewReady(previewScreen, false)
            },
        )
        bindPreviewActions(currentView, smallPreview)

        requireActivity().onBackPressedDispatcher.let {
            it.addCallback(owner = viewLifecycleOwner) {
                isEnabled = wallpaperPreviewViewModel.handleBackPressed()
                if (!isEnabled) {
                    if (
                        arguments?.getBoolean(
                            WallpaperPreviewActivity.HIDE_SURFACES_FOR_EXIT_TRANSITION
                        ) == true
                    ) {
                        surfacesBinding?.hideSurfaces()
                    }
                    it.onBackPressed()
                }
            }
        }
        /**
         * We need to keep the reference shortly, because the activity will be forced to restart due
         * to the theme color update from the system wallpaper change. The activityReference is used
         * to kill [WallpaperPreviewActivity].
         */
        val activityReference = activity
        checkNotNull(previewPager)
        ApplyWallpaperScreenBinder.bind(
            previewPager = previewPager,
            viewModel = wallpaperPreviewViewModel,
            lifecycleOwner = viewLifecycleOwner,
            mainScope = mainScope,
            isFoldable = isFoldable,
            wallpaperConnectionUtils = wallpaperConnectionUtils,
        ) {
            Toast.makeText(context, R.string.wallpaper_set_successfully_message, Toast.LENGTH_SHORT)
                .show()
            // should invalidate live wallpaper cache for any wallpaper that is set
            wallpaperPreviewViewModel.wallpaper?.value?.let {
                categoryWallpapersRepository.invalidateCache(it.commonWallpaperData.id.collectionId)
            }

            if (activityReference != null) {
                if (wallpaperPreviewViewModel.isNewTask) {
                    activityReference.window?.exitTransition = Slide(Gravity.END)
                    val launchSource =
                        activityReference.intent.getStringExtra(WALLPAPER_LAUNCH_SOURCE)
                            ?: LAUNCH_SOURCE_SETTINGS_HOMEPAGE
                    val intent = Intent(activityReference, TrampolinePickerActivity::class.java)
                    intent.setFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                    intent.putExtra(WALLPAPER_LAUNCH_SOURCE, launchSource)
                    activityReference.startActivity(
                        intent,
                        ActivityOptions.makeSceneTransitionAnimation(activityReference).toBundle(),
                    )
                } else {
                    activityReference.setResult(RESULT_OK)
                }
                activityReference.finish()
            }
        }

        val dialogView = inflater.inflate(R.layout.set_wallpaper_progress_dialog_view, null)
        setWallpaperProgressDialog =
            AlertDialog.Builder(requireActivity()).setView(dialogView).create()
        SetWallpaperProgressDialogBinder.bind(
            viewModel = wallpaperPreviewViewModel,
            lifecycleOwner = viewLifecycleOwner,
        ) { visible ->
            setWallpaperProgressDialog?.let { if (visible) it.show() else it.dismiss() }
        }

        currentView.doOnPreDraw {
            // FullPreviewConfigViewModel not being null indicates that we are navigated to small
            // preview from the full preview, and therefore should play the shared element re-enter
            // animation. Reset it after views are finished binding.
            wallpaperPreviewViewModel.resetFullPreviewConfigViewModel()
        }

        shareActivityResult =
            registerForActivityResult(
                object : ActivityResultContract<Intent, Int>() {
                    override fun createIntent(context: Context, input: Intent): Intent {
                        return input
                    }

                    override fun parseResult(resultCode: Int, intent: Intent?): Int {
                        return resultCode
                    }
                }
            ) {
                currentView
                    .findViewById<PreviewActionGroup>(R.id.action_button_group)
                    ?.setIsChecked(Action.SHARE, false)
            }

        if (wallpaperPreviewViewModel.launchedForWallpaperEffects) {
            wallpaperPreviewViewModel.wallpaper.value?.let { wallpaperModel ->
                ExtendedWallpaperEffectsUtils.registerExtendedWallpaperEffectsActivityLauncher(
                        activity = requireActivity(),
                        lifecycleOwner = viewLifecycleOwner,
                        wallpaperPreviewViewModel = wallpaperPreviewViewModel,
                        context = context,
                        exitActivityOnCancel = wallpaperPreviewViewModel.launchedForWallpaperEffects,
                    )
                    .let { launcher ->
                        arguments?.clear()
                        launchExtendedEffectWallpaperJob =
                            mainScope.launch {
                                context?.let { unwrappedContext ->
                                    ExtendedWallpaperEffectsUtils.startExtendedWallpaperEffects(
                                        wallpaperModel,
                                        launcher,
                                        unwrappedContext,
                                        wallpaperConnectionUtils,
                                    )
                                }
                            }
                    }
            }
        }

        return currentView
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        isFirstBindingDeferred.complete(savedInstanceState == null)
    }

    override fun onStop() {
        super.onStop()
        // onStop won't destroy view
        isViewDestroyed = false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        launchExtendedEffectWallpaperJob?.cancel()
        setWallpaperProgressDialog?.dismiss()
        isViewDestroyed = true
    }

    override fun getDefaultTitle(): CharSequence {
        return getString(R.string.preview)
    }

    override fun getToolbarTextColor(): Int {
        return ContextCompat.getColor(requireContext(), R.color.system_on_surface)
    }

    /**
     * Sets up a focus listener for the preview card to handle accessibility focus events. When the
     * card receives focus, it selects the corresponding preview screen.
     */
    private fun setUpPreviewCardFocusListener(view: View, screen: Screen) {
        val previewCard = view.requireViewById<View>(R.id.preview)
        previewCard.isFocusable = true
        previewCard.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

        previewCard.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                wallpaperPreviewViewModel.setSmallPreviewSelectedTab(screen)
            }
        }

        ViewCompat.setAccessibilityDelegate(
            previewCard,
            object : AccessibilityDelegateCompat() {
                override fun onPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) {
                    super.onPopulateAccessibilityEvent(host, event)
                    if (event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
                        wallpaperPreviewViewModel.setSmallPreviewSelectedTab(screen)
                    }
                }
            },
        )
    }

    private fun setUpTransitionListener(previewPager: MotionLayout) {
        previewPager.addTransitionListener(
            object : EmptyTransitionListener {
                override fun onTransitionCompleted(motionLayout: MotionLayout?, currentId: Int) {
                    if (
                        currentId == R.id.lock_preview_selected ||
                            currentId == R.id.home_preview_selected
                    ) {
                        // When user swipes to lock or home screen, we need to update the state of
                        // the selected tab in the view model
                        wallpaperPreviewViewModel.setSmallPreviewSelectedTab(
                            if (currentId == R.id.lock_preview_selected) Screen.LOCK_SCREEN
                            else Screen.HOME_SCREEN
                        )
                    } else if (currentId == R.id.apply_wallpaper_preview_only) {
                        // When transition to state of apply wallpaper preview only, it should
                        // always proceed to transition to the apply wallpaper all state to also
                        // fade in the action buttons at the bottom.
                        previewPager.transitionToState(R.id.apply_wallpaper_all)
                    } else if (
                        currentId == R.id.apply_wallpaper_lock_preview_selected ||
                            currentId == R.id.apply_wallpaper_home_preview_selected
                    ) {
                        wallpaperPreviewViewModel.setApplyWallpaperPreviewSelectedTab(
                            if (currentId == R.id.apply_wallpaper_lock_preview_selected)
                                Screen.LOCK_SCREEN
                            else Screen.HOME_SCREEN
                        )
                    }
                }
            }
        )
    }

    private fun bindScreenPreview(
        view: View,
        isFirstBindingDeferred: CompletableDeferred<Boolean>,
        isFoldable: Boolean,
        lockPreviewShades: List<View>?,
        homePreviewShades: List<View>?,
        onPreviewReady: ((Screen) -> Unit)? = null,
        onStartTransition: (() -> Unit)? = null,
        onPreviewSurfaceDestroyed: ((Screen) -> Unit)? = null,
    ) {
        surfacesBinding =
            SmallPreviewScreenBinder.bind(
                    applicationContext = appContext,
                    mainScope = mainScope,
                    lifecycleOwner = viewLifecycleOwner,
                    fragmentLayout = view as MotionLayout,
                    viewModel = wallpaperPreviewViewModel,
                    previewDisplaySize =
                        displayUtils.getRealSize(displayUtils.getWallpaperDisplay()),
                    transition = (reenterTransition as Transition?),
                    transitionConfig = wallpaperPreviewViewModel.fullPreviewConfigViewModel.value,
                    wallpaperConnectionUtils = wallpaperConnectionUtils,
                    isFirstBindingDeferred = isFirstBindingDeferred,
                    isFoldable = isFoldable,
                    onPreviewReady = onPreviewReady,
                    onStartTransition = onStartTransition,
                    onPreviewSurfaceDestroyed = onPreviewSurfaceDestroyed,
                ) { sharedElement ->
                    lockPreviewShades?.forEach { it.isGone = true }
                    homePreviewShades?.forEach { it.isGone = true }
                    val extras =
                        FragmentNavigatorExtras(sharedElement to FULL_PREVIEW_SHARED_ELEMENT_ID)
                    // Set to false on small-to-full preview transition to remove surfaceView
                    // jank.
                    (view as ViewGroup).isTransitionGroup = false
                    findNavController().let {
                        if (it.currentDestination?.id == R.id.smallPreviewFragment) {
                            wallpaperPreviewViewModel.onTransitionToFullPreview()
                            it.navigate(
                                resId = R.id.action_smallPreviewFragment_to_fullPreviewFragment,
                                args = null,
                                navOptions = null,
                                navigatorExtras = extras,
                            )
                        }
                    }
                }
                .also {
                    if (
                        arguments?.getBoolean(
                            WallpaperPreviewActivity.HIDE_SURFACES_FOR_ENTER_TRANSITION
                        ) == true
                    ) {
                        // Activity enter transition only plays once. Remove enter transition
                        // argument so it is not saved across configuration change.
                        arguments?.remove(
                            WallpaperPreviewActivity.HIDE_SURFACES_FOR_ENTER_TRANSITION
                        )
                        it.hideSurfaces()
                    }
                }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Always reset isTransitionGroup value on start for the edge case that the
                // navigation is cancelled and the fragment resumes.
                (view as ViewGroup).isTransitionGroup = true
            }
        }
    }

    private fun bindPreviewActions(view: View, smallPreview: MotionLayout) {
        val actionButtonGroup = view.findViewById<PreviewActionGroup>(R.id.action_button_group)
        val floatingSheet = view.findViewById<PreviewActionFloatingSheet>(R.id.floating_sheet)
        if (actionButtonGroup == null || floatingSheet == null) {
            return
        }

        PreviewActionsBinder.bind(
            actionGroup = actionButtonGroup,
            floatingSheet = floatingSheet,
            smallPreview = smallPreview,
            previewViewModel = wallpaperPreviewViewModel,
            actionsViewModel = wallpaperPreviewViewModel.previewActionsViewModel,
            deviceDisplayType = displayUtils.getCurrentDisplayType(requireActivity()),
            activity = requireActivity(),
            lifecycleOwner = viewLifecycleOwner,
            logger = logger,
            imageEffectDialogUtil = imageEffectDialogUtil,
            packageStatusNotifier = packageStatusNotifier,
            categoryWallpapersRepository = categoryWallpapersRepository,
            wallpaperPreferences = wallpaperPreferences,
            onNavigateToEditScreen = { navigateToEditScreen(it) },
            onStartShareActivity = { shareActivityResult.launch(it) },
        )
    }

    private fun navigateToEditScreen(intent: Intent) {
        findNavController()
            .navigate(
                resId = R.id.action_smallPreviewFragment_to_creativeEditPreviewFragment,
                args = Bundle().apply { putParcelable(ARG_EDIT_INTENT, intent) },
                navOptions = null,
                navigatorExtras = null,
            )
    }

    fun onEnterAnimationComplete() {
        surfacesBinding?.showSurfaces()
    }

    companion object {
        const val SMALL_PREVIEW_HOME_SHARED_ELEMENT_ID = "small_preview_home"
        const val SMALL_PREVIEW_LOCK_SHARED_ELEMENT_ID = "small_preview_lock"
        const val SMALL_PREVIEW_HOME_FOLDED_SHARED_ELEMENT_ID = "small_preview_home_folded"
        const val SMALL_PREVIEW_HOME_UNFOLDED_SHARED_ELEMENT_ID = "small_preview_home_unfolded"
        const val SMALL_PREVIEW_LOCK_FOLDED_SHARED_ELEMENT_ID = "small_preview_lock_folded"
        const val SMALL_PREVIEW_LOCK_UNFOLDED_SHARED_ELEMENT_ID = "small_preview_lock_unfolded"
        const val FULL_PREVIEW_SHARED_ELEMENT_ID = "full_preview"
        const val ARG_EDIT_INTENT = "arg_edit_intent"
        const val PREVIEW_RESULT_REGISTRY = "preview_result_registry"
        const val SHOULD_NAVIGATE_TO_EXTENDED_WALLPAPER_EFFECTS =
            "should_navigate_to_extended_wallpaper_effects"
        const val TAG = "SmallPreviewFragment"
    }
}
