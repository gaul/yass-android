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
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectResult;

import java.io.IOException;
import java.io.InputStream;

public final class YassBroadcastReceiver extends BroadcastReceiver {
    private static final String TAG = "YassBroadcastReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Received new photo: " + intent.getData().getPath());
        new UploadBlobTask(context).execute(intent);
    }

    public String getRealPathFromURI(Uri contentUri, Context context) {
        try {
            String[] proj = {MediaStore.Images.Media.DATA};

            Cursor cursor =  context.getContentResolver().query(contentUri, proj, null, null, null);
            int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
            cursor.moveToFirst();
            String path = cursor.getString(column_index);
            cursor.close();
            return path;
        } catch (Exception e) {
            return contentUri.getPath();
        }
    }

    // TODO: needs to work offline
    private final class UploadBlobTask extends AsyncTask<Intent, Void, String> {
        private final Context context;

        UploadBlobTask(Context context) {
            this.context = context;
        }

        @Override
        public String doInBackground(Intent... intent) {
            MainActivity.YassPreferences preferences = new MainActivity.YassPreferences(context);
            if (!preferences.cameraUpload) {
                return null;
            }

            String[] proj = {MediaStore.Images.Media.DATA};

            Uri uri = intent[0].getData();
            Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
            int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
            cursor.moveToFirst();
            String fileName = cursor.getString(nameIndex);
            long fileSize = cursor.getLong(sizeIndex);
            cursor.close();
            Log.i(TAG, "Handling intent: " + fileName + " " + fileSize);

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
            return result.getETag();
        }

        @Override
        protected void onPostExecute(String eTag) {
        }
    }
}
