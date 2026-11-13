package dev.glasslauncher.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** A disk that cannot take a write (full storage) must leave the saved config alone and never throw into the launcher (a crash found by the monkey on a full /data). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ConfigStoreFullDiskTest {
    @Test fun aFailedWriteDoesNotThrowAndKeepsTheSavedConfig() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // Its own DataStore on this test's files: the launcher's is one per process and stays on whichever test's folder came first.
            val dir = File(context.filesDir, "full-disk-test").also { it.mkdirs() }
            val files = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = scope) { File(dir, "launcher.preferences_pb") }
            val store = ConfigStore(context, scope, files)
            assertTrue(runBlocking { store.update { it.copy(boldText = true) } })
            assertTrue("the store writes into ${dir.path}", dir.isDirectory)
            // `config` follows the DataStore from another thread, so the value lands after update() returns: wait for it, in real time.
            val deadline = System.nanoTime() + 20_000_000_000L
            while (!store.config.value.boldText && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("the first write reaches the config", store.config.value.boldText)
            // A directory nothing can be created in: the write's temp file fails with an IOException, as ENOSPC does.
            check(dir.setWritable(false)) { "cannot make the directory read-only here" }
            try {
                val writes = store.writes
                assertFalse(runBlocking { store.update { it.copy(boldText = false) } })
                assertEquals("no write was counted", writes, store.writes)
                assertTrue("the saved config is unchanged", runBlocking { kotlinx.coroutines.flow.flowOf(store.config.value).let { store.config.value.boldText } })
            } finally { dir.setWritable(true) }
        } finally { scope.cancel() }
    }
}
