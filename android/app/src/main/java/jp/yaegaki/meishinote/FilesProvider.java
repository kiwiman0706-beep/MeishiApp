package jp.yaegaki.meishinote;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

public class FilesProvider extends ContentProvider {
  public boolean onCreate() {
    return true;
  }

  public static Uri uri(Context c, File f) {
    String base = f.getParentFile().equals(c.getCacheDir()) ? "cache" : "media";
    return new Uri.Builder()
        .scheme("content")
        .authority(c.getPackageName() + ".files")
        .appendPath(base)
        .appendPath(f.getName())
        .build();
  }

  private File file(Uri uri) throws FileNotFoundException {
    try {
      if (uri.getPathSegments().size() != 2) throw new Exception();
      String name = uri.getLastPathSegment();
      if (!name.matches("[A-Za-z0-9_.-]+") || name.contains("..")) throw new Exception();
      String base = uri.getPathSegments().get(0);
      if (base.equals("cache")) return new File(getContext().getCacheDir(), name);
      if (base.equals("media")) return Store.get(getContext()).mediaFile(name);
      throw new Exception();
    } catch (Exception e) {
      throw new FileNotFoundException("Invalid path");
    }
  }

  public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
    File f = file(u);
    if (!mode.equals("r")
        && !(u.getPathSegments().get(0).equals("cache") && f.getName().startsWith("camera-")))
      throw new FileNotFoundException("Read only");
    return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode));
  }

  public String getType(Uri u) {
    String n = u.getLastPathSegment();
    if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
    if (n.endsWith(".png")) return "image/png";
    if (n.endsWith(".webp")) return "image/webp";
    if (n.endsWith(".heic") || n.endsWith(".heif")) return "image/heif";
    if (n.endsWith(".m4a")) return "audio/mp4";
    if (n.endsWith(".zip")) return "application/zip";
    if (n.endsWith(".csv")) return "text/csv";
    if (n.endsWith(".pdf")) return "application/pdf";
    return "application/octet-stream";
  }

  public Cursor query(Uri u, String[] projection, String selection, String[] args, String sort) {
    try {
      File f = file(u);
      String[] cols =
          projection == null
              ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
              : projection;
      MatrixCursor m = new MatrixCursor(cols);
      Object[] row = new Object[cols.length];
      for (int i = 0; i < cols.length; i++)
        row[i] =
            cols[i].equals(OpenableColumns.DISPLAY_NAME)
                ? f.getName()
                : cols[i].equals(OpenableColumns.SIZE) ? f.length() : null;
      m.addRow(row);
      return m;
    } catch (Exception e) {
      return null;
    }
  }

  public Uri insert(Uri u, ContentValues v) {
    throw new UnsupportedOperationException();
  }

  public int update(Uri u, ContentValues v, String s, String[] a) {
    throw new UnsupportedOperationException();
  }

  public int delete(Uri u, String s, String[] a) {
    throw new UnsupportedOperationException();
  }
}
