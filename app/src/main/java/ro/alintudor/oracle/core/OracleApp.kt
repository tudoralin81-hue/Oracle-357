package ro.alintudor.oracle.core

import android.app.Application

/**
 * App-wide Context holder — used only by lightweight, purely local logging
 * (OracleNetworkLog) that has no natural Context of its own to reach for.
 * OracleApiClient is a stateless network layer called from dozens of places
 * throughout the app; threading a Context into every one of those call
 * sites just so a handful of log lines can be written would touch far more
 * of the app than the logging itself is worth. Nothing else depends on this.
 */
class OracleApp : Application() {
    companion object {
        lateinit var context: Application
            private set
    }

    override fun onCreate() {
        super.onCreate()
        context = this
    }
}
