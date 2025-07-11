package com.intellij.driver.sdk.ui.components.common.toolwindows

import com.intellij.driver.sdk.ui.components.ComponentData
import com.intellij.driver.sdk.ui.components.UiComponent

open class ToolWindowUiComponent(data: ComponentData): UiComponent(data) {

  val toolWindowHeader = x(ToolWindowHeaderUi::class.java) { byType("com.intellij.toolWindow.ToolWindowHeader") }

  class ToolWindowHeaderUi(data: ComponentData): UiComponent(data) {
    val optionsButton = x { byAccessibleName("Options") }
    val hideButton = x { byAccessibleName("Hide") }
  }
}