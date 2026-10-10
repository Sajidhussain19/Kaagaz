package com.kaagaz.app.llm

import android.content.Context

/** Process-wide services so a loaded Gemma engine survives activity recreation. */
object Services {
    @Volatile private var gemmaRuntime: GemmaRuntime? = null
    fun gemma(context: Context): GemmaRuntime = gemmaRuntime ?: synchronized(this) { gemmaRuntime ?: GemmaRuntime(context.applicationContext).also { gemmaRuntime = it } }
}
