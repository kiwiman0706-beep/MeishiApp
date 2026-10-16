package jp.yaegaki.meishinote;

import android.net.Uri;
import java.io.*;
import java.net.*;
import org.json.*;

// Minimal Drive REST v3 client. The access token comes from Google Play services
// (AuthorizationClient) and is never stored by the app.
public final class Drive {
  static final String API = "https://www.googleapis.com/drive/v3/";
  static final String FOLDER = "application/vnd.google-apps.folder";
  public static final String DONE_FOLDER = "登録済み";
  final String token;

  public Drive(String token) {
    this.token = token;
  }

  private HttpURLConnection open(String path, String method) throws Exception {
    HttpURLConnection con = (HttpURLConnection) new URL(API + path).openConnection();
    con.setConnectTimeout(20000);
    con.setReadTimeout(120000);
    con.setRequestProperty("Authorization", "Bearer " + token);
    if (method.equals("PATCH")) {
      // HttpURLConnection has no PATCH; Google APIs accept the override header.
      con.setRequestMethod("POST");
      con.setRequestProperty("X-HTTP-Method-Override", "PATCH");
    } else con.setRequestMethod(method);
    return con;
  }

  private JSONObject json(String path, String method, JSONObject body) throws Exception {
    HttpURLConnection con = open(path, method);
    try {
      if (body != null) {
        con.setDoOutput(true);
        con.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        try (OutputStream out = con.getOutputStream()) {
          out.write(body.toString().getBytes("UTF-8"));
        }
      }
      int status = con.getResponseCode();
      InputStream stream = status < 400 ? con.getInputStream() : con.getErrorStream();
      String text = "";
      if (stream != null)
        try (InputStream in = stream) {
          text = new String(Store.read(in, 8 * 1024 * 1024), "UTF-8");
        }
      if (status >= 400) throw failure(status, text);
      return text.isEmpty() ? new JSONObject() : new JSONObject(text);
    } finally {
      con.disconnect();
    }
  }

  static IOException failure(int status, String text) {
    String message = "";
    try {
      message = new JSONObject(text).getJSONObject("error").optString("message");
    } catch (Exception ignored) {
    }
    return new IOException(
        "Google Drive HTTP "
            + status
            + "\n"
            + message
            + (status == 401 || status == 403
                ? "\nDriveへのアクセスが許可されていません。もう一度お試しいただき、許可画面で「許可」を選んでください。"
                : status == 404 ? "\nフォルダが見つかりません。メニューからフォルダを設定し直してください。" : ""));
  }

  static String q(String s) {
    return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
  }

  private JSONArray list(String query, String order) throws Exception {
    JSONArray out = new JSONArray();
    String page = "";
    do {
      JSONObject r =
          json(
              "files?pageSize=200&supportsAllDrives=true&includeItemsFromAllDrives=true"
                  + "&fields="
                  + Uri.encode("nextPageToken,files(id,name,mimeType,size)")
                  + "&orderBy="
                  + Uri.encode(order)
                  + "&q="
                  + Uri.encode(query)
                  + (page.isEmpty() ? "" : "&pageToken=" + Uri.encode(page)),
              "GET",
              null);
      JSONArray files = r.optJSONArray("files");
      if (files != null) for (int i = 0; i < files.length(); i++) out.put(files.get(i));
      page = r.optString("nextPageToken");
    } while (!page.isEmpty() && out.length() < 1000);
    return out;
  }

  public JSONArray folders(String parent) throws Exception {
    return list(
        q(parent) + " in parents and mimeType=" + q(FOLDER) + " and trashed=false", "name");
  }

  // Only the folder's own files: the 登録済み subfolder is never re-read.
  public JSONArray cards(String folder) throws Exception {
    return list(
        q(folder)
            + " in parents and trashed=false and (mimeType='application/pdf' or mimeType contains 'image/')",
        "name");
  }

  public void download(String id, File to) throws Exception {
    HttpURLConnection con = open("files/" + Uri.encode(id) + "?alt=media&supportsAllDrives=true", "GET");
    try {
      int status = con.getResponseCode();
      if (status >= 400) {
        String text = "";
        InputStream err = con.getErrorStream();
        if (err != null)
          try (InputStream in = err) {
            text = new String(Store.read(in, 1024 * 1024), "UTF-8");
          }
        throw failure(status, text);
      }
      try (InputStream in = con.getInputStream();
          OutputStream out = new FileOutputStream(to)) {
        Store.copy(in, out, 100L * 1024 * 1024);
      }
    } finally {
      con.disconnect();
    }
  }

  public String subfolder(String parent, String name) throws Exception {
    JSONArray found =
        list(
            q(parent)
                + " in parents and mimeType="
                + q(FOLDER)
                + " and trashed=false and name="
                + q(name),
            "name");
    if (found.length() > 0) return found.getJSONObject(0).getString("id");
    return json(
            "files?supportsAllDrives=true&fields=id",
            "POST",
            new JSONObject()
                .put("name", name)
                .put("mimeType", FOLDER)
                .put("parents", new JSONArray().put(parent)))
        .getString("id");
  }

  public void move(String id, String from, String to) throws Exception {
    json(
        "files/"
            + Uri.encode(id)
            + "?supportsAllDrives=true&fields=id&addParents="
            + Uri.encode(to)
            + "&removeParents="
            + Uri.encode(from),
        "PATCH",
        new JSONObject());
  }
}
