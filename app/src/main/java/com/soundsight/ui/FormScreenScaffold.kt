package com.soundsight.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The standard scaffold for full-screen forms (Settings, Direction Arrow, Collision Detection,
 * Emergency Profile).
 *
 * ## Why the app bar changes shape at all
 *
 * A collapsing [LargeTopAppBar] is the right default for these long pages - but it starts at
 * three lines tall, and in a **compact-height** window (short landscape, the bar-mounted phone's
 * primary orientation) that initial state spends most of the screen on a decorative expanded
 * title before the rider scrolls anything. Current Material guidance itself moved away from
 * recommending large bars for exactly this reason. Its flexible replacements are still alpha-only
 * on PulsePath's stable dependency surface, so the interim policy is the documented one: keep the
 * large bar where there is room for it, drop to the stable small [TopAppBar] when the window is
 * height-constrained.
 *
 * The threshold (below 480dp of height) matches Google's documented compact height class, and is
 * measured from this component's own constraints - no adaptive dependency, no device-type guess.
 *
 * High-priority actions (back, language globe) render identically in both variants, so nothing
 * about navigation changes across the boundary.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormScreenScaffold(
    title: String,
    onBackContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.background,
    snackbarHost: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    titleTag: String? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isCompactHeight = maxHeight < 480.dp
        val scrollBehavior = if (isCompactHeight) {
            TopAppBarDefaults.pinnedScrollBehavior()
        } else {
            TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        }

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = containerColor,
            snackbarHost = snackbarHost,
            topBar = {
                val titleContent: @Composable () -> Unit = {
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        modifier = if (titleTag != null) {
                            Modifier.testTag(titleTag)
                        } else {
                            Modifier
                        }
                    )
                }
                if (isCompactHeight) {
                    TopAppBar(
                        title = titleContent,
                        navigationIcon = onBackContent,
                        actions = actions,
                        scrollBehavior = scrollBehavior
                    )
                } else {
                    LargeTopAppBar(
                        title = titleContent,
                        navigationIcon = onBackContent,
                        actions = actions,
                        scrollBehavior = scrollBehavior
                    )
                }
            }
        ) { innerPadding ->
            content(innerPadding)
        }
    }
}
