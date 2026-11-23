package app.lintas.ui

import app.lintas.update.ReleaseInfo
import app.lintas.update.UpdateState
import app.lintas.update.UpdateStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateUiTest {
    private val release = ReleaseInfo(
        versionName = "1.0.8",
        tag = "v1.0.8",
        notes = "",
        pageUrl = "https://github.com/hafizhlf/lintas-vpn/releases/tag/v1.0.8",
        apkUrl = "https://example.com/lintas-1.0.8.apk",
        apkSize = 1,
        apkSha256 = null,
    )

    @Test fun errorActionMatchesDialogRetry() {
        val state = UpdateState(
            status = UpdateStatus.ERROR,
            currentVersion = "1.0.7",
            error = "Gagal",
        )
        assertEquals("Coba lagi", updateAction(state))
    }

    @Test fun readyActionIsInstall() {
        val state = UpdateState(
            status = UpdateStatus.READY,
            currentVersion = "1.0.7",
            release = release,
        )
        assertEquals("Pasang", updateAction(state))
    }

    @Test fun availableSubtitleWinsOverJustUpdatedNotice() {
        val state = UpdateState(
            status = UpdateStatus.AVAILABLE,
            currentVersion = "1.0.7",
            release = release,
            justUpdatedTo = "1.0.7",
        )
        assertEquals("Versi 1.0.8 tersedia", updateSubtitle(state))
    }

    @Test fun justUpdatedShowsWhenUpToDate() {
        val state = UpdateState(
            status = UpdateStatus.UP_TO_DATE,
            currentVersion = "1.0.8",
            justUpdatedTo = "1.0.8",
        )
        assertEquals("Diperbarui ke 1.0.8", updateSubtitle(state))
    }
}
