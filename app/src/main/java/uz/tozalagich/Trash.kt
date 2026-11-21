package uz.tozalagich

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File

/** Savat: fayllar avval shu yerga ko'chadi va 7 kundan keyin butunlay o'chadi. */
object Trash {
    const val DAYS = 7

    class Entry(val id: String, val time: Long, val size: Long, val original: String)

    fun dir(): File = File(Environment.getExternalStorageDirectory(), ".TozalagichSavat")

    private fun index(): File = File(dir(), "index.tsv")

    @Synchronized
    fun list(): List<Entry> {
        val idx = index()
        if (!idx.exists()) return emptyList()
        val out = ArrayList<Entry>()
        try {
            idx.forEachLine { line ->
                val p = line.split('\t', limit = 4)
                if (p.size == 4) {
                    val t = p[1].toLongOrNull()
                    val s = p[2].toLongOrNull()
                    if (t != null && s != null && File(dir(), p[0]).exists()) {
                        out.add(Entry(p[0], t, s, p[3]))
                    }
                }
            }
        } catch (e: Exception) {
            // o'qib bo'lmasa, bo'sh ro'yxat
        }
        return out
    }

    private fun write(entries: List<Entry>) {
        dir().mkdirs()
        val sb = StringBuilder()
        for (e in entries) {
            sb.append(e.id).append('\t').append(e.time).append('\t').append(e.size).append('\t')
                .append(e.original).append('\n')
        }
        index().writeText(sb.toString())
    }

    private fun rescan(ctx: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        try {
            MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null)
        } catch (e: Exception) {
            // galereya yangilanmasa ham zarari yo'q
        }
    }

    /** Fayllarni Savatga ko'chiradi. Muvaffaqiyatli ko'chganlarini qaytaradi. */
    @Synchronized
    fun move(ctx: Context, files: List<File>): List<File> {
        val d = dir()
        d.mkdirs()
        try {
            File(d, ".nomedia").createNewFile()
        } catch (e: Exception) {
            // ahamiyatsiz
        }
        val entries = ArrayList(list())
        val moved = ArrayList<File>()
        val now = System.currentTimeMillis()
        var n = 0
        for (f in files) {
            val id = "${now}_${n++}"
            val target = File(d, id)
            val size = f.length()
            var ok = f.renameTo(target)
            if (!ok) {
                try {
                    f.copyTo(target, overwrite = true)
                    ok = f.delete()
                    if (!ok) target.delete()
                } catch (e: Exception) {
                    target.delete()
                }
            }
            if (ok) {
                entries.add(Entry(id, now, size, f.absolutePath.replace('\t', ' ')))
                moved.add(f)
            }
        }
        write(entries)
        rescan(ctx, moved.map { it.absolutePath })
        return moved
    }

    /** Savatdan asl joyiga qaytaradi. Joy band bo'lsa, nomiga "(qaytarilgan)" qo'shadi. */
    @Synchronized
    fun restore(ctx: Context, sel: List<Entry>): Int {
        var count = 0
        val restored = ArrayList<String>()
        for (e in sel) {
            val src = File(dir(), e.id)
            if (!src.exists()) continue
            var dest = File(e.original)
            dest.parentFile?.mkdirs()
            if (dest.exists()) {
                val base = dest.nameWithoutExtension
                val ext = dest.extension
                val parent = dest.parentFile
                var i = 1
                do {
                    val name = base + " (qaytarilgan" + i + ")" + (if (ext.isEmpty()) "" else ".$ext")
                    dest = File(parent, name)
                    i++
                } while (dest.exists())
            }
            var ok = src.renameTo(dest)
            if (!ok) {
                try {
                    src.copyTo(dest, overwrite = true)
                    ok = src.delete()
                } catch (e2: Exception) {
                    ok = false
                }
            }
            if (ok) {
                count++
                restored.add(dest.absolutePath)
            }
        }
        write(list().filter { File(dir(), it.id).exists() })
        rescan(ctx, restored)
        return count
    }

    /** Savatdan butunlay o'chiradi. */
    @Synchronized
    fun deleteForever(sel: List<Entry>): Int {
        var count = 0
        for (e in sel) {
            if (File(dir(), e.id).delete()) count++
        }
        write(list().filter { File(dir(), it.id).exists() })
        return count
    }

    /** 7 kundan oshgan fayllarni o'chiradi. */
    @Synchronized
    fun purgeOld(): Int {
        val limit = System.currentTimeMillis() - DAYS * 24L * 60 * 60 * 1000
        val old = list().filter { it.time < limit }
        if (old.isEmpty()) return 0
        return deleteForever(old)
    }
}
