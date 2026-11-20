package dsh.ui

import dsh.platform.PickedFile
import kotlinx.coroutines.flow.MutableStateFlow

/** Content shared into the app from other apps (Android share sheet); consumed by the next focused composer. */
object SharedInbox {
    data class Shared(val text: String?, val files: List<PickedFile>)
    val pending = MutableStateFlow<Shared?>(null)
    fun offer(text: String?, files: List<PickedFile>) { if (text != null || files.isNotEmpty()) pending.value = Shared(text, files) }
}
