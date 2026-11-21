package com.kalize.recorder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A simple explanation-first CPU test. It measures a short local workload;
 * it does not change CPU settings or predict exact game frame rates.
 */
class DeviceToolsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        setContent {
            KalizeTheme {
                val scope = rememberCoroutineScope()
                var running by remember { mutableStateOf(false) }
                var result by remember { mutableStateOf<CpuBenchmarkResult?>(null) }
                var showDetails by remember { mutableStateOf(false) }
                var message by remember { mutableStateOf("Run the test to see a simple rating of your phone's processor.") }

                if (showDetails) {
                    AlertDialog(
                        onDismissRequest = { showDetails = false },
                        title = { Text("Understanding your CPU result") },
                        text = {
                            Column(
                                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text("What is the CPU?", fontWeight = FontWeight.Bold)
                                Text("The CPU is one of your phone's main working parts. It handles instructions and calculations for apps, games, recording and many other tasks.")
                                Text("What does the rating mean?", fontWeight = FontWeight.Bold)
                                Text("Kalize gives your phone a simple label such as Average, Good or Excellent based on this short test. It is a guide to this test only, not an official industry grade.")
                                Text("Single-core score", fontWeight = FontWeight.Bold)
                                Text("This checks how quickly one worker can handle the test. It gives a rough idea of performance when a task mainly uses one processor core.")
                                Text("Multi-core score", fontWeight = FontWeight.Bold)
                                Text("This checks several workers at the same time. It gives a rough idea of how the processor handles work that can be shared across multiple cores.")
                                Text("Stability", fontWeight = FontWeight.Bold)
                                Text("This shows how similar three repeat tests were. A higher percentage means the results were more consistent during this run. It is not a battery-health score.")
                                Text("Important things to know", fontWeight = FontWeight.Bold)
                                Text("• A higher score does not guarantee a particular game frame rate or recording quality.\n• The score is Kalize's own workload index, not a universal score to compare directly with other benchmark apps.\n• Heat, battery-saver mode, background apps and Android power management can change the result.\n• The test does not overclock, boost or permanently change your phone.\n• For a fair comparison, run it again under similar conditions.")
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showDetails = false }) { Text("Got it") }
                        }
                    )
                }

                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("DEVICE PERFORMANCE", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    Text("CPU benchmark", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("A simple check of how quickly your phone handles calculations. No technical knowledge needed.")

                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("YOUR PHONE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(deviceName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("$cores processor core(s) detected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("HOW IT WORKS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text("Press the button and Kalize will run a short calculation test. It usually takes a few seconds. You can keep using your phone, but closing demanding apps first may make the result more consistent.")
                            Button(
                                onClick = {
                                    running = true
                                    message = "Testing your phone… please wait."
                                    scope.launch {
                                        val measured = withContext(Dispatchers.Default) { CpuBenchmark.run() }
                                        result = measured
                                        message = "Test complete."
                                        running = false
                                    }
                                },
                                enabled = !running,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (running) "Testing…" else if (result == null) "Test my phone" else "Run test again")
                            }
                            if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    result?.let { score ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("YOUR RESULT", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Text(score.rating, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                Text(ratingDescription(score.rating), style = MaterialTheme.typography.bodyMedium)
                                Text("Kalize score: ${score.overallScore}", style = MaterialTheme.typography.titleMedium)
                                HorizontalDivider()
                                ScoreRow("One task at a time", score.singleCoreScore.toString(), "Single-core")
                                ScoreRow("Several tasks together", score.multiCoreScore.toString(), "Multi-core")
                                ScoreRow("How consistent the test was", "${score.stabilityPercent}%", "Stability")
                                Text("Test used ${score.coreCount} worker(s). These numbers describe this Kalize test, not guaranteed game FPS.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    OutlinedButton(onClick = { showDetails = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Details — explain my result")
                    }
                    OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Close")
                    }
                }
            }
        }
    }

    private fun ratingDescription(rating: String): String = when (rating) {
        "Very weak" -> "This test completed at a lower speed. Keep expectations modest when running several demanding tasks together."
        "Weak" -> "Your phone handled the test, but its result suggests it may take longer with demanding calculations."
        "Average" -> "Your phone delivered a middle-range result in this Kalize test. Everyday performance still depends on the app and other phone parts."
        "Good" -> "Your phone delivered a good result in this test and handled the calculation workload comfortably."
        "Very good" -> "Your phone delivered a strong result in this test. Real-world performance can still vary by app and temperature."
        "Excellent" -> "Your phone delivered a very strong result in this test. This does not guarantee a particular game or recording frame rate."
        else -> "This is the result of a short Kalize calculation test."
    }
}

@Composable
private fun ScoreRow(label: String, value: String, technicalName: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(technicalName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}
