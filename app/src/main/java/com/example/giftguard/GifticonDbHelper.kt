package com.example.giftguard

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class GifticonDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private val TAG = "GifticonDbHelper"

    companion object {
        const val DATABASE_VERSION = 1
        const val DATABASE_NAME = "GifticonDB.db"

        // 테이블 및 컬럼 정의
        const val TABLE_NAME = "gifticons"
        const val COLUMN_ID = "_id"
        const val COLUMN_TITLE = "title"
        const val COLUMN_CODE = "code"
        const val COLUMN_MEMO = "memo"
        const val COLUMN_IMAGE_URI = "image_uri"
        const val COLUMN_SAVED_DATE = "saved_date"
    }

    private val SQL_CREATE_ENTRIES =
        "CREATE TABLE ${TABLE_NAME} (" +
                "${COLUMN_ID} INTEGER PRIMARY KEY," +
                "${COLUMN_TITLE} TEXT," +
                "${COLUMN_CODE} TEXT UNIQUE," + // 코드는 중복되지 않도록 UNIQUE 설정
                "${COLUMN_MEMO} TEXT," +
                "${COLUMN_IMAGE_URI} TEXT," +
                "${COLUMN_SAVED_DATE} INTEGER)"

    private val SQL_DELETE_ENTRIES = "DROP TABLE IF EXISTS ${TABLE_NAME}"

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_ENTRIES)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL(SQL_DELETE_ENTRIES)
        onCreate(db)
    }

    // 🌟 기프티콘 정보를 데이터베이스에 저장하는 함수
    fun insertGifticon(title: String, code: String, memo: String, imageUri: String): Boolean {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_TITLE, title)
            put(COLUMN_CODE, code)
            put(COLUMN_MEMO, memo)
            put(COLUMN_IMAGE_URI, imageUri)
            put(COLUMN_SAVED_DATE, System.currentTimeMillis())
        }

        // 데이터 삽입. 중복된 코드(UNIQUE 설정됨)가 있으면 실패하고 -1을 반환
        val newRowId = db.insert(TABLE_NAME, null, values)
        db.close()

        if (newRowId == -1L) {
            Log.e(TAG, "기프티콘 저장 실패: 코드 중복 또는 DB 오류")
            return false
        }
        Log.i(TAG, "기프티콘 저장 성공: ID $newRowId")
        return true
    }
}