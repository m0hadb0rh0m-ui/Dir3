package app.dir3

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.view.accessibility.AccessibilityManager
import java.io.File
import java.net.URI

// ───────────── المراقبة ─────────────

data class Snapshot(
    val ramTotal: Long,
    val ramAvail: Long,
    val storTotal: Long,
    val storAvail: Long,
    val battPct: Int,
    val battTempC: Float,
    val charging: Boolean,
) {
    val ramUsedFrac: Float
        get() = if (ramTotal <= 0) 0f else ((ramTotal - ramAvail).toFloat() / ramTotal).coerceIn(0f, 1f)
    val storUsedFrac: Float
        get() = if (storTotal <= 0) 0f else ((storTotal - storAvail).toFloat() / storTotal).coerceIn(0f, 1f)
}

object Monitor {
    fun read(ctx: Context): Snapshot {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)

        val st = StatFs(Environment.getDataDirectory().path)
        val storTotal = st.blockCountLong * st.blockSizeLong
        val storAvail = st.availableBlocksLong * st.blockSizeLong

        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
        val temp = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        return Snapshot(mi.totalMem, mi.availMem, storTotal, storAvail, pct, temp, charging)
    }

    fun tips(s: Snapshot): List<String> {
        val out = mutableListOf<String>()
        if (s.ramTotal > 0 && s.ramAvail < s.ramTotal * 0.15) {
            out += "الذاكرة شبه ممتلئة. أغلق التطبيقات الثقيلة من قائمة التطبيقات الأخيرة، ولا تعتمد على «منظّفات الرام» لأن أندرويد يعيد تشغيل ما تقفله."
        }
        if (s.storTotal > 0 && s.storAvail < s.storTotal * 0.10) {
            out += "مساحة التخزين أقل من 10%. افتح تبويب «تنظيف» لمعرفة الملفات الكبيرة."
        }
        if (s.battTempC >= 40f) {
            out += "حرارة البطارية مرتفعة. أوقف الشحن أو الألعاب وأخرج الجهاز من الغلاف."
        }
        if (s.battPct in 0..14 && !s.charging) {
            out += "البطارية منخفضة. فعّل وضع توفير الطاقة."
        }
        if (out.isEmpty()) out += "كل شيء ضمن الحدود الطبيعية."
        return out
    }
}

// ───────────── فحص التطبيقات ─────────────

data class AppRisk(
    val label: String,
    val pkg: String,
    val score: Int,
    val reasons: List<String>,
)

object Scanner {
    private val perms = mapOf(
        "android.permission.READ_SMS" to ("قراءة الرسائل" to 2),
        "android.permission.SEND_SMS" to ("إرسال رسائل SMS" to 2),
        "android.permission.RECEIVE_SMS" to ("استقبال الرسائل" to 1),
        "android.permission.READ_CALL_LOG" to ("سجل المكالمات" to 2),
        "android.permission.RECORD_AUDIO" to ("الميكروفون" to 1),
        "android.permission.CAMERA" to ("الكاميرا" to 1),
        "android.permission.READ_CONTACTS" to ("جهات الاتصال" to 1),
        "android.permission.ACCESS_FINE_LOCATION" to ("الموقع الدقيق" to 1),
        "android.permission.SYSTEM_ALERT_WINDOW" to ("الرسم فوق التطبيقات الأخرى" to 2),
        "android.permission.REQUEST_INSTALL_PACKAGES" to ("تثبيت تطبيقات أخرى" to 2),
        "android.permission.BIND_DEVICE_ADMIN" to ("مدير الجهاز" to 3),
        "android.permission.MANAGE_EXTERNAL_STORAGE" to ("الوصول لكل الملفات" to 1),
        "android.permission.QUERY_ALL_PACKAGES" to ("قراءة قائمة التطبيقات" to 1),
    )

    private val trustedStores = setOf(
        "com.android.vending",
        "com.sec.android.app.samsungapps",
        "com.huawei.appmarket",
        "com.xiaomi.mipicks",
        "com.heytap.market",
        "com.vivo.appstore",
        "com.amazon.venezia",
        "org.fdroid.fdroid",
    )

