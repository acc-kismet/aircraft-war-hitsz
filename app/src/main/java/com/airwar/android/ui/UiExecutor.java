package com.airwar.android.ui;

import android.app.Activity;

public final class UiExecutor {
    private UiExecutor() {
    }

    // 后台线程请求完成后统一从这里切回主线程，减少页面层重复样板代码。
    public static void run(Activity activity, Runnable action) {
        if (activity == null || action == null) {
            return;
        }
        activity.runOnUiThread(action);
    }
}
