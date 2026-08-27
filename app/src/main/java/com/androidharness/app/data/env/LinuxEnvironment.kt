package com.androidharness.app.data.env

import android.content.Context
import android.os.Build
import android.system.Os
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import com.androidharness.app.tools.ShellPolicy
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class PkgMeta(
    val name: String,
    val version: String,
    val depends: List<String>,
    val filename: String,
    val size: Long,
    val sha256: String,
)

/** Parses the Debian Packages index served by the Termux repository. */
object PackageIndex {
    fun parse(text: String): Map<String, PkgMeta> {
        val out = mutableMapOf<String, PkgMeta>()
        text.split(Regex("\\n\\n")).forEach { block ->
            val fields = mutableMapOf<String, String>()
            var current: String? = null
            block.lines().forEach { line ->
                when {
                    line.isEmpty() -> Unit
                    line.startsWith(" ") -> {
                        val key = current ?: return@forEach
                        fields[key] = (fields[key] ?: "") + "\n" + line.trim()
                    }
                    else -> {
                        val i = line.indexOf(':')
                        if (i > 0) {
                            current = line.substring(0, i)
                            fields[current!!] = line.substring(i + 1).trim()
                        }
                    }
                }
            }
            val name = fields["Package"] ?: return@forEach
            val filename = fields["Filename"] ?: return@forEach
            val depends = (fields["Depends"] ?: "")
                .split(',')
                .map { it.trim().substringBefore('(').trim().split('|').first().trim() }
                .filter { it.isNotBlank() }
            out[name] = PkgMeta(
                name = name,
                version = fields["Version"] ?: "",
                depends = depends,
                filename = filename,
                size = fields["Size"]?.toLongOrNull() ?: 0L,
                sha256 = fields["SHA256"] ?: "",
            )
        }
        return out
    }
}

/**
 * Termux packages ship absolute symlinks into their own build prefix
 * (/data/data/com.termux/files/usr/...); inside this app's prefix those
 * dangle (bzcmp, bzless, and many man-page links). Rewrites them as paths
 * relative to the link's directory, pointing at the same file inside our
 * prefix, which also keeps the installed tree relocatable for the
 * shell-user re-deploy.
 */
internal object TermuxLinkRewrite {
    private const val TERMUX_USR = "/data/data/com.termux/files/usr/"

    /**
     * @param linkName raw symlink target from the package archive
     * @param linkPath where the symlink itself lives inside the prefix (e.g. "bin/bzcmp")
     * @return rewritten relative target, or null when nothing needs rewriting
     */
    fun relativeTarget(linkName: String, linkPath: String): String? {
        if (!linkName.startsWith(TERMUX_USR)) return null
        val inside = linkName.removePrefix(TERMUX_USR)
        val depth = linkPath.trim('/').split('/').dropLast(1).count { it.isNotEmpty() }
        return "../".repeat(depth) + inside
    }
}

sealed interface EnvState {
    data object NotInstalled : EnvState
    data class Downloading(val index: Int, val total: Int, val pkg: String) : EnvState
    data class Installing(val index: Int, val total: Int, val pkg: String) : EnvState
    data object Ready : EnvState
    data class Failed(val message: String) : EnvState
}

/**
 * Installs a self-contained Linux userspace (bash, coreutils, git, python,
 * node…) into the app's private storage, sourced from the public Termux
 * package repository. No root, no external app required.
 */
class LinuxEnvironmentManager(private val context: Context) {

    val prefix: File = File(context.filesDir, "linux")
    private val marker = File(prefix, ".harness-installed")
    private val tempDir = File(context.cacheDir, "deb-download").apply { mkdirs() }

