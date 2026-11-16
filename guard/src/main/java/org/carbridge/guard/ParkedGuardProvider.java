package org.carbridge.guard;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

/**
 * CarBridge adds this provider to a car copy's manifest. Android creates providers when the app's
 * process starts, before any activity, which lets the parked-only lock start without changing
 * the app's own code. It serves no data.
 */
public final class ParkedGuardProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        Context context = getContext();
        Context appContext = context == null ? null : context.getApplicationContext();
        if (appContext instanceof Application) {
            ParkedGuard.install((Application) appContext);
        }
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
