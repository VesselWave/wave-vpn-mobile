// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.qa

import android.app.Application
import androidx.test.runner.AndroidJUnitRunner

/**
 * Seeds a synthetic, offline session before WaveApp.onCreate restores it, so the real
 * repository/TunnelManager/UI run without an AirVPN account or network request.
 *
 * Refuses to run in any package other than the isolated qa build: seeding replaces the
 * stored session, which must never happen to the signed-in debug or release installs.
 */
class ForumQaRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        check(app.packageName == QA_PACKAGE) {
            "Forum QA tests only run in $QA_PACKAGE (got ${app.packageName}); they replace the stored session"
        }
        SyntheticSession.seed(app)
        super.callApplicationOnCreate(app)
    }

    companion object { const val QA_PACKAGE = "me.vwave.vpn.qa" }
}
