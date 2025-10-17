package com.example.giftguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.app.NotificationManager

class NotificationActionReceiver : BroadcastReceiver() {

    private val TAG = "NotifReceiver"

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action
        val imageUriString = intent.getStringExtra("EXTRA_IMAGE_URI")
        val notificationId = intent.getIntExtra("EXTRA_NOTIFICATION_ID", -1)

        if (action == "ACTION_CONFIRM_OCR" && imageUriString != null) {
            Log.d(TAG, "OCR 확인 요청 수신됨. URI: $imageUriString")

            // 기존 알림 닫기
            if (notificationId != -1) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(notificationId)
            }

            val imageUri = Uri.parse(imageUriString)

            // 서비스에 OCR 실행을 지시하는 Intent를 보냅니다.
            val serviceIntent = Intent(context, ImageObserverService::class.java).apply {
                // 🌟 ImageObserverService에서 정의한 상수 사용
                setAction(ImageObserverService.ACTION_RUN_OCR)
                putExtra("EXTRA_IMAGE_URI", imageUri.toString())
            }
            context.startService(serviceIntent)
        }
    }
}