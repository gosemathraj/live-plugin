package liveplugin.toolwindow.addplugin.git


import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import liveplugin.IdeUtil.showErrorDialog
import liveplugin.LivePluginAppComponent.Companion.livePluginsPath
import liveplugin.toolwindow.RefreshPluginsPanelAction
import liveplugin.toolwindow.addplugin.PluginIdValidator
import liveplugin.toolwindow.addplugin.git.AddPluginFromGistAction.Companion.GistUrlValidator.Companion.extractGistIdFrom
import liveplugin.toolwindow.util.createFile
import org.jetbrains.plugins.github.api.GithubApiRequest
import org.jetbrains.plugins.github.api.GithubApiRequestExecutor
import org.jetbrains.plugins.github.api.GithubApiRequests
import org.jetbrains.plugins.github.api.GithubServerPath
import org.jetbrains.plugins.github.api.data.GithubGist
import org.jetbrains.plugins.github.util.GithubSettings
import java.io.IOException
import javax.swing.Icon

class AddPluginFromGistAction: AnAction("Copy from Gist", "Copy from Gist", AllIcons.Vcs.Vendors.Github), DumbAware {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project
        val gistUrl = askUserForGistUrl(event) ?: return

        fetchGistFrom(
            gistUrl,
            event,
            onSuccess = { gist ->
                val newPluginId = askUserNewPluginName(project)
                if (newPluginId != null) {
                    try {
                        createPluginFrom(gist, newPluginId)
                    } catch (e: IOException) {
                        showMessageThatCreatingPluginFailed(e, newPluginId, project)
                    }
                    RefreshPluginsPanelAction.refreshPluginTree()
                }
            },
            onFailure = {
                showMessageThatFetchingGistFailed(it, project)
            }
        )
    }

    private fun showMessageThatCreatingPluginFailed(e: IOException, newPluginId: String?, project: Project?) {
        showErrorDialog(project, "Error adding plugin \"$newPluginId\" to $livePluginsPath", dialogTitle)
        log.info(e)
    }

    private companion object {
        private val log = Logger.getInstance(AddPluginFromGistAction::class.java)
        private const val dialogTitle = "Copy Plugin From Gist"
        private val defaultIcon: Icon? = null

        private fun askUserForGistUrl(event: AnActionEvent): String? =
            Messages.showInputDialog(
                event.project,
                "Enter gist URL:",
                dialogTitle,
                defaultIcon,
                "",
                GistUrlValidator()
            )

        private fun fetchGistFrom(
            gistUrl: String,
            event: AnActionEvent,
            onSuccess: (GithubGist) -> Unit,
            onFailure: (IOException) -> Unit
        ) {
            val project = event.project ?: return
            object: Task.Backgroundable(project, "Fetching Gist", false, ALWAYS_BACKGROUND) {
                private var gist: GithubGist? = null
                private var exception: IOException? = null

                override fun run(indicator: ProgressIndicator) {
                    try {
                        val gistId = extractGistIdFrom(gistUrl)
                        val request = GithubApiRequests.Gists.get(
                            server = GithubServerPath.DEFAULT_SERVER,
                            id = gistId
                        )
                        gist = SimpleExecutor().execute(request)

                    } catch (e: IOException) {
                        exception = e
                    }
                }

                override fun onSuccess() {
                    val e = exception
                    if (e != null) onFailure(e) else onSuccess(gist!!)
                }
            }.queue()
        }

        private fun askUserNewPluginName(project: Project?): String? =
            Messages.showInputDialog(project, "Enter new plugin name:", dialogTitle, defaultIcon, "", PluginIdValidator())

        private fun createPluginFrom(gist: GithubGist, pluginId: String?) =
            gist.files.forEach { gistFile ->
                createFile("$livePluginsPath/$pluginId", gistFile.filename, gistFile.content)
            }

        private fun showMessageThatFetchingGistFailed(e: IOException?, project: Project?) {
            showErrorDialog(project, "Failed to fetch gist", dialogTitle)
            log.info(e!!)
        }

        private class SimpleExecutor: GithubApiRequestExecutor.Base(GithubSettings.getInstance()) {
            override fun <T> execute(indicator: ProgressIndicator, request: GithubApiRequest<T>): T =
                createRequestBuilder(request).execute(request, indicator)
        }

        private class GistUrlValidator: InputValidatorEx {
            private var errorText: String? = null

            override fun checkInput(inputString: String): Boolean {
                val isValid = inputString.lastIndexOf('/') != -1
                errorText = if (isValid) null else "Gist URL should have at least one '/' symbol"
                return isValid
            }

            override fun getErrorText(inputString: String) = errorText

            override fun canClose(inputString: String) = true

            companion object {
                fun extractGistIdFrom(gistUrl: String): String {
                    val i = gistUrl.lastIndexOf('/')
                    return gistUrl.substring(i + 1)
                }
            }
        }
    }
}
