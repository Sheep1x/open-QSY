package com.openqsy.glasses.quiz

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * 题库装载器 —— 目标是把「换题库」这件事压到**零操作**。
 *
 * 用户不需要知道 idx 和 json 的区别，不需要编译，不需要敲命令：
 * 只要题库文件出现在设备的常见目录里，应用下次启动（或热重载时）就会自己用上它。
 *
 * 查找顺序（先命中先用）：
 * 1. `filesDir/qbank.idx`     —— WiFi 上传页写进来的，优先级最高
 * 2. `/sdcard/Download`       —— 最常见的落点（浏览器下载、adb push、文件管理器）
 * 3. `/sdcard/Documents`
 * 4. `/sdcard/RKDownload`     —— Rokid 系统自己的下载目录
 * 5. `/sdcard` 根目录（只看一层）
 * 6. APK 内置 `assets/qbank.idx` —— 兜底，保证永远有题库可用
 *
 * 支持两种格式：
 * - `*.idx`  已编译索引，直接读
 * - `*.json` 原始题库，[IndexBuilder] 现场编译；编译产物按「文件名+大小+修改时间」
 *            缓存到 `filesDir/qbank-cache/`，同一份 json 不会重复编译
 */
class QBankLoader(private val context: Context) {

    enum class Kind { Uploaded, Discovered, Assets }

    data class Source(
        val kind: Kind,
        val file: File?,
        val label: String,
        val modifiedAt: Long,
    )

    data class Loaded(
        val store: QBankStore,
        val source: Source,
        val title: String,
        val questionCount: Int,
        val termCount: Int,
        val byteSize: Int,
        val compileMs: Long,
    ) {
        fun describe(): String = buildString {
            if (title.isNotBlank()) append(title).append(" · ")
            append(questionCount).append(" 题 · ")
            append("%.2f".format(byteSize / 1048576f)).append(" MB")
        }
    }

    class LoadException(message: String, cause: Throwable? = null) : Exception(message, cause)

    // ------------------------------------------------------------------ 目录

    /** 上传落点：应用私有目录，不需要任何存储权限。 */
    val uploadDir: File get() = context.filesDir

    private val cacheDir: File
        get() = File(context.filesDir, "qbank-cache").apply { if (!exists()) mkdirs() }

