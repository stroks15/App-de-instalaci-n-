package com.dexterh4ck.equipos.util

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager

/**
 * Reporte de uso de aplicaciones (control de uso) vía UsageStatsManager.
 * Requiere que el usuario otorgue "Acceso de uso" (Ajustes → Seguridad → Apps con acceso de uso).
 */
object UsageStatsHelper {

    fun hasUsageAccess(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Devuelve JSON con el tiempo de uso por app en las últimas N horas.
     */
    fun getUsageReport(context: Context, hours: Int): String {
        if (!hasUsageAccess(context)) {
            return "[]"
        }

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - (hours * 3600_000L)

        val stats: List<UsageStats> = try {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        // Agrupar por paquete
        val byPackage = mutableMapOf<String, Long>()
        stats.forEach { s ->
            if (s.totalTimeInForeground > 0) {
                byPackage[s.packageName] = (byPackage[s.packageName] ?: 0L) + s.totalTimeInForeground
            }
        }

        val pm = context.packageManager
        val rows = byPackage.entries
            .sortedByDescending { it.value }
            .map { (pkg, millis) ->
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) {
                    pkg
                }
                mapOf(
                    "packageName" to pkg,
                    "appName" to label,
                    "usageSeconds" to (millis / 1000).toInt(),
                    "usageMinutes" to (millis / 60000).toInt()
                )
            }

        return rows.toJsonArray()
    }

    private fun List<Map<String, Any>>.toJsonArray(): String {
        return "[" + joinToString(",") { row ->
            "{" + row.entries.joinToString(",") { "\"${it.key}\":${toJsonValue(it.value)}" } + "}"
        } + "]"
    }

    private fun toJsonValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        is Number -> value.toString()
        is Boolean -> value.toString()
        else -> "\"$value\""
    }
}