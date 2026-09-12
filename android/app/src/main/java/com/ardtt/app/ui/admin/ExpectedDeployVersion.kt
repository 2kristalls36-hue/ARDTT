package com.ardtt.app.ui.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ardtt.app.deploy.DeployBundle
import com.ardtt.app.deploy.DeployVersionCatalog

/**
 * Live expected stack version for server cards.
 *
 * [DeployVersionCatalog.refresh] runs in [com.ardtt.app.ArdttApp] after first
 * composition, so a one-shot `remember { expectedVersion() }` stays on the
 * APK fallback (1.0.52) even after GitHub already advertised a newer stack.
 */
@Composable
internal fun rememberExpectedDeployVersion(): String {
    val context = LocalContext.current
    val catalogLatest by DeployVersionCatalog.latest.collectAsStateWithLifecycle()
    val bundled = remember(context) { DeployBundle.offlineFallback(context) }
    val seeded = remember(context) { DeployVersionCatalog.expectedVersion(context) }
    LaunchedEffect(Unit) {
        runCatching { DeployVersionCatalog.refresh(context.applicationContext) }
    }
    return DeployVersionCatalog.resolvedExpected(
        catalogLatest,
        DeployBundle.maxVersion(seeded, bundled),
    )
}
