package rw.rutongo.mmems.android

import android.app.Application
import rw.rutongo.mmems.android.sync.SyncWorker

class MmemsApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher")
        container = AppContainer(this)
        SyncWorker.schedule(this)
    }
}
