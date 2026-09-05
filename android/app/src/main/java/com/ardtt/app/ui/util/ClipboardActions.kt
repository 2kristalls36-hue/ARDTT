package com.ardtt.app.ui.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Copy / share helpers.
 *
 * Four screens carried their own `getSystemService(CLIPBOARD_SERVICE)` block
 * and their own `ACTION_SEND` builder, with only one of them guarding against a
 * missing share target. These two functions are the shared path.
 */

/** Puts [text] on the clipboard and confirms with a toast. */
fun copyToClipboard(
    context: Context,
    text: String,
    clipLabel: String = "ARDTT",
    toast: String? = "Скопировано",
) {
    if (text.isBlank()) return
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText(clipLabel, text))
    if (toast != null) {
        Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
    }
}

/** Opens the system share sheet; reports failure instead of crashing. */
fun shareText(
    context: Context,
    text: String,
    subject: String? = null,
    chooserTitle: String = "Поделиться",
) {
    if (text.isBlank()) return
    runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        context.startActivity(
            Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { error ->
        Toast.makeText(
            context,
            error.message ?: "Не удалось поделиться",
            Toast.LENGTH_SHORT,
        ).show()
    }
}
