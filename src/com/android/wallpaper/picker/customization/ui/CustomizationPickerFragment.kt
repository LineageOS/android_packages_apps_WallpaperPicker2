/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.wallpaper.picker.customization.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.stats.style.StyleEnums
import android.view.LayoutInflater
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.ViewStub
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toolbar
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.graphics.Insets
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.transition.Transition
import com.android.customization.picker.clock.ui.view.ClockViewFactory
import com.android.customization.picker.icon.ui.util.IconStyleViewUtil
import com.android.systemui.customization.clocks.utils.ViewUtils.animateToAlpha
import com.android.wallpaper.R
import com.android.wallpaper.config.BaseFlags
import com.android.wallpaper.model.ImageWallpaperInfo
import com.android.wallpaper.model.Screen
import com.android.wallpaper.model.Screen.HOME_SCREEN
import com.android.wallpaper.model.Screen.LOCK_SCREEN
import com.android.wallpaper.module.LargeScreenMultiPanesChecker
import com.android.wallpaper.module.MultiPanesChecker
import com.android.wallpaper.module.logging.UserEventLogger
import com.android.wallpaper.picker.AppbarFragment
import com.android.wallpaper.picker.MyPhotosStarter
import com.android.wallpaper.picker.category.ui.view.CategoriesFragment
import com.android.wallpaper.picker.category.ui.view.MyPhotosStarterImpl
import com.android.wallpaper.picker.category.ui.view.PhotoPickerFragment
import com.android.wallpaper.picker.category.ui.view.providers.IndividualPickerFactory
import com.android.wallpaper.picker.common.preview.data.repository.PersistentWallpaperModelRepository
import com.android.wallpaper.picker.common.preview.ui.binder.BasePreviewBinder
import com.android.wallpaper.picker.common.preview.ui.binder.PreviewAlphaAnimationBinder
import com.android.wallpaper.picker.common.preview.ui.binder.WorkspaceBinder
import com.android.wallpaper.picker.customization.ui.CustomizationPickerActivity.ActivityEnterAnimationCallback
import com.android.wallpaper.picker.customization.ui.binder.ColorUpdateBinder
import com.android.wallpaper.picker.customization.ui.binder.CustomizationOptionsBinder
import com.android.wallpaper.picker.customization.ui.binder.CustomizationPickerBinder2
import com.android.wallpaper.picker.customization.ui.binder.DarkModeUpdateBinder
import com.android.wallpaper.picker.customization.ui.binder.PackThemeSuggestedEntryBinder
import com.android.wallpaper.picker.customization.ui.binder.PreviewPagerBinder
import com.android.wallpaper.picker.customization.ui.binder.ToolbarBinder
import com.android.wallpaper.picker.customization.ui.util.CustomizationOptionUtil
import com.android.wallpaper.picker.customization.ui.util.CustomizationOptionUtil.CustomizationOption
import com.android.wallpaper.picker.customization.ui.util.CustomizationOptionViewUtil
import com.android.wallpaper.picker.customization.ui.util.EmptyTransitionListener
import com.android.wallpaper.picker.customization.ui.view.PackThemeSuggestedChip
import com.android.wallpaper.picker.customization.ui.view.PreviewPagerViews
import com.android.wallpaper.picker.customization.ui.view.WallpaperPickerEntry
import com.android.wallpaper.picker.customization.ui.viewmodel.ColorUpdateViewModel
import com.android.wallpaper.picker.customization.ui.viewmodel.CustomizationOptionsData
import com.android.wallpaper.picker.customization.ui.viewmodel.CustomizationPickerViewModel2
import com.android.wallpaper.picker.customization.ui.viewmodel.CustomizationPickerViewModel2.Companion.KEY_DESTINATION
import com.android.wallpaper.picker.data.WallpaperModel
import com.android.wallpaper.picker.di.modules.MainDispatcher
import com.android.wallpaper.picker.preview.ui.WallpaperPreviewActivity
import com.android.wallpaper.picker.preview.ui.view.ClickableMotionLayout
import com.android.wallpaper.picker.wallpapers.data.repository.CategoryWallpapersRepository
import com.android.wallpaper.picker.wallpapers.ui.view.CategoryWallpapersFragment
import com.android.wallpaper.util.ActivityUtils
import com.android.wallpaper.util.CuratedPhotosTimeUtil
import com.android.wallpaper.util.DisplayUtils
import com.android.wallpaper.util.LaunchSourceUtils.WALLPAPER_LAUNCH_SOURCE
import com.android.wallpaper.util.WallpaperConnection
import com.android.wallpaper.util.converter.WallpaperModelFactory
import com.android.wallpaper.util.wallpaperconnection.WallpaperConnectionUtils
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint(AppbarFragment::class)
class CustomizationPickerFragment :
    Hilt_CustomizationPickerFragment(), ActivityEnterAnimationCallback {

    @Inject lateinit var customizationOptionUtil: CustomizationOptionUtil
    @Inject lateinit var customizationOptionViewUtil: CustomizationOptionViewUtil
    @Inject lateinit var customizationOptionsBinder: CustomizationOptionsBinder
    @Inject lateinit var packThemeSuggestedEntryBinder: PackThemeSuggestedEntryBinder
    @Inject lateinit var toolbarBinder: ToolbarBinder
    @Inject lateinit var colorUpdateViewModel: ColorUpdateViewModel
    @Inject lateinit var clockViewFactory: ClockViewFactory
    @Inject lateinit var workspaceBinder: WorkspaceBinder
    @Inject lateinit var displayUtils: DisplayUtils
    @Inject lateinit var wallpaperConnectionUtils: WallpaperConnectionUtils
    @Inject lateinit var persistentWallpaperModelRepository: PersistentWallpaperModelRepository
    @Inject @MainDispatcher lateinit var mainScope: CoroutineScope
    @Inject lateinit var multiPanesChecker: MultiPanesChecker
    @Inject lateinit var individualPickerFactory: IndividualPickerFactory
    @Inject lateinit var userEventLogger: UserEventLogger
    @Inject lateinit var curatedPhotosTimeUtil: CuratedPhotosTimeUtil
    @Inject lateinit var wallpaperModelFactory: WallpaperModelFactory
    @Inject lateinit var myPhotosStarterImpl: MyPhotosStarterImpl
    @Inject lateinit var iconStyleViewUtil: IconStyleViewUtil
    @Inject lateinit var categoryWallpapersRepository: CategoryWallpapersRepository

    private val customizationPickerViewModel: CustomizationPickerViewModel2 by viewModels()

    private val isOnMainScreen = {
        customizationPickerViewModel.customizationOptionsViewModel.selectedOption.value == null
    }

    private var fullyCollapsed = false

    private var onBackPressedCallback: OnBackPressedCallback? = null
    private lateinit var extendedWallpaperEffectsLauncher: ActivityResultLauncher<Intent>

    private val startForResult =
        this.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    private val isDesktopUi by lazy {
        context?.applicationContext?.let { appContext ->
            BaseFlags.get(appContext).shouldShowDesktopUi(appContext)
        } ?: false
    }

    // This boolean is to determine that when onCreateView, if it is a fragment reenter after the
    // last fragment exit.
    private var isFragmentReenterAfterExit = false
    // This boolean indicates if fragment reenter transition has ended after reentering the
    // fragment.
    private var isFragmentReenterEnded = false
    // This boolean indicates if the motion container's dimensions are calculated.
    private var isMotionContainerInitialized = false
    // This boolean indicates if it is an initial Activity creation and will turn from true to false
    // after the enter animation completes.
    private var isInitialCreation = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            // Fragment is being restored, not initial creation
            isInitialCreation = false
        }

        if (!BaseFlags.get(requireContext()).isPhotoPickerEnabled()) {
            extendedWallpaperEffectsLauncher =
                registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
                    ->
                    if (
                        result.resultCode != Activity.RESULT_OK ||
                            result.data?.data == null ||
                            context == null
                    ) {
                        return@registerForActivityResult
                    }

                    result.data?.let { data ->
                        context?.let { ctx ->
                            val wallpaperModel = extractWallpaperModelFromResult(data, ctx)
                            persistentWallpaperModelRepository.setWallpaperModel(wallpaperModel)
                            ActivityUtils.startWallpaperPreviewActivity(
                                activity = requireActivity(),
                                isCreativeCategories = false,
                                shouldNavigateToExtendedWallpaperEffects = true,
                                isViewAsHome = true,
                                requestCode =
                                    CustomizationPickerActivity
                                        .VIEW_ONLY_PREVIEW_WALLPAPER_REQUEST_CODE,
                                isMultiPanesEnabled =
                                    multiPanesChecker.isMultiPanesEnabled(requireContext()),
                                setWallpaperEntryPoint =
                                    StyleEnums.SET_WALLPAPER_ENTRY_POINT_WALLPAPER_PREVIEW,
                                wallpaperLaunchSource =
                                    arguments?.getString(WALLPAPER_LAUNCH_SOURCE) ?: "",
                            )
                        }
                    }
                }
        }

        prepareFragmentExitTransitionAnimation()
        prepareFragmentReenterTransitionAnimation()
    }

    private fun extractWallpaperModelFromResult(result: Intent, context: Context): WallpaperModel {
        val imageUri = result.data
        val imageWallpaperInfo = ImageWallpaperInfo(imageUri)
        return wallpaperModelFactory.getWallpaperModel(context, imageWallpaperInfo)
    }

    override fun onStart() {
        super.onStart()
        if (BaseFlags.get(requireContext()).isPackThemeEnabled()) {
            customizationPickerViewModel.customizationOptionsViewModel.refetchThemeInfo()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        isMotionContainerInitialized = false

        // For desktop UI, use fragment_customization_picker2_desktop layout to make the whole page
        // scrollable, unlike mobile devices where only the bottom area scroll.
        // *** IMPORTANT: The view IDs are shared between fragment_customization_picker2 and
        // fragment_customization_picker2_desktop. Be cautious when modifying these ids as the
        // changes can affect both desktop and mobile UIs.
        val layoutResId =
            if (isDesktopUi) {
                R.layout.fragment_customization_picker2_desktop
            } else {
                R.layout.fragment_customization_picker2
            }
        val view = inflater.inflate(layoutResId, container, false)

        val toolbarContainer: LinearLayout = view.requireViewById(R.id.toolbar_container)
        setupToolbar(toolbarContainer)

        val showSuggestedChip =
            Settings.Secure.getInt(
                view.context.contentResolver,
                Settings.Secure.SUGGESTED_THEME_FEATURE_ENABLED,
                /* def= */ 0,
            ) == 1
        val packThemeSuggestedChip: PackThemeSuggestedChip? =
            if (BaseFlags.get(requireContext()).isPackThemeEnabled() && showSuggestedChip) {
                val stubView: ViewStub = view.requireViewById(R.id.stub_pack_theme_suggested_chip)
                stubView.inflate() as PackThemeSuggestedChip
            } else null
        packThemeSuggestedChip?.visibility = View.INVISIBLE

        val pickerMotionContainer: MotionLayout = view.requireViewById(R.id.picker_motion_layout)
        val bottomScrollView: View = view.requireViewById(R.id.bottom_scroll_view)
        // bottomScrollView should hide until we customizationOptionsData and call
        // updateHeaderHeightConstraints to make sure of the screen dimensions.
        bottomScrollView.alpha = 0f

        // Override bottom scroll view's accessibility delegate to enable the user to expand it
        // once it's been collapsed
        bottomScrollView.accessibilityDelegate?.let { base ->
            bottomScrollView.setAccessibilityDelegate(
                object : ForwardingAccessibilityDelegate(base) {
                    override fun onInitializeAccessibilityNodeInfo(
                        host: View,
                        info: AccessibilityNodeInfo,
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        if (pickerMotionContainer.currentState == R.id.collapsed_header_primary) {
                            info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        }
                    }

                    override fun performAccessibilityAction(
                        host: View,
                        action: Int,
                        args: Bundle?,
                    ): Boolean {
                        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                            pickerMotionContainer.transitionToState(R.id.expanded_header_primary)
                        }
                        return super.performAccessibilityAction(host, action, args)
                    }
                }
            )
        }
        val optionContainer: ConstraintLayout =
            view.requireViewById(R.id.customization_option_container)
        val customizationFloatingSheetContainer: FrameLayout =
            view.requireViewById(R.id.customization_option_floating_sheet_container)
        // Initialize the floating sheet to be hidden and not focusable
        customizationFloatingSheetContainer.visibility = View.INVISIBLE

        // Listen to the window's bottom nav bar height and the top status bar height and update the
        // layout padding accordingly.
        ViewCompat.setOnApplyWindowInsetsListener(pickerMotionContainer) { _, windowInsets ->
            val insets: Insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val isVisible = windowInsets.isVisible(WindowInsetsCompat.Type.navigationBars())
            // The top inset never depends on the nav bar, apply it unconditionally. Gesture
            // navigation with the hint hidden reports a visible nav bar with a bottom inset of 0
            // for as long as it is in use, which would otherwise leave the toolbar unpadded.
            applyStatusBarInset(toolbarContainer = toolbarContainer, statusBarHeight = insets.top)
            if (!(insets.bottom == 0 && isVisible)) {
                // We should do nothing in the case of "bottom inset 0 with nav bar visible".
                // The event usually happens when the system dispatches an initial pass to reset the
                // layout or prepare for the new orientation. This event is usually followed up
                // with another insets update where the bottom inset is no longer 0.
                applyNavBarInsets(
                    optionContainer = optionContainer,
                    customizationFloatingSheetContainer = customizationFloatingSheetContainer,
                    navBarHeight = insets.bottom,
                )

                if (isMotionContainerInitialized && !isDesktopUi) {
                    // Reconfigure motion container constraints if already initialized, to adjust
                    // for new insets (doing it only after it's initialized to avoid jumping if
                    // insets first arrive before the first initialization)
                    view.post {
                        updateHeaderHeightConstraints(
                            pickerMotionContainer = pickerMotionContainer,
                            wallpaperPickerEntry =
                                view.requireViewById(R.id.wallpaper_picker_entry),
                            previewLabelHeight =
                                view.requireViewById<View>(R.id.label_placeholder).height,
                            optionContainerHeight = optionContainer.height,
                            packThemeSuggestedChip = packThemeSuggestedChip,
                            bottomInset = insets.bottom,
                        )
                    }
                }
            }
            WindowInsetsCompat.CONSUMED
        }

        // When nonnull, the first screen shown is the secondary screen of the selected option.
        val initialDestination = activity?.intent?.extras?.getString(KEY_DESTINATION)
        val initialSelectedOption: CustomizationOption? =
            initialDestination?.let {
                customizationOptionUtil.getCustomizationOptionFromDestination(it)
            } ?: customizationPickerViewModel.screen.value.second
        if (initialSelectedOption != null) {
            bottomScrollView.alpha = 0.0f
            pickerMotionContainer.getConstraintSet(R.id.secondary)?.apply {
                setAlpha(R.id.customization_option_floating_sheet_container, 0.0f)
            }
        }

        // Preview pager
        val previewViewModel = customizationPickerViewModel.basePreviewViewModel
        previewViewModel.setWhichPreview(WallpaperConnection.WhichPreview.PREVIEW_CURRENT)
        // TODO (b/348462236): adjust flow so this is always false when previewing current wallpaper
        previewViewModel.setIsWallpaperColorPreviewEnabled(false)
        activity?.let {
            val size = displayUtils.getActiveDisplaySize(it)
            previewViewModel.updateDisplayConfiguration(size)
        }
        val previewPager: ClickableMotionLayout =
            if (isDesktopUi) {
                // Replace the view pager with the one for desktop UI
                val originalPreviewPager: ClickableMotionLayout =
                    view.requireViewById(R.id.preview_pager)
                val previewPagerParent: ViewGroup = originalPreviewPager.parent as ViewGroup
                previewPagerParent.removeView(originalPreviewPager)
                (inflater.inflate(R.layout.preview_pager2_desktop, previewPagerParent, false)
                        as ClickableMotionLayout)
                    .also { previewPagerParent.addView(it) }
            } else {
                view.requireViewById(R.id.preview_pager)
            }
        // Initially set the preview pager invisible. We will only show the preview pager in the
        // following conditions:
        // 1 customizationOptionsData is ready and emits data.
        // 2a When it is a Fragment reenter, after Fragment reenter transition ends.
        // 2b When it is an Activity fresh start, after the enter animation ends.
        setPreviewPagerVisible(previewPager = previewPager, isVisible = false)
        val previewPagerViews: PreviewPagerViews =
            initPreviewPager(rootView = view, previewPager = previewPager)
        bindPreviewPager(
            rootView = view,
            previewPagerViews = previewPagerViews,
            isFirstBinding = savedInstanceState == null,
        )

        // Inflate the views of customization options only when options data is ready.
        viewLifecycleOwner.lifecycleScope.launch {
            // Wait and collect only the first emission. Note that customizationOptionsData is
            // expected to stay the same across the Fragment lifecycle.
            val customizationOptionsData =
                customizationPickerViewModel.customizationOptionsViewModel.customizationOptionsData
                    .first()
            val lockScreenCustomizationOptionEntries: List<Pair<CustomizationOption, View>> =
                initCustomizationOptionEntries(
                    customizationOptionsData = customizationOptionsData,
                    view = view,
                    screen = LOCK_SCREEN,
                )
            val homeScreenCustomizationOptionEntries: List<Pair<CustomizationOption, View>> =
                initCustomizationOptionEntries(
                    customizationOptionsData = customizationOptionsData,
                    view = view,
                    screen = HOME_SCREEN,
                )
            val customizationOptionFloatingSheetViewMap: Map<CustomizationOption, View> =
                customizationOptionViewUtil.initFloatingSheet(
                    customizationOptionsData,
                    customizationFloatingSheetContainer,
                    layoutInflater,
                )

            view.post {
                if (this@CustomizationPickerFragment.view == null) return@post
                // Post to wait for the essential view dimensions to be obtained, to further
                // calculate the motion scene dimensions.
                if (!isDesktopUi) {
                    updateHeaderHeightConstraints(
                        pickerMotionContainer = pickerMotionContainer,
                        wallpaperPickerEntry = view.requireViewById(R.id.wallpaper_picker_entry),
                        previewLabelHeight =
                            view.requireViewById<View>(R.id.label_placeholder).height,
                        optionContainerHeight = optionContainer.height,
                        packThemeSuggestedChip = packThemeSuggestedChip,
                        bottomInset = optionContainer.paddingBottom,
                    )
                }
                if (initialSelectedOption != null) {
                    // If initially on secondary screen, initiate the secondary screen and bind the
                    // picker content after.
                    initSecondaryScreen(
                        customizationOptionFloatingSheetViewMap,
                        initialSelectedOption,
                        customizationFloatingSheetContainer,
                        pickerMotionContainer,
                        onTransitionComplete = {
                            // Set back the transition listener to the regular
                            setMotionLayoutOnTransitionCompleteListener(
                                pickerMotionContainer = pickerMotionContainer,
                                wallpaperPickerEntry =
                                    view.requireViewById(R.id.wallpaper_picker_entry),
                                packThemeSuggestedChip = packThemeSuggestedChip,
                            )
                            // Set back the transition alpha constraints to the regular
                            pickerMotionContainer
                                .getConstraintSet(R.id.expanded_header_primary)
                                ?.apply {
                                    setAlpha(
                                        R.id.customization_option_floating_sheet_container,
                                        0.0f,
                                    )
                                    setAlpha(R.id.bottom_scroll_view, 1.0f)
                                }
                            pickerMotionContainer
                                .getConstraintSet(R.id.collapsed_header_primary)
                                ?.apply {
                                    setAlpha(
                                        R.id.customization_option_floating_sheet_container,
                                        0.0f,
                                    )
                                    setAlpha(R.id.bottom_scroll_view, 1.0f)
                                }
                            pickerMotionContainer.getConstraintSet(R.id.secondary)?.apply {
                                setAlpha(R.id.customization_option_floating_sheet_container, 1.0f)
                            }

                            customizationFloatingSheetContainer.alpha = 1.0f
                            bindCustomizationPicker(
                                customizationOptionsData,
                                pickerMotionContainer,
                                lockScreenCustomizationOptionEntries,
                                homeScreenCustomizationOptionEntries,
                                customizationOptionFloatingSheetViewMap,
                                customizationFloatingSheetContainer,
                                packThemeSuggestedChip,
                            )
                        },
                    )
                } else {
                    setMotionLayoutOnTransitionCompleteListener(
                        pickerMotionContainer = pickerMotionContainer,
                        wallpaperPickerEntry = view.requireViewById(R.id.wallpaper_picker_entry),
                        packThemeSuggestedChip = packThemeSuggestedChip,
                    )
                    bindCustomizationPicker(
                        customizationOptionsData,
                        pickerMotionContainer,
                        lockScreenCustomizationOptionEntries,
                        homeScreenCustomizationOptionEntries,
                        customizationOptionFloatingSheetViewMap,
                        customizationFloatingSheetContainer,
                        packThemeSuggestedChip,
                    )
                }

                // Animate to show the bottomScrollView after we obtain customizationOptionsData and
                // call updateHeaderHeightConstraints
                // If initialSelectedOption is nonnull, it means that we will deep dive to show only
                // the secondary screen. We should avoid showing the primary screen content.
                if (initialSelectedOption == null) {
                    bottomScrollView.animateToAlpha(1f)
                    pickerMotionContainer.getConstraintSet(R.id.collapsed_header_primary)?.apply {
                        setAlpha(R.id.bottom_scroll_view, 1.0f)
                    }
                    pickerMotionContainer.getConstraintSet(R.id.expanded_header_primary)?.apply {
                        setAlpha(R.id.bottom_scroll_view, 1.0f)
                    }
                }

                // Only show the preview pager after we obtain customizationOptionsData and call
                // updateHeaderHeightConstraints
                // In the case when it is a fragment reenter and fragment reenter has not ended,
                // do not show the pager until onTransitionEnd is called.
                // In the case of the initial start of the Activity and the enter animation has not
                // completed, do not show the preview pager until
                // onEnterAnimationCompleteAfterActivityCreated is called.
                if ((!isFragmentReenterAfterExit || isFragmentReenterEnded) && !isInitialCreation) {
                    showPreviewPagerAndBindAlphaAnimation(previewPager)
                }

                isMotionContainerInitialized = true
            }
        }

        customizationOptionsBinder.bindDiscardChangesDialog(
            customizationOptionsViewModel =
                customizationPickerViewModel.customizationOptionsViewModel,
            lifecycleOwner = viewLifecycleOwner,
            activity = requireActivity(),
        )

        activity?.onBackPressedDispatcher?.let {
            it.addCallback {
                    isEnabled =
                        customizationPickerViewModel.customizationOptionsViewModel
                            .handleBackPressed()
                    if (!isEnabled) it.onBackPressed()
                }
                .also { callback -> onBackPressedCallback = callback }
        }

        (view as ViewGroup).isTransitionGroup = true
        return view
    }

    private fun applyStatusBarInset(toolbarContainer: LinearLayout, statusBarHeight: Int) {
        (toolbarContainer.layoutParams as MarginLayoutParams).setMargins(0, statusBarHeight, 0, 0)
    }

    private fun applyNavBarInsets(
        optionContainer: ConstraintLayout,
        customizationFloatingSheetContainer: FrameLayout,
        navBarHeight: Int,
    ) {
        val horizontalPadding =
            resources.getDimensionPixelSize(
                R.dimen.customization_option_container_horizontal_padding
            )
        optionContainer.setPaddingRelative(horizontalPadding, 0, horizontalPadding, navBarHeight)

        customizationFloatingSheetContainer.setPaddingRelative(0, 0, 0, navBarHeight)
    }

    /**
     * Update the expanded and collapsed height constraint of the header that hosts the home and
     * lock screen preview and label. The header expands and collapses with a scroll gesture.
     */
    private fun updateHeaderHeightConstraints(
        pickerMotionContainer: MotionLayout,
        wallpaperPickerEntry: WallpaperPickerEntry,
        previewLabelHeight: Int,
        optionContainerHeight: Int,
        packThemeSuggestedChip: PackThemeSuggestedChip?,
        bottomInset: Int,
    ) {
        val isLargeScreenSingleDisplayPortrait = displayUtils.isLargeScreenSingleDisplayPortrait()
        val wallpaperPickerEntryExpandedHeight = wallpaperPickerEntry.expandedHeight
        // Do not collapse the wallpaper entry when isLargeScreenSingleDisplayPortrait
        val wallpaperPickerEntryCollapsedHeight =
            if (isLargeScreenSingleDisplayPortrait) wallpaperPickerEntryExpandedHeight
            else wallpaperPickerEntry.collapsedHeight

        val minCollapsedPreviewHeight =
            resources.getDimensionPixelSize(
                R.dimen.customization_picker_min_preview_collapsed_height
            )

        val minCollapsedPagerHeight = minCollapsedPreviewHeight + previewLabelHeight
        val minExpandedPreviewHeight =
            resources.getDimensionPixelSize(
                R.dimen.customization_picker_min_preview_expanded_height
            )
        val maxExpandedPreviewHeight =
            resources.getDimensionPixelSize(
                R.dimen.customization_picker_max_preview_expanded_height
            )
        val minExpandedPagerHeight = minExpandedPreviewHeight + previewLabelHeight
        val maxExpandedPagerHeight = maxExpandedPreviewHeight + previewLabelHeight

        val isHeaderCollapsed = pickerMotionContainer.currentState == R.id.collapsed_header_primary
        val wallpaperEntryCollapseDistant =
            wallpaperPickerEntryExpandedHeight - wallpaperPickerEntryCollapsedHeight
        // For collapsed, it needs to show the all option entries, with the collapsed wallpaper
        // entry, which shows as a single button.
        val collapsedHeaderHeight =
            (pickerMotionContainer.height -
                    (optionContainerHeight -
                        (packThemeSuggestedChip?.height ?: 0) -
                        (if (isHeaderCollapsed) 0 else wallpaperEntryCollapseDistant)))
                .coerceAtLeast(minCollapsedPagerHeight)
        pickerMotionContainer
            .getConstraintSet(R.id.collapsed_header_primary)
            ?.constrainHeight(R.id.preview_header, collapsedHeaderHeight)
        // For expanded, it needs to show at least half of the entry view below the wallpaper entry.
        val expandedHeaderHeight =
            (pickerMotionContainer.height -
                    wallpaperPickerEntryExpandedHeight -
                    bottomInset -
                    resources.getDimensionPixelSize(R.dimen.customization_option_entry_height) / 2)
                .coerceAtMost(maxExpandedPagerHeight)
                .coerceAtLeast(minExpandedPagerHeight)
        pickerMotionContainer
            .getConstraintSet(R.id.expanded_header_primary)
            ?.constrainHeight(R.id.preview_header, expandedHeaderHeight)
    }

    private fun setMotionLayoutOnTransitionCompleteListener(
        pickerMotionContainer: MotionLayout,
        wallpaperPickerEntry: WallpaperPickerEntry,
        packThemeSuggestedChip: PackThemeSuggestedChip?,
    ) {
        val isLargeScreenSingleDisplayPortrait = displayUtils.isLargeScreenSingleDisplayPortrait()
        // Transition listener handle 2 things
        // 1. Expand and collapse the wallpaper entry
        // 2. Reset the transition and preview when transition back to the primary
        // screen
        pickerMotionContainer.setTransitionListener(
            object : EmptyTransitionListener {

                override fun onTransitionCompleted(motionLayout: MotionLayout?, currentId: Int) {
                    if (currentId == R.id.expanded_header_primary) {
                        // Do not collapse or expand the wallpaper entry when
                        // isLargeScreenSingleDisplayPortrait is true
                        if (!isLargeScreenSingleDisplayPortrait) {
                            wallpaperPickerEntry.expand()
                            packThemeSuggestedChip?.animateToExpanded()
                        }
                    } else if (currentId == R.id.collapsed_header_primary) {
                        // Do not collapse or expand the wallpaper entry when
                        // isLargeScreenSingleDisplayPortrait is true
                        if (!isLargeScreenSingleDisplayPortrait) {
                            wallpaperPickerEntry.collapse()
                            packThemeSuggestedChip?.animateToCollapsed({})
                        }
                    }

                    if (
                        currentId == R.id.expanded_header_primary ||
                            currentId == R.id.collapsed_header_primary
                    ) {
                        // This is when we complete the transition back to the primary screen. Post
                        // to let this transition fully complete first
                        pickerMotionContainer.post {
                            pickerMotionContainer.setTransition(R.id.transition_primary)
                        }
                        // Reset the preview only after the transition is completed, because the
                        // reset will trigger the animation of the UI components in the floating
                        // sheet content, which can possibly be interrupted by the floating sheet
                        // translating down.
                        customizationPickerViewModel.customizationOptionsViewModel.resetPreview()
                        // Hide and block focus on the floating sheet when on primary screen
                        val floatingSheetContainer: FrameLayout =
                            view?.findViewById(R.id.customization_option_floating_sheet_container)
                                ?: return
                        floatingSheetContainer.visibility = View.INVISIBLE
                    } else if (currentId == R.id.secondary) {
                        customizationPickerViewModel.customizationOptionsViewModel
                            .onTransitionToSecondaryScreenComplete()
                        // Show and allow focus on the floating sheet when on secondary screen
                        val floatingSheetContainer: FrameLayout =
                            view?.findViewById(R.id.customization_option_floating_sheet_container)
                                ?: return
                        floatingSheetContainer.visibility = View.VISIBLE
                    }
                }
            }
        )
    }

    /** Only called when the first screen shown on the picker is a secondary screen. */
    private fun initSecondaryScreen(
        customizationOptionFloatingSheetViewMap: Map<CustomizationOption, View>,
        initSelectedOption: CustomizationOption,
        customizationFloatingSheetContainer: FrameLayout,
        pickerMotionContainer: MotionLayout,
        onTransitionComplete: () -> Unit,
    ) {
        // If initially on secondary screen, set the correspondent floating layout
        // content, calculate motion layout dimensions and bind the content after.
        customizationOptionFloatingSheetViewMap[initSelectedOption]?.let { floatingSheetView ->
            setCustomizationOptionFloatingSheet(
                floatingSheetViewContent = floatingSheetView,
                floatingSheetContainer = customizationFloatingSheetContainer,
                motionContainer = pickerMotionContainer,
                onSetComplete = {
                    pickerMotionContainer.setTransitionListener(
                        object : EmptyTransitionListener {
                            override fun onTransitionCompleted(
                                motionLayout: MotionLayout?,
                                currentId: Int,
                            ) {
                                if (currentId == R.id.secondary) {
                                    onTransitionComplete.invoke()
                                }
                            }
                        }
                    )
                    pickerMotionContainer.setTransitionDuration(0)
                    pickerMotionContainer.transitionToState(R.id.secondary)
                },
            )
        }
    }

    private fun bindCustomizationPicker(
        customizationOptionsData: CustomizationOptionsData,
        pickerMotionContainer: MotionLayout,
        lockScreenCustomizationOptionEntries: List<Pair<CustomizationOption, View>>,
        homeScreenCustomizationOptionEntries: List<Pair<CustomizationOption, View>>,
        customizationOptionFloatingSheetViewMap: Map<CustomizationOption, View>,
        customizationFloatingSheetContainer: FrameLayout,
        packThemeSuggestedChip: PackThemeSuggestedChip?,
    ) {
        CustomizationPickerBinder2.bind(
            customizationOptionsData = customizationOptionsData,
            view = pickerMotionContainer,
            lockScreenCustomizationOptionEntries = lockScreenCustomizationOptionEntries,
            homeScreenCustomizationOptionEntries = homeScreenCustomizationOptionEntries,
            customizationOptionFloatingSheetViewMap = customizationOptionFloatingSheetViewMap,
            viewModel = customizationPickerViewModel,
            colorUpdateViewModel = colorUpdateViewModel,
            customizationOptionsBinder = customizationOptionsBinder,
            lifecycleOwner = viewLifecycleOwner,
            navigateToPrimary = {
                if (pickerMotionContainer.currentState == R.id.secondary) {
                    // For some reasons, for transitioning to R.id.collapsed_header_primary or
                    // R.id.expanded_header_primary we need to use different methods; otherwise
                    // there will be unexpected expand or collapse of the preview after the
                    // transition completes.
                    if (fullyCollapsed) {
                        pickerMotionContainer.transitionToState(R.id.collapsed_header_primary)
                    } else {
                        pickerMotionContainer.setTransition(
                            R.id.secondary,
                            R.id.expanded_header_primary,
                        )
                        pickerMotionContainer.transitionToEnd()
                    }
                }
            },
            navigateToSecondary = { option ->
                if (pickerMotionContainer.currentState != R.id.secondary) {
                    customizationOptionFloatingSheetViewMap[option]?.let { floatingSheetView ->
                        setCustomizationOptionFloatingSheet(
                            floatingSheetViewContent = floatingSheetView,
                            floatingSheetContainer = customizationFloatingSheetContainer,
                            motionContainer = pickerMotionContainer,
                            onSetComplete = {
                                userEventLogger.logEnterScreen(
                                    userEventLogger.transformCustomizationOptionToScreenForLogging(
                                        option
                                    )
                                )
                                // Transition to secondary screen after content is set
                                fullyCollapsed = pickerMotionContainer.progress == 1.0f
                                pickerMotionContainer.transitionToState(R.id.secondary)
                            },
                        )
                    }
                }
            },
            navigateToWallpaperCategoriesScreen = { _ ->
                switchFragment(
                    CategoriesFragment.newInstance(
                        destinationScreen =
                            customizationPickerViewModel.selectedPreviewScreen.value,
                        wallpaperLaunchSource = arguments?.getString(WALLPAPER_LAUNCH_SOURCE) ?: "",
                    )
                )
            },
            navigateToMoreLockScreenSettingsActivity = {
                activity?.startActivity(Intent(Settings.ACTION_LOCKSCREEN_SETTINGS))
            },
            navigateToColorContrastSettingsActivity = {
                activity?.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_COLOR_CONTRAST_SETTINGS)
                )
            },
            navigateToLockScreenNotificationsSettingsActivity = {
                activity?.startActivity(Intent(Settings.ACTION_LOCKSCREEN_NOTIFICATIONS_SETTINGS))
            },
            navigateToPreviewScreen = { wallpaperModel, setEntryPoint ->
                persistentWallpaperModelRepository.setWallpaperModel(wallpaperModel)
                ActivityUtils.startWallpaperPreviewActivity(
                    activity = requireActivity(),
                    isCreativeCategories = false,
                    shouldNavigateToExtendedWallpaperEffects = false,
                    isViewAsHome = !isDesktopUi,
                    requestCode =
                        CustomizationPickerActivity.VIEW_ONLY_PREVIEW_WALLPAPER_REQUEST_CODE,
                    isMultiPanesEnabled = multiPanesChecker.isMultiPanesEnabled(requireContext()),
                    setWallpaperEntryPoint = setEntryPoint,
                    wallpaperLaunchSource = arguments?.getString(WALLPAPER_LAUNCH_SOURCE) ?: "",
                )
            },
            navigateToPackThemeActivity = { intent -> context?.startActivity(intent) },
            navigateToScreenSaverSettingsActivity = {
                activity?.startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
            },
            navigateToWallpaperCollectionScreen = { categoryModel, categoryType ->
                if (BaseFlags.get(requireContext()).isWallpapersFragmentEnabled()) {
                    categoryWallpapersRepository.setSelectedCategory(category = categoryModel)
                    switchFragment(CategoryWallpapersFragment())
                } else {
                    switchFragment(
                        individualPickerFactory.getIndividualPickerInstance(
                            categoryModel.commonCategoryData.collectionId,
                            categoryType,
                            customizationPickerViewModel.selectedPreviewScreen.value,
                        )
                    )
                }
            },
            navigateToExtendedWallpaperEffects = {
                if (BaseFlags.get(pickerMotionContainer.context).isPhotoPickerEnabled()) {
                    switchFragment(
                        PhotoPickerFragment.newInstance(
                            shouldNavigateToExtendedWallpaperEffects = true,
                            wallpaperLaunchSource =
                                arguments?.getString(WALLPAPER_LAUNCH_SOURCE) ?: "",
                        )
                    )
                } else {
                    myPhotosStarterImpl.requestCustomPhotoPicker(
                        object : MyPhotosStarter.PermissionChangedListener {
                            override fun onPermissionsGranted() {}

                            override fun onPermissionsDenied(dontAskAgain: Boolean) {}
                        },
                        requireActivity(),
                        extendedWallpaperEffectsLauncher,
                    )
                }
            },
            packThemeSuggestedChip = packThemeSuggestedChip,
            packThemeSuggestedEntryBinder = packThemeSuggestedEntryBinder,
            curatedPhotosTimeUtil = curatedPhotosTimeUtil,
            userEventLogger = userEventLogger,
            iconStyleViewUtil = iconStyleViewUtil,
        )

        customizationOptionsBinder.bindDiscardChangesDialog(
            customizationOptionsViewModel =
                customizationPickerViewModel.customizationOptionsViewModel,
            lifecycleOwner = viewLifecycleOwner,
            activity = requireActivity(),
        )
    }

    private fun switchFragment(fragment: Fragment) {
        if (isAdded) {
            parentFragmentManager.commit {
                replace(R.id.fragment_container, fragment)
                addToBackStack(null)
            }
        }
    }

    override fun onEnterAnimationCompleteAfterActivityCreated() {
        if (isInitialCreation && isMotionContainerInitialized) {
            // Only show the preview pager when the motion container's dimensions are calculated;
            // otherwise, we should wait until customizationOptionsData is ready and the dimensions
            // are calculated.
            val previewPager: ClickableMotionLayout =
                view?.findViewById(R.id.preview_pager) ?: return
            // Show the preview pager only after enter animation completes. If the preview pager was
            // invisible, making it visible will trigger the surface view's surfaceCreated callback,
            // as well as the binding of the wallpaper preview and workspace preview.
            showPreviewPagerAndBindAlphaAnimation(previewPager)
        }
        isInitialCreation = false
    }

    override fun onDestroyView() {
        context?.applicationContext?.let { appContext ->
            // TODO(b/333879532): Only disconnect when leaving the Activity without introducing
            // black
            //  preview. If onDestroy is caused by an orientation change, we should keep the
            // connection
            //  to avoid initiating the engines again.
            // TODO(b/328302105): MainScope ensures the job gets done non-blocking even if the
            //   activity has been destroyed already. Consider making this part of
            //   WallpaperConnectionUtils.
            mainScope.launch { wallpaperConnectionUtils.disconnectAll() }
        }

        super.onDestroyView()
        onBackPressedCallback?.remove()
    }

    private fun setupToolbar(toolbarContainer: LinearLayout) {
        val toolbar: Toolbar = toolbarContainer.requireViewById(R.id.toolbar)
        val navButton: FrameLayout = toolbarContainer.requireViewById(R.id.nav_button)
        toolbar.title = getString(R.string.app_name)
        toolbar.setBackgroundColor(Color.TRANSPARENT)
        DarkModeUpdateBinder.bind(
            onProgressChange = { progress ->
                val shouldUseLightText = progress == 1f
                setUpStatusBar(shouldUseLightText)
            },
            colorUpdateViewModel = colorUpdateViewModel,
            // Status bar text can only be set to light or dark, and cannot be animated
            shouldAnimate = { false },
            lifecycleOwner = viewLifecycleOwner,
        )

        // The navButton (close button) should be visible on a customization option screen.
        // When on the main screen, the nav button (back button) is hidden for large screen devices
        // with multi-pane layouts.
        viewLifecycleOwner.lifecycleScope.launch {
            customizationPickerViewModel.customizationOptionsViewModel.selectedOption.collect {
                selectedOption ->
                navButton.isVisible =
                    if (selectedOption != null) {
                        true
                    } else {
                        !(multiPanesChecker.isMultiPanesEnabled(requireContext()) &&
                            displayUtils.isLargeScreenOrUnfoldedDisplay(requireContext()))
                    }
            }
        }

        toolbarBinder.bind(
            toolbarContainer,
            customizationPickerViewModel.customizationOptionsViewModel,
            colorUpdateViewModel,
            viewLifecycleOwner,
        ) {
            activity?.onBackPressedDispatcher?.onBackPressed()
        }
    }

    private fun initPreviewPager(
        rootView: View,
        previewPager: ClickableMotionLayout,
    ): PreviewPagerViews {
        previewPager.addClickableViewId(R.id.preview_card)

        // Inflate the clock and attach to the lock preview and bind clock view
        val lockPreview: View = previewPager.requireViewById(R.id.lock_preview)
        val lockPreviewContainer: ViewGroup =
            lockPreview.requireViewById(R.id.wallpaper_preview_crop)
        val clockHostView =
            customizationOptionViewUtil.createClockPreviewAndAddToParent(
                lockPreviewContainer,
                layoutInflater,
            )
        // The shade covers the two surface views of the preview and reveals when the preview is
        // ready to show.
        val lockPreviewShade =
            layoutInflater.inflate(R.layout.preview_shade, lockPreviewContainer, false)
        lockPreviewContainer.addView(lockPreviewShade)

        val homePreview: View = previewPager.requireViewById(R.id.home_preview)
        val homePreviewContainer: ViewGroup =
            homePreview.requireViewById(R.id.wallpaper_preview_crop)
        // The shade covers the two surface views of the preview and reveals when the preview is
        // ready to show.
        val homePreviewShade =
            layoutInflater.inflate(R.layout.preview_shade, homePreviewContainer, false)
        homePreviewContainer.addView(homePreviewShade)

        // Disable accessibility hierarchy embedding for workspace preview surfaces to ensure
        // services don't attempt to navigate or interact with purely decorative preview content.
        val lockWorkspaceSurface: SurfaceView = lockPreview.requireViewById(R.id.workspace_surface)
        val homeWorkspaceSurface: SurfaceView = homePreview.requireViewById(R.id.workspace_surface)
        lockWorkspaceSurface.setAccessibilityHierarchyEmbeddingEnabled(false)
        homeWorkspaceSurface.setAccessibilityHierarchyEmbeddingEnabled(false)

        // Sets up focus listeners for the lock preview and home preview to handle accessibility
        // focus events.
        if (isDesktopUi) {
            setUpPreviewCardFocusListener(
                lockPreview.requireViewById<View>(R.id.preview_card),
                previewPager,
                LOCK_SCREEN,
            )
            setUpPreviewCardFocusListener(
                homePreview.requireViewById<View>(R.id.preview_card),
                previewPager,
                HOME_SCREEN,
            )
        }

        return PreviewPagerViews(
            previewPager = previewPager,
            lockPreviewLabel = previewPager.requireViewById(R.id.lock_preview_label),
            homePreviewLabel = previewPager.requireViewById(R.id.home_preview_label),
            lockPreviewLabelContainer =
                previewPager.requireViewById(R.id.lock_preview_label_container),
            homePreviewLabelContainer =
                previewPager.requireViewById(R.id.home_preview_label_container),
            lockPreview = previewPager.requireViewById(R.id.lock_preview),
            homePreview = previewPager.requireViewById(R.id.home_preview),
            lockPreviewShade = lockPreviewShade,
            homePreviewShade = homePreviewShade,
            clockHostView = clockHostView,
            clockFaceClickDelegateView = rootView.requireViewById(R.id.clock_face_click_delegate),
        )
    }

    private fun bindPreviewPager(
        rootView: View,
        previewPagerViews: PreviewPagerViews,
        isFirstBinding: Boolean,
    ) {
        PreviewPagerBinder.bind(previewPagerViews, customizationPickerViewModel, viewLifecycleOwner)

        ColorUpdateBinder.bind(
            setColor = { color -> previewPagerViews.lockPreviewLabel.setTextColor(color) },
            color = colorUpdateViewModel.colorOnSurface,
            shouldAnimate = isOnMainScreen,
            lifecycleOwner = viewLifecycleOwner,
        )

        ColorUpdateBinder.bind(
            setColor = { color -> previewPagerViews.homePreviewLabel.setTextColor(color) },
            color = colorUpdateViewModel.colorOnSurface,
            shouldAnimate = isOnMainScreen,
            lifecycleOwner = viewLifecycleOwner,
        )

        ColorUpdateBinder.bind(
            setColor = { color -> previewPagerViews.lockPreviewShade.setBackgroundColor(color) },
            color = colorUpdateViewModel.colorSurfaceContainer,
            shouldAnimate = { previewPagerViews.lockPreviewShade.alpha != 0F },
            lifecycleOwner = viewLifecycleOwner,
        )

        ColorUpdateBinder.bind(
            setColor = { color -> previewPagerViews.homePreviewShade.setBackgroundColor(color) },
            color = colorUpdateViewModel.colorSurfaceContainer,
            shouldAnimate = { previewPagerViews.homePreviewShade.alpha != 0F },
            lifecycleOwner = viewLifecycleOwner,
        )

        // Bind the clock view if nonnull
        val clockHostView = previewPagerViews.clockHostView
        val clockFaceClickDelegateView = previewPagerViews.clockFaceClickDelegateView
        if (clockHostView != null && clockFaceClickDelegateView != null) {
            customizationOptionsBinder.bindClockPreview(
                context = requireContext(),
                rootView = rootView,
                clockHostView = clockHostView,
                clockFaceClickDelegateView = clockFaceClickDelegateView,
                viewModel = customizationPickerViewModel,
                colorUpdateViewModel = colorUpdateViewModel,
                lifecycleOwner = viewLifecycleOwner,
                clockViewFactory = clockViewFactory,
            )
        }

        bindPreview(
            screen = LOCK_SCREEN,
            previewPager = previewPagerViews.previewPager,
            preview = previewPagerViews.lockPreview,
            isFirstBinding = isFirstBinding,
        )

        bindPreview(
            screen = HOME_SCREEN,
            previewPager = previewPagerViews.previewPager,
            preview = previewPagerViews.homePreview,
            isFirstBinding = isFirstBinding,
        )
    }

    private fun bindPreview(
        screen: Screen,
        previewPager: ClickableMotionLayout,
        preview: View,
        isFirstBinding: Boolean,
    ) {
        val appContext = context?.applicationContext ?: return
        val activity = activity ?: return
        val previewViewModel = customizationPickerViewModel.basePreviewViewModel

        val previewCard: View = preview.requireViewById(R.id.preview_card)

        BasePreviewBinder.bind(
            applicationContext = appContext,
            view = previewCard,
            viewModel = customizationPickerViewModel,
            colorUpdateViewModel = colorUpdateViewModel,
            workspaceBinder = workspaceBinder,
            screen = screen,
            deviceDisplayType = displayUtils.getCurrentDisplayType(activity),
            displaySize =
                if (displayUtils.isOnWallpaperDisplay(activity))
                    previewViewModel.wallpaperDisplaySize.value
                else previewViewModel.smallerDisplaySize,
            mainScope = mainScope,
            lifecycleOwner = viewLifecycleOwner,
            wallpaperConnectionUtils = wallpaperConnectionUtils,
            isFirstBindingDeferred = CompletableDeferred(isFirstBinding),
            onLaunchPreview = { wallpaperModel ->
                persistentWallpaperModelRepository.setWallpaperModel(wallpaperModel)
                val multiPanesChecker = LargeScreenMultiPanesChecker()
                val isMultiPanel = multiPanesChecker.isMultiPanesEnabled(appContext)
                startForResult.launch(
                    WallpaperPreviewActivity.intentBuilder(appContext, false)
                        .viewAsHome(screen == HOME_SCREEN)
                        .newTask(isMultiPanel)
                        // Hide info sheet for current wallpapers because attribution is not
                        // updated when language updates, see b/418619944
                        .hideInfoSheet(true)
                        .wallpaperLaunchSource(arguments?.getString(WALLPAPER_LAUNCH_SOURCE) ?: "")
                        .build()
                )
            },
            onTransitionToScreen = { toScreen -> previewPager.transitionToScreen(toScreen) },
            onPreviewReady = { previewScreen ->
                customizationPickerViewModel.setPreviewReady(previewScreen, true)
            },
            onPreviewSurfaceDestroyed = { previewScreen ->
                customizationPickerViewModel.setPreviewReady(previewScreen, false)
            },
            clockViewFactory = clockViewFactory,
        )
    }

    private fun initCustomizationOptionEntries(
        customizationOptionsData: CustomizationOptionsData,
        view: View,
        screen: Screen,
    ): List<Pair<CustomizationOption, View>> {
        val optionEntriesContainer =
            view.requireViewById<LinearLayout>(
                when (screen) {
                    LOCK_SCREEN -> R.id.lock_customization_option_container
                    HOME_SCREEN -> R.id.home_customization_option_container
                }
            )
        val optionEntries =
            customizationOptionViewUtil.getOptionEntries(
                customizationOptionsData = customizationOptionsData,
                screen = screen,
                optionContainer = optionEntriesContainer,
                layoutInflater = layoutInflater,
            )
        optionEntries.onEachIndexed { index, (_, view) ->
            val isFirst = index == 0
            val isLast = index == optionEntries.size - 1
            view.setBackgroundResource(
                if (isFirst && isLast) R.drawable.customization_option_entry_singleton_background
                else if (isFirst) R.drawable.customization_option_entry_top_background
                else if (isLast) R.drawable.customization_option_entry_bottom_background
                else R.drawable.customization_option_entry_background
            )
            optionEntriesContainer.addView(view)
        }
        return optionEntries
    }

    private fun ClickableMotionLayout.transitionToScreen(screen: Screen) {
        val targetState =
            when (screen) {
                LOCK_SCREEN -> R.id.lock_preview_selected
                HOME_SCREEN -> R.id.home_preview_selected
            }
        transitionToState(targetState, ANIMATION_DURATION)
    }

    /**
     * Sets up a focus listener for the preview card to handle accessibility focus events. When the
     * preview card receives focus, it transitions the preview pager to the corresponding screen.
     */
    private fun setUpPreviewCardFocusListener(
        previewCard: View,
        previewPager: ClickableMotionLayout,
        screen: Screen,
    ) {
        previewCard.isFocusable = true
        previewCard.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

        previewCard.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                previewPager.transitionToScreen(screen)
            }
        }

        ViewCompat.setAccessibilityDelegate(
            previewCard,
            object : AccessibilityDelegateCompat() {
                override fun onPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) {
                    super.onPopulateAccessibilityEvent(host, event)
                    if (event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
                        previewPager.transitionToScreen(screen)
                    }
                }
            },
        )
    }

    /**
     * Set customization option floating sheet content to the floating sheet container and get the
     * new container's height for repositioning the preview's guideline.
     *
     * @param isInitialSecondaryScreen If the first screen shown is a secondary screen, we should
     *   hide the bottom scroll view on the main screen as well as the floating sheet on the
     *   secondary screen for the whole transition.
     */
    private fun setCustomizationOptionFloatingSheet(
        floatingSheetViewContent: View,
        floatingSheetContainer: FrameLayout,
        motionContainer: MotionLayout,
        onSetComplete: () -> Unit,
    ) {
        floatingSheetContainer.removeAllViews()
        floatingSheetContainer.addView(floatingSheetViewContent)

        floatingSheetViewContent.doOnPreDraw {
            val translationY = floatingSheetViewContent.height
            floatingSheetContainer.translationY = 0.0f
            floatingSheetContainer.alpha = 0.0f
            // Update the motion container
            motionContainer.getConstraintSet(R.id.expanded_header_primary)?.apply {
                setTranslationY(
                    R.id.customization_option_floating_sheet_container,
                    translationY.toFloat(),
                )
                setAlpha(R.id.customization_option_floating_sheet_container, 0.0f)
                connect(
                    R.id.customization_option_floating_sheet_container,
                    ConstraintSet.BOTTOM,
                    R.id.picker_motion_layout,
                    ConstraintSet.BOTTOM,
                )
                constrainHeight(
                    R.id.customization_option_floating_sheet_container,
                    ConstraintLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            motionContainer.getConstraintSet(R.id.collapsed_header_primary)?.apply {
                setTranslationY(
                    R.id.customization_option_floating_sheet_container,
                    translationY.toFloat(),
                )
                setAlpha(R.id.customization_option_floating_sheet_container, 0.0f)
                connect(
                    R.id.customization_option_floating_sheet_container,
                    ConstraintSet.BOTTOM,
                    R.id.picker_motion_layout,
                    ConstraintSet.BOTTOM,
                )
                constrainHeight(
                    R.id.customization_option_floating_sheet_container,
                    ConstraintLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            motionContainer.getConstraintSet(R.id.secondary)?.apply {
                setTranslationY(R.id.customization_option_floating_sheet_container, 0.0f)
                constrainHeight(
                    R.id.customization_option_floating_sheet_container,
                    ConstraintLayout.LayoutParams.WRAP_CONTENT,
                )
                if (isDesktopUi) {
                    constrainHeight(
                        R.id.preview_header,
                        resources.getDimensionPixelSize(
                            R.dimen.customization_picker_preview_header_expanded_height
                        ),
                    )
                }
            }
            // Wait until motion container's constraints are updated
            motionContainer.post { onSetComplete() }
        }
    }

    companion object {
        private const val ANIMATION_DURATION = 200
    }

    private fun prepareFragmentExitTransitionAnimation() {
        val transition = (exitTransition as? Transition) ?: return
        transition.addListener(
            object : Transition.TransitionListener {
                override fun onTransitionStart(transition: Transition) {
                    val previewPager: View = view?.findViewById(R.id.preview_pager) ?: return
                    setPreviewPagerVisible(previewPager = previewPager, isVisible = false)
                    isFragmentReenterAfterExit = true
                }

                override fun onTransitionEnd(transition: Transition) {}

                override fun onTransitionCancel(transition: Transition) {
                    val previewPager: View = view?.findViewById(R.id.preview_pager) ?: return
                    setPreviewPagerVisible(previewPager = previewPager, isVisible = true)
                    isFragmentReenterAfterExit = false
                }

                override fun onTransitionPause(transition: Transition) {}

                override fun onTransitionResume(transition: Transition) {}
            }
        )
    }

    private fun prepareFragmentReenterTransitionAnimation() {
        val transition = (reenterTransition as? Transition) ?: return
        transition.addListener(
            object : Transition.TransitionListener {
                override fun onTransitionStart(transition: Transition) {
                    isFragmentReenterEnded = false
                }

                override fun onTransitionEnd(transition: Transition) {
                    if (isMotionContainerInitialized) {
                        // Only show the preview pager when the motion container's dimensions are
                        // calculated; otherwise, we should wait until customizationOptionsData
                        // is ready and the dimensions are calculated.
                        val rootView = view ?: return
                        val previewPager: ClickableMotionLayout =
                            rootView.requireViewById(R.id.preview_pager)
                        showPreviewPagerAndBindAlphaAnimation(previewPager)
                    }
                    isFragmentReenterEnded = true
                }

                override fun onTransitionCancel(transition: Transition) {}

                override fun onTransitionPause(transition: Transition) {}

                override fun onTransitionResume(transition: Transition) {}
            }
        )
    }

    private fun showPreviewPagerAndBindAlphaAnimation(previewPager: ClickableMotionLayout) {
        setPreviewPagerVisible(previewPager = previewPager, isVisible = true)
        PreviewAlphaAnimationBinder.bind(
            previewPager = previewPager,
            viewModel = customizationPickerViewModel,
            lifecycleOwner = viewLifecycleOwner,
        )
    }

    /**
     * Specifically set the preview pager visible or invisible. We set the preview pager invisible
     * early before some Fragment transitions. This is because we encounter the preview flashing
     * issue due to the unexpected [SurfaceView] callbacks of onSurfaceCreated and
     * onSurfaceDestroyed, during Fragment transition.
     */
    private fun setPreviewPagerVisible(previewPager: View, isVisible: Boolean) {
        val lockPreviewLabel: View = previewPager.requireViewById(R.id.lock_preview_label)
        val homePreviewLabel: View = previewPager.requireViewById(R.id.home_preview_label)
        val lockPreview: View = previewPager.requireViewById(R.id.lock_preview)
        val homePreview: View = previewPager.requireViewById(R.id.home_preview)
        val lockWallpaperSurface: SurfaceView = lockPreview.requireViewById(R.id.wallpaper_surface)
        val lockWorkspaceSurface: SurfaceView = lockPreview.requireViewById(R.id.workspace_surface)
        val homeWallpaperSurface: SurfaceView = homePreview.requireViewById(R.id.wallpaper_surface)
        val homeWorkspaceSurface: SurfaceView = homePreview.requireViewById(R.id.workspace_surface)
        lockPreviewLabel.visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
        lockWallpaperSurface.visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
        lockWorkspaceSurface.visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
        homePreviewLabel.visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
        homeWallpaperSurface.visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
        homeWorkspaceSurface.visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
    }

    /**
     * Utility class for extending [View.AccessibilityDelegate] that forwards all methods to a base
     * delegate implementation.
     *
     * This class exists because setting an AccessibilityDelegate or AccessibilityDelegateCompat
     * that does not do forwarding causes views like NestedScrollView to behave improperly in
     * TalkBack, etc.
     */
    open class ForwardingAccessibilityDelegate(val base: View.AccessibilityDelegate) :
        View.AccessibilityDelegate() {
        override fun sendAccessibilityEvent(host: View, eventType: Int) {
            base.sendAccessibilityEvent(host, eventType)
        }

        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            base.onInitializeAccessibilityNodeInfo(host, info)
        }

        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
            return base.performAccessibilityAction(host, action, args)
        }

        override fun sendAccessibilityEventUnchecked(host: View, event: AccessibilityEvent) {
            base.sendAccessibilityEventUnchecked(host, event)
        }

        override fun dispatchPopulateAccessibilityEvent(
            host: View,
            event: AccessibilityEvent,
        ): Boolean {
            return base.dispatchPopulateAccessibilityEvent(host, event)
        }

        override fun onPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) {
            base.onPopulateAccessibilityEvent(host, event)
        }

        override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) {
            base.onInitializeAccessibilityEvent(host, event)
        }

        override fun addExtraDataToAccessibilityNodeInfo(
            host: View,
            info: AccessibilityNodeInfo,
            extraDataKey: String,
            arguments: Bundle?,
        ) {
            base.addExtraDataToAccessibilityNodeInfo(host, info, extraDataKey, arguments)
        }

        override fun onRequestSendAccessibilityEvent(
            host: ViewGroup,
            child: View,
            event: AccessibilityEvent,
        ): Boolean {
            return base.onRequestSendAccessibilityEvent(host, child, event)
        }

        override fun getAccessibilityNodeProvider(host: View): AccessibilityNodeProvider? {
            return base.getAccessibilityNodeProvider(host)
        }

        override fun createAccessibilityNodeInfo(host: View): AccessibilityNodeInfo {
            return base.createAccessibilityNodeInfo(host)
        }
    }
}
