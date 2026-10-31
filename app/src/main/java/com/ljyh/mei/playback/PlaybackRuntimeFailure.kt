package com.ljyh.mei.playback

import androidx.media3.common.PlaybackException
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong

/** Runtime failures are retryable observations, never permanent copyright/account verdicts. */
enum class PlaybackRuntimeFailureKind(val message: String) {
    SourceUnavailable("当前歌曲音源不可用，已暂停；可手动重试或选择其他歌曲"),
    Connection("网络或播放链接异常，已暂停当前歌曲"),
    LocalFile("本地文件无法读取，已暂停当前歌曲"),
    SourceAccess("无法访问播放资源，已暂停当前歌曲"),
    AudioFormat("音频暂时无法播放，已暂停当前歌曲"),
    SourceRecovery("音源恢复失败，已暂停当前歌曲"),
    Unknown("播放失败，已暂停当前歌曲"),
}

private val runtimeFailureIds = AtomicLong()

/** Only public playback identities and fixed UI copy cross the service boundary. */
data class PlaybackRuntimeFailure(
    val mediaId: String?,
    val queueEntryId: String?,
    val kind: PlaybackRuntimeFailureKind,
    val errorCode: Int,
    val message: String = kind.message,
    val id: Long = runtimeFailureIds.incrementAndGet(),
)

/** Inspect typed causes, including loader wrappers; raw error text is neither UI copy nor evidence. */
internal fun playbackRuntimeFailureKind(errorCode: Int, cause: Throwable?): PlaybackRuntimeFailureKind {
    val causes = generateSequence(cause) { it.cause }.take(16).toList()
    return when {
        causes.any { it is SourceNotFoundException } -> PlaybackRuntimeFailureKind.SourceUnavailable
        errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            causes.any { it is UnknownHostException || it is SocketTimeoutException || it is ConnectException } ->
            PlaybackRuntimeFailureKind.Connection
        errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND || causes.any { it is FileNotFoundException } ->
            PlaybackRuntimeFailureKind.LocalFile
        errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION || causes.any { it is SecurityException } ->
            PlaybackRuntimeFailureKind.SourceAccess
        shouldTryLowerPlaybackQuality(errorCode) -> PlaybackRuntimeFailureKind.AudioFormat
        shouldRefreshPlaybackSource(errorCode) || shouldRecoverStalledPlayback(errorCode, cause) ->
            PlaybackRuntimeFailureKind.SourceRecovery
        else -> PlaybackRuntimeFailureKind.Unknown
    }
}
