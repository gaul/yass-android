package org.gaul.yass;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public final class YassDbHelper extends SQLiteOpenHelper {
    private static final int DATABASE_VERSION = 1;
    private static final String DATABASE_NAME = "yass.db";

    private static final String SQL_CREATE_ENTRIES =
            "CREATE TABLE camera_uploads (" +
                    "serial INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL" +
                    ", file_name TEXT NOT NULL" +
                    ", file_uri TEXT NOT NULL" +
                    ", file_size INTEGER NOT NULL)";
    private static final String SQL_DELETE_ENTRIES =
            "DROP TABLE IF EXISTS camera_uploads";

    public YassDbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_ENTRIES);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL(SQL_DELETE_ENTRIES);
        onCreate(db);
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onUpgrade(db, oldVersion, newVersion);
    }
}
