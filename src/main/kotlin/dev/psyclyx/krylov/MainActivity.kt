package dev.psyclyx.krylov

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {
    private external fun nativeGreeting(): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        System.loadLibrary("krylov")
        setContentView(TextView(this).apply {
            text = "Krylov\n\n${nativeGreeting()}"
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        })
    }
}
