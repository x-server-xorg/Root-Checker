package com.fluent.rootchecker

import android.os.Build
import android.system.Os
import java.io.File
import java.util.concurrent.TimeUnit

/** Результат одной проверки. */
data class RootCheckResult(
    val title: String,
    val description: String,
    val status: CheckStatus,
    val detail: String
)

enum class CheckStatus { SAFE, ROOT, WARNING, UNKNOWN }

/**
 * Набор из 14 независимых проверок наличия root-доступа.
 * Каждая проверка изолирована: исключение внутри неё не прерывает остальные.
 */
object RootScanner {

    private val SU_PATHS = listOf(
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/system/sbin/su",
        "/data/local/su",
        "/data/local/bin/su",
        "/data/local/xbin/su",
        "/su/bin/su",
        "/vendor/bin/su",
        "/magisk/.core/bin/su"
    )

    private val ROOT_PACKAGES = linkedMapOf(
        "com.topjohnwu.magisk" to "Magisk",
        "io.github.vvb2060.magisk" to "Magisk (Alpha)",
        "eu.chainfire.supersu" to "SuperSU",
        "com.koushikdutta.superuser" to "Superuser (Koush)",
        "com.thirdparty.superuser" to "Superuser (3rd party)",
        "com.noshufou.android.su" to "Superuser (ChainsDD)",
        "com.noshufou.android.su.elite" to "Superuser Elite",
        "com.yellowes.su" to "Superuser (Yellowes)",
        "com.kingroot.kinguser" to "KingRoot",
        "com.kingo.root" to "KingoRoot",
        "com.smedialink.oneclickroot" to "One Click Root",
        "com.zhiqupk.root.global" to "Root Global",
        "com.alephzain.framaroot" to "Framaroot",
        "com.geohot.towelroot" to "Towelroot",
        "com.me.phh.superuser" to "phh Superuser",
        "org.kernelsu" to "KernelSU",
        "org.kernelsu.next" to "KernelSU Next",
        "com.rifsxd.ksu" to "KernelSU Manager",
        "me.weishu.kernelsu" to "KernelSU (старый)",
        "com.saurik.substrate" to "Cydia Substrate"
    )

    private val CLOAK_PACKAGES = linkedMapOf(
        "com.devadvance.rootcloak" to "Root Cloak",
        "com.formyhm.hideroot" to "Hide Root",
        "com.am.phh.rootcloak" to "RootCloak Plus",
        "com.am.phh.rootcloak2" to "RootCloak Plus 2",
        "com.am.phh.superuser" to "phh Superuser (обход)"
    )

    private val ROOT_FILES = listOf(
        "/sbin/.magisk",
        "/debug_ramdisk/.magisk",
        "/data/adb/magisk",
        "/data/adb/ksu",
        "/data/adb/modules",
        "/data/adb/ap",
        "/cache/magisk.log",
        "/su",
        "/system/bin/.ext/.su",
        "/system/xbin/daemonsu"
    )

    val checks: List<() -> RootCheckResult> = listOf(
        spec("Бинарники su", "Поиск файла su в системе") { checkSuBinaries() },
        spec("Команда «which su»", "Поиск su в переменной PATH") { checkWhichSu() },
        spec("Запуск «su -c id»", "Попытка реально получить root-сессию") { checkSuExec() },
        spec("Теги сборки", "Прошивка собрана с test-keys?") { checkTestKeys() },
        spec("Рут-менеджеры", "Установлены Magisk, SuperSU, KingRoot и др.") { checkRootManagers() },
        spec("Файлы Magisk / KernelSU", "Следы рут-систем в /data и /sbin") { checkRootFiles() },
        spec("SUID-бинарники", "Файлы с битом setuid в системных каталогах") { checkSuid() },
        spec("Запись в /system", "Смонтирован ли системный раздел на запись") { checkSystemWritable() },
        spec("ro.secure", "Отключена ли защита adb root") { checkRoSecure() },
        spec("ro.debuggable", "Сборка с отладочным доступом?") { checkRoDebuggable() },
        spec("Тип сборки", "ro.build.type: user / userdebug / eng") { checkBuildType() },
        spec("BusyBox", "Наличие busybox — частый спутник кастомных прошивок") { checkBusybox() },
        spec("Загрузчик", "Состояние verified boot / блокировки загрузчика") { checkBootloader() },
        spec("Скрыльщики root", "Приложения для маскировки root (Root Cloak и т.п.)") { checkCloakApps() }
    )

    // ---------------------------------------------------------------- helpers