    /**
     * Real cwd for shell commands when the active workspace is a SAF folder.
     * Lives on shared storage (external files dir) so the Shizuku shell uid can
     * enter it too, so the old private-storage location is migrated once.
     */
    val shellFallbackRoot: File = run {
        val newDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "shell-workspace")
        val oldDir = File(context.filesDir, "shell-workspace")
        if (oldDir.exists() && !newDir.exists() && oldDir != newDir) {
            runCatching { oldDir.renameTo(newDir) }
        }
        newDir.apply { mkdirs() }
    }

    /**
     * The app's private data directory (/data/user/0/<pkg>). Processes that run
     * as other uids (Shizuku's shell uid) cannot enter it, so shell routing
     * uses this to decide which tier can reach the working directory.
     */
    val internalDataRoot: File = context.dataDir

    init {
        // An older build left these world-writable; restore private defaults.
        runCatching { Os.chmod(context.dataDir.absolutePath, 0x1C0 /* 0700 */) }
        runCatching { Os.chmod(context.filesDir.absolutePath, 0x1C0 /* 0700 */) }
        // Bug 1 fix: materialize the bundled Mozilla CA store into the prefix
        // so curl/python/git/node in the shell tier verify TLS by default.
        com.androidharness.app.tools.NetTls.ensureInstalled(prefix, context)
        // Bug 2 fix: provision the designated exec-capable scratch dirs.
        runCatching { ensureScratchDirs() }
    }

    private val _state = MutableStateFlow<EnvState>(
        if (marker.exists()) EnvState.Ready else EnvState.NotInstalled
    )
    val state: StateFlow<EnvState> = _state

    val isReady: Boolean get() = _state.value is EnvState.Ready

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Base packages for a usable coding shell (git pulls its own deps). */
    val corePackages = listOf("bash", "busybox", "ca-certificates", "git")

    /** Everything a coding agent may need, used by the chat install card. */
    val fullPackages = corePackages + listOf("python", "python-pip", "nodejs", "npm")

    /** Installs [wanted] plus their full dependency closure. Resumes after interruptions. */
    suspend fun install(wanted: List<String>) {
        if (_state.value is EnvState.Ready && wanted.all { installedContains(it) }) return
        withContext(Dispatchers.IO) {
            try {
                val indexText = fetchText(indexUrl())
                val index = PackageIndex.parse(indexText)
                val already = installedPackages().toSet()
                val closure = resolve(index, wanted).filter { it.name !in already }
                if (closure.isEmpty()) {
                    markInstalled(wanted)
                    _state.value = EnvState.Ready
                    return@withContext
                }

                _state.value = EnvState.Downloading(0, closure.size, closure.first().name)
                closure.forEachIndexed { i, pkg ->
                    _state.value = EnvState.Downloading(i, closure.size, pkg.name)
                    val debFile = File(tempDir, pkg.filename.substringAfterLast('/'))
                    downloadVerified("$BASE_URL/${pkg.filename}", debFile, pkg.sha256)
                    _state.value = EnvState.Installing(i, closure.size, pkg.name)
                    extractDeb(debFile)
                    debFile.delete()
                    // record progress per package so a killed install resumes
                    markInstalled(listOf(pkg.name))
                }

                File(prefix, "home").mkdirs()
                File(prefix, "tmp").mkdirs()
                File(prefix, "etc/termux").mkdirs()
                markInstalled(wanted)
                ensureShims()
                _state.value = EnvState.Ready
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _state.value = EnvState.Failed(e.message ?: "Install failed")
            }
        }
    }

    private fun markInstalled(names: List<String>) {
        val current = installedPackages().toMutableSet()
        current.addAll(names)
        marker.writeText(current.joinToString("\n"))
    }

    suspend fun uninstall() = withContext(Dispatchers.IO) {
        runCatching { prefix.deleteRecursively() }
        runCatching { stagingDir.deleteRecursively() }
        _state.value = EnvState.NotInstalled
    }

    fun installedPackages(): List<String> =
        runCatching { marker.readText().lines().filter { it.isNotBlank() } }.getOrDefault(emptyList())

    private fun installedContains(name: String): Boolean = installedPackages().any { it == name }

    /** Package name → binaries proving it is actually usable (any-of). */
    private val BINARY_PROOF: Map<String, List<String>> = mapOf(
        "bash" to listOf("bin/bash"),
        "busybox" to listOf("bin/busybox"),
        "git" to listOf("bin/git"),
        "python" to listOf("bin/python3", "bin/python"),
        "python-pip" to listOf("bin/pip", "bin/pip3"),
        "nodejs" to listOf("bin/node"),
        "npm" to listOf("bin/npm"),
        "ca-certificates" to listOf("etc/tls/cacert.pem"),
    )

    /**
     * What is missing from the installed environment, human-readable. Checks
     * BOTH the package marker and the binaries on disk, so a marker entry whose
     * binary vanished counts as missing (that was the silent no-op bug).
     */
    fun checkMissing(): String {
        val installed = installedPackages().toSet()
        val missingPkgs = fullPackages.filter { it !in installed }
        val missingBins = BINARY_PROOF.filterKeys { it in installed || it in fullPackages }
            .filterValues { progs -> progs.none { File(prefix, it).exists() } }
            .keys.toList()
        return when {
            missingPkgs.isEmpty() && missingBins.isEmpty() ->
                "All present: bash, git, python, pip, node, npm."
            else -> buildString {
                if (missingPkgs.isNotEmpty()) {
                    append("Missing packages: ").append(missingPkgs.joinToString(", "))
                }
                if (missingBins.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append("Broken (marked installed but binary gone): ")
                        .append(missingBins.joinToString(", "))
                }
            }
        }
    }

    /**
     * Update / check-missing: installs anything absent, and REINSTALLS
     * packages whose marker entry exists but whose binaries are gone (a
     * half-broken prefix never repaired itself before, so the button looked
     * dead). Returns true when the environment is Ready afterwards.
     */
    suspend fun updateEnvironment(): Boolean = withContext(Dispatchers.IO) {
        val installed = installedPackages().toSet()
        val broken = installed.filter { pkg ->
            BINARY_PROOF[pkg]?.let { progs -> progs.none { File(prefix, it).exists() } } ?: false
        }
        if (broken.isNotEmpty()) {
            // Un-mark the broken ones so install() re-downloads them.
            marker.writeText(installed.filter { it !in broken }.joinToString("\n"))
        }
        install(fullPackages)
        isReady
    }

    // ------------------------------------------------------------------
    // Self-heal for prefixes installed by older builds
    //
    // Two legacy gaps: bundles installed before npm joined fullPackages
    // (node present, npm missing) and symlinks extracted verbatim from
    // termux packages pointing into /data/data/com.termux. Both are fixed
    // in place on app start; healthy prefixes are untouched.
    // ------------------------------------------------------------------

    /** True when an installed prefix has node but is missing npm. */
    fun needsRepair(): Boolean =
        marker.exists() &&
            File(prefix, "bin/node").exists() &&
            !File(prefix, "bin/npm").exists()

    /** Repoints dangling termux-absolute symlinks in bin/ and bin/applets. */
    private fun repairLegacySymlinks(): Int {
        var fixed = 0
        for (dir in listOf(File(prefix, "bin"), File(prefix, "bin/applets"))) {
            dir.listFiles()?.forEach { f ->
                val link = runCatching { Os.readlink(f.absolutePath) }.getOrNull() ?: return@forEach
                val rewritten = TermuxLinkRewrite.relativeTarget(link, f.toRelativeString(prefix)) ?: return@forEach
                runCatching {
                    Os.remove(f.absolutePath)
                    Os.symlink(rewritten, f.absolutePath)
                }.onSuccess { fixed++ }
            }
        }
        return fixed
    }

    /**
     * Bug 3 fix (existing installs): delete leftover Termux wrapper scripts
     * in bin/ (pm, cmd, am, settings, ...) whose shebang points into the
     * Termux prefix. Fresh installs never get them (filtered at extract);
     * prefixes installed before the fix are cleaned here on app start so
     * the real /system binaries stop being shadowed with exit 126.
     */
    private fun purgeDeadTermuxShims(): Int {
        var removed = 0
        val binDir = File(prefix, "bin")
        binDir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            if (f.isElf()) return@forEach
            val firstLine = runCatching { f.bufferedReader().use { it.readLine() } }.getOrNull() ?: return@forEach
            if (firstLine.trimStart().startsWith("#!/data/data/com.termux/")) {
                if (runCatching { f.delete() }.getOrDefault(false)) removed++
            }
        }
        return removed
    }

    /**
     * One-shot repair for existing installs: relink dangling symlinks and
     * install npm when the nodejs-only bundle predates it. A network failure
     * never demotes a Ready environment to Failed: the state is restored and
     * repair retries on the next app start. Returns a summary, or null when
     * nothing needed fixing.
     */
    suspend fun repairIfNeeded(): String? {
        runCatching { gitGlobalConfig() }
        if (!marker.exists()) return null
        return withContext(Dispatchers.IO) {
            val relinked = repairLegacySymlinks()
            val purged = purgeDeadTermuxShims()
            val npmMissing = needsRepair()
            if (relinked == 0 && purged == 0 && !npmMissing) return@withContext null
            var npmNote: String? = null
            if (npmMissing) {
                val wasReady = _state.value is EnvState.Ready
                // install() catches its own errors into EnvState.Failed, so
                // restore Ready here when the env was healthy before repair.
                runCatching { install(fullPackages) }
                if (_state.value is EnvState.Failed && wasReady) _state.value = EnvState.Ready
                npmNote = if (File(prefix, "bin/npm").exists()) "npm installed"
                else "npm still missing (retry on next launch)"
            }
            ensureShims()
            buildString {
                if (relinked > 0) append("relinked ").append(relinked).append(" dangling symlinks")
                if (purged > 0) {
                    if (isNotEmpty()) append("; ")
                    append("removed ").append(purged).append(" dead Termux shim(s) shadowing system binaries")
                }
                if (npmNote != null) append(if (isEmpty()) "" else "; ").append(npmNote)
            }.ifBlank { null }
        }
    }

    /** Environment for spawned processes (PATH/LD_LIBRARY_PATH/HOME/…). */
    fun processEnv(): Map<String, String> = buildMap {
        put("PATH", "${prefix.absolutePath}/bin:${prefix.absolutePath}/bin/applets:/system/bin:/system/xbin:/vendor/bin")
        put("LD_LIBRARY_PATH", "${prefix.absolutePath}/lib")
        put("HOME", "${prefix.absolutePath}/home")
        put("TMPDIR", "${prefix.absolutePath}/tmp")
        put("PREFIX", prefix.absolutePath)
        put("TERM", "xterm-256color")
        put("LANG", "C.UTF-8")
        // The bundled (Termux-built) git warns "templates not found" on every
        // init because it looks under its old build prefix. Point it at the
        // templates shipped in our prefix, or disable templates if absent.
        put("GIT_TEMPLATE_DIR", gitTemplatesDir().absolutePath)
        // Same re-rooting problem for git's exec helpers (git-remote-https,
        // git-upload-pack…): its compiled-in exec path points at the old
        // Termux prefix. Without this, HTTPS clones die with
        // "remote helper 'https' aborted session".
        put("GIT_EXEC_PATH", File(prefix, "libexec/git-core").absolutePath)
        // Bug 5 fix: a generated global config marks every repo safe, so
        // plain shell git inside a uid=2000-owned checkout never hits
        // "detected dubious ownership". Identity is NOT set here: the
        // git_commit tool already auto-configures it per repo.
        put("GIT_CONFIG_GLOBAL", gitGlobalConfig().absolutePath)
        put("HARNESS_GIT_CONFIG", gitGlobalConfig().absolutePath)
        // bash sources this for `bash -c`: shims make every toolchain binary
        // runnable despite the W^X exec restriction on app-private files.
        if (shimFile.exists()) put("BASH_ENV", shimFile.absolutePath)
        putAll(tlsEnvVars())
        // Bug 2 fix: tell every spawned shell where exec-capable scratch lives.
        // This env serves APP-uid processes: they cannot write /data/local/tmp
        // (SELinux), so they get the app-private mirror; the privileged tier's
        // tmpProcessEnv exports the shared tmp scratch instead.
        put("HARNESS_SCRATCH", appPrivateScratch.absolutePath)
    }

    // ------------------------------------------------------------------
    // Bug 2 fix: exec-capable scratch dirs
    //
    // The workspace usually lives on shared storage (FUSE), which does not
    // preserve POSIX exec bits (chmod +x is a no-op: files stay -rw-rw----)
    // and cannot host symlinks, so JDK/Gradle/native binaries extracted there
    // fail with "Permission denied" and tarballs containing symlinks fail to
    // extract. These scratch dirs live on filesystems that support both:
    //
    //  - SCRATCH_TMP (/data/local/tmp/androidharness-scratch): world-writable
    //    tmpfs/ext4, which works for BOTH the app uid and the Shizuku shell uid.
    //  - the app-private mirror under /data/data/<pkg>/files/.harness-scratch:
    //    fallback when SELinux denies tmp access; the app-uid linker
    //    workaround makes binaries here runnable.
    //
    // The shell sandbox has a deliberate carve-out for exactly these paths
    // (ShellPolicy) and env vars advertise them to every spawned shell.
    // ------------------------------------------------------------------

    /** Creates and permission-opens the exec-capable scratch dirs. Idempotent. */
    fun ensureScratchDirs() {
        // Shared location: both the app uid and the Shizuku shell uid can use
        // it, so 0777 (only reachable via adb/shizuku on real devices).
        val tmpScratch = File(ShellPolicy.SCRATCH_TMP)
        tmpScratch.mkdirs()
        runCatching { Os.chmod(tmpScratch.absolutePath, 0x1FF /* 0777 */) }
        // App-private fallback for this build's package id: the ONLY location
        // the app uid can reliably write (SELinux denies app-writes to
        // /data/local/tmp even with mode 777).
        appPrivateScratch.mkdirs()
    }

    /**
     * Scratch dir usable by THIS app's uid for direct writes (tar extraction
     * etc.). App-private, so both package flavors map to their own copy.
     */
    val appPrivateScratch: File = File(context.filesDir, ".harness-scratch")

    /**
     * TLS trust vars (Bug 1 fix): point curl/python/git/node at the CA bundle
     * materialized into the prefix. Falls back to the system store path when
     * the bundled asset could not be provisioned.
     */
    fun tlsEnvVars(): Map<String, String> {
        val dir = runCatching { File(prefix, "etc/tls") }.getOrNull()
        val preferred = File(prefix, com.androidharness.app.tools.NetTls.BUNDLE_RELATIVE_PATH)
        val path = when {
            preferred.isFile -> preferred.absolutePath
            dir != null && dir.isDirectory ->
                File(dir, "cacert.pem").absolutePath
            else -> "/system/etc/security/cacerts" // last resort: anchors dir hint
        }
        return com.androidharness.app.tools.NetTls.envVars(path)
    }

    /** Empty (templates disabled) when the prefix has no git-core templates. */
    private fun gitTemplatesDir(): File =
        File(prefix, "share/git-core/templates").takeIf { it.isDirectory } ?: File("")

    /**
     * Bug 5 fix: global git config marking every repository safe.
     * Created once per app start (and repaired if deleted), so plain shell
     * git in repos owned by another uid (Shizuku writes as uid 2000, the
     * app is u0_aXXX) never dies on "detected dubious ownership".
     */
    fun gitGlobalConfig(): File {
        val f = File(prefix, "etc/gitconfig")
        if (!f.exists() || !f.readText().contains("[safe]")) {
            f.parentFile?.mkdirs()
            f.writeText("[safe]\n\tdirectory = *\n")
        }
        return f
    }

    // ------------------------------------------------------------------
    // W^X workaround: per-binary linker shims
    //
    // On targetSdk 29+, execve() of app-data files is denied, so only the
    // outermost process can be launched via /system/bin/linker64. So a bare
    // `python3` or even `ls | head` from inside bash dies with EACCES. The
    // shim file defines a bash function per toolchain binary that re-routes
    // it through linker64, so plain command names work everywhere.
    // ------------------------------------------------------------------

    private val shimFile: File get() = File(prefix, "etc/harness-shims.sh")

    private fun linkerPath(): String? = when {
        File("/system/bin/linker64").exists() -> "/system/bin/linker64"
        File("/apex/com.android.runtime/bin/linker64").exists() -> "/apex/com.android.runtime/bin/linker64"
        File("/system/bin/linker").exists() -> "/system/bin/linker"
        else -> null
    }

    private fun File.isElf(): Boolean = runCatching {
        inputStream().use { ins ->
            val magic = ByteArray(4)
            ins.read(magic) == 4 &&
                magic[0] == 0x7f.toByte() && magic[1] == 'E'.code.toByte() &&
                magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()
        }
    }.getOrDefault(false)

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** (Re)generates the shim file. Idempotent; safe to call any time. */
    fun ensureShims() {
        runCatching {
            val linker = linkerPath() ?: return
            val binDir = File(prefix, "bin")
            if (!binDir.isDirectory) return
            val bash = File(binDir, "bash").absolutePath
            val sb = StringBuilder()
            sb.append("# auto-generated by AndroidHarness: do not edit\n")
            sb.append("# routes every bundled binary through the dynamic linker (W^X exec fix)\n")

            fun addShim(dir: File, file: File) {
                val name = file.name
                // function names must be sane identifiers
                if (!name.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.+:-]*"))) return
                val target = if (file.canonicalPath != file.absolutePath) File(file.canonicalPath) else file
                when {
                    target.name == "busybox" && name != "busybox" ->
                        // applet symlink → busybox dispatches on argv[0]/first arg
                        sb.append(name).append("() { command ").append(linker)
                            .append(' ').append(shellQuote(target.absolutePath))
                            .append(' ').append(shellQuote(name)).append(" \"$@\"; }\n")
                    target.isElf() ->
                        sb.append(name).append("() { command ").append(linker)
                            .append(' ').append(shellQuote(target.absolutePath)).append(" \"$@\"; }\n")
                    else -> {
                        val firstLine = runCatching {
                            target.bufferedReader().use { it.readLine() }
                        }.getOrNull()?.trim() ?: ""
                        val interp = when {
                            firstLine.contains("node") -> {
                                val nodeBin = File(binDir, "node")
                                if (nodeBin.exists()) nodeBin.absolutePath else bash
                            }
                            firstLine.contains("python") -> {
                                val pyBin = File(binDir, "python3").takeIf { it.exists() }
                                    ?: File(binDir, "python")
                                if (pyBin.exists()) pyBin.absolutePath else bash
                            }
                            else -> bash
                        }
                        sb.append(name).append("() { command ").append(linker)
                            .append(' ').append(shellQuote(interp))
                            .append(' ').append(shellQuote(target.absolutePath)).append(" \"$@\"; }\n")
                    }
                }
            }

            binDir.listFiles()?.forEach { if (it.isFile || it.canonicalPath != it.absolutePath) addShim(binDir, it) }
            File(prefix, "bin/applets").listFiles()?.forEach { addShim(File(prefix, "bin/applets"), it) }

            shimFile.parentFile?.mkdirs()
            shimFile.writeText(sb.toString())
        }
    }

    /**
     * Environment for the Shizuku (shell/root uid) tier, whose copy of the
     * toolchain lives at /data/local/tmp/androidharness/linux.
     */
    fun tmpProcessEnv(): Map<String, String> = buildMap {
        put("PATH", "$TMP_PREFIX/bin:$TMP_PREFIX/bin/applets:/system/bin:/system/xbin:/vendor/bin")
        put("LD_LIBRARY_PATH", "$TMP_PREFIX/lib")
        put("HOME", "$TMP_PREFIX/home")
        put("TMPDIR", "$TMP_PREFIX/tmp")
        put("PREFIX", TMP_PREFIX)
        put("TERM", "xterm-256color")
        put("LANG", "C.UTF-8")
        // Same templates fix as the app-side env, for the /data/local/tmp copy.
        val templates = File("$TMP_PREFIX/share/git-core/templates")
        put("GIT_TEMPLATE_DIR", if (templates.isDirectory) templates.absolutePath else "")
        // Re-rooted git needs its exec helpers (git-remote-https etc.) pointed
        // at our deployed copy or HTTPS remotes abort with a missing helper.
        put("GIT_EXEC_PATH", "$TMP_PREFIX/libexec/git-core")
        // Bug 5 fix: same safe.directory global config for the shell-uid
        // tier, written under the deployed prefix.
        put("GIT_CONFIG_GLOBAL", "$TMP_PREFIX/etc/gitconfig")
        put("HARNESS_GIT_CONFIG", "$TMP_PREFIX/etc/gitconfig")
        // Bug 1 fix: the deployed copy carries its own CA bundle; export the
        // standard TLS vars so curl/python/git/node verify certificates.
        val tlsBundle = File("$TMP_PREFIX/etc/tls/cacert.pem")
        putAll(
            com.androidharness.app.tools.NetTls.envVars(
                if (tlsBundle.isFile) tlsBundle.absolutePath else "/system/etc/security/cacerts",
            ),
        )
        // Bug 2 fix: exec-capable scratch location for the privileged tier.
        put("HARNESS_SCRATCH", ShellPolicy.SCRATCH_TMP)
    }

    // ------------------------------------------------------------------
    // Deploy to the Shizuku (shell-uid) tier
    //
    // SELinux blocks the shell uid from reading the app's private data dir
    // entirely (chmod cannot fix it), and /sdcard is mounted noexec, so the
    // only working path is: prefix → tar.gz on shared storage (shell-readable)
    // → Shizuku untars into /data/local/tmp/androidharness (shell-exec-able).
    // ------------------------------------------------------------------

    private val stagingDir: File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "linux-deploy")
    private val stagingTar: File get() = File(stagingDir, "prefix.tar.gz")
    private val stagingMarker: File get() = File(stagingDir, ".harness-staged")

    /** Hash of the installed package set, so it re-stages/re-deploys when it changes. */
    fun packageSetHash(): String = ("v5-safegit\n" + installedPackages().sorted().joinToString("\n"))
        .let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }

    /** Writes (or refreshes) the staging tarball on shared storage. */
    fun stageForShell() {
        runCatching {
            if (!File(prefix, "bin/bash").exists()) return
            // Bug 5 fix: the safe.directory config must exist before the
            // prefix is tarred, or the deployed copy exports a missing file.
            runCatching { gitGlobalConfig() }
            val hash = packageSetHash()
            if (stagingMarker.exists() && stagingMarker.readText() == hash && stagingTar.exists()) return
            stagingDir.mkdirs()
            // Bug 1 fix: ship the CA bundle next to the tarball. The staging
            // dir lives on shared storage where the shell uid can read it, so
            // the privileged deploy can install the trust anchors as well.
            runCatching {
                val dst = File(stagingDir, "etc/tls/cacert.pem")
                dst.parentFile?.mkdirs()
                val src = File(prefix, com.androidharness.app.tools.NetTls.BUNDLE_RELATIVE_PATH)
                if (src.isFile) {
                    if (!dst.isFile || dst.length() != src.length()) src.copyTo(dst, overwrite = true)
                } else {
                    context.assets.open(com.androidharness.app.tools.NetTls.ASSET_PATH).use { input ->
                        java.io.FileOutputStream(dst).use { out -> input.copyTo(out) }
                    }
                }
            }
            val tmp = File(stagingDir, "prefix.tar.gz.tmp")
            org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(
                java.util.zip.GZIPOutputStream(tmp.outputStream().buffered()),
            ).use { tar ->
                tar.setLongFileMode(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream.LONGFILE_POSIX)
                writeTarEntries(prefix, "linux", tar)
            }
            tmp.renameTo(stagingTar)
            stagingMarker.writeText(hash)
        }
    }

    private fun writeTarEntries(
        dir: File,
        base: String,
        tar: org.apache.commons.compress.archivers.tar.TarArchiveOutputStream,
    ) {
        dir.listFiles()?.forEach { child ->
            val name = "$base/${child.name}"
            val link = runCatching { Os.readlink(child.absolutePath) }.getOrNull()
            val entry = when {
                link != null -> org.apache.commons.compress.archivers.tar.TarArchiveEntry(
                    name,
                    org.apache.commons.compress.archivers.tar.TarArchiveEntry.LF_SYMLINK,
                ).apply {
                    linkName = link
                }
                child.isDirectory ->
                    // tar marks directories with a trailing slash, without which
                    // they extract as 0-byte files and children can't unpack.
                    org.apache.commons.compress.archivers.tar.TarArchiveEntry("$name/")
                else -> org.apache.commons.compress.archivers.tar.TarArchiveEntry(child, name)
            }
            entry.mode = when {
                link != null -> 0x1FF // 0777
                child.isDirectory -> 0x1ED // 0755
                child.canExecute() -> 0x1ED // 0755
                else -> 0x1A4 // 0644
            }
            if (child.isDirectory && link == null) {
                tar.putArchiveEntry(entry)
                tar.closeArchiveEntry()
                writeTarEntries(child, name, tar)
            } else {
                tar.putArchiveEntry(entry)
                if (link == null && child.isFile) child.inputStream().use { it.copyTo(tar) }
                tar.closeArchiveEntry()
            }
        }
    }

    /**
     * Ensures the shell-user toolchain exists at /data/local/tmp/androidharness:
     * stages the prefix as a tarball on shared storage, then has Shizuku untar
     * it. No-op when the deployed copy matches the current package set.
     */
    private val deployLock = kotlinx.coroutines.sync.Mutex()

    suspend fun ensureShellDeploy(shizuku: ShizukuManager): Boolean {
        if (!isReady) return false
        return deployLock.withLock {
            stageForShell()
            if (!stagingTar.exists()) return@withLock false
            shizuku.ensureTmpPrefix(stagingTar.absolutePath, packageSetHash())
        }
    }

    /** bash if installed, else null (callers fall back to toybox sh). */
    fun bashExecutable(): File? {
        val bash = File(prefix, "bin/bash")
        return bash.takeIf { it.canExecute() }
    }

    /**
     * Builds a shell process for [command]. Prefix binaries are launched
     * through the system dynamic linker: Android often refuses execve() on
     * app-data files (EACCES) even when the exec bits are set, while
     * linker64/linker map the ELF themselves, which is always permitted.
     * Falls back to direct exec, and toybox sh remains the last resort for
     * callers when the environment is not installed.
     */
    fun shellProcessBuilder(command: String): ProcessBuilder {
        val bash = bashExecutable()
        if (bash != null) {
            val linker = when (Build.SUPPORTED_ABIS.firstOrNull()) {
                "x86_64", "arm64-v8a" -> "/system/bin/linker64"
                else -> "/system/bin/linker"
            }
            val useLinker = File(linker).exists()
            val builder = if (useLinker) {
                ProcessBuilder(linker, bash.absolutePath, "-c", command)
            } else {
                ProcessBuilder(bash.absolutePath, "-c", command)
            }
            return builder.apply { environment().putAll(processEnv()) }
        }
        return ProcessBuilder("sh", "-c", command)
    }

    /**
     * Starts [command] with the best available shell: linker-launched bash,
     * then direct bash, then toybox sh. Never throws; [fallbackUsed] reports
     * which tier ended up running.
     */
    fun startShell(command: String, cwd: File): Pair<Process, ShellTier> {
        val envAvailable = bashExecutable() != null
        if (envAvailable && !shimFile.exists()) ensureShims()
        val linker = when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "x86_64", "arm64-v8a" -> "/system/bin/linker64"
            else -> "/system/bin/linker"
        }
        if (envAvailable && File(linker).exists()) {
            val p = runCatching {
                ProcessBuilder(linker, bashExecutable()!!.absolutePath, "-c", command)
                    .directory(cwd)
                    .apply { environment().putAll(processEnv()) }
                    .start()
            }.getOrNull()
            if (p != null) return p to ShellTier.LINUX
        }
        if (envAvailable) {
            val p = runCatching {
                ProcessBuilder(bashExecutable()!!.absolutePath, "-c", command)
                    .directory(cwd)
                    .apply { environment().putAll(processEnv()) }
                    .start()
            }.getOrNull()
            if (p != null) return p to ShellTier.LINUX
        }
        return ProcessBuilder("sh", "-c", command).directory(cwd).start() to ShellTier.TOYBOX
    }

    enum class ShellTier { LINUX, TOYBOX }

    // ------------------------------------------------------------------

    private fun abiName(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "aarch64"
        "x86_64" -> "x86_64"
        "armeabi-v7a" -> "arm"
        "x86" -> "i686"
        else -> "aarch64"
    }

    private fun indexUrl(): String = "$BASE_URL/dists/stable/main/binary-${abiName()}/Packages"

    private fun fetchText(url: String): String =
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} fetching repository index")
            resp.body?.string() ?: throw IllegalStateException("Empty repository index")
        }

    private fun resolve(index: Map<String, PkgMeta>, wanted: List<String>): List<PkgMeta> {
        val visited = LinkedHashSet<String>()
        fun visit(name: String) {
            if (name in visited) return
            val meta = index[name] ?: return
            meta.depends.forEach { visit(it) }
            visited += name
        }
        wanted.forEach { visit(it) }
        return visited.mapNotNull { index[it] }
    }

    private fun downloadVerified(url: String, dest: File, expectedSha256: String) {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} downloading $url")
            val body = resp.body ?: throw IllegalStateException("Empty download")
            val digest = MessageDigest.getInstance("SHA-256")
            dest.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (expectedSha256.isNotBlank() && !actual.equals(expectedSha256, ignoreCase = true)) {
                dest.delete()
                throw IllegalStateException("Checksum mismatch for ${dest.name}")
            }
        }
    }

    private fun extractDeb(deb: File) {
        ArArchiveInputStream(deb.inputStream().buffered()).use { ar ->
            var entry = ar.nextEntry
            while (entry != null && !entry.name.startsWith("data.tar")) {
                entry = ar.nextEntry
            }
            if (entry == null) throw IllegalStateException("No data archive in ${deb.name}")
            // Choose the decompressor from the extension, since auto-detection would
            // need a markable stream, which ArArchiveInputStream is not.
            val name = entry.name
            val input: java.io.InputStream = when {
                name.endsWith(".xz") || name.endsWith(".lzma") ->
                    CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.XZ, ar)
                name.endsWith(".gz") ->
                    CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.GZIP, ar)
                name.endsWith(".bz2") ->
                    CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.BZIP2, ar)
                name.endsWith(".zst") ->
                    CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.ZSTANDARD, ar)
                else -> ar // plain tar
            }
            TarArchiveInputStream(input).use { tar ->
                while (true) {
                    val e = tar.nextTarEntry ?: break
                    var rel = e.name.removePrefix("./").removePrefix("/")
                    val termuxPrefix = "data/data/com.termux/files/usr"
                    if (rel.startsWith(termuxPrefix)) {
                        rel = rel.removePrefix(termuxPrefix).removePrefix("/")
                    }
                    if (rel.isEmpty() || rel.startsWith("data/data")) continue

                    val target = File(prefix, rel)
                    when {
                        e.isDirectory -> target.mkdirs()
                        e.isSymbolicLink -> {
                            target.parentFile?.mkdirs()
                            // Termux packages carry absolute symlinks into their
                            // own build prefix; rewrite them into our prefix or
                            // they arrive dangling (bzcmp, bzless, …).
                            val linkName = TermuxLinkRewrite.relativeTarget(e.linkName, rel) ?: e.linkName
                            runCatching { Os.symlink(linkName, target.absolutePath) }
                        }
                        else -> {
                            target.parentFile?.mkdirs()
                            // Bug 3 fix: Termux wrapper scripts (bin/pm, bin/cmd,
                            // bin/am, bin/settings, ...) carry a dead shebang into
                            // the Termux prefix (/data/data/com.termux/files/usr/bin/sh).
                            // In the shell-uid tier the prefix bin dir is first on
                            // PATH, so these shadows die with exit 126 "bad
                            // interpreter". Drop non-ELF bin entries whose first
                            // line targets the Termux prefix: the real /system
                            // binaries win instead. Peek is stream-safe: the bytes
                            // read are written back when the entry is kept.
                            var skipWrite = false
                            val peeked = ByteArray(256)
                            if (rel.startsWith("bin/") && !rel.contains("/applets/")) {
                                val n = runCatching { tar.read(peeked, 0, peeked.size) }.getOrDefault(-1)
                                val firstLine = if (n > 0)
                                    String(peeked, 0, n, Charsets.UTF_8).lineSequence().firstOrNull()?.trim() ?: ""
                                else ""
                                if (firstLine.startsWith("#!/data/data/com.termux/")) {
                                    // drain the rest of the entry without writing it
                                    tar.copyTo(object : java.io.OutputStream() {
                                        override fun write(b: Int) {}
                                    })
                                    skipWrite = true
                                } else if (n > 0) {
                                    FileOutputStream(target).use { out ->
                                        out.write(peeked, 0, n)
                                        tar.copyTo(out)
                                    }
                                    runCatching { Os.chmod(target.absolutePath, e.mode.toInt() and 0xFFF) }
                                    skipWrite = true
                                } // n <= 0: empty entry, fall through to the plain writer
                            }
                            if (skipWrite) continue
                            FileOutputStream(target).use { out -> tar.copyTo(out) }
                            runCatching { Os.chmod(target.absolutePath, e.mode.toInt() and 0xFFF) }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val BASE_URL = "https://packages.termux.dev/apt/termux-main"

        /** Where the shell-user copy of [PREFIX] lives (exec-able by shell uid). */
        const val TMP_PREFIX_BASE = "/data/local/tmp/androidharness"
        private const val TMP_PREFIX = "$TMP_PREFIX_BASE/linux"
    }
}
