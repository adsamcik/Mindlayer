package com.adsamcik.mindlayer.service.engine

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeferredStoreMigrationTest {
    @Test fun `migration preserves chat rows and adds embedding columns idempotently`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "deferred-migration-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val db = helper.writableDatabase
        db.execSQL("CREATE TABLE deferred_inference (requestId TEXT NOT NULL PRIMARY KEY, uid INTEGER NOT NULL, sessionId TEXT NOT NULL, promptChars INTEGER NOT NULL, mediaCount INTEGER NOT NULL, metricsJson TEXT, resultText TEXT, errorCodeInt INTEGER NOT NULL, errorCodeName TEXT, statusCode INTEGER NOT NULL, createdAtMs INTEGER NOT NULL, completedAtMs INTEGER, expiresAtMs INTEGER NOT NULL, fetchedAtMs INTEGER, truncated INTEGER NOT NULL DEFAULT 0)")
        repeat(3) { i ->
            db.execSQL("INSERT INTO deferred_inference (requestId, uid, sessionId, promptChars, mediaCount, metricsJson, resultText, errorCodeInt, errorCodeName, statusCode, createdAtMs, completedAtMs, expiresAtMs, fetchedAtMs, truncated) VALUES ('chat-" + i + "', 7, 's', 1, 0, NULL, 'ok', 0, NULL, 1, 1, 2, 999, NULL, 0)")
        }
        DeferredDatabase.MIGRATION_2_3.migrate(db)
        db.query("SELECT COUNT(*), MIN(kind), SUM(CASE WHEN blob_path IS NULL THEN 1 ELSE 0 END), SUM(CASE WHEN blob_bytes IS NULL THEN 1 ELSE 0 END), SUM(CASE WHEN per_item_metadata_json IS NULL THEN 1 ELSE 0 END) FROM deferred_inference").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(3, c.getInt(0))
            assertEquals("chat", c.getString(1))
            assertEquals(3, c.getInt(2))
            assertEquals(3, c.getInt(3))
            assertEquals(3, c.getInt(4))
        }
        db.execSQL("INSERT INTO deferred_inference (requestId, uid, sessionId, promptChars, mediaCount, metricsJson, resultText, errorCodeInt, errorCodeName, statusCode, createdAtMs, completedAtMs, expiresAtMs, fetchedAtMs, truncated, kind, blob_path, blob_bytes, per_item_metadata_json) VALUES ('emb', 7, '', 2, 0, NULL, NULL, 0, NULL, 1, 1, 2, 999, NULL, 0, 'embedding', '/blob', 8, '[]')")
        db.query("SELECT kind, blob_path, blob_bytes, per_item_metadata_json FROM deferred_inference WHERE requestId='emb'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("embedding", c.getString(0))
            assertEquals("/blob", c.getString(1))
            assertEquals(8, c.getLong(2))
            assertEquals("[]", c.getString(3))
        }
        DeferredDatabase.MIGRATION_2_3.migrate(db)
        db.query("PRAGMA table_info(deferred_inference)").use { c ->
            var count = 0
            while (c.moveToNext()) if (c.getString(c.getColumnIndex("name")) == "per_item_metadata_json") count++
            assertEquals(1, count)
        }
        db.close()
        helper.close()
        context.deleteDatabase(name)
    }
}