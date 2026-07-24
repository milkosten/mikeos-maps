package com.mikeos.core.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * A thin Activity that hosts the [AgentInspector] Composable. An app opens the inspector
 * with a one-liner:
 *
 * ```kotlin
 * AgentInspectorActivity.start(context)
 * ```
 *
 * The Activity is declared in the core module's manifest, so consuming apps inherit it
 * via manifest merge and need no wiring of their own.
 */
class AgentInspectorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AgentInspector(onBack = { finish() }) }
    }

    companion object {
        /** Open the Agent Inspector from anywhere with a [Context]. */
        fun start(context: Context) {
            val intent = Intent(context, AgentInspectorActivity::class.java)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
