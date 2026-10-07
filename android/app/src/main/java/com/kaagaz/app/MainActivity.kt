package com.kaagaz.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "Kaagaz test app launched")
        setContentView(buildContent())
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "Kaagaz test app resumed")
    }

    private fun buildContent(): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(36), dp(28), dp(36))
            setBackgroundColor(Color.rgb(248, 250, 252))

            addView(TextView(context).apply {
                text = getString(R.string.app_name)
                textSize = 32f
                setTextColor(Color.rgb(15, 23, 42))
                gravity = Gravity.CENTER
            })

            addView(TextView(context).apply {
                text = getString(R.string.tagline)
                textSize = 17f
                setTextColor(Color.rgb(51, 65, 85))
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(28))
            })

            addView(Button(context).apply {
                text = getString(R.string.pipeline_ready)
                contentDescription = getString(R.string.pipeline_ready_description)
                minHeight = dp(48)
                setOnClickListener {
                    Log.i(TAG, "Pipeline verification button tapped")
                    Toast.makeText(context, R.string.pipeline_confirmed, Toast.LENGTH_SHORT).show()
                }
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    companion object {
        private const val TAG = "KaagazPipeline"
    }
}
