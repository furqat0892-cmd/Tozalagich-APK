package uz.tozalagich

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class Item(
    val file: File,
    val size: Long,
    val title: String,
    val note: String,
    val path: String,
    var checked: Boolean,
    val group: Int = 0,
    val keep: Boolean = false,
    val entry: Trash.Entry? = null
)

class ScanResult(val apks: List<Item>, val dups: List<Item>, val caches: List<Item>)

private class ApkInfo(val f: File, val pkg: String?, val code: Long, val ver: String, val label: String)

object Scanner {
    private val skipTop = setOf("Android", ".TozalagichSavat")
    private val cacheDirs = setOf(
        ".thumbnails", "thumbnails", "cache", ".cache", "temp", "tmp", ".tmp", ".temp", "lost.dir"
    )
    private val cacheExt = setOf("tmp", "temp", "log", "crdownload", "part", "partial", "cache")
    private const val MIN_DUP = 50L * 1024
    private const val HEAD = 64 * 1024
    private val copyRegex = Regex("""(\(\d+\)|\bcopy\b|\bnusxa\b)""", RegexOption.IGNORE_CASE)

    private fun walk(
        dir: File,
        inCache: Boolean,
        isTop: Boolean,
        cancel: () -> Boolean,
        onFile: (File, Boolean) -> Unit
    ) {
        if (cancel()) return
        val children = dir.listFiles() ?: return
        for (c in children) {
            if (cancel()) return
            if (c.isDirectory) {
                if (isTop && c.name in skipTop) continue
                val nowCache = inCache || c.name.lowercase() in cacheDirs
                walk(c, nowCache, false, cancel, onFile)
            } else {
                onFile(c, inCache)
            }
        }
    }

    private fun hash(f: File, headOnly: Boolean, cancel: () -> Boolean): String? {
        try {
            val md = MessageDigest.getInstance("MD5")
            FileInputStream(f).use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                    if (headOnly) break
                    if (cancel()) return null
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            return null
        }
    }

    @Suppress("DEPRECATION")
    fun scan(ctx: Context, progress: (String) -> Unit, cancel: () -> Boolean): ScanResult {
        val root = Environment.getExternalStorageDirectory()
        val apkFiles = ArrayList<File>()
        val cacheFiles = ArrayList<File>()
        val bySize = HashMap<Long, MutableList<File>>()
        var seen = 0

        walk(root, false, true, cancel) { f, inCache ->
            seen++
            if (seen % 300 == 0) progress("Fayllar ko'rilmoqda: $seen")
            val name = f.name
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext == "apk") {
                apkFiles.add(f)
            } else if (inCache || ext in cacheExt || name.startsWith(".thumbdata")) {
                cacheFiles.add(f)
            } else {
                val len = f.length()
                if (len >= MIN_DUP) bySize.getOrPut(len) { ArrayList() }.add(f)
            }
        }

        // Ilovaning o'z keshi
        val own = listOfNotNull(ctx.cacheDir, ctx.externalCacheDir)
        for (d in own) {
            walk(d, true, false, cancel) { f, _ -> cacheFiles.add(f) }
        }

        // ---------- APK ----------
        progress("APK fayllar tahlil qilinmoqda…")
        val pm = ctx.packageManager
        val infos = ArrayList<ApkInfo>()
        for (f in apkFiles) {
            if (cancel()) break
            try {
                val pi = pm.getPackageArchiveInfo(f.absolutePath, 0)
                if (pi == null) {
                    infos.add(ApkInfo(f, null, 0L, "", ""))
                } else {
                    val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
                    var label = ""
                    try {
                        val ai = pi.applicationInfo
                        if (ai != null) {
                            ai.sourceDir = f.absolutePath
                            ai.publicSourceDir = f.absolutePath
                            label = ai.loadLabel(pm).toString()
                        }
                    } catch (e: Throwable) {
                        // nom topilmasa fayl nomi ko'rsatiladi
                    }
                    infos.add(ApkInfo(f, pi.packageName, code, pi.versionName ?: "", label))
                }
            } catch (e: Throwable) {
                infos.add(ApkInfo(f, null, 0L, "", ""))
            }
        }

