package com.bi2qfa.sonyconnect.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.bi2qfa.sonyconnect.data.PairingStore
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

/**
 * 下载落盘双通道：SAF 树（用户选择的目录）或应用专属目录（免权限兜底）。
 * 传输写 <name>.part，commit() 原子改名 —— 断点续传语义的基础。
 *
 * <p>多设备适配：外部传进来的 [cameraPath] 必须**已含设备子目录**
 * （`设备型号_SN码/DCIM/…`，见 [StorageSink.Companion.deviceFolderFor]）——
 * 两台相机的相机内路径完全一样，不隔离就会互相覆盖、续传还会写到别人的断点上。
 */
interface StorageSink {
    fun partBytes(): Long

    /**
     * 目标卷可用空间；无法可靠查询时返回 null。
     * 调用方只在能确认空间不足时拒绝新传输，不会因为未知值误伤。
     */
    fun availableBytes(): Long? = null

    fun appendStream(): OutputStream

    /**
     * 从零开始写（**截断**已有 .part）。
     *
     * base == 0 时必须用它而不是 [appendStream]：目标目录里可能残留着上一次
     * （甚至另一台相机）留下的同名 `.part`，追加会把那些字节当成文件前缀，
     * 产出的文件前半段是别人的数据，且没有任何校验和能发现。
     */
    fun truncateStream(): OutputStream

    /**
     * 将已验证完整的 .part 提交为最终文件。
     *
     * 成功返回前必须确认最终文件存在且大小等于 [expectedBytes]；任何不确定情况
     * 都抛 [IOException]，让任务明确失败而不是假 DONE。
     */
    fun commit(expectedBytes: Long)

    fun discard()

    companion object {
        /**
         * 设备子目录名：`设备型号_SN码_设备码前8位`（例如 `ILCE-6300_05186914_a1339c31`）。
         *
         * 多设备适配：每台相机的文件落在自己的子目录里，别把两台混在同一层。
         * 型号/SN 取自本机的配对记录（[PairingStore]）—— 那也是手机端唯一同时
         * 存着这两项的地方。两者都不全时退化为设备码，绝不退化成公共目录
         * （退化到公共目录就等于把两台的文件混回去，而且是静默的）。
         *
         * ★ **尾巴上那截设备码不是装饰**：型号 + SN 是机身属性，同一个机身重装/清过
         *   相机端数据就会换一个设备码，配对表里于是留下多条型号+SN 完全一样的记录
         *   （用户实测三条 "ILCE-6300 05186914"）。只按 型号_SN 命名，这三条就写进
         *   同一个目录 —— 队列按设备码分开了、文件却又混在一起，隔离只做了一半。
         *   加上设备码前 8 位，目录与队列、缩略图缓存（同样按设备码分）三者才对齐。
         *
         * 名字里的路径分隔符与控制字符要清掉：SN 或型号理论上不含这些，但一个
         * 带 `/` 的名字会直接变成两级目录，甚至把 SAF 建档打偏。
         */
        fun deviceFolderFor(guidHex: String?): String {
            val rec = guidHex?.takeIf { it.isNotBlank() }?.let { PairingStore.find(it) }
            val model = rec?.peerModel?.trim().orEmpty()
            val serial = rec?.peerSerial?.trim().orEmpty()
            val base = when {
                model.isNotEmpty() && serial.isNotEmpty() -> model + "_" + serial
                model.isNotEmpty() -> model
                serial.isNotEmpty() -> serial
                else -> ""
            }
            val code = com.bi2qfa.sonyconnect.core.ConnectionCenter.shortCode(guidHex)
            val raw = when {
                base.isEmpty() -> code.ifEmpty { "unknown" }      // 只有设备码可用
                code.isEmpty() -> base                            // 连设备码都没有（理论上到不了）
                else -> base + "_" + code
            }
            return sanitizeDirName(raw)
        }

        private fun sanitizeDirName(name: String): String {
            val sb = StringBuilder(name.length)
            for (c in name) {
                if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' ||
                    c == '"' || c == '<' || c == '>' || c == '|' || c < ' '
                ) {
                    sb.append('_')
                } else {
                    sb.append(c)
                }
            }
            val s = sb.toString().trim().trim('.')
            return if (s.isEmpty()) "unknown" else s
        }

