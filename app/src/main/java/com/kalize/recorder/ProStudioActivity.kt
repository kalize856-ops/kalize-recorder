package com.kalize.recorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class ProStudioActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("kalize_settings", MODE_PRIVATE) }
    private var rewardedAd: RewardedAd? = null
    private var adLoading = false
    private var adMessage by mutableStateOf("Loading a reward ad for Pro capture options…")

    private var requestingInternalAudio = false
    private var permissionGrantedAction: (() -> Unit)? = null
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (requestingInternalAudio) {
                prefs.edit().putBoolean("internal_audio_enabled", true).putBoolean("microphone_enabled", false).apply()
            } else {
                prefs.edit().putBoolean("microphone_enabled", true).putBoolean("internal_audio_enabled", false).apply()
            }
            permissionGrantedAction?.invoke()
        }
        permissionGrantedAction = null
        requestingInternalAudio = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pro capture options are unlocked by a rewarded ad for this visit only.
        // Reset any previously selected Pro preset so it cannot remain active without a fresh reward.
        if (prefs.getString("capture_profile", "Gameplay") == "High quality" || prefs.getInt("bitrate_preset", 1) == 2) {
            prefs.edit().putString("capture_profile", "Gameplay").putInt("bitrate_preset", 1).apply()
        }
        MobileAds.initialize(this) { loadRewardedAd() }
        setContent {
            KalizeTheme {
                var microphone by remember { mutableStateOf(prefs.getBoolean("microphone_enabled", false)) }
                var internalAudio by remember { mutableStateOf(prefs.getBoolean("internal_audio_enabled", false)) }
                var bitrate by remember { mutableIntStateOf(prefs.getInt("bitrate_preset", 1)) }
                var profile by remember { mutableStateOf(prefs.getString("capture_profile", "Gameplay") ?: "Gameplay") }
                var proUnlocked by remember { mutableStateOf(false) }

                fun selectProOptions() {
                    if (proUnlocked) {
                        profile = "High quality"
                        bitrate = 2
                        prefs.edit().putString("capture_profile", profile).putInt("bitrate_preset", bitrate).apply()
                    } else {
                        showRewardedAd {
                            proUnlocked = true
                            profile = "High quality"
                            bitrate = 2
                            prefs.edit().putString("capture_profile", profile).putInt("bitrate_preset", bitrate).apply()
                            adMessage = "Pro capture options unlocked for this visit."
                        }
                    }
                }

                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("KALIZE PRO STUDIO", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    Text("Capture controls", style = MaterialTheme.typography.headlineMedium)
                    Text("Choose a capture profile")
                    listOf("Gameplay", "Tutorial").forEach { option ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            RadioButton(selected = profile == option, onClick = {
                                profile = option
                                prefs.edit().putString("capture_profile", option).apply()
                                bitrate = if (option == "Gameplay") 1 else 0
                                prefs.edit().putInt("bitrate_preset", bitrate).apply()
                            })
                            Text(option)
                        }
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = profile == "High quality", onClick = { selectProOptions() })
                        Column(Modifier.weight(1f)) {
                            Text("High quality · Pro")
                            Text("Rewarded-ad unlock", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()
                    Text("Video bitrate")
                    listOf("Balanced · 8 Mbps", "High · 16 Mbps").forEachIndexed { index, label ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            RadioButton(selected = bitrate == index, onClick = {
                                bitrate = index
                                prefs.edit().putInt("bitrate_preset", index).apply()
                                if (index == 0) profile = "Tutorial"
                                if (index == 1) profile = "Gameplay"
                                prefs.edit().putString("capture_profile", profile).apply()
                            })
                            Text(label)
                        }
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = bitrate == 2, onClick = { selectProOptions() })
                        Column(Modifier.weight(1f)) {
                            Text("Maximum · 28 Mbps · Pro")
                            Text("Rewarded-ad unlock", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (!proUnlocked) {
                        OutlinedButton(
                            onClick = { showRewardedAd {
                                proUnlocked = true
                                adMessage = "Pro capture options unlocked for this visit."
                            } },
                            enabled = !adLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (adLoading) "Loading reward ad…" else "Watch ad to unlock Pro capture options")
                        }
                    }
                    Text(adMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Microphone audio")
                            Text("Records your microphone alongside screen video.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = microphone, onCheckedChange = { enabled ->
                            if (enabled && ContextCompat.checkSelfPermission(this@ProStudioActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                requestingInternalAudio = false
                                permissionGrantedAction = { microphone = true; internalAudio = false }
                                audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                microphone = enabled
                                if (enabled) internalAudio = false
                                prefs.edit().putBoolean("microphone_enabled", enabled).putBoolean("internal_audio_enabled", if (enabled) false else internalAudio).apply()
                            }
                        })
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Internal app/game audio")
                            Text("Capture eligible playback audio on Android 10+; some apps block capture.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = internalAudio, enabled = android.os.Build.VERSION.SDK_INT >= 29 && !microphone, onCheckedChange = { enabled ->
                            if (enabled && ContextCompat.checkSelfPermission(this@ProStudioActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                requestingInternalAudio = true
                                permissionGrantedAction = { internalAudio = true; microphone = false }
                                audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                                internalAudio = false
                            } else {
                                internalAudio = enabled
                                prefs.edit().putBoolean("internal_audio_enabled", enabled).putBoolean("microphone_enabled", if (enabled) false else microphone).apply()
                            }
                        })
                    }
                    Text("Microphone and internal audio are separate modes. Internal audio uses Android playback-capture rules and may be silent when the game or app opts out. If capture cannot start, Kalize falls back to video-only recording.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Save and close") }
                }
            }
        }
    }

    private fun loadRewardedAd() {
        if (adLoading || rewardedAd != null) return
        adLoading = true
        RewardedAd.load(
            this,
            "ca-app-pub-3940256099942544/5224354917",
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    adLoading = false
                    adMessage = "Watch an ad to unlock High quality and Maximum bitrate."
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedAd = null
                    adLoading = false
                    adMessage = "Reward ad unavailable. Try again when a connection is available."
                }
            }
        )
    }

    private fun showRewardedAd(onRewardEarned: () -> Unit) {
        val ad = rewardedAd
        if (ad == null) {
            adMessage = "The reward ad is loading or unavailable. Please try again shortly."
            loadRewardedAd()
            return
        }
        rewardedAd = null
        var earned = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                if (!earned) adMessage = "The ad was closed before the reward was earned."
                loadRewardedAd()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                adMessage = "The reward ad could not be shown. Please try again."
                loadRewardedAd()
            }
        }
        ad.show(this) {
            earned = true
            onRewardEarned()
        }
    }
}
