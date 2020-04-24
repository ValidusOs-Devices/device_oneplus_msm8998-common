/*
 * Copyright (C) 2015-2016 The CyanogenMod Project
 * Copyright (C) 2017 The LineageOS Project
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

package org.carbonrom.settings.device;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.session.MediaSessionLegacyHelper;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.KeyEvent;

import com.android.internal.os.DeviceKeyHandler;
import com.android.internal.util.ArrayUtils;

public class KeyHandler implements DeviceKeyHandler {

    private static final String TAG = KeyHandler.class.getSimpleName();
    private static final int GESTURE_REQUEST = 1;
    private static String FPNAV_ENABLED_PROP = "sys.fpnav.enabled";

    private static final int ZEN_MODE_VIBRATION = 4;

    // Supported scancodes
    private static final int FLIP_CAMERA_SCANCODE = 249;
    private static final int MODE_TOTAL_SILENCE = 600;
    private static final int MODE_PRIORITY_ONLY = 601;
    private static final int MODE_VIBRATION = 602;
    private static final int MODE_NONE = 603;

    private static final int GESTURE_WAKELOCK_DURATION = 3000;

    private static final SparseIntArray sSupportedSliderModes = new SparseIntArray();
    static {
        sSupportedSliderModes.put(MODE_TOTAL_SILENCE, Settings.Global.ZEN_MODE_NO_INTERRUPTIONS);
        sSupportedSliderModes.put(MODE_PRIORITY_ONLY,
                Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS);
        sSupportedSliderModes.put(MODE_VIBRATION, ZEN_MODE_VIBRATION);
        sSupportedSliderModes.put(MODE_NONE, Settings.Global.ZEN_MODE_OFF);
    }

    private final Context mContext;
    private final AudioManager mAudioManager;
    private final PowerManager mPowerManager;
    private final NotificationManager mNotificationManager;
    private EventHandler mEventHandler;
    private SensorManager mSensorManager;
    private Sensor mProximitySensor;
    private Vibrator mVibrator;
    WakeLock mProximityWakeLock;
    WakeLock mGestureWakeLock;
    private int mProximityTimeOut;
    private boolean mProximityWakeSupported;

    public KeyHandler(Context context) {
        mContext = context;
        mAudioManager = context.getSystemService(AudioManager.class);
        mPowerManager = context.getSystemService(PowerManager.class);
        mNotificationManager = context.getSystemService(NotificationManager.class);
        mEventHandler = new EventHandler();
        mGestureWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "GestureWakeLock");

        mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (mVibrator == null || !mVibrator.hasVibrator()) {
            mVibrator = null;
        }
    }

    private class EventHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            if (msg.arg1 == FLIP_CAMERA_SCANCODE) {
                mGestureWakeLock.acquire(GESTURE_WAKELOCK_DURATION);
                doHapticFeedback();
            }
        }
    }

    private boolean hasSetupCompleted() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, 0) != 0;
    }

    public KeyEvent handleKeyEvent(KeyEvent event) {
        int scanCode = event.getScanCode();
        boolean isKeySupported = scanCode == FLIP_CAMERA_SCANCODE;
        boolean isSliderModeSupported = sSupportedSliderModes.indexOfKey(scanCode) >= 0;
        if (!isKeySupported && !isSliderModeSupported) {
            return event;
        }

        if (!hasSetupCompleted()) {
            return event;
        }

        // We only want ACTION_UP event, except FLIP_CAMERA_SCANCODE
        if (scanCode == FLIP_CAMERA_SCANCODE) {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return null;
            }
        } else if (event.getAction() != KeyEvent.ACTION_UP) {
            return null;
        }

        if (isSliderModeSupported) {
            if (scanCode == MODE_VIBRATION) {
                mNotificationManager.setZenMode(Settings.Global.ZEN_MODE_OFF, null, TAG);
                mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_VIBRATE);
            } else {
                mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL);
                mNotificationManager.setZenMode(sSupportedSliderModes.get(scanCode), null, TAG);
            }
            doHapticFeedback();
        } 
        return null;
    }

    private Message getMessageForKeyEvent(int scancode) {
        Message msg = mEventHandler.obtainMessage(GESTURE_REQUEST);
        msg.arg1 = scancode;
        return msg;
    }

    private void processEvent(final int scancode) {
        mProximityWakeLock.acquire();
        mSensorManager.registerListener(new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                mProximityWakeLock.release();
                mSensorManager.unregisterListener(this);
                if (!mEventHandler.hasMessages(GESTURE_REQUEST)) {
                    // The sensor took to long, ignoring.
                    return;
                }
                mEventHandler.removeMessages(GESTURE_REQUEST);
                if (event.values[0] == mProximitySensor.getMaximumRange()) {
                    Message msg = getMessageForKeyEvent(scancode);
                    mEventHandler.sendMessage(msg);
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}

        }, mProximitySensor, SensorManager.SENSOR_DELAY_FASTEST);
    }

    private void doHapticFeedback() {
        if (mVibrator == null) {
            return;
        }
	mVibrator.vibrate(50);
    }

    public void handleNavbarToggle(boolean enabled) {
        SystemProperties.set(FPNAV_ENABLED_PROP, enabled ? "0" : "1");
    }

    public boolean canHandleKeyEvent(KeyEvent event) {
        return false;
        }

}
