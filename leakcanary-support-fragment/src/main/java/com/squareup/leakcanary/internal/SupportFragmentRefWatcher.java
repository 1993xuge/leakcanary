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
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.view.View;
import com.squareup.leakcanary.RefWatcher;

class SupportFragmentRefWatcher implements FragmentRefWatcher {
    private final RefWatcher refWatcher;

    SupportFragmentRefWatcher(RefWatcher refWatcher) {
        this.refWatcher = refWatcher;
    }

    private final FragmentManager.FragmentLifecycleCallbacks fragmentLifecycleCallbacks =
            new FragmentManager.FragmentLifecycleCallbacks() {

                @Override
                public void onFragmentViewDestroyed(FragmentManager fm, Fragment fragment) {
                    // Fragment View Destory
                    View view = fragment.getView();
                    if (view != null) {
                        // 监测 Fragment中的View
                        refWatcher.watch(view);
                    }
                }

                @Override
                public void onFragmentDestroyed(FragmentManager fm, Fragment fragment) {
                    // Fragment Destory，watch fragment
                    refWatcher.watch(fragment);
                }
            };

    @Override
    public void watchFragments(Activity activity) {
        if (activity instanceof FragmentActivity) {
            // Activity必须是 FragmentActivity的子类
            FragmentManager supportFragmentManager =
                    ((FragmentActivity) activity).getSupportFragmentManager();
            // 为 Activity注册一个 FragmentLifecycleCallbacks，用来监测 Fragment的生命周期
            // 同样也是监测到 Fragment Destory时，才去 watch
            supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentLifecycleCallbacks, true);
        }
    }
}
