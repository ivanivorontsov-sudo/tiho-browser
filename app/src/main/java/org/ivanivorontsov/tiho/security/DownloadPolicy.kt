package org.ivanivorontsov.tiho.security

/** Download risk assessment (pure JVM, unit-tested). */
object DownloadPolicy {
    /** Executable / script / installer types: opening one can install or run code. */
    val DANGEROUS = setOf(
        "apk", "apks", "apkm", "xapk", "aab", "dex", "jar", "exe", "msi", "msix", "com", "scr", "pif", "cpl", "dll", "sys",
        "bat", "cmd", "ps1", "psm1", "vbs", "vbe", "js", "jse", "mjs", "wsf", "wsh", "hta", "lnk", "reg", "inf",
        "sh", "bash", "zsh", "csh", "command", "run", "bin", "elf", "py", "pyc", "pl", "rb", "php",
        "deb", "rpm", "appimage", "dmg", "pkg", "app", "iso", "img", "vhd", "vhdx",
        "docm", "xlsm", "pptm", "dotm", "xlam", "jnlp", "svg", "html", "htm", "xhtml", "mht", "mhtml", "swf",
    )
    private val DANGEROUS_MIME = listOf(
        "application/vnd.android.package-archive", "application/x-msdownload", "application/x-msdos-program", "application/x-ms-installer",
        "application/x-sh", "application/x-shellscript", "application/javascript", "text/javascript", "application/x-executable",
        "application/java-archive", "application/x-bat", "application/hta",
    )

    data class Risk(val dangerous: Boolean, val insecure: Boolean, val ext: String) {
        val any get() = dangerous || insecure
    }

    fun extension(name: String): String {
        val n = name.trim().trimEnd('.', ' ').lowercase()
        val i = n.lastIndexOf('.')
        return if (i < 0) "" else n.substring(i + 1)
    }

    fun assess(fileName: String, url: String, mime: String?): Risk {
        val ext = extension(fileName)
        val m = mime?.lowercase()?.substringBefore(';')?.trim()
        val dangerous = ext in DANGEROUS || (m != null && DANGEROUS_MIME.any { m == it }) ||
            // double extensions like "invoice.pdf.exe" are covered above; also catch "file.apk?x" names
            Regex("\\.(apk|exe|bat|cmd|sh|js|vbs|ps1|msi|scr)(\\W|$)", RegexOption.IGNORE_CASE).containsMatchIn(fileName)
        val insecure = url.startsWith("http://", ignoreCase = true)
        return Risk(dangerous, insecure, ext)
    }

    fun warningText(r: Risk): String = buildString {
        if (r.dangerous) append("⚠ Опасный тип файла (.${r.ext.ifBlank { "?" }}): такой файл может установить программу или выполнить код. Открывайте только из проверенного источника.\n")
        if (r.insecure) append("⚠ Загрузка по незащищённому HTTP: файл могут подменить по дороге.\n")
    }.trim()
}
