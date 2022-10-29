/*
 * Copyright 2021-2022 KasukuSakura Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/KasukuSakura/mirai-login-solver-sakura/blob/main/LICENSE
 */

package com.kasukusakura.mlss.resolver

import kotlinx.coroutines.*
import net.miginfocom.swing.MigLayout
import java.awt.*
import java.awt.event.*
import java.beans.PropertyChangeListener
import java.net.URI
import java.util.*
import javax.swing.*
import kotlin.coroutines.CoroutineContext


internal sealed class WindowResult {
    abstract val cancelled: Boolean
    abstract val valueAsString: String?
    abstract val value: Any?

    internal object Cancelled : WindowResult() {
        override val valueAsString: String? get() = null
        override val value: Any? get() = null
        override val cancelled: Boolean get() = true

        override fun toString(): String {
            return "WindowResult.Cancelled"
        }
    }

    internal object WindowClosed : WindowResult() {
        override val valueAsString: String? get() = null
        override val value: Any? get() = null
        override val cancelled: Boolean get() = true

        override fun toString(): String {
            return "WindowResult.WindowClosed"
        }
    }

    internal object SelectedOK : WindowResult() {
        override val valueAsString: String get() = "true"
        override val value: Any get() = true
        override val cancelled: Boolean get() = false

        override fun toString(): String {
            return "WindowResult.SelectedOK"
        }
    }

    internal class Confirmed(private val data: String) : WindowResult() {
        override fun toString(): String {
            return "WindowResult.Confirmed($data)"
        }

        override val valueAsString: String get() = data
        override val value: Any get() = data
        override val cancelled: Boolean get() = false
    }

    internal class ConfirmedAnything(override val value: Any? = null) : WindowResult() {
        override val valueAsString: String?
            get() = value?.toString()

        override val cancelled: Boolean get() = false

        override fun toString(): String {
            return "WindowResult.ConfirmedAnything(value=$value)@${hashCode()}"
        }
    }
}

@Suppress("PropertyName")
internal class WindowsOptions(
    val layout: MigLayout,
    val contentPane: Container,
    val optionPane: JOptionPane,
    val response: CompletableDeferred<WindowResult>,
    val parentWindow: Window,
    val subCoroutineScope: CoroutineScope,
    val swingActionsScope: CoroutineScope,
) {
    var width: Int = 3

    fun appendFillX(sub: Component) {
        contentPane.add(sub, "spanx $width,growx,wrap")
    }

    fun appendFillWithLabel(name: String, comp: Component): JLabel {
        val label = JLabel(name)
        contentPane.add(label)
        contentPane.add(comp.also { label.labelFor = it }, "spanx ${width - 1},growx,wrap")
        return label
    }

    fun filledTextField(name: String, value: String): JTextField {
        val field = JTextField(value)
        if (name.isEmpty()) {
            appendFillX(field)
        } else {
            appendFillWithLabel(name, field)
        }
        return field
    }

    private companion object {
        @JvmStatic
        private fun getMnemonic(key: String, l: Locale): Int {
            val value = UIManager.get(key, l) as String? ?: return 0
            try {
                return value.toInt()
            } catch (_: NumberFormatException) {
            }
            return 0
        }
    }

    private val l get() = optionPane.locale
    val BTN_YES by lazy {
        ButtonFactory(
            UIManager.getString("OptionPane.yesButtonText", l),
            getMnemonic("OptionPane.yesButtonMnemonic", l),
            null, -1
        )
    }
    val BTN_NO by lazy {
        ButtonFactory(
            UIManager.getString("OptionPane.noButtonText", l),
            getMnemonic("OptionPane.noButtonMnemonic", l),
            null, -1
        )
    }
    val BTN_OK by lazy {
        ButtonFactory(
            UIManager.getString("OptionPane.okButtonText", l),
            getMnemonic("OptionPane.okButtonMnemonic", l),
            null, -1
        )
    }
    val BTN_CANCEL by lazy {
        ButtonFactory(
            UIManager.getString("OptionPane.cancelButtonText", l),
            getMnemonic("OptionPane.cancelButtonMnemonic", l),
            null, -1
        )
    }

    fun ButtonFactory.withValue(v: WindowResult): JButton = withAction {
        optionPane.value = v
    }

    fun ButtonFactory.withValue(block: () -> WindowResult): JButton = withAction {
        optionPane.value = block()
    }

    fun JButton.withValue(v: WindowResult): JButton = withAction {
        optionPane.value = v
    }

    fun JButton.withValue(block: () -> WindowResult): JButton = withAction {
        optionPane.value = block()
    }

    fun ButtonFactory.withAction(action: ActionListener): JButton {
        return createButton().also { btn ->
            btn.name = "OptionPane.button"
            btn.addActionListener(action)
        }
    }

    fun ButtonFactory.attachToTextField(field: JTextField): JButton = withAction {
        optionPane.value = WindowResult.Confirmed(field.text)
    }

    fun <T : Any> T.asInitialValue(): T {
        optionPane.initialValue = this@asInitialValue
        return this@asInitialValue
    }

    fun openBrowserOrAlert(url: String) {
        // Try to open browser safely. mamoe/mirai#694
        try {
            Desktop.getDesktop().browse(URI(url))
        } catch (ex: Exception) {
            JOptionPane.showInputDialog(
                parentWindow,
                "Failed to open external url",
                windowTitle,
                JOptionPane.WARNING_MESSAGE,
                null,
                null,
                url
            )
        }
    }

    val windowTitle: String
        get() {
            return when (val pw = parentWindow) {
                is JFrame -> pw.title
                else -> (pw as Dialog).title
            }
        }

    fun alertError(msg: String) {
        JOptionPane.showMessageDialog(
            parentWindow,
            msg,
            windowTitle,
            JOptionPane.ERROR_MESSAGE
        )
    }

    internal fun <T> runBlockingAWT(action: suspend CoroutineScope.() -> T): T =
        runBlocking(swingActionsScope.coroutineContext, block = action)

    internal fun JButton.withActionBlocking(action: suspend CoroutineScope.() -> Unit): JButton = withAction {
        runBlockingAWT(action)
    }

    internal fun JButton.withActionBlockingAE(
        action: suspend CoroutineScope.(ActionEvent) -> Unit
    ): JButton = withAction { evt ->
        runBlocking(swingActionsScope.coroutineContext) {
            action(evt)
        }
    }

}

