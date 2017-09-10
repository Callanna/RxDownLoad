package com.callanna.rxdownload;

import android.text.TextUtils;
import android.util.Log;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.reactivex.disposables.Disposable;
import okhttp3.internal.http.HttpHeaders;
import retrofit2.Response;

import static android.text.TextUtils.concat;
import static java.io.File.separator;
import static java.lang.String.format;
import static java.util.Locale.getDefault;
import static java.util.TimeZone.getTimeZone;

/**
 * Author: Season(ssseasonnn@gmail.com)
 * Date: 2016/11/2
 * Time: 09:07
 * 工具类
 */
public class Utils {
    private static final String TAG = "RxDownLoad";
    private static final CharSequence CACHE = ".cache";
    private static final CharSequence TMP_SUFFIX = ".tmp";
    private static final CharSequence LMF_SUFFIX = ".lmf";
    private static boolean DEBUG = true;

    public static void setDebug(boolean flag) {
        DEBUG = flag;
    }

    public static void log(String message) {
        if (empty(message)) return;
        if (DEBUG) {
            Log.i(TAG, message);
        }
    }

    public static void log(String message, Object... args) {
        log(format(getDefault(), message, args));
    }

    public static void log(Throwable throwable) {
        Log.w(TAG, throwable);
    }

    public static String formatStr(String str, Object... args) {
        return format(getDefault(), str, args);
    }

    public static boolean empty(String string) {
        return TextUtils.isEmpty(string);
    }

    /**
     * convert long to GMT string
     *
     * @param lastModify long
     * @return String
     */
    public static String longToGMT(long lastModify) {
        Date d = new Date(lastModify);
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        sdf.setTimeZone(getTimeZone("GMT"));
        return sdf.format(d);
    }

    /**
     * convert GMT string to long
     *
     * @param GMT String
     * @return long
     * @throws ParseException
     */
    public static long GMTToLong(String GMT) throws ParseException {
        if (GMT == null || "".equals(GMT)) {
            return new Date().getTime();
        }
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        sdf.setTimeZone(getTimeZone("GMT"));
        Date date = sdf.parse(GMT);
        return date.getTime();
    }

    public static void close(Closeable closeable) throws IOException {
        if (closeable != null) {
            closeable.close();
        }
    }

    public static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (RuntimeException rethrown) {
                throw rethrown;
            } catch (Exception ignored) {
            }
        }
    }


    public static void dispose(Disposable disposable) {
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
    }

    public static String lastModify(Response<?> response) {
        String last = response.headers().get("Last-Modified");
        log("------->lastModify :"+last);
        return last;
    }

    public static long contentLength(Response<?> response) {
        return HttpHeaders.contentLength(response.headers());
    }

    public static String fileName(String url, Response<?> response) {
        String fileName = contentDisposition(response);
        if (empty(fileName)) {
            fileName = url.substring(url.lastIndexOf('/') + 1);
        }
        if (fileName.startsWith("\"")) {
            fileName = fileName.substring(1);
        }
        if (fileName.endsWith("\"")) {
            fileName = fileName.substring(0, fileName.length() - 1);
        }
        return fileName;
    }

    public static String contentDisposition(Response<?> response) {
        String disposition = response.headers().get("Content-Disposition");
        if (empty(disposition)) {
            return "";
        }
        Matcher m = Pattern.compile(".*filename=(.*)").matcher(disposition.toLowerCase());
        if (m.find()) {
            return m.group(1);
        } else {
            return "";
        }
    }

    public static boolean isChunked(Response<?> response) {
        return "chunked".equals(transferEncoding(response));
    }

    public static boolean notSupportRange(Response<?> response) {
        return (TextUtils.isEmpty(contentRange(response)) && !TextUtils.equals(acceptRanges(response), "bytes")) || contentLength(response) == -1 ||
                isChunked(response);
    }


    /**
     * return file paths
     *
     * @param saveName saveName
     * @param savePath savePath
     * @return filePath, tempPath, lmfPath
     */
    public static String[] getPaths(String saveName, String savePath) {
        String cachePath = concat(savePath, separator, CACHE).toString();
        String filePath = concat(savePath, separator, saveName).toString();
        String tempPath = concat(cachePath, separator, saveName, TMP_SUFFIX).toString();
        String lmfPath = concat(cachePath, separator, saveName, LMF_SUFFIX).toString();
        return new String[]{filePath, tempPath, lmfPath};
    }

    /**
     * return files
     *
     * @param saveName saveName
     * @param savePath savePath
     * @return file, tempFile, lmfFile
     */
    public static File[] getFiles(String saveName, String savePath) {
        String[] paths = getPaths(saveName, savePath);
        return new File[]{new File(paths[0]), new File(paths[1]), new File(paths[2])};
    }
    public static void mkdirs(String... paths) {
        for (String each : paths) {
            File file = new File(each);
            if (file.exists() && file.isDirectory()) {
            } else {
               file.mkdirs();
            }
        }
    }


    /**
     * delete files
     *
     * @param files files
     */
    public static void deleteFiles(File... files) {
        for (File each : files) {
            if (each.exists()) {
                boolean flag = each.delete();
                if (flag) {
                    log(format(getDefault(), "FILE_DELETE_SUCCESS", each.getName()));
                } else {
                    log(format(getDefault(), "FILE_DELETE_FAILED", each.getName()));
                }
            }
        }
    }

    private static String transferEncoding(Response<?> response) {
        return response.headers().get("Transfer-Encoding");
    }

    private static String contentRange(Response<?> response) {
        Log.d("duanyl", "contentRange: "+response.headers().get("Content-Range"));
        return response.headers().get("Content-Range");
    }

    private static String acceptRanges(Response<?> response) {
        Log.d("duanyl", "acceptRanges: "+ response.headers().get("Accept-Ranges"));
        return response.headers().get("Accept-Ranges");
    }
}
