package com.google.aiedge.edge_gallery_flutter

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    private val nativeBridgeHandler by lazy { NativeBridgeHandler(this) }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        nativeBridgeHandler.attach(flutterEngine)
    }
}
