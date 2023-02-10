/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.systemui.screenshot

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.UserHandle
import android.util.Log
import android.view.Display
import com.android.internal.R
import com.android.internal.messages.nano.SystemMessageProto
import com.android.systemui.SystemUIApplication
import com.android.systemui.util.NotificationChannels
import com.android.systemui.screenshot.LegacyScreenshotController.SCREENSHOT_URI_ID
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

/** Convenience class to handle showing and hiding notifications while taking a screenshot. */
class ScreenshotNotificationsController
@AssistedInject
internal constructor(
    @Assisted private val displayId: Int,
    private val context: Context,
    private val notificationManager: NotificationManager,
    private val devicePolicyManager: DevicePolicyManager,
) {
    private val res = context.resources
    private val TAG: String = "ScreenshotNotificationsController"

    /**
     * Sends a notification that the screenshot capture has failed.
     *
     * Errors for the non-default display are shown in a unique separate notification.
     */
    fun notifyScreenshotError(msgResId: Int) {
        val displayErrorString =
            if (displayId != Display.DEFAULT_DISPLAY) {
                " ($externalDisplayString)"
            } else {
                ""
            }
        val errorMsg = res.getString(msgResId) + displayErrorString

        // Repurpose the existing notification or create a new one
        val builder =
            Notification.Builder(context, NotificationChannels.ALERTS)
                .setTicker(res.getString(com.android.systemui.res.R.string.screenshot_failed_title))
                .setContentTitle(
                    res.getString(com.android.systemui.res.R.string.screenshot_failed_title)
                )
                .setContentText(errorMsg)
                .setSmallIcon(com.android.systemui.res.R.drawable.stat_notify_image_error)
                .setWhen(System.currentTimeMillis())
                .setVisibility(Notification.VISIBILITY_PUBLIC) // ok to show outside lockscreen
                .setCategory(Notification.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setColor(context.getColor(R.color.system_notification_accent_color))
        val intent =
            devicePolicyManager.createAdminSupportIntent(
                DevicePolicyManager.POLICY_DISABLE_SCREEN_CAPTURE
            )
        if (intent != null) {
            val pendingIntent =
                PendingIntent.getActivityAsUser(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE,
                    null,
                    UserHandle.CURRENT
                )
            builder.setContentIntent(pendingIntent)
        }
        SystemUIApplication.overrideNotificationAppName(context, builder, true)
        val notification = Notification.BigTextStyle(builder).bigText(errorMsg).build()
        // A different id for external displays to keep the 2 error notifications separated.
        val id =
            if (displayId == Display.DEFAULT_DISPLAY) {
                SystemMessageProto.SystemMessage.NOTE_GLOBAL_SCREENSHOT
            } else {
                SystemMessageProto.SystemMessage.NOTE_GLOBAL_SCREENSHOT_EXTERNAL_DISPLAY
            }
        notificationManager.notify(id, notification)
    }

    /**
     * Shows a notification containing the screenshot and the chip actions
     * @param imageData for actions, uri. cannot be null
     */
    fun showPostActionNotification(imageData: ScreenshotData, uri: Uri) {
        val SCREENSHOT_URI_ID = "android:screenshot_uri_id"
        val uri = uri
        val requestCode = uri.toString().hashCode()

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            setDataAndType(uri, "image/*")
        }
        val pendingViewIntent = PendingIntent.getActivity(
            context,
            0,
            viewIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
            type = "image/*"
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        val pendingShareIntent = PendingIntent.getActivity(
            context,
            requestCode,
            shareIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        val actionShare = Notification.Action.Builder(
            0, // no icon
            res.getText(com.android.systemui.res.R.string.screenrecord_share_label),
            pendingShareIntent
        ).build()

        val deleteIntent = Intent(context, DeleteScreenshotReceiver::class.java).apply {
            putExtra(SCREENSHOT_URI_ID, uri.toString())
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        val pendingDeleteIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            deleteIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        val actionDelete = Notification.Action.Builder(
            0, // no icon
            res.getText(com.android.systemui.res.R.string.screenshot_delete_label),
            pendingDeleteIntent
        ).build()

        val notificationBuilder = Notification.Builder(context, NotificationChannels.SCREENSHOTS_HEADSUP)
            .setTicker(res.getString(com.android.systemui.res.R.string.screenshot_saved_title))
            .setContentTitle(res.getString(com.android.systemui.res.R.string.screenshot_saved_title))
            .setContentText(res.getString(com.android.systemui.res.R.string.screenrecord_save_text))
            .setSmallIcon(com.android.systemui.res.R.drawable.screenshot_image)
            .setWhen(System.currentTimeMillis())
            .setAutoCancel(true)
            .setContentIntent(pendingViewIntent)
            .addAction(actionShare)
            .addAction(actionDelete)
            .setColor(context.getColor(com.android.internal.R.color.system_notification_accent_color))

        // Add BigPictureStyle if bitmap is available
        imageData.bitmap?.let {
            notificationBuilder.setStyle(Notification.BigPictureStyle().bigPicture(it))
        }

        notificationManager.notify(requestCode, notificationBuilder.build())
    }

    private val externalDisplayString: String
        get() =
            res.getString(
                com.android.systemui.res.R.string.screenshot_failed_external_display_indication
            )

    /** Factory for [ScreenshotNotificationsController]. */
    @AssistedFactory
    fun interface Factory {
        fun create(displayId: Int): ScreenshotNotificationsController
    }
}
