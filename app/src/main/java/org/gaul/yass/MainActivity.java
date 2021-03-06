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

import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import com.amazonaws.AmazonClientException;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class MainActivity extends AppCompatActivity {
    private static final String TAG = "yass";
    private AmazonS3 client;
    private YassPreferences preferences;
    private ListView mListView;
    private final List<String> listItems = new ArrayList<String>();
    private ArrayAdapter<String> adapter;
    private String prefix = "";
    private ObjectListing listing;
    private SharedPreferences.OnSharedPreferenceChangeListener listener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                    MainActivity.this.preferences = new YassPreferences(getApplicationContext());
                    MainActivity.this.client = getS3Client(MainActivity.this.preferences);
                }
            };

    public MainActivity() {
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        prefs.registerOnSharedPreferenceChangeListener(listener);
        preferences = new YassPreferences(getApplicationContext());
        // TODO: if prefs not set, show settings

        client = getS3Client(preferences);

        this.mListView = (ListView) findViewById(R.id.blob_list_view);
        this.adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, listItems);
        mListView.setAdapter(adapter);
        mListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int position, long arg3) {
                String path = prefix + listItems.get(position);
                if (listing.getCommonPrefixes().contains(path)) {
                    MainActivity.this.prefix = path;
                    new BlobListTask().execute(path);
                } else {
                    new SelectBlobTask().execute(path);
                }
            }
        });
        // TODO: long press
        new BlobListTask().execute("");
    }

    @Override
    public void onBackPressed() {
        if (prefix.equals("")) {
            super.onBackPressed();
        }

        int index = prefix.lastIndexOf('/', prefix.length() - 2);
        prefix = prefix.substring(0, index + 1);
        Log.i(TAG, "Changing prefix to: " + prefix);
        new BlobListTask().execute(prefix);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_reload:
                Log.i(TAG, "reload");
                new BlobListTask().execute(this.prefix);
                return true;

            case R.id.action_settings:
                Log.i(TAG, "settings");
                Intent myIntent = new Intent(MainActivity.this, SettingsActivity.class);
                startActivity(myIntent);
                return true;

            default:
                return super.onOptionsItemSelected(item);

        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu, menu);
        return true;
    }

    private class BlobListTask extends AsyncTask<String, Void, Collection<String>> {
        @Override
        public Collection<String> doInBackground(String... path) {
            List<String> listItems = new ArrayList<>();
            String prefix = path[0];
            try {
                MainActivity.this.listing = client.listObjects(new ListObjectsRequest()
                        .withBucketName(preferences.bucketName)
                        .withDelimiter("/")
                        .withPrefix(prefix));
            } catch (AmazonClientException ace) {
                Log.e(TAG, "Error listing with prefix: " + prefix + " " + ace.getMessage());
                return null;
            }
            for (S3ObjectSummary summary : MainActivity.this.listing.getObjectSummaries()) {
                String key = summary.getKey();
                Log.d(TAG, "listing key: " + key);
                if (key.equals(prefix)) {
                    continue;
                }
                listItems.add(key.substring(prefix.length()));
            }
            for (String commonPrefix : MainActivity.this.listing.getCommonPrefixes()) {
                Log.d(TAG, "listing common prefix: " + commonPrefix);
                listItems.add(commonPrefix.substring(prefix.length()));
            }
            Collections.sort(listItems);
            return listItems;
        }

        @Override
        protected void onPostExecute(Collection<String> listItems) {
            if (listItems == null) {
                Toast.makeText(MainActivity.this, "Could not list keys", Toast.LENGTH_LONG).show();
                return;
            }
            MainActivity.this.listItems.clear();
            MainActivity.this.listItems.addAll(listItems);
            adapter.notifyDataSetChanged();
        }
    }

    private class SelectBlobTask extends AsyncTask<String, Integer, File> {
        private ProgressDialog dialog;
        private S3Object object;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            dialog = new ProgressDialog(MainActivity.this);
            dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            dialog.setMessage("Downloading...");
            // TODO: use human-friendly units via setProgressNumberFormat
            dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                    cancel(false);
                }
            });
            dialog.show();
        }

        @Override
        public File doInBackground(String... path) {
            String key = path[0];
            Log.d(TAG, "downloading: " + key);

            S3Object object;
            try {
                object = client.getObject(preferences.bucketName, path[0]);
            } catch (AmazonClientException ace) {
                Log.e(TAG, "Error getting blob: " + key + " " + ace.getMessage());
                return null;
            }
            long contentLength = object.getObjectMetadata().getContentLength();
            dialog.setMax((int) contentLength);
            File file;
            try {
                String eTag = object.getObjectMetadata().getETag();
                if (eTag == null) {
                    // Some object stores do not return a sensible ETag, e.g., S3Proxy with
                    // filesystem backend.
                    eTag = UUID.randomUUID().toString();
                }
                file = File.createTempFile(eTag, null, MainActivity.this.getCacheDir());
                byte[] buffer = new byte[4096];
                try (InputStream is = object.getObjectContent();
                     OutputStream os = new FileOutputStream(file)) {
                    long progress = 0;
                    while (true) {
                        int count = is.read(buffer);
                        if (count == -1) {
                            break;
                        }
                        os.write(buffer, 0, count);
                        progress += count;
                        publishProgress((int) progress);
                        if (isCancelled()) {
                            Log.i(TAG, "Cancelling: " + key);
                            return null;
                        }
                    }
                }
            } catch (IOException ioe) {
                Log.e(TAG, "Error downloading blob: " + key + " " + ioe.getMessage());
                return null;
            }

            this.object = object;
            return file;
        }

        @Override
        protected void onProgressUpdate(Integer... progress) {
            dialog.setProgress(progress[0]);
        }

        @Override
        protected void onPostExecute(File file) {
            super.onPostExecute(file);
            if (dialog != null) {
                dialog.dismiss();
            }

            if (file == null) {
                Toast.makeText(MainActivity.this, "Could not download file", Toast.LENGTH_LONG).show();
                return;
            }

            Uri uri = FileProvider.getUriForFile(MainActivity.this, "org.gaul.yass", file);
            String mime = object.getObjectMetadata().getContentType();

            if (mime == null || mime.equals("binary/octet-stream")) {
                int index = object.getKey().lastIndexOf('.');
                if (index != -1) {
                    mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                            object.getKey().substring(index + 1));
                    Log.d(TAG, "guessed mime type: " + mime);
                }
            }

            // TODO: does not work for HTML
            Intent intent = new Intent();
            intent.setAction(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException anfe) {
                // TODO: convert to text/plain?
                Log.e(TAG, "No intent for " + object.getKey() + " with mime " + mime + " " + anfe);
                Toast.makeText(MainActivity.this, "No registered intent", Toast.LENGTH_LONG).show();
                return;
            }
        }

        @Override
        protected void onCancelled() {
            super.onCancelled();
            if (dialog != null) {
                dialog.dismiss();
            }
        }
    }

    static final class YassPreferences {
        final String accessKey;
        final String secretKey;
        final String bucketName;
        final String endpoint;
        final boolean cameraUpload;
        final boolean cameraUploadOnlyOnWifi;

        YassPreferences(Context context) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            // TODO: should default values be null?
            this.accessKey = prefs.getString("access_key", "access_key");
            this.secretKey = prefs.getString("secret_key", "secret_key");
            this.bucketName = prefs.getString("bucket_name", "bucket_name");
            this.endpoint = prefs.getString("endpoint", null);
            this.cameraUpload = prefs.getBoolean("camera_upload", false);
            this.cameraUploadOnlyOnWifi = prefs.getBoolean("camera_upload_only_on_wifi", false);
        }
    }

    static AmazonS3Client getS3Client(YassPreferences preferences) {
        BasicAWSCredentials awsCreds = new BasicAWSCredentials(preferences.accessKey,
                preferences.secretKey);
        AmazonS3Client client = new AmazonS3Client(awsCreds, new ClientConfiguration());
        if (preferences.endpoint != null && !preferences.endpoint.isEmpty()) {
            client.setEndpoint(preferences.endpoint);
        }
        return client;
    }
}
