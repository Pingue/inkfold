package uk.co.mfrost.inkfold.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.core.content.pm.PackageInfoCompat

private const val REPO_URL = "https://github.com/Pingue/inkfold"

/** Version info as recorded in the installed package, so it tracks the CI-derived version. */
private data class AppVersion(val name: String, val code: Long)

private fun Context.appVersion(): AppVersion = runCatching {
    val info = packageManager.getPackageInfo(packageName, 0)
    AppVersion(info.versionName ?: "unknown", PackageInfoCompat.getLongVersionCode(info))
}.getOrElse { AppVersion("unknown", 0) }

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val version = remember { context.appVersion() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About Inkfold") },
        text = {
            Column {
                Text("Version ${version.name} (build ${version.code})", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "A pen-first note-taking app with Google Drive sync.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("MIT licence \u00b7 $REPO_URL", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = { uriHandler.openUri(REPO_URL) }) { Text("Open GitHub") } },
    )
}
