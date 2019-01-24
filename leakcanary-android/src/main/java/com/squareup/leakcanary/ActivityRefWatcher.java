/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.leakcanary;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.support.annotation.NonNull;
import com.squareup.leakcanary.internal.ActivityLifecycleCallbacksAdapter;

/**
 * @deprecated This was initially part of the LeakCanary API, but should not be any more.
 * {@link AndroidRefWatcherBuilder#watchActivities} should be used instead.
 * We will make this class internal in the next major version.
 */
@SuppressWarnings("DeprecatedIsStillUsed")
@Deprecated
public final class ActivityRefWatcher {

    public static void installOnIcsPlus(@NonNull Application application,
                                        @NonNull RefWatcher refWatcher) {
        install(application, refWatcher);
    }

    public static void install(@NonNull Context context, @NonNull RefWatcher refWatcher) {
        Application application = (Application) context.getApplicationContext();
        // 创建一个 ActivityRefWatcher对象
        ActivityRefWatcher activityRefWatcher = new ActivityRefWatcher(application, refWatcher);

        // 注册 监听Activity生命周期的Callback，
        // 在这个Callback中只关心所有Activity的Destory事件，因为 LeakCanary是在Activity被销毁时 才去watch
        application.registerActivityLifecycleCallbacks(activityRefWatcher.lifecycleCallbacks);
    }

    private final Application.ActivityLifecycleCallbacks lifecycleCallbacks =
            new ActivityLifecycleCallbacksAdapter() {
                @Override
                public void onActivityDestroyed(Activity activity) {
                    // 在Activity Destory时，开始 监测 被销毁的 Activity
                    refWatcher.watch(activity);
                }
            };

    private final Application application;
    private final RefWatcher refWatcher;

    private ActivityRefWatcher(Application application, RefWatcher refWatcher) {
        this.application = application;
        this.refWatcher = refWatcher;
    }

    public void watchActivities() {
        // Make sure you don't get installed twice.
        stopWatchingActivities();
        application.registerActivityLifecycleCallbacks(lifecycleCallbacks);
    }

    public void stopWatchingActivities() {
        application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks);
    }
}
