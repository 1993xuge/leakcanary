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

import java.io.File;
import java.lang.ref.ReferenceQueue;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

import static com.squareup.leakcanary.HeapDumper.RETRY_LATER;
import static com.squareup.leakcanary.Preconditions.checkNotNull;
import static com.squareup.leakcanary.Retryable.Result.DONE;
import static com.squareup.leakcanary.Retryable.Result.RETRY;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Watches references that should become weakly reachable. When the {@link RefWatcher} detects that
 * a reference might not be weakly reachable when it should, it triggers the {@link HeapDumper}.
 *
 * <p>This class is thread-safe: you can call {@link #watch(Object)} from any thread.
 */
public final class RefWatcher {

    public static final RefWatcher DISABLED = new RefWatcherBuilder<>().build();

    // 异步分析线程池
    private final WatchExecutor watchExecutor;
    // 用于判断是否debug模式，如果正在调试中，就不会执行内存泄露的监测判断
    private final DebuggerControl debuggerControl;
    // 触发gc
    private final GcTrigger gcTrigger;
    // 用于dump内存堆快照信息
    private final HeapDumper heapDumper;
    // 用于监听 产生Heap文件的回调
    private final HeapDump.Listener heapdumpListener;
    private final HeapDump.Builder heapDumpBuilder;
    // 会持有那些待检测的 以及 已经发生内存泄露的引用的Key
    private final Set<String> retainedKeys;
    // 引用队列，主要用于判断 弱引用所持有的对象是否已经被 执行了gc垃圾回收
    private final ReferenceQueue<Object> queue;

    RefWatcher(WatchExecutor watchExecutor, DebuggerControl debuggerControl, GcTrigger gcTrigger,
               HeapDumper heapDumper, HeapDump.Listener heapdumpListener, HeapDump.Builder heapDumpBuilder) {
        this.watchExecutor = checkNotNull(watchExecutor, "watchExecutor");
        this.debuggerControl = checkNotNull(debuggerControl, "debuggerControl");
        this.gcTrigger = checkNotNull(gcTrigger, "gcTrigger");
        this.heapDumper = checkNotNull(heapDumper, "heapDumper");
        this.heapdumpListener = checkNotNull(heapdumpListener, "heapdumpListener");
        this.heapDumpBuilder = heapDumpBuilder;
        retainedKeys = new CopyOnWriteArraySet<>();
        queue = new ReferenceQueue<>();
    }

    /**
     * Identical to {@link #watch(Object, String)} with an empty string reference name.
     *
     * @see #watch(Object, String)
     */
    public void watch(Object watchedReference) {
        watch(watchedReference, "");
    }

    /**
     * Watches the provided references and checks if it can be GCed. This method is non blocking,
     * the check is done on the {@link WatchExecutor} this {@link RefWatcher} has been constructed
     * with.
     * Activity、Fragment、View，最终都会 调用到这个方法
     *
     * @param referenceName An logical identifier for the watched object.
     */
    public void watch(Object watchedReference, String referenceName) {
        if (this == DISABLED) {
            return;
        }
        // 检测watchedReference是否为null
        checkNotNull(watchedReference, "watchedReference");
        // 检测referenceName是否为null
        checkNotNull(referenceName, "referenceName");
        final long watchStartNanoTime = System.nanoTime();
        // 1、创建唯一的key，并将其加入到retainedKeys中
        String key = UUID.randomUUID().toString();
        retainedKeys.add(key);

        // 2、创建一个弱引用，将key与watchedReference绑定，
        // 同时此处为弱引用关联一个ReferenceQueue，以便在gc回收这个对象后，将弱引用加入到队列中
        final KeyedWeakReference reference =
                new KeyedWeakReference(watchedReference, key, referenceName, queue);

        // 3、开启 异步线程，执行内存泄漏 分析工作
        ensureGoneAsync(watchStartNanoTime, reference);
    }

    /**
     * LeakCanary will stop watching any references that were passed to {@link #watch(Object, String)}
     * so far.
     */
    public void clearWatchedReferences() {
        retainedKeys.clear();
    }

    boolean isEmpty() {
        removeWeaklyReachableReferences();
        return retainedKeys.isEmpty();
    }

    HeapDump.Builder getHeapDumpBuilder() {
        return heapDumpBuilder;
    }

    Set<String> getRetainedKeys() {
        return new HashSet<>(retainedKeys);
    }

    private void ensureGoneAsync(final long watchStartNanoTime, final KeyedWeakReference reference) {
        // 在 线程池 中执行 Runnable
        watchExecutor.execute(new Retryable() {
            @Override
            public Retryable.Result run() {
                return ensureGone(reference, watchStartNanoTime);
            }
        });
    }

    @SuppressWarnings("ReferenceEquality")
        // Explicitly checking for named null.
    Retryable.Result ensureGone(final KeyedWeakReference reference, final long watchStartNanoTime) {
        long gcStartNanoTime = System.nanoTime();
        // 用于 计算从调用 watch方法 到 调用 GC垃圾回收 所用的时间
        long watchDurationMs = NANOSECONDS.toMillis(gcStartNanoTime - watchStartNanoTime);

        // 移除掉已经回收的对象的key
        removeWeaklyReachableReferences();

        // 如果此时 已经处于 debug的状态，那么就直接return
        if (debuggerControl.isDebuggerAttached()) {
            // The debugger can create false leaks.
            return RETRY;
        }

        // 如果retainedKeys已经不包含这个reference，说明对象已经回收了
        if (gone(reference)) {
            return DONE;
        }

        // 手动 触发GC
        gcTrigger.runGc();
        // 再次移除GC后已经回收的对象的key
        removeWeaklyReachableReferences();
        if (!gone(reference)) {
            // 如果reference没被回收，则表明已经发生了内存泄漏
            long startDumpHeap = System.nanoTime();
            // gc的时间
            long gcDurationMs = NANOSECONDS.toMillis(startDumpHeap - gcStartNanoTime);

            // dump出所需要的heap文件
            File heapDumpFile = heapDumper.dumpHeap();
            if (heapDumpFile == RETRY_LATER) {
                // Could not dump the heap.
                return RETRY;
            }
            // dump内存堆快照信息的时间
            long heapDumpDurationMs = NANOSECONDS.toMillis(System.nanoTime() - startDumpHeap);

            HeapDump heapDump = heapDumpBuilder.heapDumpFile(heapDumpFile).referenceKey(reference.key)
                    .referenceName(reference.name)
                    .watchDurationMs(watchDurationMs)
                    .gcDurationMs(gcDurationMs)
                    .heapDumpDurationMs(heapDumpDurationMs)
                    .build();

            // 分析 内存泄露
            heapdumpListener.analyze(heapDump);
        }
        return DONE;
    }

    /**
     * 判断 retainedKeys 中是否包含 reference的key，以表明reference所引用的对象是否已经被回收。
     * return true：不包含reference的key，表明reference所引用的对象已经被回收。
     * return false：包含reference的key，表明reference所引用的对象没有被回收。
     */
    private boolean gone(KeyedWeakReference reference) {
        return !retainedKeys.contains(reference.key);
    }

    /**
     *
     * 循环从引用队列中取出加入其中的虚引用KeyedWeakReference对象，将标识这个虚引用的key从retainedKeys中移除。
     * retainedKeys中剩下的就是 标识 未被垃圾回收的被检测对象的虚引用的key
     */
    private void removeWeaklyReachableReferences() {
        // WeakReferences are enqueued as soon as the object to which they point to becomes weakly
        // reachable. This is before finalization or garbage collection has actually happened.
        KeyedWeakReference ref;
        while ((ref = (KeyedWeakReference) queue.poll()) != null) {
            retainedKeys.remove(ref.key);
        }
    }
}
