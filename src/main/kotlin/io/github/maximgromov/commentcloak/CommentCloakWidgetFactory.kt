package io.github.maximgromov.commentcloak

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent

const val COMMENT_CLOAK_WIDGET_ID: String = "CommentCloakWidget"

class CommentCloakWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = COMMENT_CLOAK_WIDGET_ID

    override fun getDisplayName(): String = "CommentCloak"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = CommentCloakWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

class CommentCloakWidget(project: Project) :
    EditorBasedWidget(project),
    StatusBarWidget.Multiframe,
    StatusBarWidget.TextPresentation {

    override fun ID(): String = COMMENT_CLOAK_WIDGET_ID

    override fun copy(): StatusBarWidget = CommentCloakWidget(project)

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        super<EditorBasedWidget>.install(statusBar)
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(CommentCloakListener.TOPIC, object : CommentCloakListener {
                override fun stateChanged(enabled: Boolean) {
                    statusBar.updateWidget(ID())
                }
            })
    }

    override fun getText(): String =
        if (CommentCloakSettings.getInstance().enabled) "Comments: hidden" else "Comments: shown"

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getTooltipText(): String {
        val shortcut = shortcutText()
        val action = if (CommentCloakSettings.getInstance().enabled) "show" else "hide"
        val suffix = if (shortcut.isEmpty()) "" else " ($shortcut)"
        return "CommentCloak \u2014 click to $action comments$suffix"
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer<MouseEvent> {
        if (!isDisposed && !project.isDisposed) {
            CommentCloakManager.getInstance(project).toggle()
        }
    }

    private fun shortcutText(): String {
        val action = ActionManager.getInstance().getAction("CommentCloak.Toggle") ?: return ""
        val shortcut = action.shortcutSet.shortcuts.firstOrNull { it is KeyboardShortcut } ?: return ""
        return KeymapUtil.getShortcutText(shortcut)
    }
}
