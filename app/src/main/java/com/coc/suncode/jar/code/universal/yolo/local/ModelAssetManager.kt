package com.coc.suncode.jar.code.universal.yolo.local

import android.content.Context
import android.util.Log
import com.coc.suncode.core.util.basic.ShowMessage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Resolves bundled ONNX assets into private storage without a shared server.
 *
 * The plugin is loaded by an isolated class loader. Consequently more than one
 * copy of this object may run in the same process and access the same files.
 * Model extraction therefore happens per request, writes to a unique temporary
 * file, and publishes the completed file with an atomic rename. Existing files
 * are reused when their source version and size metadata still match.
 */
object ModelAssetManager {

    private const val TAG = "ModelAssetManager"
    private const val MODELS_DIR_NAME = "models"
    private const val ONNX_SUFFIX = ".onnx"
    private const val META_SUFFIX = ".meta"

    /** A process-local guard prevents duplicate work for callers in one loader. */
    private val extractionLock = Any()

    /**
     * Keeps the old startup API for callers, but intentionally does not copy all
     * models eagerly. The requested model is copied by [resolveModelFile].
     */
    fun startExtraction(context: Context) {
        val modelsDir = modelsDir(context.applicationContext)
        if (!modelsDir.exists() && !modelsDir.mkdirs()) {
            ShowMessage.record("Cannot create YOLO model directory: ${modelsDir.absolutePath}", Log.ERROR, TAG)
            return
        }
        ShowMessage.record(
            "YOLO model extraction deferred until first use: dir=${modelsDir.absolutePath}, loader=${javaClass.classLoader}",
            Log.DEBUG,
            TAG
        )
    }

    /** Resolves one model, extracting only that asset when necessary. */
    fun resolveModelFile(context: Context, assetName: String): File {
        require(assetName.endsWith(ONNX_SUFFIX)) { "Unsupported model asset: $assetName" }
        val appContext = context.applicationContext
        val modelsDir = modelsDir(appContext)
        if (!modelsDir.exists() && !modelsDir.mkdirs()) {
            throw IOException("Failed to create model directory: ${modelsDir.absolutePath}")
        }

        val sourceVersion = sourceVersion(appContext)
        val versionKey = sourceVersion.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        var target = File(modelsDir, "$assetName.$versionKey")
        var metadata = File(modelsDir, target.name + META_SUFFIX)

        synchronized(extractionLock) {
            if (isUsable(target, metadata, sourceVersion)) {
                ShowMessage.record("Using cached YOLO model: $assetName (${target.length()} bytes)", Log.DEBUG, TAG)
                return target
            }

            // Never remove a file that another class-loader may still have open.
            // A unique candidate lets an active older cache continue serving its
            // current inference while this loader publishes a repaired copy.
            if (target.exists() || metadata.exists()) {
                target = File(modelsDir, "$assetName.$versionKey-${System.nanoTime()}")
                metadata = File(modelsDir, target.name + META_SUFFIX)
            }

            ShowMessage.record(
                "Extracting requested YOLO model: $assetName, source=$sourceVersion, loader=${javaClass.classLoader}",
                Log.INFO,
                TAG
            )
            val temporary = File.createTempFile(assetName.removeSuffix(ONNX_SUFFIX) + "-", ".tmp", modelsDir)
            try {
                var copiedBytes = 0L
                appContext.assets.open(assetName).use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var count = input.read(buffer)
                        while (count >= 0) {
                            if (count > 0) {
                                output.write(buffer, 0, count)
                                copiedBytes += count
                            }
                            count = input.read(buffer)
                        }
                        output.fd.sync()
                    }
                }
                if (copiedBytes <= 0L) {
                    throw IOException("Bundled model asset is empty: $assetName")
                }

                val published = publishIfAbsent(temporary, target)
                if (!published && !isUsable(target, metadata, sourceVersion)) {
                    // A racing loader won publication but has not written its
                    // tiny metadata sidecar yet. The content length is still
                    // checked before accepting that completed target.
                    if (!target.isFile || target.length() != copiedBytes) {
                        throw IOException("Racing model publication is incomplete: $assetName")
                    }
                }
                writeMetadata(metadata, sourceVersion, copiedBytes)
                if (!isUsable(target, metadata, sourceVersion)) {
                    throw IOException("Extracted model failed validation: $assetName")
                }
                ShowMessage.record("YOLO model ready: $assetName (${target.length()} bytes)", Log.INFO, TAG)
                return target
            } catch (error: Exception) {
                temporary.delete()
                ShowMessage.record("YOLO model extraction failed: $assetName", Log.ERROR, TAG, error)
                throw error
            } finally {
                temporary.delete()
            }
        }
    }

    private fun modelsDir(context: Context): File = File(context.filesDir, MODELS_DIR_NAME)

    private fun sourceVersion(context: Context): String {
        // Package update time changes whenever the bundled models are updated,
        // including reinstalls where the APK file timestamp is preserved.
        return runCatching {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${packageInfo.lastUpdateTime}:${packageInfo.versionCode}"
        }.getOrElse {
            val source = File(context.applicationInfo.sourceDir)
            "${source.length()}:${source.lastModified()}"
        }
    }

    private fun isUsable(file: File, metadata: File, sourceVersion: String): Boolean {
        if (!file.isFile || file.length() <= 0L || !metadata.isFile) return false
        val fields = runCatching { metadata.readText().trim().split('\t') }.getOrNull() ?: return false
        return fields.size == 2 && fields[0] == sourceVersion && fields[1].toLongOrNull() == file.length()
    }

    private fun writeMetadata(metadata: File, sourceVersion: String, size: Long) {
        val temporary = File.createTempFile(metadata.name, ".tmp", metadata.parentFile)
        try {
            temporary.writeText("$sourceVersion\t$size")
            if (!temporary.renameTo(metadata)) {
                // Another class-loader may have published the same metadata.
                // Its contents are equivalent for this source version.
                temporary.delete()
            }
        } finally {
            temporary.delete()
        }
    }

    private fun publishIfAbsent(source: File, target: File): Boolean {
        // renameTo is atomic when the destination is absent. If another loader
        // won the race, retain its complete destination and discard our temp.
        if (source.renameTo(target)) return true
        if (target.isFile && target.length() > 0L) {
            source.delete()
            return false
        }
        throw IOException("Failed to publish model file: ${target.absolutePath}")
    }
}
