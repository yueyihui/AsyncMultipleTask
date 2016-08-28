package com.android.mms;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.util.Comparator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/* Copyright (c) 2016, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
/**
 * Created by c_yuelia@qti.qualcomm.com on 5/20/16.
 */
public abstract class AsyncMultipleTask<Params, Result> {

    private static InternalHandler sHandler;
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE = CPU_COUNT;
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2;
    private static final int KEEP_ALIVE = 1;

    private static final int MESSAGE_POST_RESULT = 0x1;

    private static AtomicInteger priority = new AtomicInteger(0);

    protected AtomicInteger getDefaultPriorityPlan() {
        return priority;
    }

    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);

        public Thread newThread(Runnable r) {
            return new Thread(r, "AsyncMultipleTask #" + mCount.getAndIncrement());
        }
    };

    @SuppressWarnings("unchecked")
    protected static BlockingQueue<Runnable> mBlockingQueue =
            new PriorityBlockingQueue<Runnable>(128, new Comparator<Runnable>() {
                @Override
                public int compare(Runnable o1, Runnable o2) {
                    if (o1 instanceof Comparable && o2 instanceof Comparable) {
                        Comparable c1 = (Comparable) o1;
                        Comparable c2 = (Comparable) o2;
                        return c1.compareTo(c2);
                    } else {
                        return 0;
                    }
                }
            });

    public static final ThreadPoolExecutor mThreadPoolExecutor =
            new ThreadPoolExecutor(CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE,
                    TimeUnit.MINUTES, mBlockingQueue, sThreadFactory);

    private class Task extends FutureTask<Result> implements Comparable<Task> {
        private long mPriority;

        public Task(WorkerRunnable<Params, Result> runnable) {
            super(runnable);
        }

        public long getPriority() {
            return mPriority;
        }

        public void setPriority(long priority) {
            mPriority = priority;
        }

        @Override
        protected void done() {
            try {
                Result result = get();
                sendMessagePostResult(result);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
        }

        @Override
        public int compareTo(Task otherTask) {
            return AsyncMultipleTask.this.compareTo(this, otherTask);
        }
    }

    private synchronized void sendMessagePostResult(Result result) {
        Message msg = getHandler().obtainMessage(MESSAGE_POST_RESULT,
                new AsyncTaskResult<Result>(this, result));
        msg.sendToTarget();
    }

    protected int compareTo(Task self, Task otherTask) {
        return self.getPriority() > otherTask.getPriority() ? -1 : 1;
    }

    protected abstract class WorkerRunnable<Data, Outcome> implements Callable<Outcome> {
        public Data params;
    }

    private final Task mTask;
    private final WorkerRunnable<Params, Result> mWorkRunnable;

    public AsyncMultipleTask(boolean allowCoreThreadTimeOut) {
        mThreadPoolExecutor.allowCoreThreadTimeOut(allowCoreThreadTimeOut);
        mWorkRunnable = new WorkerRunnable<Params, Result>() {
            @Override
            public Result call() throws Exception {
                return doInBackground(params);
            }
        };
        mTask = new Task(mWorkRunnable);
    }

    protected abstract Result doInBackground(Params params);

    public synchronized void execute(Params params) {
        mWorkRunnable.params = params;
        mTask.setPriority(this.setPriority(mWorkRunnable));
        mThreadPoolExecutor.submit(mTask);
    }

    protected abstract long setPriority(WorkerRunnable<Params, Result> runnable);

    private static InternalHandler getHandler() {
        synchronized (AsyncMultipleTask.class) {
            if (sHandler == null) {
                sHandler = new InternalHandler();
            }
            return sHandler;
        }
    }

    private static class InternalHandler extends Handler {
        public InternalHandler() {
            super(Looper.getMainLooper());
        }

        @SuppressWarnings({"unchecked", "RawUseOfParameterizedType"})
        @Override
        public void handleMessage(Message msg) {
            AsyncTaskResult<?> result = (AsyncTaskResult<?>) msg.obj;
            switch (msg.what) {
                case MESSAGE_POST_RESULT:
                    result.getTask().onPostExecute(result.getResult());
                    break;
            }
        }
    }

    private static class AsyncTaskResult<Data> {
        final Data mResult;
        final AsyncMultipleTask mTask;

        AsyncTaskResult(AsyncMultipleTask task, Data result) {
            mResult = result;
            mTask = task;
        }

        public AsyncMultipleTask getTask() {
            return mTask;
        }

        public Data getResult() {
            return mResult;
        }
    }

    protected void onPostExecute(Result object) {

    }

}
