package dev.live.translator.mobile.source

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dev.live.translator.mobile.model.CaptureTarget
import dev.live.translator.mobile.model.ResolvedCaptureTarget

class SourceUnavailable(message: String) : Exception(message)

class SourceCatalog(private val context: Context) {
    private val packageManager = context.packageManager
    private val ownPackage = context.packageName
    private val ownUid = context.applicationInfo.uid

    fun visibleCaptureTargets(): List<CaptureTarget> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val records = packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { info ->
                val app = info.activityInfo?.applicationInfo ?: return@mapNotNull null
                CaptureTarget(
                    packageName = app.packageName,
                    label = app.loadLabel(packageManager).toString().ifBlank { app.packageName },
                    uid = app.uid,
                    available = app.enabled,
                )
            }
        return filterTargets(records, ownPackage, ownUid)
    }

    fun resolveStartTarget(packageName: String): ResolvedCaptureTarget {
        if (packageName == ownPackage) throw SourceUnavailable("Власний застосунок не може бути джерелом")
        val app = try {
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") packageManager.getApplicationInfo(packageName, 0)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            throw SourceUnavailable("Вибрану програму не знайдено")
        }
        if (!app.enabled || app.uid == ownUid) throw SourceUnavailable("Вибрана програма недоступна")
        return ResolvedCaptureTarget(packageName, app.loadLabel(packageManager).toString(), app.uid)
    }

    companion object {
        fun filterTargets(records: List<CaptureTarget>, ownPackage: String, ownUid: Int): List<CaptureTarget> =
            records.asSequence()
                .filter { it.packageName != ownPackage && it.uid != ownUid }
                .distinctBy { it.packageName }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                .toList()
    }
}