internal class ButtonFactory(
    private val text: String,
    private val mnemonic: Int,
    private val icon: Icon?,
    private val minimumWidth: Int = -1,
) {

    fun createButton(): JButton {
        val button = if (minimumWidth > 0) {
            ConstrainedButton(text, minimumWidth)
        } else {
            JButton(text)
        }
        if (icon != null) {
            button.icon = icon
        }
        if (mnemonic != 0) {
            button.mnemonic = mnemonic
        }
        return button
    }

    private class ConstrainedButton(text: String?, val minimumWidth: Int) : JButton(text) {
        override fun getMinimumSize(): Dimension {
            val min = super.getMinimumSize()
            min.width = min.width.coerceAtLeast(minimumWidth)
            return min
        }

        override fun getPreferredSize(): Dimension {
            val pref = super.getPreferredSize()
            pref.width = pref.width.coerceAtLeast(minimumWidth)
            return pref
        }
    }
}

internal suspend fun openWindowCommon(
    window: Window,
    title: String,
    isTopLevel: Boolean = true,
    blockingDisplay: Boolean = false,
    overrideResponse: CompletableDeferred<WindowResult>? = null,
    action: WindowsOptions.() -> Unit,
): WindowResult {
    val currentCoroutineContext = currentCoroutineContext()
    val subSupervisorJob = SupervisorJob(parent = currentCoroutineContext[Job])
    val subSwingSupervisorJob = SupervisorJob(parent = subSupervisorJob)
    val subCoroutineScope = CoroutineScope(currentCoroutineContext + subSupervisorJob)
    val swingActionsScope = CoroutineScope(currentCoroutineContext + subSwingSupervisorJob + SwingxDispatcher)

    val response = overrideResponse ?: CompletableDeferred()

    val optionPane = JOptionPane()
    optionPane.messageType = JOptionPane.PLAIN_MESSAGE
    optionPane.optionType = JOptionPane.OK_CANCEL_OPTION

    val contentPane = JPanel()
    val migLayout = MigLayout("", "[][][fill,grow]", "")
    contentPane.layout = migLayout
    optionPane.message = contentPane


    val realWindow = if (isTopLevel) {
        (window as JFrame).title = title
        window
    } else {
        JDialog(window, title, Dialog.ModalityType.APPLICATION_MODAL)
    }

    response.invokeOnCompletion {
        SwingUtilities.invokeLater { realWindow.dispose() }
    }

    action.invoke(
        WindowsOptions(
            layout = migLayout,
            contentPane = contentPane,
            optionPane = optionPane,
            response = response,
            parentWindow = realWindow,
            subCoroutineScope = subCoroutineScope,
            swingActionsScope = swingActionsScope,
        )
    )

    fun processed() {
        val value0 = optionPane.value
        if (value0 is WindowResult) response.complete(value0)
        if (optionPane.options == null) {
            val rsp = when (value0) {
                JOptionPane.OK_OPTION -> WindowResult.SelectedOK
                JOptionPane.CANCEL_OPTION -> WindowResult.Cancelled
                JOptionPane.NO_OPTION -> WindowResult.Cancelled
                else -> WindowResult.Cancelled // Unknown
            }
            response.complete(rsp)
        } else {
            // Unknown
            response.complete(WindowResult.Cancelled)
        }
    }

    val listener = PropertyChangeListener { evt ->
        if (realWindow.isVisible && evt.source === optionPane) {
            if (evt.propertyName != JOptionPane.VALUE_PROPERTY) return@PropertyChangeListener
            if (evt.newValue != null && evt.newValue != JOptionPane.UNINITIALIZED_VALUE) {
                realWindow.isVisible = false
            }
        }
    }
    optionPane.selectInitialValue()
    optionPane.addPropertyChangeListener(listener)
    val adapter = object : WindowAdapter() {
        private var gotFocus = false
        override fun windowClosing(we: WindowEvent?) {
            // println("Window closing....")
            response.complete(WindowResult.WindowClosed)

            optionPane.value = null
        }

        override fun windowClosed(e: WindowEvent?) {
            optionPane.removePropertyChangeListener(listener)

            response.complete(WindowResult.WindowClosed)
        }

        override fun windowGainedFocus(we: WindowEvent?) {
            // Once window gets focus, set initial focus
            if (!gotFocus) {
                optionPane.selectInitialValue()
                gotFocus = true
            }
        }
    }
    realWindow.addWindowListener(adapter)
    realWindow.addWindowFocusListener(adapter)
    realWindow.addHierarchyListener { evt ->
        if (evt.source !== realWindow) return@addHierarchyListener
        if (evt.changed !== realWindow) return@addHierarchyListener
        if (realWindow.isVisible) return@addHierarchyListener
        if (evt.id == HierarchyEvent.HIERARCHY_CHANGED) {
            if ((evt.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong()) != 0L) {
                // Windows disposing...
                processed()
            }
        }

    }
    realWindow.addComponentListener(object : ComponentAdapter() {
        override fun componentShown(ce: ComponentEvent) {
            // reset value to ensure closing works properly
            optionPane.value = JOptionPane.UNINITIALIZED_VALUE
        }
    })

    if (isTopLevel) {
        window as JFrame
        window.contentPane = optionPane
        window.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
    } else {
        realWindow.layout = BorderLayout()
        realWindow.add(optionPane, BorderLayout.CENTER)
        realWindow.componentOrientation = window.componentOrientation
    }
    realWindow.pack()
    if (isTopLevel) {
        window.setLocationRelativeTo(null)
    } else {
        realWindow.setLocationRelativeTo(window)
    }
    if (blockingDisplay) {
        realWindow.isVisible = true
    } else {
        SwingUtilities.invokeLater { realWindow.isVisible = true }
    }

    return try {
        response.await().also {
            subSwingSupervisorJob.complete()
            subSwingSupervisorJob.join()
            subSupervisorJob.cancel()
        }
    } catch (anyErr: Throwable) {
        subSupervisorJob.completeExceptionally(anyErr)
        throw anyErr
    }
}

internal fun JButton.withAction(action: ActionListener): JButton = apply {
    addActionListener(action)
}

internal object SwingxDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            block.run()
        } else {
            SwingUtilities.invokeLater(block)
        }
    }
}
