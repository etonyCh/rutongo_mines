package rw.rutongo.mmems.android

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import rw.rutongo.mmems.android.data.AppSettings
import rw.rutongo.mmems.android.data.DatabaseKeyStore
import rw.rutongo.mmems.android.data.EncryptedTokenStore
import rw.rutongo.mmems.android.data.KtorMmemsApi
import rw.rutongo.mmems.android.data.LocalStore
import rw.rutongo.mmems.android.data.MmemsApi
import rw.rutongo.mmems.android.data.TokenStore
import rw.rutongo.mmems.android.db.MmemsDatabase
import rw.rutongo.mmems.android.sync.SyncEngine

class AppContainer(
    context: Context,
) {
    val appContext: Context = context.applicationContext
    val settings = AppSettings(context)
    val tokens: TokenStore = EncryptedTokenStore(context)
    private val driver =
        AndroidSqliteDriver(
            schema = MmemsDatabase.Schema,
            context = context,
            name = "mmems.db",
            factory = SupportOpenHelperFactory(DatabaseKeyStore.passphrase(context)),
        )
    val store = LocalStore(driver)
    val api: MmemsApi = KtorMmemsApi(tokens, settings)
    val syncEngine = SyncEngine(store, api)
}
