package com.soundsight.ui.hud

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.soundsight.core.SoundSightStrings
import kotlinx.coroutines.delay

/** Whether the app currently holds RECORD_AUDIO. */
fun hasMicPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

/**
 * The microphone request, as a Material You dialog over the HUD.
 *
 * This used to be a full-screen step in a four-screen onboarding flow that a rider had to walk
 * through before ever seeing the app. That ordering was backwards: the permission prompt is asking
 * for something whose purpose only makes sense once you have seen the screen it powers. Now the
 * app opens straight onto the HUD and this arrives on top of it, so the rider is looking at the
 * thing the microphone is for while deciding whether to grant it.
 *
 * Two states, one dialog. Before the system prompt has been permanently declined it offers to ask;
 * afterwards Android will not show the system prompt again at all, so the only honest action left
 * is a link into app settings - and the dialog says so rather than firing a request that silently
 * does nothing.
 */
@Composable
fun MicPermissionPrompt(
    onGranted: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val strings = SoundSightStrings.current
    var permanentlyDenied by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val audioGranted = results[Manifest.permission.RECORD_AUDIO] ?: hasMicPermission(context)
        if (audioGranted) {
            onGranted()
        } else {
            // No rationale offered means "don't ask again" was chosen (or the OS blocked it), and
            // launching the request again would be a button that does nothing.
            val activity = context as? Activity
            val showRationale = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.RECORD_AUDIO
            )
            if (!showRationale) permanentlyDenied = true
        }
    }

    // Once the rider has been sent to app settings, the grant happens outside this process and
    // nothing calls back. Polling only while the dialog is up - it leaves composition the moment
    // permission arrives - is cheaper than wiring a lifecycle observer for a one-shot case.
    LaunchedEffect(permanentlyDenied) {
        if (!permanentlyDenied) return@LaunchedEffect
        while (true) {
            delay(700L)
            if (hasMicPermission(context)) {
                onGranted()
                return@LaunchedEffect
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (permanentlyDenied) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                contentDescription = null
            )
        },
        title = {
            Text(
                text = if (permanentlyDenied) {
                    strings.onboardingPermDeniedTitle
                } else {
                    strings.micPermissionTitle
                }
            )
        },
        text = {
            Text(
                text = if (permanentlyDenied) {
                    strings.onboardingPermDeniedBody
                } else {
                    strings.micPermissionBody
                }
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (permanentlyDenied) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            )
                        )
                    } else {
                        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            // Asked in the same batch rather than as a second dialog: the
                            // foreground service that does the listening cannot run without a
                            // notification, so these two are one decision, not two.
                            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        launcher.launch(permissions.toTypedArray())
                    }
                },
                modifier = Modifier.testTag("btn_grant_permissions")
            ) {
                Text(
                    text = if (permanentlyDenied) {
                        strings.onboardingOpenSettingsButton
                    } else {
                        strings.onboardingPermGrantButton
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = strings.micPermissionLater)
            }
        },
        modifier = Modifier.testTag("dialog_mic_permission")
    )
}
