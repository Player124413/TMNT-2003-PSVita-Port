package org.tmnt.port;

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

/** Read-only, grant-only sharing of our own diagnostic text; no storage permission. */
public final class DiagnosticProvider extends ContentProvider {
    public static Uri uri(Context c, File f) { return new Uri.Builder().scheme("content").authority(c.getPackageName()+".diagnostics").appendPath(f.getName()).build(); }
    private File file(Uri uri) throws FileNotFoundException {
        if (uri.getPathSegments().size()!=1 || !"tmnt-log.txt".equals(uri.getLastPathSegment())) throw new FileNotFoundException();
        File f=new File(new File(getContext().getCacheDir(),"diagnostics"),"tmnt-log.txt");
        if(!f.isFile())throw new FileNotFoundException(); return f;
    }
    @Override public boolean onCreate(){return true;}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!"r".equals(mode))throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri){return "text/plain";}
    @Override public Cursor query(Uri uri,String[] projection,String s,String[] a,String order) {
        File f; try { f=file(uri); } catch(FileNotFoundException e) { return null; }
        MatrixCursor c=new MatrixCursor(projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection);
        MatrixCursor.RowBuilder r=c.newRow(); for(String col:c.getColumnNames()) r.add(OpenableColumns.SIZE.equals(col)?f.length():f.getName()); return c;
    }
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
