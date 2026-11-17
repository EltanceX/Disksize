package com.example.disksize.open;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Runs under the test APK UID, without dependencies supplied by the target APK. */
public class ExternalOpenReceiverActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle result = new Bundle();
        try {
            Uri uri = getIntent().getData();
            if (Document.MIME_TYPE_DIR.equals(getIntent().getType())) {
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri));
                try (Cursor cursor = getContentResolver().query(children,
                        new String[] {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                    while (cursor.moveToNext()) {
                        if ("file.txt".equals(cursor.getString(1))) uri = DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(0));
                    }
                }
            }
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
                result.putString("text", output.toString(StandardCharsets.UTF_8.name()));
            }
            try { getContentResolver().openFileDescriptor(uri, "rw").close(); result.putBoolean("writeDenied", false); }
            catch (Exception expected) { result.putBoolean("writeDenied", true); }
            try {
                Cursor cursor = getContentResolver().query(Uri.parse(getIntent().getStringExtra("outside")), null, null, null, null);
                if (cursor != null) cursor.close();
                result.putBoolean("outsideDenied", false);
            } catch (SecurityException expected) { result.putBoolean("outsideDenied", true); }
            result.putInt("uid", Process.myUid());
        } catch (Exception error) { result.putString("error", error.toString()); }
        String action = getIntent().getStringExtra("replyAction");
        if (action != null) sendBroadcast(new Intent(action).setPackage("com.example.disksize").putExtras(result));
        finish();
    }
}
