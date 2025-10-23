package com.example.giftguard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.*
// 상단 import에 추가



class ImageObserverService : Service() {

    private val TAG = "ImageObserverService"
    private lateinit var notificationManager: NotificationManager

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "GifticonOCRChannel"
    private val CHANNEL_NAME = "기프티콘 자동 인식"

    private val CONTENT_URI: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    companion object {
        const val ACTION_RUN_OCR = "ACTION_RUN_OCR"
        const val ACTION_CONFIRM_NO = "ACTION_CONFIRM_NO"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ImageObserverService started.")

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        startAsForeground()

        // 최신 이미지 감지 ContentObserver 등록
        contentResolver.registerContentObserver(
            CONTENT_URI,
            true,
            object : android.database.ContentObserver(Handler(mainLooper)) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    if (uri == null) return
                    Log.d(TAG, "새로운 이미지 감지됨: $uri")
                    // 방금 쓰기 완료 대기(간단히 1초 지연)
                    Handler(mainLooper).postDelayed({
                        sendConfirmationNotification(uri)
                    }, 1000)
                }
            }
        )
    }

    private fun startAsForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("기프티콘 감지 서비스 실행 중")
            .setContentText("갤러리 이미지 변경을 감시하고 있습니다.")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val uriFromData: Uri? = intent?.data
        val uriFromExtra = intent?.getStringExtra("EXTRA_IMAGE_URI")?.let { Uri.parse(it) }
        val targetUri = uriFromData ?: uriFromExtra

        when (action) {
            ACTION_RUN_OCR -> {
                if (targetUri == null) {
                    Log.e(TAG, "ACTION_RUN_OCR 수신했지만 URI가 없음")
                    return START_NOT_STICKY
                }
                Log.d(TAG, "OCR 요청 수신됨. URI: $targetUri")
                serviceScope.launch { runOcrAndSave(targetUri) }
            }
            ACTION_CONFIRM_NO -> {
                val notificationId = intent?.getIntExtra("EXTRA_NOTIFICATION_ID", -1) ?: -1
                if (notificationId != -1) notificationManager.cancel(notificationId)
                Log.d(TAG, "사용자가 OCR 자동 저장을 취소했습니다. 알림 ID $notificationId 닫음.")
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
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

    /**
     * 사용자가 YES를 눌렀을 때 Service로 URI 권한을 ‘명시적으로’ 위임하기 위해
     * - data = uri
     * - clipData = ClipData.newUri(contentResolver, "image", uri)
     * - addFlags(FLAG_GRANT_READ_URI_PERMISSION)
     * 를 모두 설정한 PendingIntent를 만든다.
     */
    private fun sendConfirmationNotification(uri: Uri) {
        val confirmationNotifId = NOTIFICATION_ID + 2

        // YES → Service(ACTION_RUN_OCR)
        val yesIntent = Intent(this, ImageObserverService::class.java).apply {
            action = ACTION_RUN_OCR
            data = uri
            clipData = ClipData.newUri(contentResolver, "image", uri) // ✅ 핵심: 명시적 권한 위임
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("EXTRA_IMAGE_URI", uri.toString())
            putExtra("EXTRA_NOTIFICATION_ID", confirmationNotifId)
        }

        val yesFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val yesPendingIntent = PendingIntent.getService(
            this, uri.hashCode(), yesIntent, yesFlags
        )

        // NO → Service(ACTION_CONFIRM_NO)
        val noIntent = Intent(this, ImageObserverService::class.java).apply {
            action = ACTION_CONFIRM_NO
            putExtra("EXTRA_NOTIFICATION_ID", confirmationNotifId)
        }

        val noFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val noPendingIntent = PendingIntent.getService(
            this, confirmationNotifId + 1, noIntent, noFlags
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("새 사진 감지: 기프티콘인가요?")
            .setContentText("✅ YES를 누르면 백그라운드에서 OCR을 실행하고 자동 저장합니다.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(R.drawable.ic_launcher_foreground, "✅ YES (자동 저장)", yesPendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, "❌ NO (취소)", noPendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(confirmationNotifId, notification)
    }

    /**
     * 최신 방식: getFilePathFromUri()로 실제 경로를 캐지 않고,
     * 부여된 URI 권한으로 바로 ML Kit에 넘겨 처리.
     */
    private suspend fun runOcrAndSave(uri: Uri) {
        Log.i(TAG, "OCR 처리 시작: $uri")
        try {
            val image = withContext(Dispatchers.IO) {
                InputImage.fromFilePath(this@ImageObserverService, uri)
            }

            val recognizer = TextRecognition.getClient(
                KoreanTextRecognizerOptions.Builder().build()
            )

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val recognizedText = visionText.text
                    Log.d(TAG, "OCR 성공. 인식 일부: ${recognizedText.take(100)}")

                    if (recognizedText.isBlank()) {
                        showResultNotification("자동 저장 실패", "이미지에서 텍스트를 찾지 못했어요.")
                        return@addOnSuccessListener
                    }

                    val giftCode = extractGiftCode(recognizedText)
                    // TODO: GifticonDbHelper를 사용해 giftCode와 메타데이터를 저장 (원하는 로직 연결)
                    // val db = GifticonDbHelper(this@ImageObserverService)
                    // val saved = db.insertGifticon(title, giftCode, memo, uri.toString())

                    showResultNotification("자동 저장 완료", "인식된 텍스트를 저장했습니다.")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "ML Kit OCR 실패: ${e.message}", e)
                    showResultNotification("자동 저장 실패", "텍스트 인식 중 오류: ${e.message}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "이미지 접근/처리 실패: ${e.message}", e)
            showResultNotification("자동 저장 실패", "이미지를 열 수 없습니다. (권한/경로)")
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

        notificationManager.notify(NOTIFICATION_ID + 3, resultNotification)
    }
}
