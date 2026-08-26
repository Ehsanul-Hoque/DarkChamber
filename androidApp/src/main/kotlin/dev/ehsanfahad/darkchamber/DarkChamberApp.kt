package dev.ehsanfahad.darkchamber

import android.app.Application
import dev.ehsanfahad.darkchamber.app.di.appModules
import dev.ehsanfahad.darkchamber.core.logging.initLogging
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * The Android composition-root launcher (cycle 5a). Configures logging and starts Koin with the
 * shared graph ([appModules]); the Android platform module threads the `Context` via
 * [androidContext]. The vault itself is opened lazily by `VaultBootstrap` — nothing cryptographic
 * runs at process start. No `INTERNET`, no network, no analytics (hard rules #3/#7).
 */
class DarkChamberApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initLogging()
        startKoin {
            androidContext(this@DarkChamberApp)
            modules(appModules())
        }
    }
}
