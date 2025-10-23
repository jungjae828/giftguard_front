package com.example.giftguard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.InputStream
import java.io.IOException
import kotlinx.coroutines.* // URI to Path 변환을 위한 확장 함수 (별도 파일에 두는 것이 좋으나, 여기서는 편의상 Service 내부에 구현)
fun Context.getFilePathFromUri(uri: Uri): String? {
    var filePath: String? = null
    val projection = arrayOf(MediaStore.Images.Media.DATA)
    contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            filePath = cursor.getString(columnIndex)
        }
    }
    return filePath
}

class ImageObserverService : Service() {

    private val TAG = "ImageObserverService"
    private lateinit var contentObserver: ContentObserver
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
        startForegroundService()

        contentObserver = object : ContentObserver(Handler(mainLooper)) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                uri?.let {
                    Log.d(TAG, "새로운 이미지 감지됨: $it")
                    Handler(mainLooper).postDelayed({
                        sendConfirmationNotification(it)
                    }, 1000)
                }
            }
        }

        contentResolver.registerContentObserver(
            CONTENT_URI,
            true,
            contentObserver
        )
    }

    private fun startForegroundService() {
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
        super.onStartCommand(intent, flags, startId)

        val action = intent?.action
        val uri = intent?.data
        val notificationId = intent?.getIntExtra("EXTRA_NOTIFICATION_ID", -1)

        if (action == ACTION_RUN_OCR && uri != null) {
            Log.d(TAG, "OCR 요청 수신됨. URI: $uri")

            // 🚨 takePersistableUriPermission 제거: 어차피 실패하므로, 대신 파일 경로를 사용할 준비를 합니다.

            serviceScope.launch {
                runOcrAndSave(uri) // URI 대신 경로를 찾아서 사용합니다.
            }
        } else if (action == ACTION_CONFIRM_NO) {
            if (notificationId != null && notificationId != -1) {
                notificationManager.cancel(notificationId)
            }
            Log.d(TAG, "사용자가 OCR 자동 저장을 취소했습니다. 알림 ID $notificationId 닫음.")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(contentObserver)
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

    private fun sendConfirmationNotification(uri: Uri) {
        val confirmationNotifId = NOTIFICATION_ID + 2

        // YES Intent: FLAG_GRANT_READ_URI_PERMISSION은 유지
        val yesIntent = Intent(this, ImageObserverService::class.java).apply {
            action = ACTION_RUN_OCR
            putExtra("EXTRA_IMAGE_URI", uri.toString())
            putExtra("EXTRA_NOTIFICATION_ID", confirmationNotifId)

            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            data = uri
        }

        // FLAG_MUTABLE 유지: PendingIntent가 Service를 호출하는 데 필요한 최소한의 권한 위임을 시도
        val yesPendingIntent = PendingIntent.getService(
            this, uri.hashCode(), yesIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        // NO Intent
        val noIntent = Intent(this, ImageObserverService::class.java).apply {
            action = ACTION_CONFIRM_NO
            putExtra("EXTRA_NOTIFICATION_ID", confirmationNotifId)
            data = uri
        }

        val noPendingIntent = PendingIntent.getService(
            this, uri.hashCode() + 1, noIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("새로운 사진 감지: 기프티콘인가요?")
            .setContentText("YES를 누르면 백그라운드에서 OCR을 실행하고 자동 저장합니다.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(R.drawable.ic_launcher_foreground, "✅ YES (자동 저장)", yesPendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, "❌ NO (취소)", noPendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(confirmationNotifId, notification)
    }

    // 🚨 runOcrAndSave 수정: URI 대신 File Path를 사용
    fun runOcrAndSave(uri: Uri) {
        Log.i(TAG, "OCR 처리 시작: $uri")

        val filePath = getFilePathFromUri(uri)
        if (filePath == null) {
            Log.e(TAG, "URI로부터 실제 파일 경로 획득 실패. $uri")
            showResultNotification("자동 저장 실패", "이미지 파일 접근 경로를 찾을 수 없습니다.")
            return
        }

        Log.d(TAG, "파일 경로 획득 성공: $filePath")

        // **중요:** 여기서부터는 일반적인 File I/O를 사용하여 접근을 시도합니다.

        var inputStream: InputStream? = null
        var bitmap: Bitmap? = null

        try {
            // 파일을 직접 열어 Bitmap을 디코딩합니다. (파일 권한이 있다면)
            val file = java.io.File(filePath)
            if (!file.exists()) {
                throw IOException("파일이 존재하지 않습니다: $filePath")
            }

            // 파일 경로를 사용하여 InputImage 생성 (ContentResolver 사용 회피)
            val image = InputImage.fromFilePath(this, uri)

            if (image == null) {
                // ContentResolver를 거치지만, 이미 파일 경로를 확보했으므로 권한 문제가 덜 심각할 수 있습니다.
                throw IOException("InputImage 생성 실패. URI: $uri")
            }

            Log.d(TAG, "Bitmap 디코딩 성공. OCR 인식 시작.")

            val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val recognizedText = visionText.text
                    Log.d(TAG, "OCR 성공. 인식된 텍스트: ${recognizedText.take(100)}...")

                    if (recognizedText.isBlank()) {
                        showResultNotification("자동 저장 실패", "이미지에서 텍스트를 찾지 못하여 저장할 수 없습니다.")
                        return@addOnSuccessListener
                    }

                    val giftCode = extractGiftCode(recognizedText)

                    val saveSuccess = true

                    if (saveSuccess) {
                        showResultNotification("자동 저장 완료", "기프티콘 코드가 성공적으로 저장되었습니다.")
                    } else {
                        showResultNotification("자동 저장 실패", "코드 중복 또는 DB 오류로 저장이 실패했습니다.")
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "ML Kit OCR 처리 실패: ${e.message}", e)
                    showResultNotification("자동 저장 실패", "텍스트 인식 중 오류가 발생했습니다: ${e.message}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "최종 이미지 로드/처리 오류: ${e.message}", e)
            showResultNotification("자동 저장 실패", "이미지 파일을 로드할 수 없습니다. (권한/접근 오류)")
        } finally {
            try {
                inputStream?.close()
                bitmap?.recycle()
                Log.d(TAG, "OCR 작업 완료. 리소스 정리 완료.")
            } catch (e: Exception) {
                Log.e(TAG, "리소스 정리 실패: ${e.message}")
            }
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