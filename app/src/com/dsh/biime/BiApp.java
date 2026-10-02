package com.dsh.biime;

import android.app.Application;

/**
 * 只为尽早装上崩溃自诊断：Application.onCreate 比任何 Activity 都早，
 * 这样连 Activity 构造/主题解析阶段炸掉也能留下堆栈。
 */
public class BiApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
    }
}
