package com.livetranslate.app.util

import android.content.Context
import androidx.annotation.StringRes

/**
 * Process-wide access to localized strings for non-UI layers (service,
 * WebSocket client, audio capturers, export). Initialized from the
 * Application in onCreate — application context, so no leaks.
 */
object AppStrings {
    lateinit var context: Context
        private set

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    fun get(@StringRes res: Int, vararg args: Any): String =
        if (args.isEmpty()) context.getString(res) else context.getString(res, *args)
}
