/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tokeng.gms.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class TokenDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_ACCOUNTS (
                $COL_INSTANCE_ID TEXT PRIMARY KEY NOT NULL,
                $COL_EMAIL TEXT NOT NULL,
                $COL_MASTER_TOKEN TEXT NOT NULL,
                $COL_AAS_TOKEN TEXT,
                $COL_SID TEXT,
                $COL_LSID TEXT,
                $COL_ANDROID_ID TEXT NOT NULL,
                $COL_SECURITY_TOKEN TEXT NOT NULL,
                $COL_DEVICE_NAME TEXT NOT NULL,
                $COL_DEVICE_MODEL TEXT NOT NULL,
                $COL_DEVICE_BRAND TEXT NOT NULL,
                $COL_DEVICE_FINGERPRINT TEXT NOT NULL,
                $COL_DEVICE_SDK INTEGER NOT NULL,
                $COL_FIRST_NAME TEXT,
                $COL_LAST_NAME TEXT,
                $COL_GOOGLE_USER_ID TEXT,
                $COL_SYNC_STATUS TEXT NOT NULL DEFAULT 'PENDING',
                $COL_REGISTERED_AT INTEGER NOT NULL,
                $COL_LAST_SYNC_AT INTEGER NOT NULL DEFAULT 0,
                $COL_ACCOUNT_STATUS TEXT NOT NULL DEFAULT '$STATUS_ACTIVE',
                $COL_LAST_VALIDATED_AT INTEGER NOT NULL DEFAULT 0,
                $COL_LAST_VALIDATION_RESULT TEXT,
                $COL_SIGNED_OUT_REASON TEXT,
                $COL_AUTH_FAIL_COUNT INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tokeng_email ON $TABLE_ACCOUNTS($COL_EMAIL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tokeng_sync ON $TABLE_ACCOUNTS($COL_SYNC_STATUS)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tokeng_status ON $TABLE_ACCOUNTS($COL_ACCOUNT_STATUS)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            addColumnIfMissing(db, TABLE_ACCOUNTS, COL_ACCOUNT_STATUS, "TEXT NOT NULL DEFAULT '$STATUS_ACTIVE'")
            addColumnIfMissing(db, TABLE_ACCOUNTS, COL_LAST_VALIDATED_AT, "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, TABLE_ACCOUNTS, COL_LAST_VALIDATION_RESULT, "TEXT")
            addColumnIfMissing(db, TABLE_ACCOUNTS, COL_SIGNED_OUT_REASON, "TEXT")
            addColumnIfMissing(db, TABLE_ACCOUNTS, COL_AUTH_FAIL_COUNT, "INTEGER NOT NULL DEFAULT 0")
            try {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_tokeng_status ON $TABLE_ACCOUNTS($COL_ACCOUNT_STATUS)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed creating status index", e)
            }
        }
        if (oldVersion < 3) {
            addColumnIfMissing(db, TABLE_ACCOUNTS, COL_INSTANCE_ID, "TEXT")
            try {
                db.execSQL("UPDATE $TABLE_ACCOUNTS SET $COL_INSTANCE_ID = $COL_EMAIL WHERE $COL_INSTANCE_ID IS NULL OR $COL_INSTANCE_ID = ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_tokeng_instance ON $TABLE_ACCOUNTS($COL_INSTANCE_ID)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_tokeng_email ON $TABLE_ACCOUNTS($COL_EMAIL)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed creating instance index or backfilling", e)
            }
        }
    }

    private fun addColumnIfMissing(db: SQLiteDatabase, table: String, col: String, def: String) {
        var cursor: Cursor? = null
        try {
            cursor = db.rawQuery("PRAGMA table_info($table)", null)
            var exists = false
            if (cursor != null && cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex("name")
                do {
                    if (nameIdx != -1 && cursor.getString(nameIdx) == col) {
                        exists = true
                        break
                    }
                } while (cursor.moveToNext())
            }
            if (!exists) {
                db.execSQL("ALTER TABLE $table ADD COLUMN $col $def")
                Log.d(TAG, "Added column $col to table $table")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed checking/adding column $col", e)
        } finally {
            cursor?.close()
        }
    }

    @Synchronized
    fun insertOrUpdate(account: TokenAccount): Boolean {
        return try {
            val db = writableDatabase
            // Clean up any existing row with this email but different instanceId to guarantee uniqueness per email
            db.delete(TABLE_ACCOUNTS, "$COL_EMAIL = ? AND $COL_INSTANCE_ID != ?", arrayOf(account.email, account.instanceId))

            val values = ContentValues().apply {
                put(COL_INSTANCE_ID, account.instanceId)
                put(COL_EMAIL, account.email)
                put(COL_MASTER_TOKEN, account.masterToken)
                put(COL_AAS_TOKEN, account.aasToken)
                put(COL_SID, account.sid)
                put(COL_LSID, account.lsid)
                put(COL_ANDROID_ID, account.androidId)
                put(COL_SECURITY_TOKEN, account.securityToken)
                put(COL_DEVICE_NAME, account.deviceName)
                put(COL_DEVICE_MODEL, account.deviceModel)
                put(COL_DEVICE_BRAND, account.deviceBrand)
                put(COL_DEVICE_FINGERPRINT, account.deviceFingerprint)
                put(COL_DEVICE_SDK, account.deviceSdk)
                put(COL_FIRST_NAME, account.firstName)
                put(COL_LAST_NAME, account.lastName)
                put(COL_GOOGLE_USER_ID, account.googleUserId)
                put(COL_SYNC_STATUS, account.syncStatus)
                put(COL_REGISTERED_AT, account.registeredAt)
                put(COL_LAST_SYNC_AT, account.lastSyncAt)
                put(COL_ACCOUNT_STATUS, account.accountStatus)
                put(COL_LAST_VALIDATED_AT, account.lastValidatedAt)
                put(COL_LAST_VALIDATION_RESULT, account.lastValidationResult)
                put(COL_SIGNED_OUT_REASON, account.signedOutReason)
                put(COL_AUTH_FAIL_COUNT, account.consecutiveAuthFailures)
            }
            val id = db.insertWithOnConflict(TABLE_ACCOUNTS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            Log.d(TAG, "Saved account ${account.email} (instanceId=${account.instanceId}) to native database (rowId=$id)")
            id != -1L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert/update account ${account.email}", e)
            false
        }
    }

    @Synchronized
    fun getAccountByInstanceId(instanceId: String): TokenAccount? {
        val db = readableDatabase
        var cursor: Cursor? = null
        return try {
            cursor = db.query(
                TABLE_ACCOUNTS,
                null,
                "$COL_INSTANCE_ID = ?",
                arrayOf(instanceId),
                null,
                null,
                null
            )
            if (cursor != null && cursor.moveToFirst()) {
                cursorToAccount(cursor)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get account by instanceId $instanceId", e)
            null
        } finally {
            cursor?.close()
        }
    }

    @Synchronized
    fun getAccountsByEmail(email: String): List<TokenAccount> {
        val list = mutableListOf<TokenAccount>()
        val db = readableDatabase
        var cursor: Cursor? = null
        try {
            cursor = db.query(
                TABLE_ACCOUNTS,
                null,
                "$COL_EMAIL = ?",
                arrayOf(email),
                null,
                null,
                "$COL_REGISTERED_AT ASC"
            )
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    list.add(cursorToAccount(cursor))
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get accounts for email $email", e)
        } finally {
            cursor?.close()
        }
        return list
    }

    @Synchronized
    fun deleteAccountByInstanceId(instanceId: String): Boolean {
        return try {
            val db = writableDatabase
            val rows = db.delete(TABLE_ACCOUNTS, "$COL_INSTANCE_ID = ?", arrayOf(instanceId))
            Log.d(TAG, "Deleted account instance $instanceId (rows=$rows)")
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete account by instanceId $instanceId", e)
            false
        }
    }

    @Synchronized
    fun getAccount(email: String): TokenAccount? {
        val db = readableDatabase
        var cursor: Cursor? = null
        return try {
            cursor = db.query(
                TABLE_ACCOUNTS,
                null,
                "$COL_EMAIL = ?",
                arrayOf(email),
                null,
                null,
                "CASE WHEN $COL_ACCOUNT_STATUS = '$STATUS_ACTIVE' THEN 0 ELSE 1 END, $COL_REGISTERED_AT DESC"
            )
            if (cursor != null && cursor.moveToFirst()) {
                cursorToAccount(cursor)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get account $email", e)
            null
        } finally {
            cursor?.close()
        }
    }

    @Synchronized
    fun getAllAccounts(): List<TokenAccount> {
        val list = mutableListOf<TokenAccount>()
        val db = readableDatabase
        var cursor: Cursor? = null
        try {
            cursor = db.query(
                TABLE_ACCOUNTS,
                null,
                null,
                null,
                null,
                null,
                "$COL_REGISTERED_AT ASC"
            )
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    list.add(cursorToAccount(cursor))
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get all accounts", e)
        } finally {
            cursor?.close()
        }
        return list
    }

    @Synchronized
    fun deleteAccount(email: String): Boolean {
        return try {
            val db = writableDatabase
            val rows = db.delete(TABLE_ACCOUNTS, "$COL_EMAIL = ?", arrayOf(email))
            Log.d(TAG, "Deleted account $email (rows=$rows)")
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete account $email", e)
            false
        }
    }

    @Synchronized
    fun deleteAllAccounts(): Boolean {
        return try {
            val db = writableDatabase
            val rows = db.delete(TABLE_ACCOUNTS, null, null)
            Log.d(TAG, "Deleted all accounts (rows=$rows)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete all accounts", e)
            false
        }
    }

    @Synchronized
    fun getUnsyncedAccounts(): List<TokenAccount> {
        val list = mutableListOf<TokenAccount>()
        val db = readableDatabase
        var cursor: Cursor? = null
        try {
            cursor = db.query(
                TABLE_ACCOUNTS,
                null,
                "$COL_SYNC_STATUS != ?",
                arrayOf("SYNCED"),
                null,
                null,
                "$COL_REGISTERED_AT ASC"
            )
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    list.add(cursorToAccount(cursor))
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get unsynced accounts", e)
        } finally {
            cursor?.close()
        }
        return list
    }

    @Synchronized
    fun getLocalOnlyAccounts(): List<TokenAccount> {
        val list = mutableListOf<TokenAccount>()
        val db = readableDatabase
        var cursor: Cursor? = null
        try {
            cursor = db.query(
                TABLE_ACCOUNTS,
                null,
                "$COL_SYNC_STATUS = ? OR $COL_SYNC_STATUS = ?",
                arrayOf("LOCAL_ONLY", "PENDING"),
                null,
                null,
                "$COL_REGISTERED_AT ASC"
            )
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    list.add(cursorToAccount(cursor))
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local-only accounts", e)
        } finally {
            cursor?.close()
        }
        return list
    }

    @Synchronized
    fun deleteSyncedAccounts(): Int {
        return try {
            val db = writableDatabase
            val rows = db.delete(TABLE_ACCOUNTS, "$COL_SYNC_STATUS = ?", arrayOf("SYNCED"))
            Log.d(TAG, "Deleted previous synced accounts (rows=$rows)")
            rows
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete synced accounts", e)
            0
        }
    }

    @Synchronized
    fun markRemainingAsLocalOnly(): Int {
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COL_SYNC_STATUS, "LOCAL_ONLY")
            }
            val rows = db.update(TABLE_ACCOUNTS, values, "$COL_SYNC_STATUS != ?", arrayOf("SYNCED"))
            Log.d(TAG, "Marked remaining accounts as LOCAL_ONLY (rows=$rows)")
            rows
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark remaining accounts as LOCAL_ONLY", e)
            0
        }
    }

    @Synchronized
    fun backupLocalAccounts(): Boolean {
        return try {
            val db = writableDatabase
            db.execSQL("DROP TABLE IF EXISTS ${TABLE_ACCOUNTS}_backup")
            db.execSQL("CREATE TABLE ${TABLE_ACCOUNTS}_backup AS SELECT * FROM $TABLE_ACCOUNTS")
            Log.i(TAG, "Backed up local accounts table to ${TABLE_ACCOUNTS}_backup successfully.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to backup local accounts", e)
            false
        }
    }

    @Synchronized
    fun restoreLocalAccounts(): Boolean {
        return try {
            val db = writableDatabase
            db.execSQL("DELETE FROM $TABLE_ACCOUNTS")
            db.execSQL("INSERT OR REPLACE INTO $TABLE_ACCOUNTS SELECT * FROM ${TABLE_ACCOUNTS}_backup")
            Log.i(TAG, "Restored local accounts from backup successfully.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore local accounts from backup", e)
            false
        }
    }

    @Synchronized
    fun updateAasToken(email: String, aasToken: String): Boolean {
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COL_AAS_TOKEN, aasToken)
            }
            val rows = db.update(TABLE_ACCOUNTS, values, "$COL_EMAIL = ?", arrayOf(email))
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update AAS token for $email", e)
            false
        }
    }

    @Synchronized
    fun updateSyncStatus(email: String, status: String, timestamp: Long): Boolean {
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COL_SYNC_STATUS, status)
                put(COL_LAST_SYNC_AT, timestamp)
            }
            val rows = db.update(TABLE_ACCOUNTS, values, "$COL_EMAIL = ?", arrayOf(email))
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update sync status for $email", e)
            false
        }
    }

    @Synchronized
    fun count(): Int {
        val db = readableDatabase
        var cursor: Cursor? = null
        return try {
            cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_ACCOUNTS", null)
            if (cursor != null && cursor.moveToFirst()) {
                cursor.getInt(0)
            } else {
                0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to count accounts", e)
            0
        } finally {
            cursor?.close()
        }
    }

    @Synchronized
    fun markValidated(email: String, masterToken: String, timestamp: Long, result: String): Boolean {
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COL_ACCOUNT_STATUS, STATUS_ACTIVE)
                put(COL_LAST_VALIDATED_AT, timestamp)
                put(COL_LAST_VALIDATION_RESULT, result)
                put(COL_SIGNED_OUT_REASON, null as String?)
                put(COL_AUTH_FAIL_COUNT, 0)
            }
            val rows = db.update(TABLE_ACCOUNTS, values, "$COL_EMAIL = ? AND $COL_MASTER_TOKEN = ?", arrayOf(email, masterToken))
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to markValidated for $email", e)
            false
        }
    }

    @Synchronized
    fun markTransient(email: String, timestamp: Long, result: String): Boolean {
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COL_LAST_VALIDATED_AT, timestamp)
                put(COL_LAST_VALIDATION_RESULT, result)
            }
            val rows = db.update(TABLE_ACCOUNTS, values, "$COL_EMAIL = ?", arrayOf(email))
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to markTransient for $email", e)
            false
        }
    }

    @Synchronized
    fun incrementAuthFailure(email: String, masterToken: String): Int {
        val db = writableDatabase
        return try {
            db.beginTransaction()
            var currentFailures = 0
            val cursor = db.rawQuery(
                "SELECT $COL_AUTH_FAIL_COUNT FROM $TABLE_ACCOUNTS WHERE $COL_EMAIL = ? AND $COL_MASTER_TOKEN = ?",
                arrayOf(email, masterToken)
            )
            if (cursor.moveToFirst()) {
                currentFailures = cursor.getInt(0)
            }
            cursor.close()

            val newFailures = currentFailures + 1
            val values = ContentValues().apply {
                put(COL_AUTH_FAIL_COUNT, newFailures)
            }
            db.update(TABLE_ACCOUNTS, values, "$COL_EMAIL = ? AND $COL_MASTER_TOKEN = ?", arrayOf(email, masterToken))
            db.setTransactionSuccessful()
            newFailures
        } catch (e: Exception) {
            Log.e(TAG, "Failed to incrementAuthFailure for $email", e)
            0
        } finally {
            try { db.endTransaction() } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun markSignedOut(email: String, masterToken: String, reason: String, timestamp: Long): Boolean {
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COL_ACCOUNT_STATUS, STATUS_SIGNED_OUT)
                put(COL_SIGNED_OUT_REASON, reason)
                put(COL_LAST_VALIDATED_AT, timestamp)
                put(COL_LAST_VALIDATION_RESULT, "INVALID")
            }
            val rows = db.update(TABLE_ACCOUNTS, values, "$COL_EMAIL = ? AND $COL_MASTER_TOKEN = ?", arrayOf(email, masterToken))
            Log.w(TAG, "Account $email marked SIGNED_OUT (reason: $reason, rows=$rows)")
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to markSignedOut for $email", e)
            false
        }
    }

    @Synchronized
    fun updateAccountStatus(email: String, status: String): Boolean {
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COL_ACCOUNT_STATUS, status)
                if (status == STATUS_ACTIVE) {
                    put(COL_AUTH_FAIL_COUNT, 0)
                    put(COL_SIGNED_OUT_REASON, null as String?)
                }
            }
            val rows = db.update(TABLE_ACCOUNTS, values, "$COL_EMAIL = ?", arrayOf(email))
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update account status for $email", e)
            false
        }
    }

    private fun cursorToAccount(c: Cursor): TokenAccount {
        return TokenAccount(
            email = c.getString(c.getColumnIndexOrThrow(COL_EMAIL)) ?: "",
            masterToken = c.getString(c.getColumnIndexOrThrow(COL_MASTER_TOKEN)) ?: "",
            aasToken = c.getString(c.getColumnIndexOrThrow(COL_AAS_TOKEN)),
            sid = c.getString(c.getColumnIndexOrThrow(COL_SID)),
            lsid = c.getString(c.getColumnIndexOrThrow(COL_LSID)),
            androidId = c.getString(c.getColumnIndexOrThrow(COL_ANDROID_ID)) ?: "",
            securityToken = c.getString(c.getColumnIndexOrThrow(COL_SECURITY_TOKEN)) ?: "",
            deviceName = c.getString(c.getColumnIndexOrThrow(COL_DEVICE_NAME)) ?: "Unknown Device",
            deviceModel = c.getString(c.getColumnIndexOrThrow(COL_DEVICE_MODEL)) ?: "Unknown Model",
            deviceBrand = c.getString(c.getColumnIndexOrThrow(COL_DEVICE_BRAND)) ?: "Unknown Brand",
            deviceFingerprint = c.getString(c.getColumnIndexOrThrow(COL_DEVICE_FINGERPRINT)) ?: "",
            deviceSdk = c.getInt(c.getColumnIndexOrThrow(COL_DEVICE_SDK)),
            firstName = c.getString(c.getColumnIndexOrThrow(COL_FIRST_NAME)),
            lastName = c.getString(c.getColumnIndexOrThrow(COL_LAST_NAME)),
            googleUserId = c.getString(c.getColumnIndexOrThrow(COL_GOOGLE_USER_ID)),
            syncStatus = c.getString(c.getColumnIndexOrThrow(COL_SYNC_STATUS)) ?: "PENDING",
            registeredAt = c.getLong(c.getColumnIndexOrThrow(COL_REGISTERED_AT)),
            lastSyncAt = c.getLong(c.getColumnIndexOrThrow(COL_LAST_SYNC_AT)),
            accountStatus = if (c.getColumnIndex(COL_ACCOUNT_STATUS) != -1) (c.getString(c.getColumnIndex(COL_ACCOUNT_STATUS)) ?: STATUS_ACTIVE) else STATUS_ACTIVE,
            lastValidatedAt = if (c.getColumnIndex(COL_LAST_VALIDATED_AT) != -1) c.getLong(c.getColumnIndex(COL_LAST_VALIDATED_AT)) else 0L,
            lastValidationResult = if (c.getColumnIndex(COL_LAST_VALIDATION_RESULT) != -1) c.getString(c.getColumnIndex(COL_LAST_VALIDATION_RESULT)) else null,
            signedOutReason = if (c.getColumnIndex(COL_SIGNED_OUT_REASON) != -1) c.getString(c.getColumnIndex(COL_SIGNED_OUT_REASON)) else null,
            consecutiveAuthFailures = if (c.getColumnIndex(COL_AUTH_FAIL_COUNT) != -1) c.getInt(c.getColumnIndex(COL_AUTH_FAIL_COUNT)) else 0,
            instanceId = if (c.getColumnIndex(COL_INSTANCE_ID) != -1 && !c.isNull(c.getColumnIndex(COL_INSTANCE_ID))) {
                c.getString(c.getColumnIndex(COL_INSTANCE_ID))
            } else {
                c.getString(c.getColumnIndexOrThrow(COL_EMAIL))
            }
        )
    }

    companion object {
        private const val TAG = "TokenDatabase"
        private const val DB_NAME = "tokeng_accounts.db"
        private const val DB_VERSION = 3

        const val TABLE_ACCOUNTS = "tokeng_accounts"
        const val COL_INSTANCE_ID = "instance_id"
        const val COL_EMAIL = "email"
        const val COL_MASTER_TOKEN = "master_token"
        const val COL_AAS_TOKEN = "aas_token"
        const val COL_SID = "sid"
        const val COL_LSID = "lsid"
        const val COL_ANDROID_ID = "android_id"
        const val COL_SECURITY_TOKEN = "security_token"
        const val COL_DEVICE_NAME = "device_name"
        const val COL_DEVICE_MODEL = "device_model"
        const val COL_DEVICE_BRAND = "device_brand"
        const val COL_DEVICE_FINGERPRINT = "device_fingerprint"
        const val COL_DEVICE_SDK = "device_sdk"
        const val COL_FIRST_NAME = "first_name"
        const val COL_LAST_NAME = "last_name"
        const val COL_GOOGLE_USER_ID = "google_user_id"
        const val COL_SYNC_STATUS = "sync_status"
        const val COL_REGISTERED_AT = "registered_at"
        const val COL_LAST_SYNC_AT = "last_sync_at"
        const val COL_ACCOUNT_STATUS = "account_status"
        const val COL_LAST_VALIDATED_AT = "last_validated_at"
        const val COL_LAST_VALIDATION_RESULT = "last_validation_result"
        const val COL_SIGNED_OUT_REASON = "signed_out_reason"
        const val COL_AUTH_FAIL_COUNT = "consecutive_auth_failures"

        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_SIGNED_OUT = "SIGNED_OUT"

        @Volatile
        private var instance: TokenDatabase? = null

        @JvmStatic
        fun getInstance(context: Context): TokenDatabase {
            return instance ?: synchronized(this) {
                instance ?: TokenDatabase(context.applicationContext).also { instance = it }
            }
        }
    }
}

