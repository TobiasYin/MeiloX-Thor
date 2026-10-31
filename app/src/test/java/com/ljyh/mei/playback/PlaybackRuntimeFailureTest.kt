package com.ljyh.mei.playback

import androidx.media3.common.PlaybackException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.*
import org.junit.Test

class PlaybackRuntimeFailureTest {
    @Test fun wrappedMissingUrlIsRecognizedWithoutParsingAnExceptionMessage() {
        val cause = IOException("loader", IOException("resolver", SourceNotFoundException("no source")))
        assertEquals(PlaybackRuntimeFailureKind.SourceUnavailable,
            playbackRuntimeFailureKind(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, cause))
        assertEquals(PlaybackRuntimeFailureKind.Unknown,
            playbackRuntimeFailureKind(PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                IOException("Unable to resolve url: copyright 403")))
    }

    @Test fun badHttpStatusesRemainConnectionFailuresRatherThanCopyrightEvidence() {
        for (status in listOf(401, 403, 404, 429, 500, 503)) {
            val kind = playbackRuntimeFailureKind(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                IOException("HTTP $status"))
            assertEquals(PlaybackRuntimeFailureKind.Connection, kind)
        }
    }

    @Test fun networkCodesAndNestedTransportFailuresNeverBecomeSourceVerdicts() {
        for (code in listOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)) {
            assertEquals(PlaybackRuntimeFailureKind.Connection, playbackRuntimeFailureKind(code, null))
        }
        for (cause in listOf(UnknownHostException(), SocketTimeoutException())) {
            assertEquals(PlaybackRuntimeFailureKind.Connection,
                playbackRuntimeFailureKind(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, IOException(cause)))
        }
    }

    @Test fun localAndResourceAccessErrorsHaveAccurateTemporaryFeedback() {
        assertEquals(PlaybackRuntimeFailureKind.LocalFile,
            playbackRuntimeFailureKind(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, FileNotFoundException()))
        assertEquals(PlaybackRuntimeFailureKind.SourceAccess,
            playbackRuntimeFailureKind(PlaybackException.ERROR_CODE_IO_NO_PERMISSION, null))
        assertEquals(PlaybackRuntimeFailureKind.SourceAccess,
            playbackRuntimeFailureKind(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, IOException(SecurityException())))
    }

    @Test fun recoveryAndQualityClassificationPreserveTheirExistingSameTrackPolicies() {
        val range = PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
        assertTrue(shouldRefreshPlaybackSource(range))
        assertEquals(PlaybackRuntimeFailureKind.SourceRecovery, playbackRuntimeFailureKind(range, null))
        val decoder = PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        assertTrue(shouldTryLowerPlaybackQuality(decoder))
        assertEquals(PlaybackRuntimeFailureKind.AudioFormat, playbackRuntimeFailureKind(decoder, null))
        assertFalse(shouldTryLowerPlaybackQuality(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
    }

    @Test fun repeatedFailureEventsHaveUniqueIdentitiesAndOnlyFixedPublicUiCopy() {
        val first = PlaybackRuntimeFailure("song", "entry", PlaybackRuntimeFailureKind.SourceUnavailable, 2000)
        val second = PlaybackRuntimeFailure("song", "entry", PlaybackRuntimeFailureKind.SourceUnavailable, 2000)
        assertTrue(second.id > first.id)
        assertEquals(first.kind.message, first.message)
        assertEquals(first.message, second.message)
        assertEquals("song", second.mediaId)
        assertEquals("entry", second.queueEntryId)
    }

    @Test fun cyclicExceptionCausesCannotCreateAnUnboundedClassificationLoop() {
        val first = IOException("first")
        val second = IOException("second")
        first.initCause(second)
        second.initCause(first)
        assertEquals(PlaybackRuntimeFailureKind.Unknown,
            playbackRuntimeFailureKind(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, first))
    }
}
