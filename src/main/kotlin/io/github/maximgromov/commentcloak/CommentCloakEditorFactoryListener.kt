package io.github.maximgromov.commentcloak

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener

/** Installs the folding machinery on every newly created main editor. */
class CommentCloakEditorFactoryListener : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val project = editor.project ?: return
        if (project.isDisposed) return
        if (!CommentFolder.isSupported(editor)) return

        CommentFolder.install(editor, project)
        if (!CommentCloakSettings.getInstance().enabled) return

        ApplicationManager.getApplication().invokeLater({
            if (!editor.isDisposed && !project.isDisposed) {
                CommentCloakManager.getInstance(project).applyTo(editor)
            }
        }, project.disposed)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        CommentFolder.release(event.editor)
    }
}
