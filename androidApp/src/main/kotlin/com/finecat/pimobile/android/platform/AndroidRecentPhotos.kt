package com.finecat.pimobile.android.platform

import android.content.ContentUris
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 最近相册照片的查询与缩略图加载。测试用 fake 实现本接口，避免依赖 ContentResolver。 */
interface RecentPhotosProvider {
    /** 最近加入相册的图片（含截图），按加入时间倒序，最多 limit 张。 */
    suspend fun recentUris(limit: Int): List<Uri>

    /** 缩略图；单张失败返回 null，由调用方跳过。 */
    suspend fun thumbnail(uri: Uri, edge: Int): ImageBitmap?
}

/**
 * 媒体库"最近照片"数据源：只读查询，不申请授权以外的任何操作。
 * 权限（READ_MEDIA_IMAGES 等）由 UI 层在打开托盘前请求。
 */
class AndroidRecentPhotos(
    private val contentResolver: ContentResolver
) : RecentPhotosProvider {
    override suspend fun recentUris(limit: Int): List<Uri> = withContext(Dispatchers.IO) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        contentResolver
            .query(collection, arrayOf(MediaStore.Images.Media._ID), null, null, sort)
            ?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                buildList {
                    while (cursor.moveToNext() && size < limit) {
                        add(ContentUris.withAppendedId(collection, cursor.getLong(idIndex)))
                    }
                }
            }
            ?: emptyList()
    }

    override suspend fun thumbnail(uri: Uri, edge: Int): ImageBitmap? =
        withContext(Dispatchers.IO) {
            // 契约：单张失败返 null（托盘被删/权限撤销常态），不在实现里抛。
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver.loadThumbnail(uri, Size(edge, edge), null).asImageBitmap()
                } else {
                    decodeSampled(uri, edge)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }

    /** API 29 之前没有 loadThumbnail：按目标边长算 inSampleSize 解码一张小图。 */
    private fun decodeSampled(uri: Uri, edge: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= edge && bounds.outHeight / (sample * 2) >= edge) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap: Bitmap? = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
        return bitmap?.asImageBitmap()
    }
}
