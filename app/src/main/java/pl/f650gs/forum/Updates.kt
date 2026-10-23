package pl.f650gs.forum

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.widget.LinearLayout
import android.widget.ProgressBar
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/*
 * New versions. The app is installed from the landing page, not a store, so once a day it reads
 * version.json next to the APK there and, when that lists a newer version, offers to install it.
 * The downloaded APK must match the SHA-256 in version.json before Android's installer gets it.
 */

internal const val UPDATE_INFO_URL = "https://ed7fanboy.github.io/f650gs/version.json"
internal const val ACTION_INSTALL_STATUS = "pl.f650gs.forum.INSTALL_STATUS"
private const val KEY_UPDATE_CHECKED = "update_checked_at"
private const val DAY_MS = 24L * 60 * 60 * 1000

/** What version.json says: the newest version and where its APK is. */
data class UpdateInfo(val versionCode: Long, val versionName: String, val apkUrl: String, val sha256: String)

/** Reads version.json; null unless it has every field and the APK comes from the landing page. */
internal fun parseUpdateInfo(json: String): UpdateInfo? = runCatching {
    val data = org.json.JSONObject(json)
    UpdateInfo(data.getLong("versionCode"), data.getString("versionName"), data.getString("apk"), data.getString("sha256").lowercase())
}.getOrNull()?.takeIf { it.apkUrl.startsWith("https://ed7fanboy.github.io/f650gs/") && Regex("[0-9a-f]{64}").matches(it.sha256) }

// An update waiting for the "install unknown apps" permission, offered again once it is granted.
private var awaitingInstallPermission: UpdateInfo? = null

internal fun MainActivity.installedVersionCode(): Long {
    val info = packageManager.getPackageInfo(packageName, 0)
    @Suppress("DEPRECATION")
    return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
}

/** Once a day (or now, when [manual]), asks the landing page for a newer version. */
internal fun MainActivity.checkForUpdate(manual: Boolean = false) {
    awaitingInstallPermission?.let { pending ->
        if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) { awaitingInstallPermission = null; downloadUpdate(pending) }
        return
    }
    val prefs = getSharedPreferences(MainActivity.PREFS, android.content.Context.MODE_PRIVATE)
    val now = System.currentTimeMillis()
    if (!manual && now - prefs.getLong(KEY_UPDATE_CHECKED, 0) < DAY_MS) return
    prefs.edit().putLong(KEY_UPDATE_CHECKED, now).apply()
    io.execute {
        val info = runCatching { parseUpdateInfo(readText(UPDATE_INFO_URL)) }.getOrNull()
        main.post {
            val current = packageManager.getPackageInfo(packageName, 0).versionName
            when {
                info == null -> if (manual) toast("Nie udało się sprawdzić aktualizacji.")
                info.versionCode > installedVersionCode() -> offerUpdate(info, current.orEmpty())
                manual -> toast("Masz najnowszą wersję ($current).")
            }
        }
    }
}

private fun readText(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Cache-Control", "no-cache")
        if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
        return connection.inputStream.bufferedReader().use { it.readText().take(64_000) }
    } finally {
        connection.disconnect()
    }
}

private fun MainActivity.offerUpdate(info: UpdateInfo, current: String) {
    AlertDialog.Builder(this)
        .setTitle("Nowa wersja ${info.versionName}")
        .setMessage("Masz wersję $current. Zainstalować nową? Ustawienia i logowanie zostaną.\n\nCo nowego: ed7fanboy.github.io/f650gs")
        .setPositiveButton("Instaluj") { _, _ ->
            if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) askInstallPermission(info) else downloadUpdate(info)
        }
        .setNegativeButton("Anuluj", null)
        .show()
}

/** Android lets an app install updates only after the member allows it, once, in the system settings. */
private fun MainActivity.askInstallPermission(info: UpdateInfo) {
    AlertDialog.Builder(this)
        .setTitle("Zgoda na instalację")
        .setMessage("Za pierwszym razem Android musi pozwolić aplikacji f650gs.pl instalować aktualizacje. Włącz „Zezwalaj z tego źródła” i wróć tutaj.")
        .setPositiveButton("Otwórz ustawienia") { _, _ ->
            awaitingInstallPermission = info
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
        .setNegativeButton("Anuluj", null)
        .show()
}

/** Downloads the APK with a progress bar, checks its SHA-256 and hands it to Android's installer. */
private fun MainActivity.downloadUpdate(info: UpdateInfo) {
    val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
    val status = label("Pobieram…", 14f, INK)
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(16), dp(24), dp(4))
        addView(status)
        addView(bar, margin(top = 10))
    }
    val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
    val dialog = AlertDialog.Builder(this)
        .setTitle("Wersja ${info.versionName}")
        .setView(box)
        .setNegativeButton("Anuluj") { _, _ -> cancel.set(true) }
        .setCancelable(false)
        .show()
    val file = File(File(cacheDir, "updates").apply { mkdirs() }, "f650gs-${info.versionName}.apk")
    io.execute {
        val result = runCatching {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val connection = URL(info.apkUrl).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
                val total = connection.contentLengthLong
                var done = 0L
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancel.get()) error("cancelled")
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            done += read
                            if (total > 0) main.post { bar.progress = (done * 100 / total).toInt(); status.text = "Pobieram… ${done * 100 / total}%" }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha != info.sha256) error("Plik nie zgadza się z sumą kontrolną ze strony, nie instaluję.")
        }
        main.post {
            dialog.dismiss()
            result.onSuccess { installApk(file) }.onFailure {
                file.delete()
                if (!cancel.get()) toast(it.localizedMessage ?: "Nie udało się pobrać aktualizacji.")
            }
        }
    }
}

/** Writes the APK into an installer session; Android then shows its own "update this app?" screen. */
private fun MainActivity.installApk(file: File) = runCatching {
    val installer = packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(packageName) }
    val session = installer.openSession(installer.createSession(params))
    session.use {
        file.inputStream().use { input ->
            it.openWrite("f650gs.apk", 0, file.length()).use { output -> input.copyTo(output); it.fsync(output) }
        }
        val callback = Intent(this, MainActivity::class.java).setAction(ACTION_INSTALL_STATUS).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // Mutable: the installer adds the status (and its confirmation screen) to this intent.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        it.commit(PendingIntent.getActivity(this, 4202, callback, flags).intentSender)
    }
    file.delete()
}.onFailure { toast("Nie udało się uruchomić instalacji: ${it.localizedMessage}") }

/** The installer's answer: show its confirmation screen, or say why it failed. */
internal fun MainActivity.handleInstallStatus(intent: Intent) {
    when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            confirm?.let { runCatching { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }
        PackageInstaller.STATUS_SUCCESS -> Unit
        PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
        else -> toast("Instalacja nie powiodła się: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()}")
    }
}