        /**
         * 落盘相对路径 = `设备子目录/相机内路径`（去前导 `/`）。
         *
         * 落盘与打开文件必须走同一个函数：两边各算一次迟早会不一致，
         * 表现是"传输页显示已完成、点开却说文件不存在"。
         *
         * ★ [deviceDir] 为空 = **1.0 时代的老条目**：那时还没有设备子目录，文件直接
         *   落在根下（`SonyConnect/DCIM/…`，本机实测就是两份目录并存）。所以空目录名
         *   按"**没有子目录**"处理，而不是拿当前设备的目录去套 —— 套上去等于指着另一个
         *   位置找文件；反过来，新条目在入队时就钉好了目录名（见 TransferStore.enqueue），
         *   永远不会是空的。
         */
        fun localRelPath(deviceDir: String, cameraPath: String): String {
            val rel = cameraPath.trimStart('/')
            return if (deviceDir.isBlank()) rel else deviceDir + "/" + rel
        }

        /** treeUri 非空走 SAF，否则应用专属目录 */
        fun resolve(context: Context, treeUri: String, cameraPath: String): StorageSink {
            return if (treeUri.isNotBlank()) {
                try {
                    DocSink(context, Uri.parse(treeUri), cameraPath)
                } catch (e: Exception) {
                    FileSink(context, cameraPath)
                }
            } else {
                FileSink(context, cameraPath)
            }
        }

        /** 按扩展名猜 MIME（SAF 建档与"打开文件"共用）。
         *  ⚠️ ARW 不能用 image/x-sony-arw：MIUI 提供器不认该 mime，建档时会
         *  按 mime 强改文件名（.part → .part.arw），导致重名堆积与 rename
         *  失败 → commit 崩批次。octet-stream 无后缀规范化，MIUI 不改写。 */
        fun mimeOf(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "mp4" -> "video/mp4"
                "mov" -> "video/quicktime"
                else -> "application/octet-stream"
            }
        }

        /**
         * 已完成文件的 content Uri（传输页点开用）：选了目录走 SAF 文档 URI，
         * 否则应用目录走 FileProvider。文件不存在/解析不了返回 null。
         */
        fun openUriOrNull(context: Context, treeUri: String, cameraPath: String): Uri? {
            val rel = cameraPath.trimStart('/')
            return if (treeUri.isNotBlank()) {
                runCatching {
                    val tree = Uri.parse(treeUri)
                    val docId = DocumentsContract.getTreeDocumentId(tree) + "/" + rel
                    DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                }.getOrNull()
            } else {
                val base = context.getExternalFilesDir("SonyConnect")
                    ?: File(context.filesDir, "SonyConnect")
                val f = File(base, rel)
                if (f.isFile) {
                    runCatching {
                        androidx.core.content.FileProvider.getUriForFile(
                            context, context.packageName + ".files", f
                        )
                    }.getOrNull()
                } else null
            }
        }

        /**
         * **下载目录现在是否可用**（点"开始传输"前的预检，用户定版：目录不存在
         * 就弹窗、文件一律不进传输队列）。
         *
         * <p>为什么需要它：目录校验原本只发生在**下载进行时**（每个文件的
         * [StorageSink.resolve] → DocSink.init）—— 用户把目录删了/回收了权限，
         * 点击"开始传输"后整批文件照常进队列，然后在逐个下载时静默变红。
         * 现在把这道判定提前到"点击那一刻"。
         *
         * <p>阻塞（SAF 要 query 一次 contentResolver），**在 IO 线程调**。
         *
         * <p>判定口径：
         * <ul>
         *   <li>选了 SAF 目录：能对树根文档做一次子项 query 即可用 —— 目录被删、
         *       卡拔出、权限被回收都会在这里抛异常或返回 null cursor；</li>
         *   <li>应用专属目录（未选目录）：能创建/写入即可用（兜底恒真）。</li>
         * </ul>
         */
        fun downloadDirAvailable(context: Context, treeUri: String): Boolean {
            if (treeUri.isBlank()) {
                val base = context.getExternalFilesDir("SonyConnect")
                    ?: File(context.filesDir, "SonyConnect")
                return runCatching { base.isDirectory && (base.canWrite() || base.mkdirs()) }
                    .getOrDefault(false)
            }
            return runCatching {
                val tree = Uri.parse(treeUri)
                val rootId = DocumentsContract.getTreeDocumentId(tree)
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
                context.contentResolver.query(
                    children,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                    null, null, null,
                )?.use { true } ?: false
            }.getOrDefault(false)
        }
    }
}

/** SAF 通道（裸 DocumentsContract，不引 documentfile 依赖） */
class DocSink(context: Context, private val treeUri: Uri, cameraPath: String) : StorageSink {

