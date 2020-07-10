/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.biometrics.sensors.fingerprint;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprint;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SELinux;
import android.util.Slog;

import com.android.server.biometrics.sensors.ClientMonitor;

import java.io.File;
import java.util.Map;

/**
 * Sets the HAL's current active user, and updates the framework's authenticatorId cache.
 */
public class FingerprintUpdateActiveUserClient extends ClientMonitor<IBiometricsFingerprint> {

    private static final String TAG = "FingerprintUpdateActiveUserClient";
    private static final String FP_DATA_DIR = "fpdata";

    private final int mCurrentUserId;
    private final boolean mHasEnrolledBiometrics;
    private final Map<Integer, Long> mAuthenticatorIds;
    private File mDirectory;

    FingerprintUpdateActiveUserClient(@NonNull Context context,
            @NonNull LazyDaemon<IBiometricsFingerprint> lazyDaemon, int userId,
            @NonNull String owner, int sensorId, int currentUserId, boolean hasEnrolledBiometrics,
            @NonNull Map<Integer, Long> authenticatorIds) {
        super(context, lazyDaemon, null /* token */, null /* listener */, userId, owner,
                0 /* cookie */, sensorId, BiometricsProtoEnums.MODALITY_UNKNOWN,
                BiometricsProtoEnums.ACTION_UNKNOWN, BiometricsProtoEnums.CLIENT_UNKNOWN);
        mCurrentUserId = currentUserId;
        mHasEnrolledBiometrics = hasEnrolledBiometrics;
        mAuthenticatorIds = authenticatorIds;
    }

    @Override
    public void start(@NonNull FinishCallback finishCallback) {
        super.start(finishCallback);

        if (mCurrentUserId == getTargetUserId()) {
            Slog.d(TAG, "Already user: " + mCurrentUserId + ", refreshing authenticatorId");
            try {
                mAuthenticatorIds.put(getTargetUserId(), mHasEnrolledBiometrics
                        ? getFreshDaemon().getAuthenticatorId() : 0L);
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to refresh authenticatorId", e);
            }
            finishCallback.onClientFinished(this, true /* success */);
            return;
        }

        int firstSdkInt = Build.VERSION.FIRST_SDK_INT;
        if (firstSdkInt < Build.VERSION_CODES.BASE) {
            Slog.e(TAG, "First SDK version " + firstSdkInt + " is invalid; must be " +
                    "at least VERSION_CODES.BASE");
        }
        File baseDir;
        if (firstSdkInt <= Build.VERSION_CODES.O_MR1) {
            baseDir = Environment.getUserSystemDirectory(getTargetUserId());
        } else {
            baseDir = Environment.getDataVendorDeDirectory(getTargetUserId());
        }

        mDirectory = new File(baseDir, FP_DATA_DIR);
        if (!mDirectory.exists()) {
            if (!mDirectory.mkdir()) {
                Slog.e(TAG, "Cannot make directory: " + mDirectory.getAbsolutePath());
                finishCallback.onClientFinished(this, false /* success */);
                return;
            }
            // Calling mkdir() from this process will create a directory with our
            // permissions (inherited from the containing dir). This command fixes
            // the label.
            if (!SELinux.restorecon(mDirectory)) {
                Slog.e(TAG, "Restorecons failed. Directory will have wrong label.");
                finishCallback.onClientFinished(this, false /* success */);
                return;
            }
        }

        startHalOperation();
    }

    @Override
    public void unableToStart() {
        // Nothing to do here
    }

    @Override
    protected void startHalOperation() {
        try {
            getFreshDaemon().setActiveGroup(getTargetUserId(), mDirectory.getAbsolutePath());
            mAuthenticatorIds.put(getTargetUserId(), mHasEnrolledBiometrics
                    ? getFreshDaemon().getAuthenticatorId() : 0L);
            mFinishCallback.onClientFinished(this, true /* success */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to setActiveGroup: " + e);
            mFinishCallback.onClientFinished(this, false /* success */);
        }
    }
}
