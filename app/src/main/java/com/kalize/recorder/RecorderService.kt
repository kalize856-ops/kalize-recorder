package com.kalize.recorder

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File

class RecorderService:Service() {
 private var projection:MediaProjection?=null
 private var recorder:MediaRecorder?=null
 private var display:VirtualDisplay?=null
 private var output:File?=null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  if(intent?.action==STOP) stopCapture() else if(intent?.action==START) startCapture(intent)
  return START_NOT_STICKY
 }
 private fun startCapture(i:Intent) {
  channel()
  val stop=PendingIntent.getService(this,2,Intent(this,RecorderService::class.java).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val n=NotificationCompat.Builder(this,"kalize_capture").setSmallIcon(android.R.drawable.presence_video_online).setContentTitle("Kalize Recorder").setContentText("Screen recording is active").setOngoing(true).addAction(0,"Stop",stop).build()
  if(Build.VERSION.SDK_INT>=29) startForeground(1,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(1,n)
  val code=i.getIntExtra(CODE,Activity.RESULT_CANCELED)
  val data=if(Build.VERSION.SDK_INT>=33) i.getParcelableExtra(DATA,Intent::class.java) else @Suppress("DEPRECATION") (i.getParcelableExtra(DATA) as? Intent)
  if(code!=Activity.RESULT_OK || data==null) { stopSelf(); return }
  try {
   val pm=getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
   projection=pm.getMediaProjection(code,data)
   val dm=DisplayMetrics()
   @Suppress("DEPRECATION") (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
   val scale=minOf(1f,1920f/maxOf(dm.widthPixels,dm.heightPixels).toFloat())
   val w=((dm.widthPixels*scale).toInt()/2)*2; val h=((dm.heightPixels*scale).toInt()/2)*2
   val dir=getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)?:filesDir
   output=File(dir,"Kalize_${System.currentTimeMillis()}.mp4")
   val mr=if(Build.VERSION.SDK_INT>=31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
   recorder=mr
   mr.setVideoSource(MediaRecorder.VideoSource.SURFACE); mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
   mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264); mr.setVideoSize(w,h)
   mr.setVideoFrameRate(30); mr.setVideoEncodingBitRate(8_000_000); mr.setOutputFile(output!!.absolutePath); mr.prepare()
   projection?.registerCallback(object:MediaProjection.Callback(){ override fun onStop(){ stopCapture() } },null)
   display=projection!!.createVirtualDisplay("KalizeCapture",w,h,dm.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,mr.surface,null,null)
   mr.start()
  } catch(_:Exception) { stopCapture() }
 }
 private fun stopCapture() {
  try { recorder?.stop() } catch(_:Exception) { output?.delete() }
  try { display?.release() } catch(_:Exception) {}
  try { recorder?.release() } catch(_:Exception) {}
  try { projection?.stop() } catch(_:Exception) {}
  recorder=null; display=null; projection=null
  stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
 }
 private fun channel() { if(Build.VERSION.SDK_INT>=26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("kalize_capture","Screen recording",NotificationManager.IMPORTANCE_LOW)) }
 override fun onBind(intent:Intent?):IBinder?=null
 companion object { const val START="kalize.start"; const val STOP="kalize.stop"; const val CODE="result_code"; const val DATA="projection_data" }
}