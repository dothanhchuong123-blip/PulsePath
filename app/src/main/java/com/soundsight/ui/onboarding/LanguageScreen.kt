package com.soundsight.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.soundsight.core.AppLanguage
import com.soundsight.core.SoundSightStrings
import com.soundsight.ui.theme.Motion
import com.soundsight.ui.theme.PillShape

@Composable
fun LanguageScreen(
    selectedLanguage: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    headerIllustration: @Composable () -> Unit = { LanguageGlobeIllustration() },
    continueButtonContent: @Composable () -> Unit = {
        Text(
            text = SoundSightStrings.current.onboardingContinue,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
) {
    val strings = SoundSightStrings.current

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .testTag("onboarding_language_screen"),
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                // Scrollable so the fixed SpaceBetween composition cannot clip in landscape or at
                // large font scales; when content fits, SpaceBetween still fills and distributes.
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Bar placeholder matching IntroCarouselScreen's 48dp skip bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            )

            // Header Section
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                headerIllustration()

                val isViSelected = selectedLanguage == AppLanguage.VI

                val viOffsetY by animateDpAsState(
                    targetValue = if (isViSelected) 0.dp else 36.dp,
                    animationSpec = Motion.dpSpatial(),
                    label = "vi_offset_y"
                )
                val enOffsetY by animateDpAsState(
                    targetValue = if (isViSelected) 36.dp else 0.dp,
                    animationSpec = Motion.dpSpatial(),
                    label = "en_offset_y"
                )

                val viScale by animateFloatAsState(
                    targetValue = if (isViSelected) 1f else 0.72f,
                    animationSpec = Motion.defaultSpatial(),
                    label = "vi_scale"
                )
                val enScale by animateFloatAsState(
                    targetValue = if (isViSelected) 0.72f else 1f,
                    animationSpec = Motion.defaultSpatial(),
                    label = "en_scale"
                )

                val viColor by animateColorAsState(
                    targetValue = if (isViSelected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.primary,
                    animationSpec = Motion.defaultEffects(),
                    label = "vi_color"
                )
                val enColor by animateColorAsState(
                    targetValue = if (isViSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                    animationSpec = Motion.defaultEffects(),
                    label = "en_color"
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Text(
                        text = "Chọn Ngôn Ngữ",
                        style = MaterialTheme.typography.headlineMedium,
                        color = viColor,
                        fontWeight = if (isViSelected) FontWeight.Bold else FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .graphicsLayer {
                                translationY = viOffsetY.toPx()
                                scaleX = viScale
                                scaleY = viScale
                                transformOrigin = TransformOrigin(0.5f, 0f)
                            }
                            .testTag("onboarding_title_vi")
                    )

                    Text(
                        text = "Choose Your Language",
                        style = MaterialTheme.typography.headlineMedium,
                        color = enColor,
                        fontWeight = if (isViSelected) FontWeight.SemiBold else FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .graphicsLayer {
                                translationY = enOffsetY.toPx()
                                scaleX = enScale
                                scaleY = enScale
                                transformOrigin = TransformOrigin(0.5f, 0f)
                            }
                            .testTag("onboarding_title_en")
                    )
                }

                Text(
                    text = strings.onboardingLanguageSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("onboarding_language_subtitle")
                )
            }

            // Language Selector Options
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                LanguageOptionCard(
                    title = "Tiếng Việt",
                    subtitle = "Vietnamese",
                    isSelected = selectedLanguage == AppLanguage.VI,
                    onClick = { onLanguageSelected(AppLanguage.VI) },
                    testTag = "onboarding_lang_vi"
                )

                LanguageOptionCard(
                    title = "English",
                    subtitle = "Tiếng Anh",
                    isSelected = selectedLanguage == AppLanguage.EN,
                    onClick = { onLanguageSelected(AppLanguage.EN) },
                    testTag = "onboarding_lang_en"
                )
            }

            // Bottom CTA
            Button(
                onClick = onContinue,
                shape = PillShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("onboarding_btn_continue")
            ) {
                continueButtonContent()
            }
        }
    }
}

@Composable
private fun LanguageOptionCard(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = Motion.defaultEffects(),
        label = "lang_card_container"
    )

    val contentColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = Motion.defaultEffects(),
        label = "lang_card_content"
    )

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.75f)
                )
            }

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = CircleShape
                        )
                )
            }
        }
    }
}

