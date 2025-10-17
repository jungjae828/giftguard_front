package com.example.giftguard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

class ImageObserverService : Service() {

    private val TAG = "ImageObserverService"
    private lateinit var contentObserver: ContentObserver
    private lateinit var notificationManager: NotificationManager

    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "GifticonOCRChannel"
    private val CHANNEL_NAME = "기프티콘 자동 인식"

    private val CONTENT_URI: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    // 🌟 액션 상수를 정의하여 BroadcastReceiver와 통일
    companion object {
        const val ACTION_RUN_OCR = "ACTION_RUN_OCR"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ImageObserverService started.")

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        // 🌟 포그라운드 서비스 시작 (Android 8.0 이상 필수)
        startForegroundService()

        contentObserver = object : ContentObserver(Handler(mainLooper)) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                uri?.let {
                    Log.d(TAG, "새로운 이미지 감지됨: $it")
                    sendConfirmationNotification(it)
                }
            }
        }

        contentResolver.registerContentObserver(
            CONTENT_URI,
            true,
            contentObserver
        )
    }

    // 🌟 포그라운드 서비스 실행을 위한 헬퍼 함수
    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("기프티콘 감지 서비스 실행 중")
            .setContentText("갤러리 이미지 변경을 감시하고 있습니다.")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

        // NOTIFICATION_ID는 0이 될 수 없습니다.
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RUN_OCR) { // 🌟 정의된 상수 사용
            val uriString = intent.getStringExtra("EXTRA_IMAGE_URI")
            if (uriString != null) {
                runOcrAndSave(Uri.parse(uriString))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(contentObserver)
        Log.d(TAG, "ImageObserverService stopped.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun sendConfirmationNotification(uri: Uri) {
        val confirmIntent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = "ACTION_CONFIRM_OCR"
            putExtra("EXTRA_IMAGE_URI", uri.toString())
            putExtra("EXTRA_NOTIFICATION_ID", NOTIFICATION_ID)
        }

        val confirmPendingIntent: PendingIntent = PendingIntent.getBroadcast(
            this,
            uri.hashCode(),
            confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("새로운 사진 감지: 기프티콘인가요?")
            .setContentText("갤러리에 새로운 이미지가 저장되었습니다. OCR로 등록하시겠어요?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(R.drawable.ic_launcher_foreground, "확인, OCR 시작", confirmPendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID + 2, notification) // 🌟 NOTIFICATION_ID가 포그라운드 알림으로 사용되므로, 다른 ID 사용
    }

    fun runOcrAndSave(uri: Uri) {
        Log.i(TAG, "사용자 확인 완료. OCR 처리 시작: $uri")

        try {
            val image = InputImage.fromFilePath(this, uri)
            val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val recognizedText = visionText.text

                    if (recognizedText.isBlank()) {
                        showResultNotification("OCR 실패", "이미지에서 텍스트를 찾지 못했습니다.")
                        return@addOnSuccessListener
                    }

                    val giftCode = extractGiftCode(recognizedText)

                    showResultNotification("OCR 완료", "기프티콘 정보가 성공적으로 인식되어 저장되었습니다.")
                }
                .addOnFailureListener { e ->
                    showResultNotification("OCR 실패", "텍스트 인식 중 오류가 발생했습니다: ${e.message}")
                }
        } catch (e: Exception) {
            showResultNotification("OCR 실패", "이미지 파일을 로드할 수 없습니다.")
        }
    }

    private fun extractGiftCode(text: String): String {
        val regex = Regex("(\\w{4}[-\\s]?){2}\\w{4}")
        return regex.find(text)?.value?.replace("\\s".toRegex(), "") ?: "코드 추출 실패"
    }

    private fun showResultNotification(title: String, content: String) {
        val resultNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID + 3, resultNotification) // 🌟 포그라운드/확인 알림과 다른 ID 사용
    }
}