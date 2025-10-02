package com.example.giftguard

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.giftguard.databinding.ActivityOcrBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions // 🚨 한국어 옵션 import

class OcrActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOcrBinding
    private var pickedUri: Uri? = null

    // 갤러리에서 이미지 선택 결과를 처리하는 런처
    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            pickedUri = uri
            binding.imagePreview.setImageURI(uri)
        } else {
            Toast.makeText(this, "이미지를 선택하지 않았어", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ❌ enableEdgeToEdge() 호출 제거 (레이아웃 패딩 사용 시 충돌 방지)
        binding = ActivityOcrBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 이미지 선택 버튼
        binding.btnPick.setOnClickListener {
            pickImage.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        // 텍스트 인식 실행 버튼
        binding.btnRecognize.setOnClickListener {
            val uri = pickedUri ?: run {
                Toast.makeText(this, "먼저 이미지를 선택해줘", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            try {
                val image = InputImage.fromFilePath(this, uri)
                // 🚨🚨 한국어 인식 클라이언트 사용 (KoreanTextRecognizerOptions)
                val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        binding.tvResult.text = visionText.text
                        if (visionText.text.isBlank()) {
                            Toast.makeText(this, "텍스트를 찾지 못했어", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .addOnFailureListener { e ->
                        binding.tvResult.text = ""
                        Toast.makeText(this, "인식 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            } catch (e: Exception) {
                Toast.makeText(this, "이미지 로드 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // 지도 화면으로 돌아가기 버튼 (activity_ocr.xml에 btnGoToMap ID가 추가되어 있어야 함)

        // 복사 버튼
        binding.btnCopy.setOnClickListener {
            val text = binding.tvResult.text?.toString().orEmpty()
            if (text.isNotBlank()) {
                val clip = ClipData.newPlainText("OCR", text)
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(clip)
                Toast.makeText(this, "복사했어", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "복사할 텍스트가 없어", Toast.LENGTH_SHORT).show()
            }
        }
    }
}