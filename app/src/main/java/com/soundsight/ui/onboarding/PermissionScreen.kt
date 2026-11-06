package com.soundsight.ui.onboarding

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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.soundsight.audio.ListenerService
import com.soundsight.core.SoundSightStrings
import com.soundsight.ui.theme.Motion
import com.soundsight.ui.theme.PillShape
import kotlinx.coroutines.delay

private fun checkHasMicPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

@Composable
fun PermissionScreen(
    /**
     * Continues into the HUD after an actual RECORD_AUDIO grant (the grant handler starts
     * monitoring on its way past). The name deliberately stops at "continue": declining below
     * also continues, just without a grant.
     */
    onContinue: () -> Unit,
    /**
     * The decline path ("Not now"): marks onboarding complete and enters a degraded HUD with
     * no microphone access. Without it a rider who denies the mic is trapped on this screen -
     * [com.soundsight.MainActivity] routes back to onboarding until completion is stored - which
     * is unacceptable in an app whose riders cannot hear what it is warning them about.
     */
    onDeclined: () -> Unit,
    modifier: Modifier = Modifier,
    rationaleIllustration: @Composable () -> Unit = { RationalePlaceholderIllustration() },
    grantButtonContent: @Composable () -> Unit = {
        Text(
            text = SoundSightStrings.current.onboardingPermGrantButton,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    },
    settingsButtonContent: @Composable () -> Unit = {
        Text(
            text = SoundSightStrings.current.onboardingOpenSettingsButton,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
) {
    val context = LocalContext.current
    val strings = SoundSightStrings.current
    var permanentlyDenied by remember { mutableStateOf(false) }

    fun handleGrantSuccess() {
        if (!ListenerService.isRunning.value) {
            ListenerService.start(context)
        }
        onContinue()
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val audioGranted = results[Manifest.permission.RECORD_AUDIO] ?: checkHasMicPermission(context)
        if (audioGranted) {
            handleGrantSuccess()
        } else {
            val activity = context as? Activity
            val showRationale = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.RECORD_AUDIO
            )
            if (!showRationale) {
                permanentlyDenied = true
            }
        }
    }

    // Polling when user is sent to OS App Settings
    LaunchedEffect(permanentlyDenied) {
        if (!permanentlyDenied) return@LaunchedEffect
        while (true) {
            delay(700L)
            if (checkHasMicPermission(context)) {
                handleGrantSuccess()
                return@LaunchedEffect
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .testTag("onboarding_permission_screen"),
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                // Scrollable so the fixed SpaceBetween composition cannot clip in landscape or
                // at large font scales: when the content fits, the column still fills the screen
                // and SpaceBetween distributes as before; when it does not, it scrolls instead.
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Main Content Area
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                rationaleIllustration()

                AnimatedContent(
                    targetState = permanentlyDenied,
                    transitionSpec = {
                        fadeIn(Motion.fastEffects()) togetherWith fadeOut(Motion.fastEffects())
                    },
                    label = "permission_title_transition"
                ) { isDenied ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (isDenied) strings.onboardingPermDeniedTitle else strings.permTitle,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("onboarding_permission_title")
                        )

                        Text(
                            text = if (isDenied) strings.onboardingPermDeniedBody else strings.permBody,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("onboarding_permission_body")
                        )
                    }
                }

                // Educational cards explaining rationale
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        PermissionBenefitRow(
                            icon = Icons.Rounded.GraphicEq,
                            title = strings.onboardingCard1Title,
                            description = strings.onboardingCard1Body
                        )
                        PermissionBenefitRow(
                            icon = Icons.Rounded.NotificationsActive,
                            title = strings.onboardingPrimerTitle,
                            description = strings.aboutLatencyNote
                        )
                    }
                }
            }

            // Bottom CTA
            Button(
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
                            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        launcher.launch(permissions.toTypedArray())
                    }
                },
                shape = PillShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("onboarding_btn_grant_perm")
            ) {
                if (permanentlyDenied) settingsButtonContent() else grantButtonContent()
            }

            // The exit. Always available, including after a denial: refusing the microphone must
            // never hold a rider in front of this screen - they get the HUD degraded, and can
            // grant later from Settings or when they next start listening. Text-styled rather
            // than filled so it reads as the quieter path next to the CTA above it.
            TextButton(
                onClick = onDeclined,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("onboarding_btn_not_now")
            ) {
                Text(
                    text = strings.micPermissionLater,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PermissionBenefitRow(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun RationalePlaceholderIllustration() {
    Box(
        modifier = Modifier
            .size(130.dp)
            .background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Mic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(64.dp)
        )
    }
}
