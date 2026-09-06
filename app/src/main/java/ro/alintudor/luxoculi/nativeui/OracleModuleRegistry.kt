package ro.alintudor.luxoculi.nativeui

/** All seven Oracle modules are registered as native application modules. */
object OracleModuleRegistry {
    val modules = listOf("PORTFOLIO","ALERTS","NEWS","GROWTH","KNOWLEDGE","ANALYSIS","WATCHLIST")

    /** News is a first-class native module; its feed is backed by normalized OracleNews records. */
    const val NEWS_MODULE = "ro.alintudor.luxoculi.nativeui.OracleNewsModule"
}
