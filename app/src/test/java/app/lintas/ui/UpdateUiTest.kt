package app.lintas.ui

import app.lintas.update.ReleaseInfo
import app.lintas.update.UpdateState
import app.lintas.update.UpdateStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateUiTest {
    private val release = ReleaseInfo(
        versionName = "1.0.8",
        notes = "",
        pageUrl = "https://github.com/hafizhlf/lintas-vpn/releases/tag/v1.0.8",
    )

    @Test fun errorActionMatchesDialogRetry() {
        val state = UpdateState(
            status = UpdateStatus.ERROR,
            currentVersion = "1.0.7",
            error = "Gagal",
        )
        assertEquals("Coba lagi", updateAction(state))
    }

    @Test fun availableActionOpensReleasePage() {
        val state = UpdateState(
            status = UpdateStatus.AVAILABLE,
            currentVersion = "1.0.7",
            release = release,
        )
        assertEquals("Buka halaman rilis", updateAction(state))
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
