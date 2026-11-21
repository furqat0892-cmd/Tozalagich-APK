package uz.tozalagich

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.text.TextUtils
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File

private class Done(val msg: String, val ui: () -> Unit)

private class Holder(
    val cb: CheckBox,
    val title: TextView,
    val note: TextView,
    val path: TextView,
    val size: TextView
)

class MainActivity : Activity() {

    private val primary = Color.parseColor("#00796B")
    private val bgColor = Color.parseColor("#F4F6F8")
    private val textColor = Color.parseColor("#1C2B33")
    private val muted = Color.parseColor("#6B7B85")

    private var apks: MutableList<Item> = ArrayList()
    private var dups: MutableList<Item> = ArrayList()
    private var caches: MutableList<Item> = ArrayList()
    private var trash: MutableList<Item> = ArrayList()
    private var tab = 0
    private var busy = false
    private var scanned = false

    @Volatile private var scanning = false
    @Volatile private var cancelled = false

    private val tabNames = arrayOf("APK", "Dublikat", "Kesh", "Savat")

    private lateinit var storageText: TextView
    private lateinit var permBox: LinearLayout
    private lateinit var scanBtn: Button
    private lateinit var statusText: TextView
    private lateinit var tabViews: List<TextView>
    private lateinit var listView: ListView
    private lateinit var emptyText: TextView
    private lateinit var summaryText: TextView
    private lateinit var allBtn: Button
    private lateinit var act1: Button
    private lateinit var act2: Button
    private val adapter = RowAdapter()

