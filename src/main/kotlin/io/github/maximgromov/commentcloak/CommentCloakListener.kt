package io.github.maximgromov.commentcloak

import com.intellij.util.messages.Topic

/** Application-wide notification that the CommentCloak toggle changed. */
interface CommentCloakListener {

    fun stateChanged(enabled: Boolean)

    companion object {
        @JvmField
        val TOPIC: Topic<CommentCloakListener> =
            Topic.create("CommentCloak state changed", CommentCloakListener::class.java)
    }
}
