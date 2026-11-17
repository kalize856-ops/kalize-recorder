package com.kalize.recorder

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

class FloatingBubbleService:Service() {
 private var wm:WindowManager?=null; private var view:TextView?=null; private lateinit var lp:WindowManager.LayoutParams
 private var downX=0f; private var downY=0f; private var originX=0; private var originY=0
 override fun onCreate() { super.onCreate()
  wm=getSystemService(WINDOW_SERVICE) as WindowManager
  val type=if(Build.VERSION.SDK_INT>=26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
  lp=WindowManager.LayoutParams(58,58,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE,PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.START; x=18; y=220 }
  view=TextView(this).apply {
   text="K"; textSize=22f; setTextColor(-1); gravity=Gravity.CENTER; setBackgroundColor(0xFF7258E8.toInt())
   setOnTouchListener { _,e -> when(e.action) {
    MotionEvent.ACTION_DOWN -> { downX=e.rawX; downY=e.rawY; originX=lp.x; originY=lp.y; true }
    MotionEvent.ACTION_MOVE -> { lp.x=originX+(e.rawX-downX).toInt(); lp.y=originY+(e.rawY-downY).toInt(); try { wm?.updateViewLayout(view,lp) } catch(_:Exception) {}; true }
    MotionEvent.ACTION_UP -> { if(abs(e.rawX-downX)<8 && abs(e.rawY-downY)<8) { startService(Intent(this@FloatingBubbleService,RecorderService::class.java).setAction(RecorderService.STOP)) }; true }
    else -> false
   } }
  }
  try { wm?.addView(view,lp) } catch(_:Exception) { stopSelf() }
 }
 override fun onDestroy() { try { wm?.removeView(view) } catch(_:Exception) {}; super.onDestroy() }
 override fun onBind(intent:Intent?):IBinder?=null
}