    private fun spec(
        title: String,
        description: String,
        body: () -> Pair<CheckStatus, String>
    ): () -> RootCheckResult = {
        try {
            val (status, detail) = body()
            RootCheckResult(title, description, status, detail)
        } catch (t: Throwable) {
            RootCheckResult(
                title,
                description,
                CheckStatus.UNKNOWN,
                "Не удалось выполнить: ${t.javaClass.simpleName}"
            )
        }
    }

    /** Запуск внешней команды с таймаутом. Возвращает (код выхода, stdout+stderr). */
    private fun exec(cmd: List<String>, timeoutSec: Long = 3): Pair<Int, String> {
        return try {
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return -1 to "превышено время ожидания (${timeoutSec} c)"
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.exitValue() to output
        } catch (t: Throwable) {
            -2 to (t.message ?: t.javaClass.simpleName)
        }
    }

    private fun systemProp(key: String): String? = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    } catch (t: Throwable) {
        null
    }

    private fun exists(path: String): Boolean = try {
        File(path).exists()
    } catch (t: Throwable) {
        false
    }

    // ----------------------------------------------------------------- checks

    private fun checkSuBinaries(): Pair<CheckStatus, String> {
        val found = SU_PATHS.filter { exists(it) }
        return if (found.isNotEmpty()) {
            CheckStatus.ROOT to "Найдено: ${found.joinToString(", ")}"
        } else {
            CheckStatus.SAFE to "su не найден в ${SU_PATHS.size} стандартных путях"
        }
    }

    private fun checkWhichSu(): Pair<CheckStatus, String> {
        val (code, out) = exec(listOf("sh", "-c", "command -v su; which su"))
        return if (code == 0 && out.isNotBlank() && !out.contains("not found")) {
            CheckStatus.ROOT to "Найдено: ${out.replace("\n", ", ")}"
        } else {
            CheckStatus.SAFE to "В PATH команда su отсутствует"
        }
    }

    private fun checkSuExec(): Pair<CheckStatus, String> {
        val (code, out) = exec(listOf("su", "-c", "id"), 3)
        return when {
            code == 0 && out.contains("uid=0") -> CheckStatus.ROOT to "Root получен: $out"
            code == -2 && out.contains("No such file") ->
                CheckStatus.SAFE to "Исполняемый файл su отсутствует"
            code == -1 -> CheckStatus.SAFE to "Команда su не отвечает (таймаут) — root не выдан"
            out.contains("not found") || out.contains("Permission denied") ->
                CheckStatus.SAFE to "Запуск отклонён: ${out.take(90)}"
            else -> CheckStatus.SAFE to "Root-сессия не получена (код $code)"
        }
    }

    private fun checkTestKeys(): Pair<CheckStatus, String> {
        val tags = Build.TAGS ?: "unknown"
        return if (tags.contains("test-keys")) {
            CheckStatus.ROOT to "Build.TAGS: $tags"
        } else {
            CheckStatus.SAFE to "Build.TAGS: $tags"
        }
    }

    private fun checkRootManagers(): Pair<CheckStatus, String> {
        val found = mutableListOf<String>()
        for ((pkg, name) in ROOT_PACKAGES) {
            val installed = try {
                @Suppress("DEPRECATION")
                applicationContext().packageManager.getPackageInfo(pkg, 0)
                true
            } catch (t: Throwable) {
                false
            }
            if (installed) found += "$name ($pkg)"
        }
        return if (found.isNotEmpty()) {
            CheckStatus.ROOT to "Установлены: ${found.joinToString(", ")}"
        } else {
            CheckStatus.SAFE to "Рут-менеджеры не обнаружены (проверено ${ROOT_PACKAGES.size})"
        }
    }

    private fun checkRootFiles(): Pair<CheckStatus, String> {
        val found = ROOT_FILES.filter { exists(it) }
        return if (found.isNotEmpty()) {
            CheckStatus.ROOT to "Найдено: ${found.joinToString(", ")}"
        } else {
            CheckStatus.SAFE to "Следов Magisk/KernelSU не найдено"
        }
    }

    private fun checkSuid(): Pair<CheckStatus, String> {
        val dirs = listOf("/system/bin", "/system/xbin", "/vendor/bin", "/sbin")
        val suid = mutableListOf<String>()
        for (dir in dirs) {
            val children = File(dir).listFiles() ?: continue
            for (child in children.take(500)) {
                try {
                    val mode = Os.stat(child.absolutePath).st_mode
                    if (mode and 0x800 != 0) { // S_ISUID
                        suid += child.path
                    }
                } catch (t: Throwable) {
                    // каталог недоступен — пропускаем
                }
            }
        }
        return when {
            suid.isEmpty() -> CheckStatus.SAFE to "SUID-бинарников в ${dirs.size} каталогах нет"
            suid.any { File(it).name == "su" } -> CheckStatus.ROOT to "Найден SUID su: ${suid.joinToString(", ")}"
            else -> CheckStatus.WARNING to "Есть SUID-файлы: ${suid.take(5).joinToString(", ")}"
        }
    }

    private fun checkSystemWritable(): Pair<CheckStatus, String> {
        val mounts = try {
            File("/proc/mounts").readText()
        } catch (t: Throwable) {
            ""
        }
        var rwMounts = mutableListOf<String>()
        for (line in mounts.lines()) {
            val f = line.trim().split(Regex("\\s+"))
            if (f.size < 4) continue
            val point = f[1]
            val options = f[3]
            val isSystem = point == "/system" || point == "/" || point.startsWith("/system/")
            if (isSystem && options.split(",").contains("rw")) rwMounts += "$point ($options)"
        }
        val canWrite = try { File("/system").canWrite() } catch (t: Throwable) { false }
        return if (rwMounts.isNotEmpty() || canWrite) {
            CheckStatus.WARNING to
                "Системный раздел доступен на запись: ${rwMounts.joinToString("; ").ifEmpty { "canWrite=true" }}"
        } else {
            CheckStatus.SAFE to "/system смонтирован read-only, запись недоступна"
        }
    }

    private fun checkRoSecure(): Pair<CheckStatus, String> {
        val value = systemProp("ro.secure")
        return when (value) {
            null -> CheckStatus.UNKNOWN to "Свойство ro.secure недоступно"
            "1" -> CheckStatus.SAFE to "ro.secure=1 (защита включена)"
            else -> CheckStatus.WARNING to "ro.secure=$value — adb root возможен"
        }
    }

    private fun checkRoDebuggable(): Pair<CheckStatus, String> {
        val value = systemProp("ro.debuggable")
        return when (value) {
            null -> CheckStatus.UNKNOWN to "Свойство ro.debuggable недоступно"
            "0" -> CheckStatus.SAFE to "ro.debuggable=0"
            else -> CheckStatus.WARNING to "ro.debuggable=$value — отладочный режим включён"
        }
    }

    private fun checkBuildType(): Pair<CheckStatus, String> {
        val value = systemProp("ro.build.type") ?: "unknown"
        return when (value) {
            "user" -> CheckStatus.SAFE to "ro.build.type=user (релизная сборка)"
            "unknown" -> CheckStatus.UNKNOWN to "ro.build.type недоступно"
            else -> CheckStatus.WARNING to "ro.build.type=$value — не пользовательская сборка"
        }
    }

    private fun checkBusybox(): Pair<CheckStatus, String> {
        val paths = listOf("/system/xbin/busybox", "/system/bin/busybox", "/sbin/busybox")
        val found = paths.filter { exists(it) }
        return if (found.isNotEmpty()) {
            CheckStatus.WARNING to "Найдено: ${found.joinToString(", ")}"
        } else {
            CheckStatus.SAFE to "BusyBox не обнаружен"
        }
    }

    private fun checkBootloader(): Pair<CheckStatus, String> {
        val state = systemProp("ro.boot.verifiedbootstate")
            ?: systemProp("ro.boot.vbmeta.device_state")
            ?: systemProp("ro.boot.flash.locked")
        return when {
            state == null -> CheckStatus.UNKNOWN to "Состояние загрузчика недоступно"
            state in setOf("orange", "yellow", "unlocked", "0") ->
                CheckStatus.WARNING to "Загрузчик разблокирован ($state)"
            state in setOf("green", "locked", "1") ->
                CheckStatus.SAFE to "Загрузчик заблокирован ($state)"
            else -> CheckStatus.UNKNOWN to "Неизвестное состояние: $state"
        }
    }

    private fun checkCloakApps(): Pair<CheckStatus, String> {
        val found = mutableListOf<String>()
        for ((pkg, name) in CLOAK_PACKAGES) {
            val installed = try {
                @Suppress("DEPRECATION")
                applicationContext().packageManager.getPackageInfo(pkg, 0)
                true
            } catch (t: Throwable) {
                false
            }
            if (installed) found += "$name ($pkg)"
        }
        return if (found.isNotEmpty()) {
            CheckStatus.WARNING to "Установлены: ${found.joinToString(", ")}"
        } else {
            CheckStatus.SAFE to "Скрыльщики root не найдены"
        }
    }
}

/** Хук для доступа к Context из объекта (устанавливается в MainActivity). */
private var appContext: android.content.Context? = null

fun setAppContext(context: android.content.Context) {
    appContext = context.applicationContext
}

private fun applicationContext(): android.content.Context =
    appContext ?: throw IllegalStateException("Context не инициализирован")
