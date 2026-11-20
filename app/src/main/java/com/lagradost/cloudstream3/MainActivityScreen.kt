package com.lagradost.cloudstream3

import androidx.compose.runtime.Composable
import com.lagradost.cloudstream4.compose.Screen

object MainActivityScreen : Screen {

    @Composable
    override fun Content() {
        // Fork (Audit-3 #24): this screen used to render upstream's
        // Compose GitHub updater dialog. The fork removed app
        // self-update entirely (see InAppUpdater), and the viewmodel
        // behind this screen was hardcoded to null, leaving ~600
        // lines of dead updater code compiled in. It is deleted
        // now; the screen stays as an empty overlay so the existing
        // call in MainActivity keeps working.
    }
}
