package com.idphoto.app

import android.content.ContentValues
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.graphics.ImageDecoder
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions

/** قالب الورقة: قياس الصورة بالملم، عدد الصور في الصف، العدد الكلي، ونسبة القص */
data class Tpl(val wMm: Float, val hMm: Float, val perRow: Int, val count: Int, val faceFactor: Float)

val TPL_TRANSACTION = Tpl(30f, 43f, 6, 9, 2.7f)
val TPL_PASSPORT = Tpl(35f, 45f, 5, 6, 2.55f)

class MainActivity : AppCompatActivity() {

    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private lateinit var smoothBar: SeekBar
    private lateinit var lightBar: SeekBar
    private lateinit var suitBox: CheckBox
    private lateinit var tplPassport: RadioButton

    private var lastUri: Uri? = null
    private var whiteBmp: Bitmap? = null   // الصورة كاملة بخلفية بيضاء (مع البدلة إن اختيرت)
    private var faceRect: Rect? = null
    private var current: Bitmap? = null    // الصورة النهائية المقصوصة والمحسّنة
    private var currentTpl: Tpl = TPL_TRANSACTION

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            lastUri = uri
            process(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        smoothBar = findViewById(R.id.smoothBar)
        lightBar = findViewById(R.id.lightBar)
        suitBox = findViewById(R.id.suitBox)
        tplPassport = findViewById(R.id.tplPassport)

        findViewById<Button>(R.id.pickBtn).setOnClickListener { picker.launch("image/*") }
        findViewById<Button>(R.id.saveBtn).setOnClickListener { saveSheet() }

        suitBox.setOnCheckedChangeListener { _, _ -> lastUri?.let { process(it) } }
        findViewById<RadioGroup>(R.id.tplGroup).setOnCheckedChangeListener { _, _ -> applyEnhance() }

        val l = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { applyEnhance() }
        }
        smoothBar.setOnSeekBarChangeListener(l)
        lightBar.setOnSeekBarChangeListener(l)
    }

    private fun setStatus(t: String) = runOnUiThread { status.text = t }

    // ---------- تحميل الصورة ----------
    private fun load(uri: Uri): Bitmap {
        val src = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val m = maxOf(info.size.width, info.size.height)
            if (m > 1600) {
                val s = 1600f / m
                decoder.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
            }
        }
    }

    private fun process(uri: Uri) {
        setStatus("جاري المعالجة... انتظر قليلاً")
        val wantSuit = suitBox.isChecked
        Thread {
            try {
                val src = load(uri)
                val face = detectFace(src)
                if (face == null) {
                    setStatus("لم أجد وجهاً في الصورة، جرّب صورة أوضح")
                    return@Thread
                }
                faceRect = face
                whiteBmp = removeBackground(src, face, wantSuit)
                setStatus("تم. عدّل التنعيم والإضاءة أو احفظ الورقة")
                applyEnhance()
            } catch (e: Exception) {
                setStatus("حدث خطأ: ${e.message}")
            }
        }.start()
    }

    // ---------- كشف الوجه ----------
    private fun detectFace(src: Bitmap): Rect? {
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build()
        val detector = FaceDetection.getClient(opts)
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(src, 0)))
        return faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }?.boundingBox
    }

    // ---------- إزالة الخلفية + البدلة (مجاناً على الهاتف) ----------
    private fun removeBackground(src: Bitmap, face: Rect, suit: Boolean): Bitmap {
        val seg = Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build()
        )
        val mask = Tasks.await(seg.process(InputImage.fromBitmap(src, 0)))
        val fb = mask.buffer.asFloatBuffer()
        val mw = mask.width
        val mh = mask.height
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val fh = face.height().toFloat()
        val fw = face.width().toFloat()
        val fx = face.exactCenterX()
        val neckY = face.bottom + 0.25f * fh      // أول خط يبدأ منه القميص والسترة

        for (y in 0 until h) {
            val my = y * mh / h
            for (x in 0 until w) {
                val c = fb.get(my * mw + x * mw / w)
                val t = ((c - 0.4f) / 0.4f).coerceIn(0f, 1f)
                val a = t * t * (3 - 2 * t)
                var r = Color.red(px[y * w + x]).toFloat()
                var g = Color.green(px[y * w + x]).toFloat()
                var b = Color.blue(px[y * w + x]).toFloat()
                if (suit && y > neckY) {
                    // لون السترة: داكن مع تدرج خفيف نحو الأسفل
                    val k = ((y - neckY) / (h - neckY)).coerceIn(0f, 1f)
                    r = 34f - 16f * k; g = 34f - 16f * k; b = 42f - 18f * k
                }
                px[y * w + x] = Color.rgb(
                    (r * a + 255 * (1 - a)).toInt(),
                    (g * a + 255 * (1 - a)).toInt(),
                    (b * a + 255 * (1 - a)).toInt()
                )
            }
        }
        val out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            .copy(Bitmap.Config.ARGB_8888, true)
        if (suit) drawShirtAndTie(Canvas(out), fx, fw, fh, neckY, h.toFloat())
        return out
    }

    private fun drawShirtAndTie(c: Canvas, fx: Float, fw: Float, fh: Float, neckY: Float, bottom: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.rgb(10, 10, 12); strokeWidth = fh * 0.025f
        }
        // القميص (مثلث أبيض على شكل V)
        val tipY = neckY + 1.05f * fh
        val shirt = Path().apply {
            moveTo(fx - 0.36f * fw, neckY - 0.02f * fh)
            lineTo(fx + 0.36f * fw, neckY - 0.02f * fh)
            lineTo(fx, tipY)
            close()
        }
        fill.color = Color.rgb(246, 246, 248)
        c.drawPath(shirt, fill)
        // ظل خفيف على القميص قرب الرقبة
        fill.color = Color.argb(40, 0, 0, 0)
        c.drawPath(Path().apply {
            moveTo(fx - 0.36f * fw, neckY - 0.02f * fh)
            lineTo(fx + 0.36f * fw, neckY - 0.02f * fh)
            lineTo(fx + 0.30f * fw, neckY + 0.10f * fh)
            lineTo(fx - 0.30f * fw, neckY + 0.10f * fh)
            close()
        }, fill)
        // حواف السترة (الياقات)
        c.drawLine(fx - 0.36f * fw, neckY - 0.02f * fh, fx, tipY, line)
        c.drawLine(fx + 0.36f * fw, neckY - 0.02f * fh, fx, tipY, line)
        c.drawLine(fx - 0.36f * fw, neckY - 0.02f * fh, fx - 0.62f * fw, neckY + 0.55f * fh, line)
        c.drawLine(fx + 0.36f * fw, neckY - 0.02f * fh, fx + 0.62f * fw, neckY + 0.55f * fh, line)
        // الكرافتة
        val tieColor = Color.rgb(12, 12, 16)
        fill.color = tieColor
        val knotTop = neckY + 0.08f * fh
        val knotBottom = neckY + 0.30f * fh
        c.drawPath(Path().apply {
            moveTo(fx - 0.09f * fw, knotTop)
            lineTo(fx + 0.09f * fw, knotTop)
            lineTo(fx + 0.12f * fw, knotBottom)
            lineTo(fx - 0.12f * fw, knotBottom)
            close()
        }, fill)
        c.drawPath(Path().apply {
            moveTo(fx - 0.07f * fw, knotBottom)
            lineTo(fx + 0.07f * fw, knotBottom)
            lineTo(fx + 0.14f * fw, bottom + fh)
            lineTo(fx - 0.14f * fw, bottom + fh)
            close()
        }, fill)
    }

    // ---------- القص: وجه + أكتاف في المنتصف حسب القالب ----------
    private fun cropPortrait(white: Bitmap, face: Rect, tpl: Tpl): Bitmap {
        val outW = (tpl.wMm * 20).toInt()
        val outH = (tpl.hMm * 20).toInt()
        val cropH = face.height() * tpl.faceFactor
        val cropW = cropH * outW / outH
        val left = face.exactCenterX() - cropW / 2f
        val top = face.exactCenterY() - cropH * 0.42f
        val s = outW / cropW
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val m = Matrix()
        m.postScale(s, s)
        m.postTranslate(-left * s, -top * s)
        c.drawBitmap(white, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    // ---------- تنعيم البشرة + الإضاءة والألوان ----------
    private fun applyEnhance() {
        val w = whiteBmp ?: return
        val f = faceRect ?: return
        val tpl = if (tplPassport.isChecked) TPL_PASSPORT else TPL_TRANSACTION
        val smooth = smoothBar.progress / 100f
        val light = lightBar.progress
        Thread {
            val r = enhance(cropPortrait(w, f, tpl), smooth, light)
            current = r
            currentTpl = tpl
            runOnUiThread { preview.setImageBitmap(r) }
        }.start()
    }

    private fun enhance(b: Bitmap, smooth: Float, light: Int): Bitmap {
        val w = b.width
        val h = b.height
        val blur = Bitmap.createScaledBitmap(
            Bitmap.createScaledBitmap(b, w / 5, h / 5, true), w, h, true
        )
        val p = IntArray(w * h)
        val q = IntArray(w * h)
        b.getPixels(p, 0, w, 0, 0, w, h)
        blur.getPixels(q, 0, w, 0, 0, w, h)
        val k = smooth * 0.85f
        for (i in p.indices) {
            val c = p[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val bl = Color.blue(c)
            val cb = 128 - 0.168736f * r - 0.331264f * g + 0.5f * bl
            val cr = 128 + 0.5f * r - 0.418688f * g - 0.081312f * bl
            if (cb in 77f..127f && cr in 133f..173f) {
                val d = q[i]
                p[i] = Color.rgb(
                    (r + (Color.red(d) - r) * k).toInt(),
                    (g + (Color.green(d) - g) * k).toInt(),
                    (bl + (Color.blue(d) - bl) * k).toInt()
                )
            }
        }
        val smoothed = Bitmap.createBitmap(p, w, h, Bitmap.Config.ARGB_8888)

        val contrast = 1f + (light - 50) / 250f + 0.04f
        val offset = (light - 50) * 0.8f + 128f * (1 - contrast)
        val cm = ColorMatrix().apply { setSaturation(1.06f) }
        cm.postConcat(ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, offset,
            0f, contrast, 0f, 0f, offset,
            0f, 0f, contrast, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        )))
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) }
        Canvas(out).drawBitmap(smoothed, 0f, 0f, paint)
        return out
    }

    // ---------- ورقة A4 بدقة 300 DPI ----------
    private fun saveSheet() {
        val photo = current
        if (photo == null) {
            Toast.makeText(this, "جهّز صورة أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        val tpl = currentTpl
        Thread {
            val mm = 300f / 25.4f
            val sheet = Bitmap.createBitmap(2480, 3508, Bitmap.Config.ARGB_8888)
            val c = Canvas(sheet)
            c.drawColor(Color.WHITE)
            val pw = (tpl.wMm * mm).toInt()
            val ph = (tpl.hMm * mm).toInt()
            val small = Bitmap.createScaledBitmap(photo, pw, ph, true)
            for (i in 0 until tpl.count) {
                val row = i / tpl.perRow
                val col = i % tpl.perRow
                val x = 5 * mm + col * (pw + 2 * mm)
                val y = 5 * mm + row * (ph + 3 * mm)
                c.drawBitmap(small, x, y, null)
            }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "A4_photos_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/IDPhoto")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use {
                    sheet.compress(Bitmap.CompressFormat.JPEG, 98, it)
                }
                setStatus("تم الحفظ في المعرض: Pictures/IDPhoto")
            } else setStatus("تعذر الحفظ")
        }.start()
    }
}
