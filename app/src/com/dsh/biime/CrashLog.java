package com.dsh.biime;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃自诊断。
 *
 * 应用没有任何联网能力，也没有权限往外写文件，所以堆栈走两条路落地：
 *   1. 应用私有目录（自己下次启动可以读出来显示）
 *   2. MediaStore 的 Download 集合（API 29+ 不需要任何权限，
 *      这样即使设备 shell 连不上也能把堆栈取出来看）
 */
public final class CrashLog {

    private static volatile boolean installed;

    private CrashLog() {
    }

    /** 装到默认异常处理器上；在 Application.onCreate 里调一次即可。 */
    public static void install(final Context ctx) {
        if (installed) {
            return;
        }
        installed = true;
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    save(app, "uncaught", e);
                } catch (Throwable ignored) {
                }
                if (prev != null) {
                    prev.uncaughtException(t, e);
                }
            }
        });
    }

    public static String header() {
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        return "BiIme 崩溃报告\n时间: " + time
                + "\n系统: Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + "\n机型: " + Build.MANUFACTURER + " " + Build.MODEL
                + "\nABI: " + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?")
                + "\n----------------------------------------\n";
    }

    public static String stack(Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println(header());
        e.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    /** 保存一份，尽量不抛异常（它自己崩了就白搭了）。 */
    public static void save(Context ctx, String tag, Throwable e) {
        String text = stack(e);
        // 1) 应用私有目录（内部 + 外部各来一份）
        writeFile(new File(ctx.getFilesDir(), "crash-" + tag + ".txt"), text);
        File ext = ctx.getExternalFilesDir(null);
        if (ext != null) {
            writeFile(new File(ext, "crash-" + tag + ".txt"), text);
        }
        // 2) 通过 MediaStore 落到 Download/，方便从容器侧取出来
        writeDownload(ctx, text);
    }

    private static void writeFile(File f, String text) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f, true);
            out.write((text + "\n=====\n").getBytes("UTF-8"));
        } catch (Throwable ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void writeDownload(Context ctx, String text) {
        if (Build.VERSION.SDK_INT < 29) {
            return;   // 29 以下要 WRITE_EXTERNAL_STORAGE，本应用不申请权限
        }
        try {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME,
                    "biime-crash-" + System.currentTimeMillis() + ".txt");
            v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) {
                return;
            }
            OutputStream os = cr.openOutputStream(uri, "wt");
            if (os != null) {
                os.write(text.getBytes("UTF-8"));
                os.close();
            }
        } catch (Throwable ignored) {
        }
    }
}
