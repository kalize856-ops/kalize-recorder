package com.kalize.recorder

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
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

class StorageDestinationActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("kalize_settings", MODE_PRIVATE) }
    private var folderName by mutableStateOf("Default: Movies/Kalize Recorder")

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
                prefs.edit().putString("recording_tree_uri", uri.toString()).apply()
                folderName = DocumentsContract.getTreeDocumentId(uri).substringAfter(':').ifBlank { "Selected folder" }
                setResult(RESULT_OK)
            } catch (error: Exception) {
                folderName = "Could not save folder permission: ${error.localizedMessage ?: "permission denied"}"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = prefs.getString("recording_tree_uri", null)?.let(Uri::parse)
        folderName = saved?.let {
            runCatching { DocumentsContract.getTreeDocumentId(it).substringAfter(':') }.getOrDefault("Selected folder")
        } ?: "Default: Movies/Kalize Recorder"
        setContent {
            KalizeTheme {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("STORAGE", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    Text("Recording destination", style = MaterialTheme.typography.headlineMedium)
                    Text("Choose internal storage or an SD-card folder. Android will ask you to grant Kalize permission to the selected folder.")
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Current destination", style = MaterialTheme.typography.labelLarge)
                            Text(folderName, style = MaterialTheme.typography.titleMedium)
                            Text("New recordings will be created in this folder. Existing recordings are not moved.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Button(onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Choose folder or SD card")
                    }
                    OutlinedButton(onClick = {
                        prefs.edit().remove("recording_tree_uri").apply()
                        folderName = "Default: Movies/Kalize Recorder"
                    }, modifier = Modifier.fillMaxWidth()) { Text("Use default Movies folder") }
                    Text("If the SD card is removed or Android revokes access, Kalize falls back to its default Movies folder. Some providers do not support direct video creation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }
            }
        }
    }
}