    private val resolver = context.contentResolver
    // ⚠️ Kotlin 按声明顺序初始化：dirs/partDoc/finalDoc 必须声明在 init 之前，
    // 否则 init 执行时它们还是 null（自定义下载目录从不生效的根源）。
    private val dirs = mutableMapOf<String, Uri>()
    private var partDoc: Uri? = null
    private var finalDoc: Uri? = null
    private val docId: String
    private val partName: String
    private val finalName: String
    private val mime: String

    init {
        val rel = cameraPath.trimStart('/')
        val dirPart = rel.substringBeforeLast('/', "")
        finalName = rel.substringAfterLast('/')
        partName = "$finalName.part"
        mime = StorageSink.mimeOf(finalName)

        var parentId = DocumentsContract.getTreeDocumentId(treeUri)
        if (dirPart.isNotBlank()) {
            for (seg in dirPart.split('/')) {
                parentId = findOrCreateDir(parentId, seg)
            }
        }
        docId = findOrCreateFile(parentId, partName)
    }

    private fun childrenOf(parentDocId: String): MutableList<Uri> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val out = mutableListOf<Uri>()
        resolver.query(
            childrenUri,
            arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1)
                val mimeOf = c.getString(2)
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                when {
                    mimeOf == DocumentsContract.Document.MIME_TYPE_DIR ->
                        dirs[dirKey(parentDocId, name)] = docUri
                    name == partName -> partDoc = docUri
                    name == finalName -> finalDoc = docUri
                }
                out.add(docUri)
            }
        }
        return out
    }

    private fun findOrCreateDir(parentDocId: String, name: String): String {
        childrenOf(parentDocId)
        dirs[dirKey(parentDocId, name)]?.let {
            return DocumentsContract.getDocumentId(it)
        }
        // 父目录必须是 parentDocId 的文档 URI（此前误挂树根，子目录层级
        // 全部塌到根上、还会反复建出 "(1)" 重复目录）
        val parentDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
        return DocumentsContract.createDocument(resolver, parentDoc, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?.let { DocumentsContract.getDocumentId(it) }
            ?: throw FileNotFoundException("无法创建目录 $name")
    }

    private fun findOrCreateFile(parentDocId: String, name: String): String {
        partDoc = null
        finalDoc = null
        dirs.clear()
        childrenOf(parentDocId)
        // 旧 final 必须保留到新文件提交成功；这里只复用/创建 .part。
        partDoc?.let { return DocumentsContract.getDocumentId(it) }
        // 同样必须在目标父目录下建文件（此前挂树根 → 文件没进所选目录）
        val parentDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
        val created = DocumentsContract.createDocument(resolver, parentDoc, mime, partName)
            ?: throw FileNotFoundException("无法创建文件 $partName")
        partDoc = created
        return DocumentsContract.getDocumentId(created)
    }

    override fun partBytes(): Long {
        val uri = partDoc ?: return 0
        return resolver.query(
            uri,
            arrayOf(android.provider.DocumentsContract.Document.COLUMN_SIZE),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0 } ?: 0
    }

    // DocumentsContract 没有公开的跨 provider 可用空间字段；不能猜一个列名。
    // SAF 路径只能靠写入失败/提交大小校验兜底，显式返回“未知”。
    override fun availableBytes(): Long? = null

    override fun appendStream(): OutputStream {
        val uri = partDoc ?: throw FileNotFoundException("part 未创建")
        return resolver.openOutputStream(uri, "wa") ?: throw FileNotFoundException("无法写入")
    }

    override fun truncateStream(): OutputStream {
        val uri = partDoc ?: throw FileNotFoundException("part 未创建")
        // "wt" = write + truncate：把上一次残留的内容清掉再写
        return resolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException("无法写入")
    }

    override fun commit(expectedBytes: Long) {
        val uri = partDoc ?: throw IOException("part 未创建")
        val actual = partBytes()
        if (actual != expectedBytes) {
            throw IOException("断点大小不一致：$actual != $expectedBytes")
        }

        val oldFinal = finalDoc
        // 优先原位 rename；旧 final 先按原逻辑处理：很多 provider 对同目录 rename
        // 是替换语义。若 provider 拒绝，则保留 .part 并明确失败，绝不在复制失败后
        // 留下半成品 final。
        val renamed = runCatching {
            DocumentsContract.renameDocument(resolver, uri, finalName)
        }.getOrNull()
        if (renamed != null && documentSize(renamed) == expectedBytes) {
            partDoc = renamed
            finalDoc = renamed
            return
        }

        // rename 不可用：先创建临时新文档，完整复制并校验后再替换旧 final。
        val copiedTemp = copyToTemp(uri, expectedBytes)
        val copied = runCatching {
            DocumentsContract.renameDocument(resolver, copiedTemp, finalName)
        }.getOrNull() ?: run {
            throw IOException("临时文件无法改名为最终文件（.part 已保留）")
        }
        // 只在最终文件验证成功后清理旧文件。
        if (oldFinal != null && copied != oldFinal) {
            runCatching { DocumentsContract.deleteDocument(resolver, oldFinal) }
        }
        partDoc = copied
        finalDoc = copied
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
    }

    private fun dirKey(parentDocId: String, name: String): String = "$parentDocId/$name"

    private fun documentSize(uri: Uri): Long = resolver.query(
        uri,
        arrayOf(DocumentsContract.Document.COLUMN_SIZE),
        null, null, null,
    )?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L

    /**
     * rename 不可用时复制为 final。复制前检查空间，复制后查询大小；验证成功前
     * 不删除 .part，也不删除旧 final 的备份。
     */
    private fun copyToTemp(uri: Uri, expectedBytes: Long): Uri {
        availableBytes()?.let { free ->
            if (free < expectedBytes) throw IOException("存储空间不足")
        }
        val docId = DocumentsContract.getDocumentId(uri)
        val parentId = docId.substringBeforeLast('/', missingDelimiterValue = docId)
        val parentDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        val tempName = "$finalName.sonyconnect-new"
        val created = DocumentsContract.createDocument(resolver, parentDoc, mime, tempName)
            ?: throw IOException("无法创建最终文件")
        try {
            resolver.openInputStream(uri)?.use { input ->
                resolver.openOutputStream(created, "wt")?.use { output ->
                    input.copyTo(output)
                } ?: throw IOException("无法打开最终文件写入流")
            } ?: throw IOException("无法打开 .part 读取流")
            if (documentSize(created) != expectedBytes) {
                throw IOException("复制后最终文件大小不一致")
            }
            return created
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            throw e
        }
    }

    override fun discard() {
        partDoc?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
    }
}

