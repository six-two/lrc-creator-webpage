package dev.lrceditor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.lrceditor.ui.LrcApp
import dev.lrceditor.ui.LrcTheme

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only handle the launching intent on a fresh start, not after rotation / process-restore.
        if (savedInstanceState == null) handleIntent(intent)
        setContent { LrcTheme { LrcApp(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * "Open with" (ACTION_VIEW, content URI in data) and "Share" (ACTION_SEND, content URI in EXTRA_STREAM).
     * Reading happens immediately in the ViewModel (the file is copied to app storage), so the temporary
     * URI grant attached to the intent is all we need.
     */
    private fun handleIntent(intent: Intent?) {
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            else -> null
        }
        if (uri != null) {
            vm.openUri(uri)
            // Consume so a later recreate doesn't reopen the same file.
            intent.action = null
        }
    }
}
