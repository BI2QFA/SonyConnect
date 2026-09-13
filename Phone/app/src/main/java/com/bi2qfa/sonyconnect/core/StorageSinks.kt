package com.bi2qfa.sonyconnect.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.bi2qfa.sonyconnect.data.PairingStore
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream









interface StorageSink {
    fun partBytes(): Long

    fun appendStream(): OutputStream

    






    fun truncateStream(): OutputStream

    fun commit()

    fun discard()

    companion object {
        
















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
                base.isEmpty() -> code.ifEmpty { "unknown" }      
                code.isEmpty() -> base                            
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

        











        fun localRelPath(deviceDir: String, cameraPath: String): String {
            val rel = cameraPath.trimStart('/')
            return if (deviceDir.isBlank()) rel else deviceDir + "/" + rel
        }

        
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

        



        fun mimeOf(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "mp4" -> "video/mp4"
                "mov" -> "video/quicktime"
                else -> "application/octet-stream"
            }
        }

        



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
    }
}


class DocSink(context: Context, private val treeUri: Uri, cameraPath: String) : StorageSink {

    private val resolver = context.contentResolver
    
    
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
                    mimeOf == DocumentsContract.Document.MIME_TYPE_DIR -> dirs[name] = docUri
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
        dirs[name]?.let {
            return DocumentsContract.getDocumentId(it)
        }
        
        
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
        
        finalDoc?.let { DocumentsContract.deleteDocument(resolver, it) }
        partDoc?.let { return DocumentsContract.getDocumentId(it) }
        
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

    override fun appendStream(): OutputStream {
        val uri = partDoc ?: throw FileNotFoundException("part 未创建")
        return resolver.openOutputStream(uri, "wa") ?: throw FileNotFoundException("无法写入")
    }

    override fun truncateStream(): OutputStream {
        val uri = partDoc ?: throw FileNotFoundException("part 未创建")
        
        return resolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException("无法写入")
    }

    override fun commit() {
        val uri = partDoc ?: return
        
        
        
        runCatching {
            DocumentsContract.renameDocument(resolver, uri, finalName)
        }.onFailure {
            runCatching { copyRename(uri) }
        }
    }

    private fun copyRename(uri: Uri) {
        
        val docId = DocumentsContract.getDocumentId(uri)
        val parentId = docId.substringBeforeLast('/', missingDelimiterValue = docId)
        val parentDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        val created = runCatching {
            DocumentsContract.createDocument(resolver, parentDoc, mime, finalName)
        }.getOrNull() ?: return
        runCatching {
            resolver.openInputStream(uri)?.use { input ->
                resolver.openOutputStream(created, "w")?.use { output ->
                    input.copyTo(output)
                }
            }
        }
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
    }

    override fun discard() {
        partDoc?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
    }
}


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

    override fun appendStream(): OutputStream {
        if (finalFile.isFile) finalFile.delete()
        return java.io.FileOutputStream(part, true) 
    }

    override fun truncateStream(): OutputStream {
        if (finalFile.isFile) finalFile.delete()
        return java.io.FileOutputStream(part, false) 
    }

    override fun commit() {
        if (finalFile.isFile) finalFile.delete()
        if (!part.renameTo(finalFile)) {
            
            part.copyTo(finalFile, overwrite = true)
            part.delete()
        }
    }

    override fun discard() {
        part.delete()
    }
}
