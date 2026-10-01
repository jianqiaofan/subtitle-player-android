package com.jianqiaofan.subtitleplayer.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract

/** System 「另存为」dialog for a JPEG export (截屏保存). */
class CreateJpegDocument : ActivityResultContract<CreateJpegDocument.Args, Uri?>() {
    data class Args(val fileName: String, val initialUri: Uri? = null)

    override fun createIntent(context: Context, input: Args): Intent {
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/jpeg"
            putExtra(Intent.EXTRA_TITLE, input.fileName)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
            if (input.initialUri != null) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, input.initialUri)
            }
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return if (resultCode == Activity.RESULT_OK) intent?.data else null
    }
}