    // ------------------------------------------------------------ UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = primary

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bgColor)

        // Sarlavha
        val header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL
        header.setBackgroundColor(primary)
        header.setPadding(dp(16), dp(14), dp(16), dp(14))
        val title = tv(22f, Color.WHITE, true)
        title.text = "Tozalagich"
        storageText = tv(13f, Color.parseColor("#CCE9E4"), false)
        header.addView(title)
        header.addView(storageText)
        root.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // Ruxsat bloki
        permBox = LinearLayout(this)
        permBox.orientation = LinearLayout.VERTICAL
        permBox.background = rounded(Color.parseColor("#FFF3E0"), 10)
        permBox.setPadding(dp(12), dp(10), dp(12), dp(10))
        val permText = tv(13f, textColor, false)
        permText.text = "Fayllarni skanerlash va tozalash uchun \"Barcha fayllarga ruxsat\" kerak."
        val permBtn = flatButton("Ruxsat berish", Color.parseColor("#E65100"))
        permBtn.setOnClickListener { requestAccess() }
        permBox.addView(permText)
        permBox.addView(permBtn)
        root.addView(permBox, margin(12, 10, 12, 0))

        // Skanerlash tugmasi va holat
        scanBtn = flatButton("Skanerlash", primary)
        scanBtn.setOnClickListener { startScan() }
        root.addView(scanBtn, margin(12, 10, 12, 0))
        statusText = tv(12.5f, muted, false)
        statusText.setPadding(dp(14), dp(6), dp(14), 0)
        root.addView(statusText, margin(0, 0, 0, 0))

        // Yorliqlar
        val tabRow = LinearLayout(this)
        tabRow.orientation = LinearLayout.HORIZONTAL
        val tvs = ArrayList<TextView>()
        for (i in 0 until 4) {
            val t = tv(13f, textColor, true)
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(10), 0, dp(10))
            t.setOnClickListener { selectTab(i) }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(2), 0, dp(2), 0)
            tabRow.addView(t, lp)
            tvs.add(t)
        }
        tabViews = tvs
        root.addView(tabRow, margin(10, 8, 10, 0))

        // Ro'yxat
        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.WHITE)
        listView = ListView(this)
        listView.divider = ColorDrawable(Color.parseColor("#E3E8EB"))
        listView.dividerHeight = 1
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ ->
            val list = shown()
            if (pos in list.indices) {
                list[pos].checked = !list[pos].checked
                refresh()
            }
        }
        emptyText = tv(14f, muted, false)
        emptyText.gravity = Gravity.CENTER
        emptyText.setPadding(dp(24), dp(24), dp(24), dp(24))
        frame.addView(
            listView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        frame.addView(
            emptyText,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        val frameLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        frameLp.setMargins(dp(12), dp(8), dp(12), 0)
        root.addView(frame, frameLp)

        // Pastki panel
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.setBackgroundColor(Color.WHITE)
        bottom.setPadding(dp(12), dp(8), dp(12), dp(10))
        summaryText = tv(13f, textColor, true)
        bottom.addView(summaryText)
        val btnRow = LinearLayout(this)
        btnRow.orientation = LinearLayout.HORIZONTAL
        allBtn = flatButton("Hammasini belgilash", Color.parseColor("#78909C"))
        act1 = flatButton("", primary)
        act2 = flatButton("Butunlay o'chirish", Color.parseColor("#C62828"))
        for (b in listOf(allBtn, act1, act2)) {
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(2), dp(4), dp(2), 0)
            btnRow.addView(b, lp)
        }
        allBtn.setOnClickListener { toggleAll() }
        act1.setOnClickListener { onAct1() }
        act2.setOnClickListener { onAct2() }
        bottom.addView(btnRow)
        root.addView(
            bottom,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        setContentView(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        updatePermUi()
        updateStorage()
        reloadTrash()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermUi()
        reloadTrash()
    }

    // ------------------------------------------------------------ yordamchilar

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun fmt(b: Long): String = Formatter.formatFileSize(applicationContext, b)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun tv(sp: Float, color: Int, bold: Boolean): TextView {
        val t = TextView(this)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    private fun rounded(color: Int, r: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        return g
    }

    private fun flatButton(text: String, color: Int): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        b.background = rounded(color, 10)
        b.stateListAnimator = null
        return b
    }

    private fun margin(l: Int, t: Int, r: Int, b: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        p.setMargins(dp(l), dp(t), dp(r), dp(b))
        return p
    }

    private fun shown(): MutableList<Item> = when (tab) {
        0 -> apks
        1 -> dups
        2 -> caches
        else -> trash
    }

    private fun confirm(title: String, msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("Ha") { _, _ -> ok() }
            .setNegativeButton("Bekor", null)
            .show()
    }

    // ------------------------------------------------------------ ruxsat va xotira

    private fun hasAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager()
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                1
            )
        }
    }

    private fun updatePermUi() {
        permBox.visibility = if (hasAccess()) View.GONE else View.VISIBLE
    }

    private fun updateStorage() {
        try {
            val st = StatFs(Environment.getExternalStorageDirectory().path)
            storageText.text = "Bo'sh joy: ${fmt(st.availableBytes)} / ${fmt(st.totalBytes)}"
        } catch (e: Exception) {
            storageText.text = ""
        }
    }

    // ------------------------------------------------------------ Savat

    private fun loadTrash(): List<Item> {
        val now = System.currentTimeMillis()
        return Trash.list().sortedByDescending { it.time }.map { e ->
            val days = ((now - e.time) / 86400000L).toInt()
            val left = maxOf(Trash.DAYS - days, 0)
            val when_ = if (days == 0) "Bugun tushgan" else "$days kun oldin tushgan"
            Item(
                File(Trash.dir(), e.id),
                e.size,
                File(e.original).name,
                "$when_ · yana $left kun saqlanadi",
                e.original,
                false,
                0,
                false,
                e
            )
        }
    }

    private fun reloadTrash() {
        if (!hasAccess()) return
        Thread {
            try {
                Trash.purgeOld()
            } catch (e: Throwable) {
                // ahamiyatsiz
            }
            val t = loadTrash()
            runOnUiThread {
                trash = t.toMutableList()
                refresh()
            }
        }.start()
    }

    // ------------------------------------------------------------ skanerlash

    private fun startScan() {
        if (scanning) {
            cancelled = true
            statusText.text = "To'xtatilmoqda…"
            return
        }
        if (busy) return
        if (!hasAccess()) {
            toast("Avval ruxsat bering")
            return
        }
        scanning = true
        cancelled = false
        scanBtn.text = "To'xtatish"
        statusText.text = "Boshlandi…"
        val ctx = applicationContext
        Thread {
            var res: ScanResult? = null
            var err: String? = null
            try {
                res = Scanner.scan(ctx, { m -> runOnUiThread { statusText.text = m } }, { cancelled })
            } catch (e: Throwable) {
                err = e.message ?: "noma'lum xatolik"
            }
            val r = res
            val e2 = err
            val wasCancelled = cancelled
            runOnUiThread {
                scanning = false
                scanBtn.text = "Skanerlash"
                if (r != null) {
                    apks = r.apks.toMutableList()
                    dups = r.dups.toMutableList()
                    caches = r.caches.toMutableList()
                    scanned = true
                    val all = apks + dups + caches
                    val total = all.filter { it.checked }.sumOf { it.size }
                    val head = if (wasCancelled) "To'xtatildi (qisman natija). " else ""
                    statusText.text = head + "Topildi: ${fmt(total)} bo'shatish mumkin " +
                        "(APK ${apks.size}, dublikat ${dups.size}, kesh ${caches.size})"
                } else {
                    statusText.text = "Xatolik: " + (e2 ?: "")
                }
                updateStorage()
                refresh()
            }
        }.start()
    }

    // ------------------------------------------------------------ amallar

    private fun selectTab(i: Int) {
        tab = i
        refresh()
        listView.setSelection(0)
    }

    private fun toggleAll() {
        val list = shown()
        val target = !(list.isNotEmpty() && list.all { it.checked })
        for (x in list) x.checked = target
        refresh()
    }

    private fun runBusy(msg: String, work: () -> Done) {
        if (busy) return
        busy = true
        statusText.text = msg
        Thread {
            val result: Done = try {
                work()
            } catch (e: Throwable) {
                Done("Xatolik: " + (e.message ?: "")) { }
            }
            runOnUiThread {
                busy = false
                result.ui()
                statusText.text = result.msg
                updateStorage()
                refresh()
            }
        }.start()
    }

    private fun pruneDups() {
        val counts = dups.groupingBy { it.group }.eachCount()
        dups.removeAll { (counts[it.group] ?: 0) < 2 }
    }

    private fun onAct1() {
        if (busy || scanning) return
        val sel = shown().filter { it.checked }
        if (sel.isEmpty()) {
            toast("Hech narsa belgilanmagan")
            return
        }
        val bytes = sel.sumOf { it.size }
        when (tab) {
            0, 1 -> confirm(
                "Savatga ko'chirish",
                "${sel.size} ta fayl (${fmt(bytes)}) Savatga ko'chiriladi. ${Trash.DAYS} kundan keyin " +
                    "butunlay o'chadi, shu vaqtgacha \"Savat\" bo'limidan qaytarib olish mumkin."
            ) { trashSelected(sel) }
            2 -> confirm(
                "Keshni o'chirish",
                "${sel.size} ta fayl (${fmt(bytes)}) butunlay o'chiriladi. Bular kesh va vaqtinchalik " +
                    "fayllar, qaytarib bo'lmaydi."
            ) { deleteCache(sel) }
            else -> confirm(
                "Qaytarish",
                "${sel.size} ta fayl asl joyiga qaytariladi."
            ) { restoreSelected(sel) }
        }
    }

    private fun onAct2() {
        if (busy || scanning || tab != 3) return
        val sel = trash.filter { it.checked }
        if (sel.isEmpty()) {
            toast("Hech narsa belgilanmagan")
            return
        }
        val bytes = sel.sumOf { it.size }
        confirm(
            "Butunlay o'chirish",
            "${sel.size} ta fayl (${fmt(bytes)}) qaytarib bo'lmaydigan qilib o'chiriladi."
        ) { purgeSelected(sel) }
    }

    private fun trashSelected(sel: List<Item>) {
        var list = sel
        var protectedCount = 0
        if (tab == 1) {
            val protect = HashSet<Item>()
            for (g in dups.groupBy { it.group }.values) {
                if (g.all { it.checked }) {
                    val k = g.firstOrNull { it.keep }
                    if (k != null) protect.add(k)
                }
            }
            protectedCount = protect.size
            list = sel.filter { it !in protect }
        }
        if (list.isEmpty()) {
            toast("Asl nusxa himoya qilindi: kamida bitta nusxa qoladi")
            return
        }
        val files = list.map { it.file }
        val extra = if (protectedCount > 0) " ($protectedCount ta asl nusxa himoyalandi)" else ""
        runBusy("Savatga ko'chirilmoqda…") {
            val moved = Trash.move(applicationContext, files)
            val paths = moved.map { it.absolutePath }.toHashSet()
            val bytes = list.filter { it.file.absolutePath in paths }.sumOf { it.size }
            val newTrash = loadTrash()
            val msg = "${moved.size} ta fayl Savatga ko'chirildi (${fmt(bytes)})$extra. " +
                "Joy Savat bo'shatilganda yoki ${Trash.DAYS} kundan keyin bo'shaydi."
            Done(msg) {
                apks.removeAll { it.file.absolutePath in paths }
                dups.removeAll { it.file.absolutePath in paths }
                pruneDups()
                trash = newTrash.toMutableList()
            }
        }
    }

    private fun deleteCache(sel: List<Item>) {
        runBusy("O'chirilmoqda…") {
            var n = 0
            var bytes = 0L
            val gone = HashSet<String>()
            for (item in sel) {
                if (item.file.delete()) {
                    n++
                    bytes += item.size
                    gone.add(item.file.absolutePath)
                }
            }
            Done("$n ta fayl o'chirildi, ${fmt(bytes)} bo'shadi.") {
                caches.removeAll { it.file.absolutePath in gone }
            }
        }
    }

    private fun restoreSelected(sel: List<Item>) {
        val entries = sel.mapNotNull { it.entry }
        runBusy("Qaytarilmoqda…") {
            val n = Trash.restore(applicationContext, entries)
            val newTrash = loadTrash()
            Done("$n ta fayl asl joyiga qaytarildi.") {
                trash = newTrash.toMutableList()
            }
        }
    }

    private fun purgeSelected(sel: List<Item>) {
        val entries = sel.mapNotNull { it.entry }
        val bytes = sel.sumOf { it.size }
        runBusy("O'chirilmoqda…") {
            val n = Trash.deleteForever(entries)
            val newTrash = loadTrash()
            Done("$n ta fayl butunlay o'chirildi, ${fmt(bytes)} bo'shadi.") {
                trash = newTrash.toMutableList()
            }
        }
    }

    // ------------------------------------------------------------ ko'rinishni yangilash

    private fun refresh() {
        val lists = listOf(apks, dups, caches, trash)
        for (i in 0 until 4) {
            val t = tabViews[i]
            t.text = tabNames[i] + " (" + lists[i].size + ")"
            if (i == tab) {
                t.setTextColor(Color.WHITE)
                t.background = rounded(primary, 8)
            } else {
                t.setTextColor(textColor)
                t.background = rounded(Color.parseColor("#E3E8EB"), 8)
            }
        }
        val list = shown()
        val sel = list.filter { it.checked }
        summaryText.text = "Tanlangan: ${sel.size} ta · ${fmt(sel.sumOf { it.size })}"
        allBtn.text = if (list.isNotEmpty() && list.all { it.checked }) "Belgini olish" else "Hammasini belgilash"
        when (tab) {
            0, 1 -> {
                act1.text = "Savatga ko'chirish"
                act2.visibility = View.GONE
            }
            2 -> {
                act1.text = "Keshni o'chirish"
                act2.visibility = View.GONE
            }
            else -> {
                act1.text = "Qaytarish"
                act2.visibility = View.VISIBLE
            }
        }
        emptyText.text = when {
            tab == 3 -> "Savat bo'sh"
            !scanned -> "\"Skanerlash\" tugmasini bosing"
            else -> "Hech narsa topilmadi"
        }
        emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    // ------------------------------------------------------------ adapter

    inner class RowAdapter : BaseAdapter() {
        override fun getCount(): Int = shown().size
        override fun getItem(position: Int): Any = shown()[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val h: Holder
            val v: View
            if (convertView == null) {
                val row = LinearLayout(this@MainActivity)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(dp(10), dp(10), dp(12), dp(10))
                val cb = CheckBox(this@MainActivity)
                cb.isFocusable = false
                cb.isClickable = false
                val col = LinearLayout(this@MainActivity)
                col.orientation = LinearLayout.VERTICAL
                val t = tv(15f, textColor, true)
                t.maxLines = 1
                t.ellipsize = TextUtils.TruncateAt.END
                val n = tv(12.5f, primary, false)
                val pa = tv(11f, muted, false)
                pa.maxLines = 1
                pa.ellipsize = TextUtils.TruncateAt.MIDDLE
                col.addView(t)
                col.addView(n)
                col.addView(pa)
                val sz = tv(13f, textColor, true)
                sz.setPadding(dp(8), 0, 0, 0)
                row.addView(cb)
                row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(sz)
                h = Holder(cb, t, n, pa, sz)
                row.tag = h
                v = row
            } else {
                v = convertView
                h = convertView.tag as Holder
            }
            val item = shown()[position]
            h.cb.isChecked = item.checked
            h.title.text = item.title
            h.note.text = item.note
            h.note.setTextColor(if (item.keep) Color.parseColor("#2E7D32") else primary)
            h.path.text = item.path
            h.size.text = fmt(item.size)
            return v
        }
    }
}
