package com.codex.qwenchat;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URLConnection;
import java.util.List;

public final class CaptureFileProvider extends ContentProvider {
    static final String AUTHORITY = "com.codex.qwenchat.captureprovider";
    private static final String CAPTURES_PATH = "captures";

    static Uri uriForFile(Context context, File file) {
        File captureRoot = new File(context.getCacheDir(), CAPTURES_PATH);
        try {
            File canonicalRoot = captureRoot.getCanonicalFile();
            File canonicalFile = file.getCanonicalFile();
            if (!canonicalFile.getPath().startsWith(canonicalRoot.getPath() + File.separator)) {
                throw new IllegalArgumentException("File is outside capture directory");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to resolve capture file", e);
        }

        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(CAPTURES_PATH)
                .appendPath(file.getName())
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        try {
            File file = resolveFile(uri);
            String contentType = URLConnection.guessContentTypeFromName(file.getName());
            return contentType == null ? "application/octet-stream" : contentType;
        } catch (FileNotFoundException e) {
            return "application/octet-stream";
        }
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        try {
            File file = resolveFile(uri);
            MatrixCursor cursor = new MatrixCursor(new String[]{
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE
            });
            cursor.addRow(new Object[]{file.getName(), file.length()});
            return cursor;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = resolveFile(uri);
        return ParcelFileDescriptor.open(file, parcelModeFor(mode));
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Insert is not supported");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Delete is not supported");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Update is not supported");
    }

    private File resolveFile(Uri uri) throws FileNotFoundException {
        Context context = getContext();
        if (context == null) {
            throw new FileNotFoundException("Context is unavailable");
        }
        if (uri == null || !AUTHORITY.equals(uri.getAuthority())) {
            throw new FileNotFoundException("Unsupported uri");
        }

        List<String> segments = uri.getPathSegments();
        if (segments.size() != 2 || !CAPTURES_PATH.equals(segments.get(0))) {
            throw new FileNotFoundException("Unsupported capture path");
        }

        File captureRoot = new File(context.getCacheDir(), CAPTURES_PATH);
        File file = new File(captureRoot, segments.get(1));
        try {
            File canonicalRoot = captureRoot.getCanonicalFile();
            File canonicalFile = file.getCanonicalFile();
            if (!canonicalFile.getPath().startsWith(canonicalRoot.getPath() + File.separator)) {
                throw new FileNotFoundException("Resolved file is outside capture directory");
            }
            return canonicalFile;
        } catch (IOException e) {
            throw new FileNotFoundException("Unable to resolve capture file");
        }
    }

    private int parcelModeFor(String mode) {
        if ("r".equals(mode)) {
            return ParcelFileDescriptor.MODE_READ_ONLY;
        }
        if ("w".equals(mode) || "wt".equals(mode)) {
            return ParcelFileDescriptor.MODE_WRITE_ONLY
                    | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE;
        }
        if ("wa".equals(mode)) {
            return ParcelFileDescriptor.MODE_WRITE_ONLY
                    | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_APPEND;
        }
        if ("rw".equals(mode)) {
            return ParcelFileDescriptor.MODE_READ_WRITE
                    | ParcelFileDescriptor.MODE_CREATE;
        }
        if ("rwt".equals(mode)) {
            return ParcelFileDescriptor.MODE_READ_WRITE
                    | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE;
        }
        throw new IllegalArgumentException("Unsupported mode: " + mode);
    }
}