    @Suppress("DEPRECATION")
    fun scanApps(ctx: Context): List<AppRisk> {
        val pm = ctx.packageManager

        val accessibilityPkgs: Set<String> = try {
            val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .map { it.resolveInfo.serviceInfo.packageName }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }

        val result = mutableListOf<AppRisk>()
        for (pi in pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
            val ai = pi.applicationInfo ?: continue
            if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue
            if (pi.packageName == ctx.packageName) continue

            var score = 0
            val reasons = mutableListOf<String>()

            for (p in pi.requestedPermissions ?: emptyArray()) {
                val entry = perms[p] ?: continue
                score += entry.second
                reasons += "يطلب: ${entry.first}"
            }

            if (pi.packageName in accessibilityPkgs) {
                score += 3
                reasons += "خدمة إعاقة (Accessibility) مفعّلة، وهي الأكثر استغلالاً في البرمجيات الخبيثة"
            }

            val installer = try {
                if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pi.packageName).installingPackageName
                else pm.getInstallerPackageName(pi.packageName)
            } catch (e: Exception) {
                null
            }
            if (installer == null || installer !in trustedStores) {
                score += 1
                reasons += "مثبّت من خارج متجر معروف"
            }

            result += AppRisk(pm.getApplicationLabel(ai).toString(), pi.packageName, score, reasons)
        }
        return result.sortedByDescending { it.score }
    }

    fun levelOf(score: Int): Int = when {
        score >= 6 -> 2
        score >= 3 -> 1
        else -> 0
    }
}

// ───────────── التخزين ─────────────

data class FileItem(val path: String, val size: Long)
data class ScanResult(val big: List<FileItem>, val junk: List<FileItem>)

object Storage {
    private const val BIG = 50L * 1024 * 1024
    private val junkExt = setOf("tmp", "temp", "log", "crdownload", "part", "apk", "bak")

    fun hasAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    fun scan(): ScanResult {
        val root = Environment.getExternalStorageDirectory()
        val big = ArrayList<FileItem>()
        val junk = ArrayList<FileItem>()
        root.walkTopDown()
            .onEnter { !(it.parentFile == root && it.name == "Android") }
            .forEach { f ->
                if (f.isFile) {
                    val len = f.length()
                    if (len >= BIG) big += FileItem(f.path, len)
                    else if (f.extension.lowercase() in junkExt) junk += FileItem(f.path, len)
                }
            }
        return ScanResult(
            big.sortedByDescending { it.size }.take(40),
            junk.sortedByDescending { it.size }.take(100),
        )
    }
}

// ───────────── فحص الروابط ─────────────

data class LinkResult(val score: Int, val reasons: List<String>) {
    val verdict: String
        get() = when {
            score >= 5 -> "خطير محتمل"
            score >= 2 -> "مشبوه"
            else -> "لا مؤشرات واضحة"
        }
}

object LinkCheck {
    private val shorteners = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "cutt.ly", "rebrand.ly", "shorturl.at",
    )
    private val riskyTlds = setOf("tk", "ml", "ga", "cf", "gq", "top", "xyz", "click", "zip", "mov")
    private val keywords = listOf("login", "verify", "secure", "account", "update", "wallet", "bank", "paypal", "gift", "free")

    fun check(raw: String): LinkResult {
        val s = raw.trim()
        val hasScheme = "://" in s
        val uri = try {
            URI(if (hasScheme) s else "https://$s")
        } catch (e: Exception) {
            return LinkResult(5, listOf("الرابط غير صالح أو مكتوب بشكل غريب"))
        }
        val host = (uri.host ?: "").lowercase()
        if (host.isEmpty()) return LinkResult(5, listOf("لا يمكن استخراج اسم الموقع من الرابط"))

        var score = 0
        val reasons = mutableListOf<String>()

        if (hasScheme && uri.scheme.equals("http", ignoreCase = true)) {
            score += 1; reasons += "غير مشفّر (http)، لا تُدخل أي كلمة مرور فيه"
        }
        if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)) {
            score += 3; reasons += "يستخدم عنوان IP بدل اسم موقع"
        }
        if ("xn--" in host) {
            score += 3; reasons += "اسم موقع بحروف مموّهة (Punycode) قد يقلّد موقعاً معروفاً"
        }
        if (host in shorteners) {
            score += 2; reasons += "رابط مختصر يخفي الوجهة الحقيقية"
        }
        if (uri.userInfo != null) {
            score += 3; reasons += "يحتوي @ قبل اسم الموقع، وهي حيلة شائعة لإخفاء الوجهة"
        }
        if (host.count { it == '.' } >= 4) {
            score += 2; reasons += "عدد كبير من النطاقات الفرعية"
        }
        if (host.substringAfterLast('.') in riskyTlds) {
            score += 2; reasons += "امتداد نطاق يكثر استخدامه في الاحتيال"
        }
        if (keywords.any { it in host }) {
            score += 2; reasons += "اسم الموقع يحتوي كلمات يستخدمها المحتالون (login, verify, bank...)"
        }
        if (host.count { it == '-' } >= 3) {
            score += 1; reasons += "شرطات كثيرة في اسم الموقع"
        }
        if (s.length > 100) {
            score += 1; reasons += "رابط طويل جداً"
        }
        return LinkResult(score, reasons)
    }
}
