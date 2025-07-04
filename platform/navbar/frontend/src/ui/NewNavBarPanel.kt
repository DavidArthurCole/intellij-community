// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.navbar.frontend.ui

import com.intellij.accessibility.AccessibilityUtils
import com.intellij.ide.IdeBundle
import com.intellij.ide.ui.UISettings
import com.intellij.internal.statistic.service.fus.collectors.UIEventLogger.NavBarShowPopup
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.platform.navbar.NavBarVmItem.Companion.SELECTED_ITEMS
import com.intellij.platform.navbar.frontend.actions.NavBarActionHandler.NAV_BAR_ACTION_HANDLER
import com.intellij.platform.navbar.frontend.actions.NavBarActionHandlerImpl
import com.intellij.platform.navbar.frontend.ui.NavBarItemComponent.Companion.isItemComponentFocusable
import com.intellij.platform.navbar.frontend.vm.NavBarItemVm
import com.intellij.platform.navbar.frontend.vm.NavBarPopupVm
import com.intellij.platform.navbar.frontend.vm.NavBarVm
import com.intellij.ui.*
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.popup.PopupOwner
import com.intellij.ui.speedSearch.SpeedSearchSupply
import com.intellij.util.AwaitCancellationAndInvoke
import com.intellij.util.awaitCancellationAndInvoke
import com.intellij.util.ui.EDT
import com.intellij.util.ui.RawSwingDispatcher
import com.intellij.util.ui.StartupUiUtil
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.accessibility.AccessibleContextUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.annotations.ApiStatus
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Point
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.lang.ref.WeakReference
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.border.LineBorder

/**
 * @param installPopupHandler Disables popup which appears on right click containing nav bar actions
 */
