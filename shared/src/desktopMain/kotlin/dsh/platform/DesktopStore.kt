package dsh.platform

import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.Properties

/**
 * File-backed store in the per-user app data dir. On Windows values are encrypted with DPAPI
 * (bound to the current Windows user); elsewhere the file is chmod 600.
 */
class DesktopKeyValueStore(private val dir: File = defaultDir()) : KeyValueStore {
    private val file = File(dir, "store.properties")
    private val props = Properties()
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    init {
        dir.mkdirs()
        if (file.exists()) file.inputStream().use { props.load(it) }
    }

    override fun get(key: String): String? {
        val raw = props.getProperty(key) ?: return null
        return runCatching {
            if (raw.startsWith("dpapi:")) String(Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(raw.removePrefix("dpapi:"))), Charsets.UTF_8)
            else raw
        }.getOrNull()
    }

    @Synchronized
    override fun put(key: String, value: String?) {
        if (value == null) props.remove(key)
        else props.setProperty(key, if (isWindows) runCatching { "dpapi:" + Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(value.toByteArray(Charsets.UTF_8))) }.getOrDefault(value) else value)
        val tmp = File(dir, "store.properties.tmp")
        tmp.outputStream().use { props.store(it, "DSH native client") }
        if (!isWindows) runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        fun defaultDir(): File {
            val os = System.getProperty("os.name").lowercase()
            val home = System.getProperty("user.home")
            return when {
                os.contains("win") -> File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "DSH")
                os.contains("mac") -> File(home, "Library/Application Support/DSH")
                else -> File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "dsh-native")
            }
        }
    }
}
