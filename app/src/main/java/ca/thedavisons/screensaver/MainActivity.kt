package ca.thedavisons.screensaver

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tv = TextView(this)
        tv.text = "MAIN ACTIVITY WORKING"
        tv.textSize = 40f
        tv.setTextColor(Color.WHITE)
        tv.setBackgroundColor(Color.BLACK)
        tv.gravity = Gravity.CENTER

        setContentView(tv)
    }
}