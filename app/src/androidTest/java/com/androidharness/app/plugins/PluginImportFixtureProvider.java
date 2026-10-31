package com.androidharness.app.plugins;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Read-only document tree in the test APK. No dependency on the target APK's Kotlin runtime. */
public final class PluginImportFixtureProvider extends ContentProvider {
    private static final String[] IDS = {"fixture", "fixture/plugin.json", "fixture/instructions.md", "fixture/skills", "fixture/skills/review", "fixture/skills/review/SKILL.md", "skill", "skill/SKILL.md", "skill/notes.md"};
    private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_FLAGS};
    private File root;
    @Override public boolean onCreate() {
        root = new File(getContext().getCacheDir(), "plugin-import-fixture");
        try {
            write("fixture/plugin.json", "{\"name\":\"folder-fixture\",\"version\":\"1.0.0\",\"instructions\":[\"instructions.md\"],\"skills\":[\"skills\"]}");
            write("fixture/instructions.md", "DOCUMENT_FOLDER_INSTRUCTIONS");
            write("fixture/skills/review/SKILL.md", "---\nname: folder-review\ndescription: Review document fixture code.\n---\nDOCUMENT_FOLDER_SKILL\n");
            write("skill/SKILL.md", "---\nname: imported-skill\ndescription: Review a standalone imported skill.\n---\nSTANDALONE_SKILL\n");
            write("skill/notes.md", "SKILL_SUPPORTING_FILE");
            String target = getContext().getPackageName().replaceFirst("\\.test$", "");
            for (String id : new String[]{"fixture", "skill"}) {
                Uri tree = DocumentsContract.buildTreeDocumentUri("com.androidharness.pluginimport.fixture", id);
                getContext().grantUriPermission(target, tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            }
            return true;
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private void write(String path, String value) throws java.io.IOException {
        File file = new File(root, path); file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(value.getBytes(StandardCharsets.UTF_8)); }
    }
    private File file(String id) {
        if (!Arrays.asList(IDS).contains(id)) throw new IllegalArgumentException("Unknown fixture document");
        return new File(root, id);
    }
    private void add(MatrixCursor cursor, String id) {
        File file = file(id);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            Object value;
            switch (column) {
                case Document.COLUMN_DOCUMENT_ID: value = id; break;
                case Document.COLUMN_DISPLAY_NAME: value = file.getName(); break;
                case Document.COLUMN_MIME_TYPE: value = file.isDirectory() ? Document.MIME_TYPE_DIR : "application/octet-stream"; break;
                case Document.COLUMN_SIZE: value = file.length(); break;
                case Document.COLUMN_FLAGS: value = 0; break;
                default: value = null;
            }
            row.add(value);
        }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        String id = DocumentsContract.getDocumentId(uri);
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : COLUMNS);
        if ("children".equals(uri.getLastPathSegment())) {
            for (File child : file(id).listFiles()) add(cursor, id + "/" + child.getName());
        } else add(cursor, id);
        return cursor;
    }
    @Override public String getType(Uri uri) { return file(DocumentsContract.getDocumentId(uri)).isDirectory() ? Document.MIME_TYPE_DIR : "application/octet-stream"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        if (!"r".equals(mode)) throw new IllegalArgumentException("Read-only fixture");
        return ParcelFileDescriptor.open(file(DocumentsContract.getDocumentId(uri)), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