@ApiStatus.Internal
class NewNavBarPanel(
  cs: CoroutineScope,
  private val vm: NavBarVm,
  val project: Project,
  val isFloating: Boolean,
  private val installPopupHandler: Boolean = true,
) : JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)), PopupOwner, UiDataProvider {

  private val myItemComponents: ArrayList<NavBarItemComponent> = ArrayList()

  var onSizeChange: Runnable? = null
    set(value) {
      EDT.assertIsEdt()
      field = value
    }

  init {
    EDT.assertIsEdt()
    isOpaque = false
    if (!ExperimentalUI.isNewUI() && StartupUiUtil.isUnderDarcula && isFloating) {
      border = LineBorder(Gray._120, 1)
    }

    if (!isFloating) {
      addFocusListener(NavBarDialogFocusListener(this))
    }
    addFocusListener(object : FocusListener {
      override fun focusGained(e: FocusEvent?) = myItemComponents.forEach(NavBarItemComponent::update)
      override fun focusLost(e: FocusEvent?) = myItemComponents.forEach(NavBarItemComponent::update)
    })
    cs.launch {
      handleItems()
    }
    putClientProperty(ActionUtil.ALLOW_ACTION_PERFORM_WHEN_HIDDEN, true)
  }

  private suspend fun handleItems() {
    vm.items.collectLatest { items ->
      coroutineScope {
        withContext(RawSwingDispatcher) {
          rebuild(this@coroutineScope, items)
        }
        handleSelection()
      }
    }
  }

  private suspend fun handleSelection() {
    vm.selectedIndex.collectLatest { index ->
      if (index < 0) {
        return@collectLatest
      }
      withContext(Dispatchers.EDT) {
        val itemComponent = myItemComponents[index]
        scrollRectToVisible(itemComponent.bounds)
        itemComponent.focusItem()
        if (!ExperimentalUI.isNewUI()) {
          // Components update themselves on selection change.
          // In total 2 components are updated: the one which became selected, and the one which lost the selection.
          // In the old UI chevron needs to know whether the next component is selected
          // regardless of selection moving to the left or right.
          // I don't really want to expose and maintain [NavBarItemVm#isNextSelected] as StateFlow,
          // so in the old UI all components are updated on each selection change.
          myItemComponents.forEach(NavBarItemComponent::update)
        }
      }
      handlePopup(index)
    }
  }

  private suspend fun handlePopup(index: Int) {
    vm.popup.collectLatest { popup ->
      if (popup == null) {
        return@collectLatest
      }
      withContext(Dispatchers.EDT) {
        showPopup(this@withContext, index, popup)
      }
    }
  }

  private suspend fun rebuild(cs: CoroutineScope, items: List<NavBarItemVm>) {
    EDT.assertIsEdt()
    removeAll()
    myItemComponents.clear()

    for (item in items) {
      val itemComponent = NavBarItemComponent(cs, item, this, installPopupHandler)
      add(itemComponent)
      myItemComponents.add(itemComponent)
    }

    revalidate()
    repaint()

    onSizeChange?.run()
    yield()
    myItemComponents.lastOrNull()?.let {
      scrollRectToVisible(it.bounds)
    }
  }

  private var popupList: WeakReference<JList<*>>? = null
    get() {
      EDT.assertIsEdt()
      return field
    }
    set(value) {
      EDT.assertIsEdt()
      field = value
    }

  @OptIn(AwaitCancellationAndInvoke::class)
  private fun showPopup(cs: CoroutineScope, itemComponentIndex: Int, vm: NavBarPopupVm<*>) {
    NavBarShowPopup.log(project)
    val itemComponent = myItemComponents[itemComponentIndex]
    val list = navBarPopupList(vm, this, isFloating).also {
      AccessibleContextUtil.setName(it, itemComponent.text)
    }.also {
      popupList = WeakReference(it)
    }
    if (!isShowing) {
      thisLogger().warn("Navigation bar panel is now showing => cannot show child popup")
      return
    }
    val popup = createNavBarPopup(list)
    popup.addHintListener {
      vm.cancel() // cancel vm when popup is cancelled
    }
    cs.awaitCancellationAndInvoke {
      popupList = null
      popup.hide() // cancel the popup when coroutine is cancelled
    }
    val offsetX = navBarPopupOffset(itemComponentIndex == 0)
    val point = getItemPopupLocation(itemComponent, popup)
    popup.show(this, point.x - offsetX, point.y, this, HintHint(this, point))
    val selectedItemIndex = vm.initialSelectedItemIndex
    if (selectedItemIndex in 0 until list.model.size) {
      ScrollingUtil.selectItem(list, selectedItemIndex)
    }
  }

  private fun getItemPopupLocation(itemComponent: Component, popupHint: LightweightHint): Point {
    val settings = UISettings.getInstance()
    val relativeY = if (ExperimentalUI.isNewUI() && settings.showNavigationBarInBottom && settings.showStatusBar) {
      -popupHint.component.preferredSize.height
    }
    else {
      itemComponent.height
    }
    val relativePoint = RelativePoint(itemComponent, Point(0, relativeY))
    return relativePoint.getPoint(this)
  }

  fun isItemFocused(): Boolean {
    return when {
      vm.popup.value != null -> false
      isItemComponentFocusable() -> UIUtil.isFocusAncestor(this)
      else -> hasFocus()
    }
  }

  override fun getBestPopupPosition(): Point? {
    val itemComponent = myItemComponents.getOrNull(vm.selectedIndex.value)
                        ?: return null
    return Point(itemComponent.x, itemComponent.y + itemComponent.height)
  }

  override fun getPopupComponent(): JComponent? {
    return popupList?.get()
  }

  override fun uiDataSnapshot(sink: DataSink) {
    sink[SELECTED_ITEMS] = vm.selection()
    sink[NAV_BAR_ACTION_HANDLER] = object : NavBarActionHandlerImpl(vm) {
      override fun isNodePopupSpeedSearchActive(): Boolean {
        val list = popupList?.get()
        return list != null && SpeedSearchSupply.getSupply(list) != null
      }
    }
  }

  override fun getAccessibleContext(): AccessibleContext {
    if (accessibleContext == null) {
      accessibleContext = AccessibleNewNavBarPanel()
      accessibleContext.accessibleName = IdeBundle.message("navigation.bar")
    }

    return accessibleContext
  }

  @Suppress("RedundantInnerClassModifier")
  private inner class AccessibleNewNavBarPanel : AccessibleJPanel() {
    override fun getAccessibleRole(): AccessibleRole = AccessibilityUtils.GROUPED_ELEMENTS
  }
}
