package com.soundsight.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.GraphicEq
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.soundsight.core.SoundSightStrings
import com.soundsight.ui.theme.Motion
import com.soundsight.ui.theme.PillShape
import kotlinx.coroutines.launch

data class PagerSlide(
    val headline: String,
    val description: String,
    /**
     * A closing sentence appended to [description] and drawn in the error colour, bold.
     *
     * Reserved for the safety line a rider has to leave onboarding still holding. It stays inside
     * the paragraph rather than becoming a separate banner - it reads as the end of the thought
     * it belongs to, and colour alone is enough to stop the eye there. Null on slides that have
     * no such line; at most one slide should set it, or the emphasis stops meaning anything.
     */
    val highlight: String? = null,
    val illustration: @Composable () -> Unit = { SlidePlaceholderIllustration() }
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IntroCarouselScreen(
    slides: List<PagerSlide>,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    skipButtonContent: @Composable () -> Unit = {
        Text(
            text = SoundSightStrings.current.onboardingSkipButton,
            style = MaterialTheme.typography.labelLarge
        )
    },
    nextButtonContent: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = SoundSightStrings.current.onboardingNextButton,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
    },
    finishButtonContent: @Composable () -> Unit = {
        Text(
            text = SoundSightStrings.current.onboardingGetStartedButton,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
) {
    val pagerState = rememberPagerState(pageCount = { slides.size })
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == slides.size - 1

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .testTag("onboarding_carousel_screen"),
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
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Navigation Action (Skip)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AnimatedVisibility(
                    visible = !isLastPage,
                    enter = fadeIn(Motion.fastEffects()),
                    exit = fadeOut(Motion.fastEffects())
                ) {
                    TextButton(
                        onClick = onSkip,
                        modifier = Modifier.testTag("onboarding_btn_skip")
                    ) {
                        skipButtonContent()
                    }
                }
            }

            // Pager content
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("onboarding_horizontal_pager")
            ) { pageIndex ->
                val slide = slides.getOrNull(pageIndex) ?: return@HorizontalPager
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    slide.illustration()

                    Spacer(modifier = Modifier.height(32.dp))

                    Text(
                        text = slide.headline,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("onboarding_slide_headline_$pageIndex")
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    val bodyColor = MaterialTheme.colorScheme.onSurfaceVariant
                    // The brand primary, not the error role: this is the app's own accent - the
                    // same colour as the pager's active dot and the Get Started pill - so the
                    // sentence reads as emphasis inside the paragraph rather than as a warning
                    // pasted on top of it.
                    val highlightColor = MaterialTheme.colorScheme.primary
                    val body = remember(slide.description, slide.highlight, highlightColor) {
                        buildAnnotatedString {
                            append(slide.description)
                            slide.highlight?.let { highlight ->
                                append(" ")
                                withStyle(
                                    SpanStyle(
                                        color = highlightColor,
                                        fontWeight = FontWeight.Bold
                                    )
                                ) {
                                    append(highlight)
                                }
                            }
                        }
                    }

                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = bodyColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("onboarding_slide_description_$pageIndex")
                    )
                }
            }

            // Bottom Bar: Dots & Next / Finish CTA
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Page Indicator Dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag("onboarding_pager_indicator")
                ) {
                    repeat(slides.size) { index ->
                        val isSelected = pagerState.currentPage == index
                        val width by animateDpAsState(
                            targetValue = if (isSelected) 24.dp else 8.dp,
                            animationSpec = Motion.dpSpatial(),
                            label = "indicator_dot_width"
                        )
                        val color by animateColorAsState(
                            targetValue = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            animationSpec = Motion.fastEffects(),
                            label = "indicator_dot_color"
                        )

                        Box(
                            modifier = Modifier
                                .height(8.dp)
                                .width(width)
                                .clip(PillShape)
                                .background(color)
                        )
                    }
                }

                // Next / Get Started Button
                Button(
                    onClick = {
                        if (isLastPage) {
                            onFinish()
                        } else {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    },
                    shape = PillShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .height(52.dp)
                        .testTag(if (isLastPage) "onboarding_btn_get_started" else "onboarding_btn_next")
                ) {
                    if (isLastPage) finishButtonContent() else nextButtonContent()
                }
            }
        }
    }
}

@Composable
fun SlidePlaceholderIllustration(modifier: Modifier = Modifier) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(230.dp)
            .clip(MaterialTheme.shapes.extraLarge)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                modifier = Modifier.size(72.dp)
            )
        }
    }
}
