package io.github.maximgromov.commentcloak

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** Icons owned by CommentCloak. Referenced from plugin.xml as `…CommentCloakIcons.Cloak`. */
object CommentCloakIcons {

    /** 16x16 eye-with-slash, used by the toggle action. */
    @JvmField
    val Cloak: Icon = IconLoader.getIcon("/icons/cloak.svg", CommentCloakIcons::class.java)
}
