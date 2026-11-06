package com.soundsight.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsBike
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.soundsight.core.PrivacyPolicyContent
import com.soundsight.core.PrivacyPolicySection
import com.soundsight.core.SoundSightStrings
import com.soundsight.ui.theme.PillShape

@Composable
fun DisclaimerScreen(
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
    mascotIllustration: @Composable () -> Unit = { MascotPlaceholderIllustration() },
    acceptButtonContent: @Composable () -> Unit = {
        Text(
            text = SoundSightStrings.current.disclaimerAcceptButton,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
) {
    val strings = SoundSightStrings.current
    var showPrivacyPolicy by remember { mutableStateOf(false) }

    if (showPrivacyPolicy) {
        PrivacyPolicyDialog(
            title = strings.privacyPolicyTitle,
            content = strings.privacyPolicyContent,
            onDismiss = { showPrivacyPolicy = false }
        )
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .testTag("onboarding_disclaimer_screen"),
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(12.dp))

                mascotIllustration()

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = strings.disclaimerTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("onboarding_disclaimer_title")
                )

                Spacer(modifier = Modifier.height(20.dp))

                Card(
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("onboarding_disclaimer_card")
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        DisclaimerBulletRow(
                            icon = Icons.Rounded.Info,
                            text = strings.disclaimerBullet1,
                            testTag = "onboarding_disclaimer_bullet_1"
                        )

                        DisclaimerBulletRow(
                            icon = Icons.AutoMirrored.Rounded.DirectionsBike,
                            text = strings.disclaimerBullet2,
                            testTag = "onboarding_disclaimer_bullet_2"
                        )

                        DisclaimerLinkedBulletRow(
                            icon = Icons.Rounded.Gavel,
                            prefix = strings.disclaimerBullet3Prefix,
                            linkText = strings.disclaimerBullet3Link,
                            suffix = strings.disclaimerBullet3Suffix,
                            testTag = "onboarding_disclaimer_bullet_3",
                            linkTestTag = "onboarding_disclaimer_terms_link",
                            onLinkClick = { showPrivacyPolicy = true }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            Button(
                onClick = onAccept,
                shape = PillShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("onboarding_btn_accept_disclaimer")
            ) {
                acceptButtonContent()
            }
        }
    }
}

@Composable
private fun PrivacyPolicyDialog(
    title: String,
    content: PrivacyPolicyContent,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("privacy_policy_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = SoundSightStrings.current.dismissLabel
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = content.introduction,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    content.sections.forEach { section ->
                        PrivacyPolicySectionCard(section = section)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onDismiss,
                    shape = PillShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("privacy_policy_dismiss_button")
                ) {
                    Text(
                        text = SoundSightStrings.current.dismissLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun PrivacyPolicySectionCard(section: PrivacyPolicySection) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("privacy_policy_section")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = section.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = section.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DisclaimerBulletRow(
    icon: ImageVector,
    text: String,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp)
            )
        }

        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DisclaimerLinkedBulletRow(
    icon: ImageVector,
    prefix: String,
    linkText: String,
    suffix: String,
    testTag: String,
    linkTestTag: String,
    onLinkClick: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val annotatedString = remember(prefix, linkText, suffix, primaryColor) {
        buildAnnotatedString {
            append(prefix)
            withLink(
                LinkAnnotation.Clickable(
                    tag = "terms_link",
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = primaryColor,
                            textDecoration = TextDecoration.Underline
                        )
                    ),
                    linkInteractionListener = { onLinkClick() }
                )
            ) {
                append(linkText)
            }
            append(suffix)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp)
            )
        }

        Text(
            text = annotatedString,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .testTag(linkTestTag)
        )
    }
}

@Composable
fun MascotPlaceholderIllustration() {
    Box(
        modifier = Modifier
            .size(130.dp)
            .background(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Security,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(68.dp)
        )
    }
}

