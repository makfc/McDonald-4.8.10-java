package com.google.android.gms.internal;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.Result;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import java.util.concurrent.TimeUnit;

public class zzoz<R extends Result> extends PendingResult<R> {
    private final Status zzaaO;

    @NonNull
    public R await(long j, @NonNull TimeUnit timeUnit) {
        throw new UnsupportedOperationException("Operation not supported on PendingResults generated by ResultTransform.createFailedResult()");
    }

    public void cancel() {
        throw new UnsupportedOperationException("Operation not supported on PendingResults generated by ResultTransform.createFailedResult()");
    }

    /* Access modifiers changed, original: 0000 */
    @NonNull
    public Status getStatus() {
        return this.zzaaO;
    }

    public void setResultCallback(@NonNull ResultCallback<? super R> resultCallback) {
        throw new UnsupportedOperationException("Operation not supported on PendingResults generated by ResultTransform.createFailedResult()");
    }

    public void setResultCallback(@NonNull ResultCallback<? super R> resultCallback, long j, @NonNull TimeUnit timeUnit) {
        throw new UnsupportedOperationException("Operation not supported on PendingResults generated by ResultTransform.createFailedResult()");
    }

    @Nullable
    public Integer zzrv() {
        throw new UnsupportedOperationException("Operation not supported on PendingResults generated by ResultTransform.createFailedResult()");
    }
}