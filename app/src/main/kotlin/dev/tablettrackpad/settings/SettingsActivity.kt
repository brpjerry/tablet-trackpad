package dev.tablettrackpad.settings

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.tablettrackpad.R
import dev.tablettrackpad.service.TrackpadService
import dev.tablettrackpad.service.TrackpadTileService

/** Hosts setup on first launch, and the settings afterwards. */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = TrackpadSettings(this)
        setContent {
            TrackpadTheme {
                // The setup step on screen, or -1 for the settings.
                var setupStep by rememberSaveable { mutableIntStateOf(if (settings.onboardingDone) -1 else 0) }
                if (setupStep < 0) {
                    SettingsScreen(settings, onRunSetup = { fromService ->
                        setupStep = if (fromService) SetupStep.entries.indexOf(SetupStep.SERVICE) else 0
                    })
                } else {
                    BackHandler(enabled = setupStep > 0 || settings.onboardingDone) {
                        setupStep = if (setupStep > 0) setupStep - 1 else -1
                    }
                    SetupScreen(
                        settings,
                        step = setupStep,
                        onStepChange = { setupStep = it },
                        onFinish = {
                            settings.onboardingDone = true
                            settings.setupReturnAt = 0L
                            setupStep = -1
                        },
                    )
                }
            }
        }
    }
}

/** Material 3 Expressive, in the system's dynamic colors. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TrackpadTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
}

/** Whether the user has switched the service on in Accessibility settings. */
fun isServiceEnabled(context: Context): Boolean {
    val manager = context.getSystemService(AccessibilityManager::class.java)
    return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
        it.resolveInfo.serviceInfo.packageName == context.packageName &&
            it.resolveInfo.serviceInfo.name == TrackpadService::class.java.name
    }
}

/**
 * Accessibility settings. Apps cannot deep-link to their own service page (that intent is
 * system-only), but AOSP-based Settings scroll to and highlight the entry named by the
 * fragment-args extras.
 */
fun openAccessibilitySettings(context: Context) {
    val key = ComponentName(context, TrackpadService::class.java).flattenToString()
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .putExtra(EXTRA_FRAGMENT_ARG_KEY, key)
        .putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, key) })
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

/** App info, where Android hides "Allow restricted settings" for sideloaded apps. */
fun openAppInfo(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/**
 * Whether Android likely blocks the accessibility switch as a restricted setting: it does for apps
 * installed from a downloaded or local APK file, but not from a store or over adb.
 */
fun isRestrictedInstall(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val source = try {
        context.packageManager.getInstallSourceInfo(context.packageName).packageSource
    } catch (_: PackageManager.NameNotFoundException) {
        return false
    }
    return source == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE ||
        source == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
}

fun isSamsung() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

fun isIgnoringBatteryOptimizations(context: Context) =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

/**
 * Asks to exempt the app from battery optimization. Galaxy devices otherwise may put the app to
 * sleep, which switches the accessibility service off.
 */
@SuppressLint("BatteryLife") // An accessibility tool the user installs and keeps running on purpose.
fun requestIgnoreBatteryOptimizations(context: Context) {
    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", context.packageName, null))
    try {
        context.startActivity(request)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

/** Shows the system dialog that adds the Quick Settings tile; [onResult] gets a StatusBarManager result code. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun requestAddTile(context: Context, onResult: (Int) -> Unit) {
    context.getSystemService(StatusBarManager::class.java).requestAddTileService(
        ComponentName(context, TrackpadTileService::class.java),
        context.getString(R.string.tile_label),
        Icon.createWithResource(context, R.drawable.ic_tile),
        context.mainExecutor,
        onResult,
    )
}

private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
private const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"
