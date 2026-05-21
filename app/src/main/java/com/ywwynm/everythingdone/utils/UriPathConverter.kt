package com.ywwynm.everythingdone.utils

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore

/**
 * Created by paulburke.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * See http://stackoverflow.com/a/27271131/3952691 for more details.
 *
 * Changed by ywwynm on 2015/9/25 to meet own requirements.
 */
object UriPathConverter {

    const val TAG: String = "UriPathConverter"

    @JvmStatic
    fun getLocalPathName(context: Context?, uri: Uri?): String? {
        val pathName: String = getPathName(context, uri) ?: return null
        if (!pathName.startsWith(Environment.getExternalStorageDirectory().absolutePath)) {
            val locations: List<String?> = FileUtil.getAllStorageLocations()!!
            for (location in locations) {
                if (pathName.startsWith(location!!)) {
                    return pathName
                }
            }
            return null
        } else {
            return pathName
        }
    }

    /**
     * Get a file path from a Uri. This will get the the path for Storage Access
     * Framework Documents, as well as the _data field for the MediaStore and
     * other file-based ContentProviders.
     *
     * @param context The context.
     * @param uri The Uri to query.
     */
    @SuppressLint("NewApi")
    @JvmStatic
    fun getPathName(context: Context?, uri: Uri?): String? {
        if (uri == null) {
            return null
        }

        // DocumentProvider
        if (DocumentsContract.isDocumentUri(context, uri)) {
            // ExternalStorageProvider
            if (isExternalStorageDocument(uri)) {
                val docId: String = DocumentsContract.getDocumentId(uri)
                val split: Array<String> = docId.split(":".toRegex()).toTypedArray()
                val type: String = split[0]

                if (type.equals("primary", ignoreCase = true)) {
                    return Environment.getExternalStorageDirectory().toString() + "/" + split[1]
                }
            } else if (isDownloadsDocument(uri)) {
                val id: String = DocumentsContract.getDocumentId(uri)
                val contentUri: Uri = ContentUris.withAppendedId(
                        Uri.parse("content://downloads/public_downloads"), java.lang.Long.valueOf(id))

                return getDataColumn(context, contentUri, null, null)
            } else if (isMediaDocument(uri)) {
                val docId: String = DocumentsContract.getDocumentId(uri)
                val split: Array<String> = docId.split(":".toRegex()).toTypedArray()
                val type: String = split[0]

                var contentUri: Uri? = null
                when (type) {
                    "image" -> contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    "video" -> contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    "audio" -> contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    else -> {}
                }

                return getDataColumn(context, contentUri, "_id=" + split[1], null)
            }
        } else if (uri.scheme!!.equals("content", ignoreCase = true)) {
            return getDataColumn(context, uri, null, null)
        } else if (uri.scheme!!.equals("file", ignoreCase = true)) {
            return uri.path
        }

        return null
    }

    /**
     * Get the value of the data column for this Uri. This is useful for
     * MediaStore Uris, and other file-based ContentProviders.
     *
     * @param context The context.
     * @param uri The Uri to query.
     * @param selection (Optional) Filter used in the query.
     * @param selectionArgs (Optional) Selection arguments used in the query.
     * @return The value of the _data column, which is typically a file path.
     */
    @JvmStatic
    fun getDataColumn(context: Context?, uri: Uri?, selection: String?,
                      selectionArgs: Array<String?>?): String? {

        var cursor: Cursor? = null
        val column = "_data"
        val projection: Array<String?> = arrayOf<String?>(column)

        try {
            cursor = context!!.contentResolver.query(uri!!, projection, selection, selectionArgs,
                    null)
            if (cursor != null && cursor.moveToFirst()) {
                val column_index: Int = cursor.getColumnIndexOrThrow(column)
                return cursor.getString(column_index)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            cursor?.close()
        }
        return null
    }


    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is ExternalStorageProvider.
     */
    @JvmStatic
    fun isExternalStorageDocument(uri: Uri?): Boolean {
        return "com.android.externalstorage.documents" == uri!!.authority
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is DownloadsProvider.
     */
    @JvmStatic
    fun isDownloadsDocument(uri: Uri?): Boolean {
        return "com.android.providers.downloads.documents" == uri!!.authority
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is MediaProvider.
     */
    @JvmStatic
    fun isMediaDocument(uri: Uri?): Boolean {
        return "com.android.providers.media.documents" == uri!!.authority
    }

}
