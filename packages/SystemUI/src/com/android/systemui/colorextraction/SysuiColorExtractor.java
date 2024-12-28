/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.colorextraction;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.colorextraction.types.ExtractionType;
import com.android.internal.colorextraction.types.Tonal;
import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;

import dagger.Lazy;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * ColorExtractor aware of wallpaper visibility
 */
@SysUISingleton
public class SysuiColorExtractor extends ColorExtractor implements Dumpable,
        ConfigurationController.ConfigurationListener {
    private static final String TAG = "SysuiColorExtractor";
    private final Tonal mTonal;
    private final GradientColors mNeutralColorsLock;
    private Lazy<SelectedUserInteractor> mUserInteractor;
    private int mMediaBackgroundColor = 0;

    private final List<MediaAccentColorListener> mListeners;// = new ArrayList<>();
    private int mMediaAccentColor = 0; // Store the color

    public static final String ACTION_MEDIA_ACCENT_COLOR_CHANGED =
        "com.android.systemui.ACTION_MEDIA_ACCENT_COLOR_CHANGED";

    @Inject
    public SysuiColorExtractor(
            Context context,
            ConfigurationController configurationController,
            DumpManager dumpManager,
            Lazy<SelectedUserInteractor> userInteractor) {
        this(
                context,
                new Tonal(context),
                configurationController,
                context.getSystemService(WallpaperManager.class),
                dumpManager,
                false /* immediately */,
                userInteractor);
    }

    @VisibleForTesting
    public SysuiColorExtractor(
            Context context,
            ExtractionType type,
            ConfigurationController configurationController,
            WallpaperManager wallpaperManager,
            DumpManager dumpManager,
            boolean immediately,
            Lazy<SelectedUserInteractor> userInteractor) {
        super(context, type, immediately, wallpaperManager);
        mTonal = type instanceof Tonal ? (Tonal) type : new Tonal(context);
        mNeutralColorsLock = new GradientColors();
        configurationController.addCallback(this);
        dumpManager.registerDumpable(getClass().getSimpleName(), this);
        mUserInteractor = userInteractor;
        mListeners = new ArrayList<>();

        // Listen to all users instead of only the current one.
        if (wallpaperManager.isWallpaperSupported()) {
            wallpaperManager.removeOnColorsChangedListener(this);
            wallpaperManager.addOnColorsChangedListener(this, null /* handler */,
                    UserHandle.USER_ALL);
        }
    }

    @Override
    protected void extractWallpaperColors() {
        super.extractWallpaperColors();
        // mTonal is final but this method will be invoked by the base class during its ctor.
        if (mTonal == null || mNeutralColorsLock == null) {
            return;
        }
        mTonal.applyFallback(mLockColors == null ? mSystemColors : mLockColors, mNeutralColorsLock);
    }

    @Override
    public void onColorsChanged(WallpaperColors colors, int which, int userId) {
        if (userId != mUserInteractor.get().getSelectedUserId()) {
            // Colors do not belong to current user, ignoring.
            return;
        }
        if ((which & WallpaperManager.FLAG_LOCK) != 0) {
            mTonal.applyFallback(colors, mNeutralColorsLock);
        }
        super.onColorsChanged(colors, which);
    }

    @Override
    public void onUiModeChanged() {
        extractWallpaperColors();
        triggerColorsChanged(WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
    }

    /**
     * Colors that should be using for scrims.
     *
     * They will be:
     * - A light gray if the wallpaper is light
     * - A dark gray if the wallpaper is very dark or we're in night mode.
     * - Black otherwise
     */
    public GradientColors getNeutralColors() {
        return mNeutralColorsLock;
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println("SysuiColorExtractor:");

        pw.println("  Current wallpaper colors:");
        pw.println("    system: " + mSystemColors);
        pw.println("    lock: " + mLockColors);

        GradientColors[] system = mGradientColors.get(WallpaperManager.FLAG_SYSTEM);
        GradientColors[] lock = mGradientColors.get(WallpaperManager.FLAG_LOCK);
        pw.println("  Gradients:");
        pw.println("    system: " + Arrays.toString(system));
        pw.println("    lock: " + Arrays.toString(lock));
        pw.println("  Neutral colors: " + mNeutralColorsLock);
    }

    public void setMediaBackgroundColor(int color) {
        Log.e(TAG, "DF: Received color: " + color);
        mMediaBackgroundColor = color;
        ArrayList<MediaAccentColorListener> callbacks = new ArrayList<>(mListeners);
        try {
            Log.e(TAG, "DF: setMediaBackgroundColor attach callbacks: ");
            for (MediaAccentColorListener listener : mListeners) {
                Log.e(TAG, "DF: for loop of listeners: " + color);
                listener.onMediaAccentColorUpdated(color);
            }
            for (int i = 0; i < callbacks.size(); i++) {
                Log.e(TAG, "setMediaBackgroundColor(): Callback " + i);
                // Todo figure out how to implement onMediaAccentColorUpdated similar to NotificationMediaManager doing this:
                // callbacks.get(i).setMediaNotificationColor(mColorExtractor.getMediaBackgroundColor());
            }
        } catch (Exception e) {
            Log.e(TAG, "DF: setMediaBackgroundColor exception: " + e);
        }
    }

    public int getMediaBackgroundColor() {
        return mMediaBackgroundColor;
    }

    public void addMediaAccentColorListener(MediaAccentColorListener listener) {
        mListeners.add(listener);
    }


    public void removeMediaAccentColorListener(MediaAccentColorListener listener) {
        mListeners.remove(listener);
    }

    public interface ColorExtractionListener {
        /**
         * Called whenever there's new ColorExtraction.
         */

        void onColorExtracted(int color);
    }

    public interface MediaAccentColorListener {
        void onMediaAccentColorUpdated(int color);
    }

}
