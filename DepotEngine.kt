package com.example.steamfetch

import `in`.dragonbra.javasteam.depotdownloader.DepotDownloader
import `in`.dragonbra.javasteam.depotdownloader.IDownloadListener
import `in`.dragonbra.javasteam.depotdownloader.data.AppItem
import `in`.dragonbra.javasteam.depotdownloader.data.DownloadItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

sealed interface DlState {
    data object Idle : DlState
    data class Active(val appId: Int, val name: String, val status: String, val percent: Float) : DlState
    data class Done(val name: String, val path: String) : DlState
    data class Failed(val name: String, val message: String) : DlState
}

object Downloads {
    val state = kotlinx.coroutines.flow.MutableStateFlow<DlState>(DlState.Idle)
}

/**
 * The only file that touches JavaSteam's depot downloader module (javasteam-depotdownloader).
 * If a future version renames something, this is the one file to adjust.
 */
object DepotEngine {

    /**
     * Downloads every depot of [appId] for [os] into [dir] as plain files, using the licenses
     * of the signed-in account (which includes Family Sharing licenses).
     */
    suspend fun download(
        appId: Int,
        dir: File,
        os: String,
        onUpdate: (status: String, percent: Float?) -> Unit,
    ) {
        val finished = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()

        val listener = object : IDownloadListener {
            override fun onItemAdded(item: DownloadItem) {
                onUpdate("Queued", null)
            }

            override fun onDownloadStarted(item: DownloadItem) {
                started.complete(Unit)
                onUpdate("Downloading…", null)
            }

            override fun onDownloadCompleted(item: DownloadItem) {
                started.complete(Unit)
                finished.complete(Unit)
            }

            override fun onDownloadFailed(item: DownloadItem, error: Throwable) {
                started.complete(Unit)
                finished.completeExceptionally(error)
            }

            override fun onStatusUpdate(message: String) {
                started.complete(Unit)
                onUpdate(message, null)
            }

            override fun onChunkCompleted(
                depotId: Int,
                depotPercentComplete: Float,
                compressedBytes: Long,
                uncompressedBytes: Long,
            ) {
                started.complete(Unit)
                onUpdate("Depot $depotId", depotPercentComplete)
            }

            override fun onDepotCompleted(depotId: Int, compressedBytes: Long, uncompressedBytes: Long) {
                started.complete(Unit)
                onUpdate("Depot $depotId finished", 100f)
            }

            override fun onFileCompleted(depotId: Int, fileName: String, depotPercentComplete: Float) {
                started.complete(Unit)
                onUpdate(fileName, depotPercentComplete)
            }
        }

        val downloader = DepotDownloader(SteamManager.steamClient, SteamManager.licenses)
        try {
            downloader.addListener(listener)
            downloader.add(AppItem(appId = appId, installDirectory = dir.absolutePath, os = os))
            downloader.finishAdding()

            // This JavaSteam version has no startDownloading(). Depending on the version, the download
            // either begins by itself after finishAdding(), or via a differently named start method.
            // Try a start method if one exists; otherwise rely on auto-start.
            Reflect.find(downloader, "startDownloading", "startDownload", "start")?.let { m ->
                Reflect.invokeAwait(downloader, m)
            }

            val begun = withTimeoutOrNull(120_000) { started.await() }
            if (begun == null) {
                throw IllegalStateException(
                    "The download never started. Please send this to the developer: " +
                        "DepotDownloader methods = ${Reflect.members(downloader)}",
                )
            }
            finished.await()
        } finally {
            runCatching { (downloader as? AutoCloseable)?.close() }
        }
    }
}
