package com.soundsight.ui.onboarding

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.soundsight.core.AppLanguage
import com.soundsight.core.SoundSightStrings

object OnboardingRoutes {
    const val LANGUAGE = "onboarding_language"
    const val CAROUSEL = "onboarding_carousel"
    const val DISCLAIMER = "onboarding_disclaimer"
    const val PERMISSION = "onboarding_permission"
}

fun NavGraphBuilder.onboardingNavGraph(
    navController: NavHostController,
    viewModel: OnboardingViewModel,
    currentLanguage: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    onOnboardingComplete: () -> Unit
) {
    composable(OnboardingRoutes.LANGUAGE) {
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is OnboardingEffect.NavigateToCarousel -> {
                        navController.navigate(OnboardingRoutes.CAROUSEL)
                    }
                    else -> {}
                }
            }
        }

        LanguageScreen(
            selectedLanguage = uiState.selectedLanguage,
            onLanguageSelected = { lang ->
                onLanguageChange(lang)
                viewModel.onEvent(OnboardingEvent.SelectLanguage(lang))
            },
            onContinue = {
                viewModel.onEvent(OnboardingEvent.ConfirmLanguage)
            }
        )
    }

    composable(OnboardingRoutes.CAROUSEL) {
        val strings = SoundSightStrings.current

        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is OnboardingEffect.NavigateToDisclaimer -> {
                        navController.navigate(OnboardingRoutes.DISCLAIMER)
                    }
                    else -> {}
                }
            }
        }

        val slides = listOf(
            PagerSlide(
                headline = strings.onboardingCard1Title,
                description = strings.onboardingCard1Body,
                illustration = { TrafficListeningIllustration() }
            ),
            PagerSlide(
                headline = strings.onboardingCard2Title,
                description = strings.onboardingCard2Body,
                illustration = { AudioAiPrivacyIllustration() }
            ),
            PagerSlide(
                headline = strings.onboardingCard3Title,
                description = strings.onboardingCard3Body,
                highlight = strings.onboardingCard3Highlight,
                illustration = { SafetyAwarenessIllustration() }
            )
        )

        IntroCarouselScreen(
            slides = slides,
            onSkip = {
                viewModel.onEvent(OnboardingEvent.SkipCarousel)
            },
            onFinish = {
                viewModel.onEvent(OnboardingEvent.SkipCarousel)
            }
        )
    }

    composable(OnboardingRoutes.DISCLAIMER) {
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is OnboardingEffect.NavigateToPermission -> {
                        navController.navigate(OnboardingRoutes.PERMISSION)
                    }
                    else -> {}
                }
            }
        }

        DisclaimerScreen(
            onAccept = {
                viewModel.onEvent(OnboardingEvent.AcceptDisclaimer)
            }
        )
    }

    composable(OnboardingRoutes.PERMISSION) {
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is OnboardingEffect.NavigateToHud -> {
                        onOnboardingComplete()
                    }
                    else -> {}
                }
            }
        }

        PermissionScreen(
            onContinue = {
                viewModel.onEvent(OnboardingEvent.MicPermissionResult(true))
            },
            onDeclined = {
                viewModel.onEvent(OnboardingEvent.DeclineMicPermission)
            }
        )
    }
}
