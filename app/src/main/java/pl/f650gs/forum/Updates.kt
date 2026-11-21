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
 * version.json may also name a minVersionCode: an installed version below it is no longer safe to use (security
 * fixes), so the offer cannot be dismissed and is checked on every start until the update is in.
 */

internal const val UPDATE_INFO_URL = "https://ed7fanboy.github.io/f650gs/version.json"
internal const val ACTION_INSTALL_STATUS = "pl.f650gs.forum.INSTALL_STATUS"
private const val KEY_UPDATE_CHECKED = "update_checked_at"
private const val KEY_MIN_VERSION = "update_min_version"
private const val KEY_UPDATE_OFFERED = "update_offered_at"
private const val KEY_INSTALL_NONCE = "install_nonce"
private const val EXTRA_INSTALL_NONCE = "pl.f650gs.forum.INSTALL_NONCE"
private const val DAY_MS = 24L * 60 * 60 * 1000
private const val HOUR_MS = 60L * 60 * 1000

/** What version.json says: the newest version and where its APK is. */
data class UpdateInfo(val versionCode: Long, val versionName: String, val apkUrl: String, val sha256: String,
                      val minVersionCode: Long = 0)

/** Reads version.json; null unless it has every field and the APK comes from the landing page. */
internal fun parseUpdateInfo(json: String): UpdateInfo? = runCatching {
    val data = org.json.JSONObject(json)
    UpdateInfo(data.getLong("versionCode"), data.getString("versionName"), data.getString("apk"), data.getString("sha256").lowercase(),
        data.optLong("minVersionCode", 0))
}.getOrNull()?.takeIf { it.apkUrl.startsWith("https://ed7fanboy.github.io/f650gs/") && Regex("[0-9a-f]{64}").matches(it.sha256) }

// An update waiting for the "install unknown apps" permission, offered again once it is granted.
private var awaitingInstallPermission: UpdateInfo? = null
// A check is under way, or the offer is on screen: onResume must not start a second one.
private var updateBusy = false
private var updateDialog: AlertDialog? = null

internal fun MainActivity.installedVersionCode(): Long {
    val info = packageManager.getPackageInfo(packageName, 0)
    @Suppress("DEPRECATION")
    return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
}

/** Asks the landing page for a newer version: at most once an hour, at once when [manual] or below the minimum. */
internal fun MainActivity.checkForUpdate(manual: Boolean = false) {
    awaitingInstallPermission?.let { pending ->
        if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) { awaitingInstallPermission = null; downloadUpdate(pending) }
        return
    }
    if (updateBusy || updateDialog?.isShowing == true) return
    val prefs = getSharedPreferences(MainActivity.PREFS, android.content.Context.MODE_PRIVATE)
    val now = System.currentTimeMillis()
    val required = prefs.getLong(KEY_MIN_VERSION, 0) > installedVersionCode()
    // version.json is read at most once an hour (so a new minimum takes effect within the hour); the optional offer is
    // shown at most once a day
    if (!manual && !required && now - prefs.getLong(KEY_UPDATE_CHECKED, 0) < HOUR_MS) return
    prefs.edit().putLong(KEY_UPDATE_CHECKED, now).apply()
    updateBusy = true
    io.execute {
        val info = runCatching { parseUpdateInfo(readText(UPDATE_INFO_URL)) }.getOrNull()
        main.post {
            updateBusy = false
            if (info != null) prefs.edit().putLong(KEY_MIN_VERSION, info.minVersionCode).apply()
            val current = packageManager.getPackageInfo(packageName, 0).versionName
            when {
                info == null -> if (manual) toast("Nie udało się sprawdzić aktualizacji.")
                info.minVersionCode > installedVersionCode() && info.versionCode > installedVersionCode() ->
                    offerUpdate(info, current.orEmpty(), required = true)
                info.versionCode > installedVersionCode() && (manual || now - prefs.getLong(KEY_UPDATE_OFFERED, 0) >= DAY_MS) -> {
                    prefs.edit().putLong(KEY_UPDATE_OFFERED, now).apply()
                    offerUpdate(info, current.orEmpty(), required = false)
                }
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

private fun MainActivity.offerUpdate(info: UpdateInfo, current: String, required: Boolean) {
    if (isFinishing || isDestroyed) return
    val builder = AlertDialog.Builder(this)
        .setTitle(if (required) "Wymagana aktualizacja" else "Nowa wersja ${info.versionName}")
        .setMessage(
            if (required) "Wersja $current ma błędy bezpieczeństwa i nie jest już obsługiwana. Zainstaluj wersję ${info.versionName}, " +
                "żeby dalej korzystać z aplikacji. Ustawienia i logowanie zostaną.\n\nCo nowego: ed7fanboy.github.io/f650gs"
            else "Masz wersję $current. Zainstalować nową? Ustawienia i logowanie zostaną.\n\nCo nowego: ed7fanboy.github.io/f650gs")
        .setPositiveButton("Instaluj") { _, _ ->
            if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) askInstallPermission(info) else downloadUpdate(info)
        }
    if (required) {
        // No way past it: Back and taps outside do nothing, the only other choice closes the app
        builder.setCancelable(false).setNegativeButton("Zamknij aplikację") { _, _ -> finishAndRemoveTask() }
    } else {
        builder.setNegativeButton("Anuluj", null)
    }
    updateDialog = builder.show()
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
        // MainActivity is exported, so any app could send ACTION_INSTALL_STATUS with an intent of its choice to start;
        // only an answer carrying this session's secret is taken as the installer's.
        val nonce = java.util.UUID.randomUUID().toString()
        getSharedPreferences(MainActivity.PREFS, android.content.Context.MODE_PRIVATE).edit().putString(KEY_INSTALL_NONCE, nonce).apply()
        val callback = Intent(this, MainActivity::class.java).setAction(ACTION_INSTALL_STATUS).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_INSTALL_NONCE, nonce)
        // Mutable: the installer adds the status (and its confirmation screen) to this intent.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        it.commit(PendingIntent.getActivity(this, 4202, callback, flags).intentSender)
    }
    file.delete()
}.onFailure { toast("Nie udało się uruchomić instalacji: ${it.localizedMessage}") }

/** The installer's answer: show its confirmation screen, or say why it failed. */
internal fun MainActivity.handleInstallStatus(intent: Intent) {
    val prefs = getSharedPreferences(MainActivity.PREFS, android.content.Context.MODE_PRIVATE)
    val expected = prefs.getString(KEY_INSTALL_NONCE, null)
    if (expected == null || intent.getStringExtra(EXTRA_INSTALL_NONCE) != expected) return   // not our installer session
    when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            // The system installer's screen only: never one of this app's own screens, never a file-access grant.
            // (resolveActivity alone can be null for the installer under Android 11+ package visibility; the secret above
            // is the real check)
            val target = confirm?.component?.packageName ?: confirm?.`package` ?: confirm?.resolveActivity(packageManager)?.packageName
            if (confirm == null || target == packageName) return
            confirm.flags = confirm.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION).inv()
            runCatching { startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return   // the installer answers again (success / failure) with the same secret
        }
        PackageInstaller.STATUS_SUCCESS -> prefs.edit().remove(KEY_INSTALL_NONCE).apply()
        PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
        else -> toast("Instalacja nie powiodła się: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()}")
    }
}
