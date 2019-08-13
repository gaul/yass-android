// Copyright 2017 Andrew Gaul <andrew@gaul.org>
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.

package org.gaul.yass;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.OpenableColumns;
import android.util.Log;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectResult;

import java.io.IOException;
import java.io.InputStream;

public final class YassBroadcastReceiver extends BroadcastReceiver {
    // TODO: needs to run once on startup

    private static final String TAG = "YassBroadcastReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction().equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
            Log.d(TAG, "Received connectivity intent: " + intent);
            if (!intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false)) {
                new UploadBlobTask(context).execute((Void) null);
            }
        } else {
            Log.d(TAG, "Received new photo: " + intent.getData().getPath());
            new EnqueueCameraTask(context).execute(intent);
        }
    }

    private final class EnqueueCameraTask extends AsyncTask<Intent, Void, Long> {
        private final Context context;

        EnqueueCameraTask(Context context) {
            this.context = context;
        }

        @Override
        public Long doInBackground(Intent... intent) {
            MainActivity.YassPreferences preferences = new MainActivity.YassPreferences(context);
            if (!preferences.cameraUpload) {
                return null;
            }

            Uri uri = intent[0].getData();
            Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
            int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
            cursor.moveToFirst();
            String fileName = cursor.getString(nameIndex);
            long fileSize = cursor.getLong(sizeIndex);
            cursor.close();

            SQLiteDatabase db = new YassDbHelper(context).getWritableDatabase();

            try {
                ContentValues values = new ContentValues();
                values.put("file_uri", uri.toString());
                values.put("file_name", fileName);
                values.put("file_size", fileSize);
                return db.insert("camera_uploads", null, values);
            } finally {
                db.close();
            }
        }

        @Override
        protected void onPostExecute(Long serial) {
            new UploadBlobTask(context).execute((Void) null);
        }
    }

    private final class UploadBlobTask extends AsyncTask<Void, Void, Long> {
        private final Context context;

        UploadBlobTask(Context context) {
            this.context = context;
        }

        @Override
        public Long doInBackground(Void... unused) {
            Log.d(TAG, "Upload Blob Task:");

            MainActivity.YassPreferences preferences = new MainActivity.YassPreferences(context);

            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            boolean isConnected = activeNetwork != null &&
                      activeNetwork.isConnectedOrConnecting();
            if (!isConnected) {
                Log.d(TAG, "Skipping camera upload because network is disconnected");
                return null;
            }
            if (preferences.cameraUploadOnlyOnWifi && activeNetwork.getType() != ConnectivityManager.TYPE_WIFI) {
                Log.d(TAG, "Skipping camera upload because Wifi is disconnected");
                return null;
            }

            long serial;
            Uri uri;
            String fileName;
            long fileSize;
            SQLiteDatabase db = new YassDbHelper(context).getReadableDatabase();
            try {
                String[] projection = {
                        "serial",
                        "file_uri",
                        "file_name",
                        "file_size"
                };
                String selection = null;
                String[] selectionArgs = null;
                String groupBy = null;
                String having = null;
                String orderBy = "serial ASC";
                String limit = "1";
                Cursor cursor = db.query("camera_uploads", projection, selection, selectionArgs,
                        groupBy, having, orderBy, limit);
                try {
                    if (!cursor.moveToNext()) {
                        Log.d(TAG, "Did not find image to upload");
                        return null;
                    }
                    serial = cursor.getLong(cursor.getColumnIndexOrThrow("serial"));
                    uri = Uri.parse(cursor.getString(cursor.getColumnIndexOrThrow("file_uri")));
                    fileName = cursor.getString(cursor.getColumnIndexOrThrow("file_name"));
                    try {
                        // get real file size since something modifies the file between the intent and uploading
                        AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(uri, "r");
                        fileSize = afd.getLength();
                        afd.close();
                    } catch (IOException ioe) {
                        Log.e(TAG, "Could not get file size: " + fileName);
                        return null;
                    }
                } finally {
                    cursor.close();
                }
            } finally {
                db.close();
            }

            Log.d(TAG, "Found image to upload: " + fileName);

            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(fileSize);
            metadata.setContentType(context.getContentResolver().getType(uri));
            PutObjectResult result;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                // TODO: limited to 5 GB
                result = MainActivity.getS3Client(preferences).putObject(preferences.bucketName,
                        "Camera Uploads/" + fileName, is, metadata);
            } catch (AmazonClientException | IOException e) {
                Log.e(TAG, "Could not upload file: " + e.getMessage());
                return null;
            }
            return serial;
        }

        @Override
        protected void onPostExecute(Long serial) {
            if (serial == null) {
                return;
            }

            SQLiteDatabase db = new YassDbHelper(context).getWritableDatabase();
            try {
                String selection = "serial = ?";
                String[] selectionArgs = {"serial"};
                db.delete("camera_uploads", selection, new String[] {serial.toString()});
            } finally {
                db.close();
            }

            new UploadBlobTask(context).execute((Void) null);
        }
    }
}
