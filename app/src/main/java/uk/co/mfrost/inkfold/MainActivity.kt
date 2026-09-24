package uk.co.mfrost.inkfold

import android.accounts.Account
import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import uk.co.mfrost.inkfold.ui.AppViewModel
import uk.co.mfrost.inkfold.ui.EditorScreen
import uk.co.mfrost.inkfold.ui.NotebookListScreen
import uk.co.mfrost.inkfold.ui.theme.InkfoldTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            InkfoldTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot()
                }
            }
        }
    }
}

/**
 * Drive access is granted in two steps, since play-services-auth 22 dropped the
 * old one-shot GoogleSignIn flow: the system account picker chooses a Google
 * account, then [AppViewModel.driveSync]'s AuthorizationClient requests
 * drive.file consent for it (only shown if not already granted).
 */
@Composable
private fun AppRoot(vm: AppViewModel = viewModel()) {
    val scope = rememberCoroutineScope()
    var pendingAccount by remember { mutableStateOf<Account?>(null) }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val account = pendingAccount
        pendingAccount = null
        when {
            account == null -> Unit
            result.resultCode != Activity.RESULT_OK -> vm.onDriveConsentDenied()
            vm.driveSync.finishAuthorization(account, result.data) -> vm.onDriveAuthorized()
            else -> vm.onDriveAuthorizationFailed()
        }
    }

    val accountPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val account = vm.driveSync.accountFromPickerResult(result.data)
        if (account == null) {
            vm.onDriveAuthorizationFailed(cancelled = true)
            return@rememberLauncherForActivityResult
        }
        pendingAccount = account
        scope.launch {
            val consentIntent = try {
                vm.driveSync.authorize(account)
            } catch (e: Exception) {
                pendingAccount = null
                vm.onDriveAuthorizationFailed(e)
                return@launch
            }
            if (consentIntent != null) {
                consentLauncher.launch(IntentSenderRequest.Builder(consentIntent.intentSender).build())
            } else {
                pendingAccount = null
                vm.onDriveAuthorized()
            }
        }
    }

    val launchSignIn = { accountPickerLauncher.launch(vm.driveSync.accountPickerIntent()) }

    if (vm.current == null) {
        NotebookListScreen(
            vm = vm,
            onSignIn = launchSignIn,
        )
    } else {
        EditorScreen(vm = vm)
    }
}