    private fun scanDirs(): List<File> {
        val dirs = ArrayList<File>(5)
        dirs += context.filesDir
        // 外部目录可能因为权限/挂载拿不到，逐个 try
        fun add(path: String) {
            runCatching {
                val f = File(path)
                if (f.isDirectory && f.canRead()) dirs += f
            }
        }
        runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }
            .getOrNull()?.let { dirs += it }
        add("/sdcard/Download")
        add("/sdcard/Documents")
        add("/sdcard/RKDownload")
        add("/sdcard")
        return dirs.distinctBy { it.absolutePath }
    }

    /**
     * 列出所有看起来像题库的文件，按修改时间从新到旧。
     *
     * 只认 `.json` / `.idx`，跳过缓存目录和我们自己写的中间文件，避免把缓存当成用户题库。
     */
    fun discover(): List<File> {
        val out = ArrayList<File>()
        for (dir in scanDirs()) {
            val files = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (f in files) {
                if (!f.isFile) continue
                val name = f.name.lowercase()
                if (!name.endsWith(".json") && !name.endsWith(".idx")) continue
                if (name.startsWith("qbank-cache")) continue
                if (name == "data_extraction_rules.json" || name == "backup_rules.json") continue
                if (f.length() < MIN_PLAUSIBLE_BYTES) continue
                out += f
            }
        }
        return out.sortedByDescending { it.lastModified() }
    }

    // ------------------------------------------------------------------ 装载

    /** 依次尝试所有候选，返回第一个能成功装载的。全都不行才回退 assets。 */
    fun loadBest(): Loaded {
        val errors = ArrayList<String>()

        // 1) 上传落点。优先 json：它带着题库名，而且有编译缓存，速度一样快；
        //    没有 json 时再用已编译的 idx。
        for (name in arrayOf("qbank.json", "qbank.idx")) {
            val uploaded = File(context.filesDir, name)
            if (!uploaded.isFile || uploaded.length() <= 0) continue
            runCatching { load(uploaded, Kind.Uploaded) }
                .onSuccess { return it }
                .onFailure { errors += "上传的 $name 读取失败：${it.message}" }
        }

        // 2) 自动发现（最新的优先）
        for (f in discover()) {
            // filesDir 里的已经在第 1 步试过了，跳过
            if (f.parentFile?.absolutePath == context.filesDir.absolutePath) continue
            runCatching { load(f, Kind.Discovered) }
                .onSuccess { return it }
                .onFailure {
                    Log.w(TAG, "跳过 ${f.name}：${it.message}")
                    errors += "${f.name}：${it.message}"
                }
        }

        // 3) 兜底 assets
        return runCatching {
            val store = QBankStore.loadFromAssets(context)
            val size = runCatching {
                context.assets.open(ASSET_INDEX).use { it.available() }
            }.getOrDefault(0)
            Loaded(
                store = store,
                source = Source(Kind.Assets, null, "内置题库", 0L),
                title = "",
                questionCount = store.docCount,
                termCount = store.termCount,
                byteSize = size,
                compileMs = 0L,
            )
        }.getOrElse { t ->
            throw LoadException(
                if (errors.isEmpty()) "没有找到任何题库文件，内置索引也读不出来"
                else "所有题库都加载失败：" + errors.take(3).joinToString("；"),
                t,
            )
        }
    }

    /** 装载指定文件；`.json` 会自动编译（带缓存）。 */
    fun load(file: File, kind: Kind = Kind.Discovered): Loaded {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".idx") -> loadIdx(file, kind)
            name.endsWith(".json") -> loadJson(file, kind)
            else -> throw LoadException("不认识的文件类型：${file.name}")
        }
    }

    private fun loadIdx(file: File, kind: Kind): Loaded {
        val bytes = runCatching { file.readBytes() }
            .getOrElse { throw LoadException("读不出文件（可能没有存储权限）", it) }
        val store = runCatching { QBankStore.fromBytes(bytes) }
            .getOrElse { throw LoadException("不是有效的索引文件：${it.message}", it) }
        return Loaded(
            store = store,
            source = Source(kind, file, file.name, file.lastModified()),
            title = "",
            questionCount = store.docCount,
            termCount = store.termCount,
            byteSize = bytes.size,
            compileMs = 0L,
        )
    }

    private fun loadJson(file: File, kind: Kind): Loaded {
        val text = runCatching { file.readText() }
            .getOrElse { throw LoadException("读不出文件（可能没有存储权限）", it) }

        // 缓存键要能反映内容版本：文件名 + 大小 + 修改时间
        val cacheKey = md5("${file.absolutePath}|${file.length()}|${file.lastModified()}")
        val cached = File(cacheDir, "qbank-cache-$cacheKey.idx")
        // 标题单独存一个极小的 sidecar：走缓存时不再解析原 JSON，否则每次启动都要
        // 把几 MB 的题库重新 parse 一遍，白等好几秒。
        val cachedTitle = File(cacheDir, "qbank-cache-$cacheKey.title")

        val t0 = System.currentTimeMillis()
        val blob: ByteArray
        val stats: IndexBuilder.Stats
        if (cached.isFile && cached.length() > 0) {
            blob = runCatching { cached.readBytes() }
                .getOrElse { throw LoadException("缓存读取失败", it) }
            val title = runCatching { cachedTitle.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: quickTitle(text).also { t ->
                    // 老缓存没有 sidecar，顺手补上，下次就不用再扫一遍原文
                    runCatching { cachedTitle.writeText(t) }
                }
            stats = IndexBuilder.Stats(
                title = title,
                questions = 0,
                terms = 0,
                postingsBytes = 0,
                textBytes = 0,
                docsBytes = 0,
                totalBytes = blob.size,
                skippedEmptyStem = 0,
            )
        } else {
            val built = runCatching { IndexBuilder.build(text) }
                .getOrElse {
                    throw LoadException(
                        it.message ?: "题库 JSON 解析失败",
                        it,
                    )
                }
            blob = built.first
            stats = built.second
            runCatching { cached.writeBytes(blob) }
                .onFailure { Log.w(TAG, "缓存写入失败（不影响使用）", it) }
            runCatching { cachedTitle.writeText(stats.title) }
            pruneCache(keep = cached)
        }

        val store = runCatching { QBankStore.fromBytes(blob) }
            .getOrElse { throw LoadException("编译出的索引读不回来：${it.message}", it) }

        // 编译产物必须能自检通过，否则宁可不用——避免用户拿着一份坏题库干等
        val check = runCatching { store.selfCheck() }.getOrNull()
        if (check != null && !check.ok) {
            throw LoadException("题库校验失败：${check.message}")
        }

        return Loaded(
            store = store,
            source = Source(kind, file, file.name, file.lastModified()),
            title = stats.title,
            questionCount = store.docCount,
            termCount = store.termCount,
            byteSize = blob.size,
            compileMs = System.currentTimeMillis() - t0,
        )
    }

    /**
     * 从 JSON 开头捞题库名，不做完整解析。
     *
     * 只是为了在页面上显示一行标题，没必要为此把几 MB 的题库重新 parse 一遍——
     * 标题几乎总在文件最前面的 meta 里。
     */
    private fun quickTitle(text: String): String {
        val head = if (text.length > 8192) text.substring(0, 8192) else text
        val m = Regex("\"title\"\\s*:\\s*\"([^\"\\\\]{1,80})\"").find(head)
        return m?.groupValues?.get(1)?.trim().orEmpty()
    }

    /** 只留最近一份缓存，避免反复换题库把私有目录撑爆。 */    private fun pruneCache(keep: File) {
        val stem = keep.name.substringBeforeLast('.')
        runCatching {
            cacheDir.listFiles()?.forEach { f ->
                if (!f.name.startsWith(stem)) f.delete()
            }
        }
    }

    // ------------------------------------------------------------------ 上传落点

    /**
     * 把上传来的字节写成可用的题库。
     *
     * - `.idx` 直接落到 `filesDir/qbank.idx`
     * - `.json` 落到 `filesDir/qbank.json`，同时编译成 `filesDir/qbank.idx`
     *
     * 返回装载结果，调用方拿到后即可热切换。
     */
    fun acceptUpload(fileName: String, bytes: ByteArray): Loaded {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".idx") -> {
                val target = File(context.filesDir, "qbank.idx")
                target.writeBytes(bytes)
                load(target, Kind.Uploaded)
            }

            lower.endsWith(".json") -> {
                val raw = File(context.filesDir, "qbank.json")
                raw.writeBytes(bytes)
                val loaded = load(raw, Kind.Uploaded)
                // 编译结果另存一份，下次启动直接命中 filesDir/qbank.idx
                val target = File(context.filesDir, "qbank.idx")
                runCatching {
                    val compiled = cacheDir.listFiles()?.firstOrNull { it.name.endsWith(".idx") }
                    if (compiled != null) compiled.copyTo(target, overwrite = true)
                }
                loaded
            }

            else -> throw LoadException("只支持 .idx 和 .json 文件")
        }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)

    companion object {
        private const val TAG = "QBankLoader"

        /** APK 内置索引的 asset 名，仅作兜底。 */
        const val ASSET_INDEX = "qbank.idx"

        /** 小于这个体积的 json/idx 基本不可能是题库，直接跳过（避免读到配置类小 json）。 */
        private const val MIN_PLAUSIBLE_BYTES = 8 * 1024L
    }
}
