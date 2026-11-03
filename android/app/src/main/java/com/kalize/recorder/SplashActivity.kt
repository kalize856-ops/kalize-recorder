
package com.kalize.recorder

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class SplashActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val visible = remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                visible.value = true
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF211744),
                                Color(0xFF100D20),
                                Color(0xFF090B14)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                AnimatedVisibility(
                    visible = visible.value,
                    enter = fadeIn() + slideInVertically(
                        initialOffsetY = { it / 5 }
                    )
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(116.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF8B6CF6)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "K",
                                color = Color.White,
                                fontSize = 76.sp,
                                fontWeight = FontWeight.Black
                            )
                        }

                        Spacer(Modifier.height(25.dp))

                        Text(
                            "ƘALIZE RECORDER",
                            color = Color.White,
                            fontSize = 25.sp,
                            fontWeight = FontWeight.Black
                        )

                        Spacer(Modifier.height(9.dp))

                        Text(
                            "YOUR SCREEN. YOUR STORY.",
                            color = Color(0xFFB7A0FF),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )

                        Spacer(Modifier.height(45.dp))

                        Text(
                            "Preparing your recording studio...",
                            color = Color(0xFFAAA3C0),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        handler.postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 2200L)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
