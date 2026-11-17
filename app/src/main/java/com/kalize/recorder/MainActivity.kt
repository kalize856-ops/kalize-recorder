package com.kalize.recorder

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity: ComponentActivity() {
 private val capture=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
  if(r.resultCode==Activity.RESULT_OK && r.data!=null) {
   val i=Intent(this,RecorderService::class.java).setAction(RecorderService.START)
    .putExtra(RecorderService.CODE,r.resultCode).putExtra(RecorderService.DATA,r.data)
   ContextCompat.startForegroundService(this,i)
  }
 }
 override fun onCreate(savedInstanceState:Bundle?) { super.onCreate(savedInstanceState)
  setContent {
   var dark by remember { mutableStateOf(true) }; var settings by remember { mutableStateOf(false) }
   val fg=if(dark) Color.White else Color(0xFF172033)
   MaterialTheme(colorScheme=if(dark) darkColorScheme(primary=Color(0xFF8B75FF)) else lightColorScheme(primary=Color(0xFF6750D8))) {
    Surface(Modifier.fillMaxSize(),color=if(dark) Color(0xFF0D1422) else Color(0xFFF4F6FB)) {
     Column(Modifier.fillMaxSize().padding(22.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
      Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
       Column { Text("KALIZE",color=Color(0xFF9B88FF),fontWeight=FontWeight.Black); Text(if(settings) "Settings" else "Recorder",color=fg,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold) }
       IconButton(onClick={settings=!settings}) { Icon(Icons.Default.Settings,"Settings",tint=fg) }
      }
      if(!settings) {
       Card(shape=RoundedCornerShape(26.dp),colors=CardDefaults.cardColors(containerColor=if(dark) Color(0xFF182337) else Color.White)) {
        Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
         Text("SCREEN STUDIO",color=Color(0xFF9B88FF),fontWeight=FontWeight.Bold)
         Text("Capture every moment.",color=fg,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
         Text("Record tutorials, gameplay and app demos. Android will ask for permission before screen capture.",color=fg)
         Button(onClick={val m=getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager; capture.launch(m.createScreenCaptureIntent())},modifier=Modifier.fillMaxWidth().height(54.dp)) { Icon(Icons.Default.Videocam,null); Spacer(Modifier.width(8.dp)); Text("Start screen recording") }
        }
       }
       Button(onClick={enableBubble()},modifier=Modifier.fillMaxWidth()) { Text("Enable floating control") }
       Text("MP4 files are saved in this app's Movies folder.",color=fg)
      } else {
       Card(colors=CardDefaults.cardColors(containerColor=if(dark) Color(0xFF182337) else Color.White)) {
        Row(Modifier.fillMaxWidth().padding(18.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
         Column { Text("Dark appearance",color=fg); Text("Switch studio theme",color=fg) }
         Switch(checked=dark,onCheckedChange={dark=it})
        }
       }
       Text("Recording controls are currently 1080p maximum at 30 FPS. Device capability and Android permissions apply.",color=fg)
      }
     }
    }
   }
  }
 }
 private fun enableBubble() {
  if(!Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
  else ContextCompat.startForegroundService(this,Intent(this,FloatingBubbleService::class.java))
 }
}