        fun installedCode(pkg: String): Long? {
            return try {
                val p = pm.getPackageInfo(pkg, 0)
                if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
            } catch (e: Exception) {
                null
            }
        }

        val apkItems = ArrayList<Item>()
        for (b in infos.filter { it.pkg == null }) {
            apkItems.add(
                Item(b.f, b.f.length(), b.f.name, "Buzilgan yoki yarim yuklangan APK", b.f.absolutePath, true)
            )
        }
        val valid = infos.filter { it.pkg != null }
        for ((pkg, list) in valid.groupBy { it.pkg!! }) {
            val sorted = list.sortedWith(
                compareByDescending<ApkInfo> { it.code }.thenByDescending { it.f.lastModified() }
            )
            val inst = installedCode(pkg)
            sorted.forEachIndexed { i, a ->
                val name = if (a.label.isNotEmpty()) a.label else a.f.name
                val ver = if (a.ver.isNotEmpty()) " v" + a.ver else ""
                val note: String
                val chk: Boolean
                if (inst != null && inst >= a.code) {
                    note = "O'rnatilgan, fayl kerak emas"
                    chk = true
                } else if (i > 0) {
                    note = "Eskiroq versiya yoki nusxa"
                    chk = true
                } else {
                    note = "Yangi, hali o'rnatilmagan"
                    chk = false
                }
                apkItems.add(Item(a.f, a.f.length(), name + ver, note, a.f.absolutePath, chk))
            }
        }
        apkItems.sortByDescending { it.size }

        // ---------- Dublikatlar ----------
        val dupItems = ArrayList<Item>()
        val waste = HashMap<Int, Long>()
        val candidates = bySize.values.filter { it.size > 1 }
        var gid = 0
        var done = 0
        for (sameSize in candidates) {
            if (cancel()) break
            done++
            progress("Dublikatlar tekshirilmoqda: $done/${candidates.size}")
            val byHead = HashMap<String, MutableList<File>>()
            for (f in sameSize) {
                val h = hash(f, true, cancel) ?: continue
                byHead.getOrPut(h) { ArrayList() }.add(f)
            }
            for (headGroup in byHead.values) {
                if (headGroup.size < 2) continue
                val byFull = HashMap<String, MutableList<File>>()
                for (f in headGroup) {
                    val full = if (f.length() <= HEAD) hash(f, true, cancel) else hash(f, false, cancel)
                    if (full == null) continue
                    byFull.getOrPut(full) { ArrayList() }.add(f)
                }
                for (g in byFull.values) {
                    if (g.size < 2) continue
                    gid++
                    val sorted = g.sortedWith(
                        compareBy<File>(
                            { if (copyRegex.containsMatchIn(it.name)) 1 else 0 },
                            { it.lastModified() },
                            { it.absolutePath.length }
                        )
                    )
                    val keeper = sorted[0]
                    val size = keeper.length()
                    waste[gid] = size * (sorted.size - 1)
                    dupItems.add(
                        Item(keeper, size, keeper.name, "Asl nusxa, saqlanadi", keeper.absolutePath, false, gid, true)
                    )
                    val keepFolder = keeper.parentFile?.name ?: ""
                    for (i in 1 until sorted.size) {
                        val c = sorted[i]
                        dupItems.add(
                            Item(c, size, c.name, "Nusxa (asl nusxa: $keepFolder)", c.absolutePath, true, gid)
                        )
                    }
                }
            }
        }
        val sortedDups = dupItems.sortedWith(
            compareByDescending<Item> { waste[it.group] ?: 0L }
                .thenBy { it.group }
                .thenByDescending { it.keep }
        )

        // ---------- Kesh ----------
        val cacheItems = ArrayList<Item>()
        for (f in cacheFiles) {
            cacheItems.add(Item(f, f.length(), f.name, "Kesh yoki vaqtinchalik fayl", f.absolutePath, true))
        }
        cacheItems.sortByDescending { it.size }

        return ScanResult(apkItems, sortedDups, cacheItems)
    }
}
