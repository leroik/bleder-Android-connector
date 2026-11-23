package dev.hybrid.scanner

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal enum class Phase { SCANNING, PAUSED, STOPPED }

internal class ScanStore(val folder: File, phase: Phase) {
    val id: String = folder.name
    private val files = File(folder, "frames").apply { mkdirs() }
    private val sent = File(folder, "sent").apply { mkdirs() }
    @Volatile var phase: Phase = phase
    val writing = AtomicBoolean()
    val next = AtomicInteger((packets().maxOfOrNull { it.nameWithoutExtension.toInt() } ?: -1) + 1)
    val saved = AtomicInteger(packets().size)
    val acknowledged = AtomicInteger(sent.list()?.size ?: 0)
    @Volatile var bytes = packets().sumOf { it.length() }
    val revision = AtomicInteger()
    @Volatile var finalizedOnPc = false
    fun setLocalPhase(value: Phase) {
        phase = value
        revision.incrementAndGet()
    }

    fun packets(): List<File> = files.listFiles { f -> f.extension == "h3s" }?.sortedBy { it.name } ?: emptyList()
    fun pending(): List<File> = packets().filter { !File(sent, it.name).exists() }
    fun resetAcknowledged() {
        sent.listFiles()?.forEach { it.delete() }
        acknowledged.set(0)
    }

    fun acknowledge(packet: File) {
        if (File(sent, packet.name).createNewFile()) acknowledged.incrementAndGet()
    }

    fun save(data: Measurement) {
        val name = "%09d.h3s".format(java.util.Locale.ROOT, data.metadata.getInt("id"))
        val destination = File(files, name)
        val temporary = File(files, "$name.tmp")
        try {
            FileOutputStream(temporary).use {
                data.write(it)
                it.fd.sync()
            }
            check(bytes + temporary.length() <= LIMIT) { "Достигнут лимит 2 ГБ на сеанс" }
            check(temporary.renameTo(destination)) { "Не удалось сохранить кадр" }
            bytes += destination.length()
            saved.incrementAndGet()
        } finally {
            temporary.delete()
        }
    }

    companion object {
        const val LIMIT = 2L * 1024 * 1024 * 1024
    }
}
