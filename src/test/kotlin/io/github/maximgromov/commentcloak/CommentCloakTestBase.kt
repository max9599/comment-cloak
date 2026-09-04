package io.github.maximgromov.commentcloak

import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ThrowableRunnable
import com.intellij.util.xmlb.XmlSerializerUtil
import java.util.EnumSet

/** Snapshots the application-level settings so tests cannot leak state into each other. */
abstract class CommentCloakTestBase : BasePlatformTestCase() {

    protected val settings: CommentCloakSettings get() = CommentCloakSettings.getInstance()

    private val backup = CommentCloakSettings()

    /**
     * The platform test framework fails a test whenever anything logs an ERROR while it runs.
     * On CI the full WebStorm distribution occasionally logs errors from services that have
     * nothing to do with this plugin (they are absent locally and appear in unrelated tests).
     * Keep failing on errors that originate in our own code; only log the foreign ones.
     */
    override fun runTestRunnable(testRunnable: ThrowableRunnable<Throwable>) {
        LoggedErrorProcessor.executeWith<Throwable>(ForeignErrorTolerantProcessor) {
            super.runTestRunnable(testRunnable)
        }
    }

    private object ForeignErrorTolerantProcessor : LoggedErrorProcessor() {
        private const val OUR_PACKAGE = "io.github.maximgromov"

        override fun processError(
            category: String,
            message: String,
            details: Array<String>,
            t: Throwable?
        ): Set<Action> {
            val ours = category.contains(OUR_PACKAGE) ||
                t?.stackTrace?.any { it.className.startsWith(OUR_PACKAGE) } == true
            return if (ours) Action.ALL else EnumSet.of(Action.LOG)
        }
    }

    override fun setUp() {
        super.setUp()
        XmlSerializerUtil.copyBean(settings, backup)
        backup.keepPatterns = ArrayList(settings.keepPatterns)
        resetToDefaults()
    }

    override fun tearDown() {
        try {
            settings.loadState(backup)
            settings.keepPatterns = ArrayList(backup.keepPatterns)
            settings.invalidateCache()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun resetToDefaults() {
        settings.enabled = false
        settings.hideLineComments = true
        settings.hideBlockComments = true
        settings.hideDocComments = true
        settings.hideHtmlComments = true
        settings.minLength = 0
        settings.foldWholeLines = true
        settings.placeholder = CommentCloakSettings.DEFAULT_PLACEHOLDER
        settings.markerStyle = MarkerStyle.PILL
        settings.partialKeep = true
        settings.keepPatterns = CommentCloakSettings.defaultKeepPatterns()
        settings.invalidateCache()
    }
}