/** 应用专属目录通道（免权限） */
class FileSink(context: Context, cameraPath: String) : StorageSink {

    private val base: File = context.getExternalFilesDir("SonyConnect")
        ?: File(context.filesDir, "SonyConnect")
    private val part: File
    private val finalFile: File

    init {
        val rel = cameraPath.trimStart('/')
        finalFile = File(base, rel)
        part = File(base, "$rel.part")
        part.parentFile?.mkdirs()
    }

    override fun partBytes(): Long = if (part.isFile) part.length() else 0

    override fun availableBytes(): Long? = runCatching { base.usableSpace }.getOrNull()

    override fun appendStream(): OutputStream {
        return java.io.FileOutputStream(part, true) // append 语义
    }

    override fun truncateStream(): OutputStream {
        return java.io.FileOutputStream(part, false) // 截断重写
    }

    override fun commit(expectedBytes: Long) {
        if (!part.isFile || part.length() != expectedBytes) {
            throw IOException("断点大小不一致")
        }
        val backup = if (finalFile.isFile) {
            val b = File(finalFile.parentFile, finalFile.name + ".sonyconnect-old")
            if (b.exists()) b.delete()
            if (!finalFile.renameTo(b)) throw IOException("无法保留旧最终文件")
            b
        } else null

        try {
            if (!part.renameTo(finalFile)) {
                availableBytes()?.let { free ->
                    if (free < expectedBytes) throw IOException("存储空间不足")
                }
                part.copyTo(finalFile, overwrite = false)
            }
            if (!finalFile.isFile || finalFile.length() != expectedBytes) {
                throw IOException("最终文件大小不一致")
            }
            if (!part.delete() && part.exists()) {
                throw IOException("无法删除已提交的 .part")
            }
            backup?.delete()
        } catch (e: Exception) {
            if (!finalFile.isFile && backup != null && backup.isFile) {
                runCatching { backup.renameTo(finalFile) }
            } else if (finalFile.isFile && part.isFile && finalFile.length() != expectedBytes) {
                runCatching { finalFile.delete() }
                if (backup != null) runCatching { backup.renameTo(finalFile) }
            }
            throw if (e is IOException) e else IOException("提交失败", e)
        }
    }

    override fun discard() {
        part.delete()
    }
}
