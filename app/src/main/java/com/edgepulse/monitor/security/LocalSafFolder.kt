package com.edgepulse.monitor.security

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/** Export only through Android's local storage provider, preserving offline monitoring. */
object LocalSafFolder {
    fun requireLocalTree(uri: Uri) {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isTreeUri(uri))
        require(uri.authority == "com.android.externalstorage.documents") {
            "Choose local device, SD-card or USB storage; cloud document providers are not permitted"
        }
    }
}
