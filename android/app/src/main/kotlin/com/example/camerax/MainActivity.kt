package com.example.camerax

import android.content.Intent
import android.os.Bundle
import com.example.camerax.camera.MainActivity
import io.flutter.embedding.android.FlutterActivity
import io.flutter.plugin.common.MethodChannel

class MainActivity: FlutterActivity() {
    private val CHANNEL = "samples.flutter.dev/camera"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MethodChannel(flutterEngine?.dartExecutor?.binaryMessenger, CHANNEL).setMethodCallHandler {
                call, result ->
            if (call.method == "startCameraX") {
                startActivity(Intent(this,MainActivity::class.java))
                finish()
            }
        }
    }
}
