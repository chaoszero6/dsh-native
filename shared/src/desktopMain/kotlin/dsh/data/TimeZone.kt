package dsh.data

actual fun currentTimeZoneId(): String? = runCatching { java.util.TimeZone.getDefault().id }.getOrNull()
