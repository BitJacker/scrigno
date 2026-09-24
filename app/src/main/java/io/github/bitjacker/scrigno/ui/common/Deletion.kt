package io.github.bitjacker.scrigno.ui.common

import android.app.Activity
import android.content.IntentSender
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/** A system confirmation dialog (Android 11+) that the UI has to show. */
data class SystemDeleteRequest(val intentSender: IntentSender, val id: Long = System.nanoTime())

/**
 * Shows Android's own "Allow Scrigno to delete these photos?" dialog when [request] is set, then
 * reports whether the user accepted.
 */
@Composable
fun SystemDeleteLauncher(
    request: SystemDeleteRequest?,
    onLaunched: () -> Unit,
    onResult: (accepted: Boolean) -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        onResult(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(request?.id) {
        if (request != null) {
            onLaunched()
            launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        }
    }
}
