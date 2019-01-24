/*
 * Copyright (C) 2018 Square, Inc.
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
package com.squareup.leakcanary.internal;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import com.squareup.leakcanary.RefWatcher;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.O;

/**
 * Internal class used to watch for fragments leaks.
 */
public interface FragmentRefWatcher {

    void watchFragments(Activity activity);

    final class Helper {

        private static final String SUPPORT_FRAGMENT_REF_WATCHER_CLASS_NAME =
                "com.squareup.leakcanary.internal.SupportFragmentRefWatcher";

        public static void install(Context context, RefWatcher refWatcher) {
            List<FragmentRefWatcher> fragmentRefWatchers = new ArrayList<>();

            if (SDK_INT >= O) {
                // Android API 26
                fragmentRefWatchers.add(new AndroidOFragmentRefWatcher(refWatcher));
            }

            try {
                // SupportFragmentRefWatcher 属于leakcanary-support-fragment中的类
                // 如果未依赖 leakcanary-support-fragment 则找不到这个类
                Class<?> fragmentRefWatcherClass = Class.forName(SUPPORT_FRAGMENT_REF_WATCHER_CLASS_NAME);
                Constructor<?> constructor =
                        fragmentRefWatcherClass.getDeclaredConstructor(RefWatcher.class);
                FragmentRefWatcher supportFragmentRefWatcher =
                        (FragmentRefWatcher) constructor.newInstance(refWatcher);
                fragmentRefWatchers.add(supportFragmentRefWatcher);
            } catch (Exception ignored) {
            }

            if (fragmentRefWatchers.size() == 0) {
                return;
            }

            Helper helper = new Helper(fragmentRefWatchers);

            // 此处，也是 注册Activity生命周期的回调，是为了 在某一Activity create时，就开始监测 其中的Activity
            Application application = (Application) context.getApplicationContext();
            application.registerActivityLifecycleCallbacks(helper.activityLifecycleCallbacks);
        }

        private final Application.ActivityLifecycleCallbacks activityLifecycleCallbacks =
                new ActivityLifecycleCallbacksAdapter() {
                    @Override
                    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                        // Activity Create时，为Activity 添加 Fragment的生命周期 Callback
                        for (FragmentRefWatcher watcher : fragmentRefWatchers) {
                            watcher.watchFragments(activity);
                        }
                    }
                };

        private final List<FragmentRefWatcher> fragmentRefWatchers;

        private Helper(List<FragmentRefWatcher> fragmentRefWatchers) {
            this.fragmentRefWatchers = fragmentRefWatchers;
        }
    }
}
