package io.github.maximgromov.commentcloak

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware

/** Global on/off switch for CommentCloak. */
class ToggleCommentCloakAction : ToggleAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent): Boolean = CommentCloakSettings.getInstance().enabled

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project
        if (project != null && !project.isDisposed) {
            CommentCloakManager.getInstance(project).setEnabled(state)
        } else {
            CommentCloakSettings.getInstance().enabled = state
            ApplicationManager.getApplication().messageBus
                .syncPublisher(CommentCloakListener.TOPIC)
                .stateChanged(state)
        }
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        val enabled = CommentCloakSettings.getInstance().enabled
        e.presentation.text = if (enabled) "Show Comments" else "Hide Comments"
        e.presentation.description = DESCRIPTION
    }

    private companion object {
        const val DESCRIPTION =
            "CommentCloak: visually hide code comments \u2014 files are never modified"
    }
}
