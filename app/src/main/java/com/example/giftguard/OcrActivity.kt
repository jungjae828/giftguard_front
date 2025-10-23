package com.example.giftguard

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.giftguard.databinding.ActivityOcrBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.*

class OcrActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOcrBinding
    private var pickedUri: Uri? = null
    private var lastRecognizedText: String = ""

    // 목록
    private lateinit var recycler: RecyclerView
    private val items = mutableListOf<Gifticon>()
    private lateinit var db: GifticonDbHelper
    private var listVisible = false

    // 포토 피커
    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            pickedUri = uri
            binding.imagePreview.setImageURI(uri)
            binding.tvResult.text = ""
            lastRecognizedText = ""
            setActionButtonsEnabled(false)
        } else {
            Toast.makeText(this, "이미지를 선택하지 않았어", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOcrBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = GifticonDbHelper(this)
        recycler = binding.recyclerGifticons
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        recycler.adapter = GifticonAdapter(items) { item -> requestDelete(item) }

        setActionButtonsEnabled(false)

        binding.btnPick.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        binding.btnRecognize.setOnClickListener {
            val uri = pickedUri ?: run {
                Toast.makeText(this, "먼저 이미지를 선택해줘", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            runOcr(uri)
        }

        binding.btnCopy.setOnClickListener {
            val text = binding.tvResult.text?.toString().orEmpty()
            if (text.isBlank()) {
                Toast.makeText(this, "복사할 텍스트가 없어", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("OCR", text))
            Toast.makeText(this, "복사했어", Toast.LENGTH_SHORT).show()
        }

        binding.btnSaveOcr.setOnClickListener {
            val uri = pickedUri ?: run {
                Toast.makeText(this, "이미지를 다시 선택해줘", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (lastRecognizedText.isBlank()) {
                Toast.makeText(this, "먼저 인식해줘", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val text = lastRecognizedText
            showSaveLiteDialog(
                imageUri = uri,
                menuInit = extractMenuName(text),
                merchantInit = extractMerchant(text),
                expiryInit = extractExpiryDate(text)
            )
        }

        binding.btnViewSaved.setOnClickListener {
            if (listVisible) {
                recycler.visibility = View.GONE
                binding.btnViewSaved.text = "저장된 기프티콘 목록 보기"
            } else {
                loadGifticons()
                recycler.visibility = View.VISIBLE
                binding.btnViewSaved.text = "목록 숨기기"
            }
            listVisible = !listVisible
        }
    }

    private fun setActionButtonsEnabled(enabled: Boolean) {
        binding.btnSaveOcr.isEnabled = enabled
        binding.btnCopy.isEnabled = enabled
    }

    // ===== OCR =====
    private fun runOcr(uri: Uri) {
        try {
            val image = InputImage.fromFilePath(this, uri)
            val recognizer = TextRecognition.getClient(
                KoreanTextRecognizerOptions.Builder().build()
            )
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    lastRecognizedText = visionText.text.orEmpty()
                    binding.tvResult.text = lastRecognizedText
                    setActionButtonsEnabled(lastRecognizedText.isNotBlank())
                }
                .addOnFailureListener { e ->
                    lastRecognizedText = ""
                    binding.tvResult.text = ""
                    setActionButtonsEnabled(false)
                    Toast.makeText(this, "인식 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            Toast.makeText(this, "이미지 로드 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ===== 저장 다이얼로그 =====
    private fun showSaveLiteDialog(imageUri: Uri, menuInit: String, merchantInit: String, expiryInit: String) {
        val etMenu = EditText(this).apply { hint = "메뉴 이름"; setText(menuInit) }
        val etMerchant = EditText(this).apply { hint = "사용처(브랜드/매장)"; setText(merchantInit) }
        val etExpiry = EditText(this).apply {
            hint = "유효기간 (YYYY-MM-DD)"
            setText(expiryInit)
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(etMenu)
            addView(etMerchant)
            addView(etExpiry)
        }

        AlertDialog.Builder(this)
            .setTitle("기프티콘으로 저장")
            .setMessage("메뉴/사용처/유효기간을 확인해줘")
            .setView(container)
            .setPositiveButton("저장") { d, _ ->
                val menu = etMenu.text?.toString()?.trim().orEmpty()
                val merchant = etMerchant.text?.toString()?.trim().orEmpty()
                val expiryRaw = etExpiry.text?.toString()?.trim().orEmpty()
                val expiryYmd = toYmd(expiryRaw) ?: ""

                if (menu.isBlank() || merchant.isBlank() || !isValidYmd(expiryYmd)) {
                    Toast.makeText(this, "입력을 확인해줘 (유효기간 예: 2025-12-31)", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                saveLite(menu, merchant, expiryYmd, imageUri.toString(), null, null)
                d.dismiss()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ===== DB 저장/조회/삭제 =====
    private fun saveLite(menu: String, merchant: String, expiry: String, imageUri: String, code: String?, memo: String?) {
        CoroutineScope(Dispatchers.IO).launch {
            val ok = db.insertGifticonLite(menu, merchant, expiry, imageUri, code, memo)
            withContext(Dispatchers.Main) {
                if (ok) {
                    Toast.makeText(this@OcrActivity, "저장 완료!", Toast.LENGTH_SHORT).show()
                    if (listVisible) loadGifticons()
                } else {
                    Toast.makeText(this@OcrActivity, "저장 실패(중복 또는 DB 오류)", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadGifticons() {
        items.clear()
        val c = db.readableDatabase.query(
            GifticonDbHelper.TABLE_NAME,
            arrayOf(
                GifticonDbHelper.COLUMN_ID,
                GifticonDbHelper.COLUMN_MENU_NAME,
                GifticonDbHelper.COLUMN_MERCHANT,
                GifticonDbHelper.COLUMN_EXPIRY_DATE,
                GifticonDbHelper.COLUMN_IMAGE_URI
            ),
            null, null, null, null,
            "${GifticonDbHelper.COLUMN_SAVED_DATE} DESC"
        )
        c.use {
            val id = it.getColumnIndexOrThrow(GifticonDbHelper.COLUMN_ID)
            val menu = it.getColumnIndexOrThrow(GifticonDbHelper.COLUMN_MENU_NAME)
            val merchant = it.getColumnIndexOrThrow(GifticonDbHelper.COLUMN_MERCHANT)
            val expiry = it.getColumnIndexOrThrow(GifticonDbHelper.COLUMN_EXPIRY_DATE)
            val image = it.getColumnIndexOrThrow(GifticonDbHelper.COLUMN_IMAGE_URI)
            while (it.moveToNext()) {
                items.add(
                    Gifticon(
                        id = it.getLong(id),
                        menuName = it.getString(menu),
                        merchant = it.getString(merchant),
                        expiryDate = it.getString(expiry),
                        imageUri = it.getString(image)
                    )
                )
            }
        }
        recycler.adapter?.notifyDataSetChanged()
    }

    private fun requestDelete(item: Gifticon) {
        AlertDialog.Builder(this)
            .setTitle("삭제할까요?")
            .setMessage("‘${item.menuName ?: "기프티콘"}’ 항목을 삭제합니다.")
            .setPositiveButton("삭제") { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    val ok = db.deleteGifticonById(item.id)
                    withContext(Dispatchers.Main) {
                        if (ok) {
                            items.removeAll { it.id == item.id }
                            recycler.adapter?.notifyDataSetChanged()
                            Toast.makeText(this@OcrActivity, "삭제 완료", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@OcrActivity, "삭제 실패", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ===== 날짜 추출(강화판) =====
    private fun extractExpiryDate(original: String): String {
        val norm = normalizeOcrNoise(original)

        val lines = norm.lines().map { it.trim() }.filter { it.isNotBlank() }
        val keywordLines = lines.filter { it.contains("유효기간") || it.contains("만료") || it.contains("까지") }
        val pools = (keywordLines + norm).distinct()

        fun rightOfRange(s: String): String {
            val parts = s.split('~', '〜', '–', '—').map { it.trim() }
            return if (parts.size >= 2) parts.last() else s
        }

        val patterns: List<Regex> = listOf(
            Regex("""(20\d{2})\s*년\s*(1[0-2]|0?[1-9])\s*월\s*(3[01]|[12]?\d)\s*일?(\s*\([^)]+\))?(\s*\d{1,2}:\d{2})?\s*(까지|만료)?"""),
            Regex("""(20\d{2})[.\-/](1[0-2]|0?[1-9])[.\-/](3[01]|[12]?\d)"""),
            Regex("""(2\d)[.\-/](1[0-2]|0?[1-9])[.\-/](3[01]|[12]?\d)"""),
            Regex("""\b((20\d{2})(1[0-2]|0[1-9])(3[01]|[12]\d))\b"""),
            Regex("""\b((\d{2})(1[0-2]|0[1-9])(3[01]|[12]\d))\b"""),
            Regex("""\b(1[0-2]|0?[1-9])[.\-/](3[01]|[12]?\d)\b""")
        )

        for (pool in pools) {
            val target = rightOfRange(pool)
            for (p in patterns) {
                val m = p.find(target) ?: continue
                val ymd = toYmd(m.value)
                if (ymd != null && isValidYmd(ymd)) return ymd
            }
        }
        return ""
    }

    // OCR 오인식 치환 + 하이픈 통일
    private fun normalizeOcrNoise(s: String): String {
        return s
            .replace('–', '-')  // en dash
            .replace('—', '-')  // em dash
            .map { ch ->
                when (ch) {
                    'l', 'I' -> '1'
                    'O' -> '0'
                    else -> ch
                }
            }.joinToString("")
    }

    // 다양한 raw 날짜를 YYYY-MM-DD로
    private fun toYmd(raw: String): String? {
        val nums = Regex("""\d+""").findAll(raw).map { it.value }.toList()

        if (nums.size >= 3 && nums[0].length == 4) {
            val y = nums[0].toIntOrNull() ?: return null
            val m = nums[1].toIntOrNull() ?: return null
            val d = nums[2].toIntOrNull() ?: return null
            return "%04d-%02d-%02d".format(y, m, d)
        }
        if (nums.size >= 3 && nums[0].length == 2) {
            val y = 2000 + (nums[0].toIntOrNull() ?: return null)
            val m = nums[1].toIntOrNull() ?: return null
            val d = nums[2].toIntOrNull() ?: return null
            return "%04d-%02d-%02d".format(y, m, d)
        }
        if (nums.size == 1) {
            val n = nums[0]
            if (n.length == 8) {
                val y = n.substring(0, 4).toIntOrNull() ?: return null
                val m = n.substring(4, 6).toIntOrNull() ?: return null
                val d = n.substring(6, 8).toIntOrNull() ?: return null
                return "%04d-%02d-%02d".format(y, m, d)
            } else if (n.length == 6) {
                val y = 2000 + (n.substring(0, 2).toIntOrNull() ?: return null)
                val m = n.substring(2, 4).toIntOrNull() ?: return null
                val d = n.substring(4, 6).toIntOrNull() ?: return null
                return "%04d-%02d-%02d".format(y, m, d)
            }
        }
        if (nums.size == 2) {
            val m = nums[0].toIntOrNull() ?: return null
            val d = nums[1].toIntOrNull() ?: return null
            val (year, month, day) = inferYear(m, d) ?: return null
            return "%04d-%02d-%02d".format(year, month, day)
        }
        return null
    }

    private fun inferYear(m: Int, d: Int): Triple<Int, Int, Int>? {
        if (m !in 1..12 || d !in 1..31) return null
        val cal = java.util.Calendar.getInstance()
        val yNow = cal.get(java.util.Calendar.YEAR)
        val mNow = cal.get(java.util.Calendar.MONTH) + 1
        val dNow = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val todayKey = yNow * 10000 + mNow * 100 + dNow
        val thisKey = yNow * 10000 + m * 100 + d
        val year = if (thisKey < todayKey) yNow + 1 else yNow
        return Triple(year, m, d)
    }

    private fun isValidYmd(ymd: String): Boolean {
        val m = Regex("""^(20\d{2})-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$""").matchEntire(ymd) ?: return false
        val year = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        val day = m.groupValues[3].toInt()
        val maxDay = when (month) {
            1,3,5,7,8,10,12 -> 31
            4,6,9,11 -> 30
            2 -> if (isLeap(year)) 29 else 28
            else -> return false
        }
        return day in 1..maxDay
    }
    private fun isLeap(y: Int) = (y % 4 == 0 && y % 100 != 0) || (y % 400 == 0)

    // ===== 사용처(브랜드) 추출 =====
    private fun extractMerchant(text: String): String {
        val brands = listOf(
            "스타벅스","이디야","투썸","할리스","폴바셋","파스쿠찌","메가커피",
            "배스킨라빈스","던킨","파리바게뜨","뚜레쥬르","버거킹","맥도날드",
            "CU","GS25","세븐일레븐","미니스톱"
        )
        val lines = text.lines()
        for (b in brands) {
            lines.firstOrNull { it.contains(b, ignoreCase = true) }?.let { return b }
        }
        return ""
    }

    // ===== 메뉴명(상품명) 추출(라벨 대응) =====
    private fun extractMenuName(text: String): String {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }

        // 라벨 키워드: 상품명/제품명/메뉴명/Item/Product/상품
        val labelRegex = Regex("""^(상품명|제품명|메뉴명|상품|Item|ITEM|Product|PRODUCT)\s*[:：\-]?\s*(.*)$""")

        // 제외 키워드/패턴
        val blacklist = listOf(
            "유효기간","까지","만료","사용처","가맹점","고객센터","쿠폰","교환","교환처",
            "코드","barcode","바코드","주문","결제","금액","포인트","잔액","환불","재발급",
            "기간","문의","번호","인증","주의","유의","안내","이용","조건","주의사항","교환방법"
        )

        fun looksBad(s: String): Boolean {
            if (s.isBlank()) return true
            if (s.length !in 2..40) return true
            if (blacklist.any { s.contains(it, ignoreCase = true) }) return true
            if (Regex("""\d{8,}""").containsMatchIn(s)) return true         // 긴 숫자
            if (Regex("""[₩\\]?\s?\d{2,3}(,\d{3})*\s*(원|KRW)?""").containsMatchIn(s)) return true // 금액
            if (Regex("""\b(옵션|사이즈|HOT|ICE|L|R|Tall|Grande|Venti)\b""", RegexOption.IGNORE_CASE).containsMatchIn(s)) return true
            return false
        }

        fun clean(s: String): String {
            var t = s
            t = t.replace(Regex("""\([^)]*\)"""), "")   // (...) 제거
            t = t.replace(Regex("""\[[^\]]*]"""), "")   // [...] 제거
            t = t.replace(Regex("""\s{2,}"""), " ")
            return t.trim().trim('-','•','·',':','：')
        }

        // 1) 라벨 줄에서 콜론 뒤 값 먼저 시도
        for ((idx, raw) in lines.withIndex()) {
            val m = labelRegex.find(raw) ?: continue
            val after = m.groupValues.getOrNull(2)?.trim().orEmpty()
            // 같은 줄에 값이 있으면 그걸 사용
            if (after.isNotBlank()) {
                val v = clean(after)
                if (!looksBad(v)) return v
            }
            // 값이 비어있으면 다음 줄 후보 사용
            if (idx + 1 < lines.size) {
                val next = clean(lines[idx + 1])
                if (!looksBad(next)) return next
            }
        }

        // 2) 브랜드 라인 아래에서 1~3줄 탐색
        val brands = listOf(
            "스타벅스","이디야","투썸","할리스","폴바셋","파스쿠찌","메가커피",
            "배스킨라빈스","던킨","파리바게뜨","뚜레쥬르","버거킹","맥도날드",
            "CU","GS25","세븐일레븐","미니스톱"
        )
        val brandIdx = lines.indexOfFirst { line -> brands.any { b -> line.contains(b, ignoreCase = true) } }
        if (brandIdx >= 0) {
            for (i in brandIdx + 1 until minOf(brandIdx + 4, lines.size)) {
                val v = clean(lines[i])
                if (!looksBad(v)) return v
            }
        }

        // 3) 전체 라인에서 첫 양호 후보
        lines.map(::clean).firstOrNull { !looksBad(it) }?.let { return it }

        return ""
    }

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()
}

// ===== RecyclerView 어댑터/뷰홀더 =====
private class GifticonAdapter(
    private val data: List<Gifticon>,
    private val onLongPressDelete: (Gifticon) -> Unit
) : RecyclerView.Adapter<GifticonViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GifticonViewHolder {
        val layout = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
        }
        val iv = ImageView(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(100, 100)
        }
        val box = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(16, 0, 0, 0)
        }
        val tvMenu = TextView(parent.context).apply { textSize = 15f }
        val tvMerchant = TextView(parent.context).apply { textSize = 14f }
        val tvExpiry = TextView(parent.context).apply { textSize = 13f; setTextColor(0xFF666666.toInt()) }

        box.addView(tvMenu)
        box.addView(tvMerchant)
        box.addView(tvExpiry)
        layout.addView(iv)
        layout.addView(box)

        return GifticonViewHolder(layout, iv, tvMenu, tvMerchant, tvExpiry, onLongPressDelete)
    }

    override fun onBindViewHolder(holder: GifticonViewHolder, position: Int) {
        holder.bind(data[position])
    }

    override fun getItemCount(): Int = data.size
}

private class GifticonViewHolder(
    private val layout: LinearLayout,
    private val iv: ImageView,
    private val tvMenu: TextView,
    private val tvMerchant: TextView,
    private val tvExpiry: TextView,
    private val onLongPressDelete: (Gifticon) -> Unit
) : RecyclerView.ViewHolder(layout) {

    private var current: Gifticon? = null

    init {
        layout.setOnLongClickListener {
            current?.let { onLongPressDelete(it) }
            true
        }
    }

    fun bind(item: Gifticon) {
        current = item
        tvMenu.text = item.menuName ?: "(메뉴 미정)"
        tvMerchant.text = item.merchant ?: "(사용처 미정)"
        tvExpiry.text = item.expiryDate ?: "(유효기간 미정)"
        try {
            if (!item.imageUri.isNullOrBlank()) iv.setImageURI(Uri.parse(item.imageUri))
            else iv.setImageDrawable(null)
        } catch (_: Exception) {
            iv.setImageDrawable(null)
        }
    }
}
