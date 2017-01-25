package org.gaul.yass;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.AmazonS3Exception;
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

public final class MainActivity extends AppCompatActivity {
    private static final String TAG = "yass";
    private AmazonS3 client;
    private String accessKey;
    private String secretKey;
    private String bucketName;
    private ListView mListView;
    private final List<String> listItems = new ArrayList<String>();
    private ArrayAdapter<String> adapter;
    private String prefix = "";
    private ObjectListing listing;
    private SharedPreferences.OnSharedPreferenceChangeListener listener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                    MainActivity.this.accessKey = prefs.getString("access_key", "access_key");
                    MainActivity.this.secretKey = prefs.getString("secret_key", "secret_key");
                    MainActivity.this.bucketName = prefs.getString("bucket_name", "bucket_name");

                    BasicAWSCredentials awsCreds = new BasicAWSCredentials(accessKey, secretKey);
                    MainActivity.this.client = new AmazonS3Client(awsCreds, new ClientConfiguration());
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
        // TODO: if prefs not set, show settings
        this.accessKey = prefs.getString("access_key", "access_key");
        this.secretKey = prefs.getString("secret_key", "secret_key");
        this.bucketName = prefs.getString("bucket_name", "bucket_name");

        BasicAWSCredentials awsCreds = new BasicAWSCredentials(accessKey, secretKey);
        client = new AmazonS3Client(awsCreds, new ClientConfiguration());

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
        inflater.inflate(R.xml.menu, menu);
        return true;
    }

    private class BlobListTask extends AsyncTask<String, Void, Collection<String>> {
        @Override
        public Collection<String> doInBackground(String... path) {
            List<String> listItems = new ArrayList<>();
            String prefix = path[0];
            try {
                MainActivity.this.listing = client.listObjects(new ListObjectsRequest()
                        .withBucketName(bucketName)
                        .withDelimiter("/")
                        .withPrefix(prefix));
            } catch (AmazonS3Exception ase) {
                Log.e(TAG, "Error listing with prefix: " + prefix + " " + ase.getMessage());
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

    // TODO: progress bar
    private class SelectBlobTask extends AsyncTask<String, Void, File> {
        private ObjectMetadata metadata;

        @Override
        public File doInBackground(String... path) {
            String key = path[0];
            Log.d(TAG, "downloading: " + key);

            S3Object object;
            try {
                object = client.getObject(bucketName, path[0]);
            } catch (AmazonS3Exception ase) {
                Log.e(TAG, "Error getting blob: " + key + " " + ase.getMessage());
                return null;
            }

            File file;
            try {
                file = File.createTempFile(object.getObjectMetadata().getETag(), null, MainActivity.this.getCacheDir());
                byte[] buffer = new byte[4096];
                try (InputStream is = object.getObjectContent();
                     OutputStream os = new FileOutputStream(file)) {
                    while (true) {
                        int count = is.read(buffer);
                        if (count == -1) {
                            break;
                        }
                        os.write(buffer, 0, count);
                    }
                }
            } catch (IOException ioe) {
                Log.e(TAG, "Error downloading blob: " + key + " " + ioe.getMessage());
                return null;
            }

            this.metadata = object.getObjectMetadata();
            return file;
        }

        @Override
        protected void onPostExecute(File file) {
            if (file == null) {
                Toast.makeText(MainActivity.this, "Could not download file", Toast.LENGTH_LONG).show();
                return;
            }

            Uri uri = FileProvider.getUriForFile(MainActivity.this, "org.gaul.yass", file);
            String mime = metadata.getContentType();

            // TODO: does not work for HTML
            Intent intent = new Intent();
            intent.setAction(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException anfe) {
                // TODO: convert to text/plain?
                Log.e(TAG, "No intent for " + file + " " + anfe);
                Toast.makeText(MainActivity.this, "No registered intent", Toast.LENGTH_LONG).show();
                return;
            }
        }
    }
}
