package app.teron.signals.data

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.json.JSONObject

/**
 * Проверка обновлений без серверов и ключей: приложение читает статический
 * `releases/TeronSignals-version.json` из ветки main репозитория на GitHub
 * и сравнивает versionCode со своим. Если версия новее — предлагает скачать
 * APK по прямой ссылке. APK всегда подписан ОДНИМ постоянным ключом
 * (keystore/teron-release.keystore), поэтому Android ставит обновление
 * поверх установленной версии без потери данных и watchlist.
 */
object UpdateChecker {

    const val VERSION_URL =
        "https://raw.githubusercontent.com/Aleksandr-pro/Teron/main/releases/TeronSignals-version.json"
    const val DOWNLOAD_PAGE =
        "https://github.com/Aleksandr-pro/Teron/blob/main/releases"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String
    )

    fun currentVersionCode(ctx: Context): Int = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
    } catch (t: Throwable) {
        0
    }

    fun currentVersionName(ctx: Context): String = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (t: Throwable) {
        "?"
    }

    /** Тихая проверка при запуске: диалог только если есть новая версия. */
    fun checkAuto(ctx: Context) {
        fetch { info ->
            if (info != null && info.versionCode > currentVersionCode(ctx)) {
                showDialog(ctx, info)
            }
        }
    }

    /** Ручная проверка по кнопке: сообщаем и при отсутствии обновлений. */
    fun checkManual(ctx: Context) {
        Toast.makeText(ctx, "Проверка обновлений…", Toast.LENGTH_SHORT).show()
        fetch { info ->
            when {
                info == null ->
                    Toast.makeText(ctx, "Не удалось проверить обновления", Toast.LENGTH_SHORT).show()
                info.versionCode > currentVersionCode(ctx) -> showDialog(ctx, info)
                else ->
                    Toast.makeText(
                        ctx,
                        "У вас последняя версия ${currentVersionName(ctx)}",
                        Toast.LENGTH_SHORT
                    ).show()
            }
        }
    }

    private fun fetch(onResult: (UpdateInfo?) -> Unit) {
        Http.background(
            block = {
                val json = JSONObject(Http.getText(VERSION_URL))
                UpdateInfo(
                    versionCode = json.optInt("versionCode", 0),
                    versionName = json.optString("versionName", ""),
                    apkUrl = json.optString(
                        "apkUrl",
                        "https://raw.githubusercontent.com/Aleksandr-pro/Teron/main/releases/TeronSignals-v${json.optString("versionName")}.apk"
                    ),
                    notes = json.optString("notes", "")
                )
            },
            onResult = { onResult(it) },
            onError = { onResult(null) }
        )
    }

    private fun showDialog(ctx: Context, info: UpdateInfo) {
        val message = buildString {
            append("Доступна версия ${info.versionName} (у вас ${currentVersionName(ctx)}).")
            if (info.notes.isNotBlank()) append("\n\n").append(info.notes)
            append("\n\nAPK скачается браузером — установите его поверх текущей версии, данные и список пар сохранятся (подпись та же).")
        }
        AlertDialog.Builder(ctx)
            .setTitle("Обновление Teron Signals")
            .setMessage(message)
            .setPositiveButton("Скачать") { _, _ ->
                val url = info.apkUrl.ifBlank { DOWNLOAD_PAGE }
                try {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (t: Throwable) {
                    Toast.makeText(ctx, "Не удалось открыть ссылку загрузки", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Позже", null)
            .show()
    }
}
