package dsh.platform

/** Small persistent key/value store. Implementations encrypt at rest (Android Keystore, Windows DPAPI). */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

data class PickedFile(val name: String, val mediaType: String, val bytes: ByteArray)

/** Platform services the shared UI needs; implemented by androidApp / desktopApp. */
interface PlatformServices {
    val name: String
    val isMobile: Boolean
    val store: KeyValueStore
    fun openUrl(url: String)
    /** Lets the user pick files to attach. [imagesOnly] limits the picker to images. */
    suspend fun pickFiles(imagesOnly: Boolean = false): List<PickedFile>
    /** Saves (desktop) or shares (Android) bytes as a file. Returns a user-visible location or null if cancelled. */
    suspend fun saveFile(suggestedName: String, mediaType: String, bytes: ByteArray): String?
    fun copyToClipboard(text: String)
    /** System notification (approvals, finished turns) — shown only when the app is not in the foreground. */
    fun notify(id: String, title: String, body: String)
    fun cancelNotification(id: String) {}
    /** Called when a turn starts/stops or approvals are pending, so Android can hold a foreground service. */
    fun setBackgroundActivity(active: Boolean, summary: String) {}
    /** On-device speech-to-text (Android system recognizer). Null when unsupported or cancelled. */
    val canDictate: Boolean get() = false
    suspend fun dictate(): String? = null
    /** Renders the pages of a PDF to PNG bytes (for document preview). Null if unsupported. */
    suspend fun renderPdf(bytes: ByteArray, maxPages: Int = 20, widthPx: Int = 1400): List<ByteArray>? = null
}
