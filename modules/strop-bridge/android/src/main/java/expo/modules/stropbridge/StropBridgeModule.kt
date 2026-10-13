package expo.modules.stropbridge

import android.content.Context
import android.os.Build
import android.util.Log
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.functions.Coroutine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "StropBridge"

class StropBridgeModule : Module() {
  private val prootManager by lazy {
    val ctx = appContext.reactContext ?: throw IllegalStateException("React context not available")
    ProotManager(ctx)
  }
  private val processes = ConcurrentHashMap<String, Process>()
  @Volatile private var destroyed = false

  private fun java.lang.Process.getPid(): Int {
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        (this as Any).javaClass.getMethod("pid").invoke(this) as Int
      } else {
        val f = this.javaClass.getDeclaredField("pid")
        f.isAccessible = true
        f.getInt(this)
      }
    } catch (e: Exception) {
      -1
    }
  }

  override fun definition() = ModuleDefinition {
    Name("StropBridge")

    Events(
      "onProotStarted",
      "onProotStopped",
      "onBootstrapOutput"
    )

    OnDestroy {
      destroyed = true
      processes.values.forEach { it.destroyForcibly() }
      processes.clear()
    }

    AsyncFunction("sha256") Coroutine { path: String ->
      withContext(Dispatchers.IO) {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        java.io.FileInputStream(path).use { fis ->
          val buffer = ByteArray(65536)
          var read: Int
          while (fis.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
          }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
      }
    }

    AsyncFunction("initialize") Coroutine { rootfsPath: String, rootfsSha256: String, rootfsVersion: String ->
      prootManager.ensureDirectories()

      if (prootManager.isInitialized && !prootManager.isRootfsCurrent(rootfsVersion)) {
        prootManager.clearBootstrapState()
      }

      val extracted = prootManager.extractRootfs(rootfsPath, rootfsSha256)
      if (!extracted) {
        throw CodedException(
          "ERR_ROOTFS_EXTRACT",
          "Rootfs extraction failed (checksum mismatch or extraction error). " +
            "Check logs for details.",
          null
        )
      }

      prootManager.markInitialized(rootfsVersion)
      true
    }

    AsyncFunction("bootstrap") Coroutine { ->
      if (!prootManager.isInitialized) {
        throw CodedException("ERR_NOT_INITIALIZED", "Call initialize() first", null)
      }
      val ctx = appContext.reactContext ?: throw CodedException("ERR_NO_CONTEXT", "React context lost", null)
      StropForegroundService.start(ctx, "Bootstrapping Linux environment...")

      var ok = false
      try {
        ok = prootManager.bootstrap { line ->
          sendEvent("onBootstrapOutput", mapOf("line" to line))
        }
        if (!ok) {
          throw CodedException("ERR_BOOTSTRAP", "Bootstrap failed — see onBootstrapOutput events", null)
        }
        true
      } finally {
        if (!ok || processes.isEmpty()) {
          StropForegroundService.stop(ctx)
        }
      }
    }

    Function("isInitialized") { prootManager.isInitialized }

    Function("isBootstrapped") { prootManager.isBootstrapped() }

    Function("readBootstrapLog") { prootManager.readBootstrapLog() }

    AsyncFunction("startProot") Coroutine { sessionName: String? ->
      val ctx = appContext.reactContext ?: throw CodedException("ERR_NO_CONTEXT", "React context lost", null)

      if (!prootManager.isInitialized) {
        throw CodedException("ERR_NOT_INITIALIZED", "Call initialize() first", null)
      }

      prootManager.ensureDirectories()

      val key = sessionName?.ifBlank { null } ?: "default"
      val existing = processes[key]
      if (existing != null && existing.isAlive) {
        return@Coroutine mapOf("success" to true, "pid" to existing.getPid())
      }

      val cmd = prootManager.buildProotCommand(key, fakeRoot = false)
      val env = prootManager.buildEnv() + prootManager.buildProotEnv()
      val logFile = File(prootManager.filesRoot, "session-$key.log")
      val proc = ProcessBuilder(cmd)
        .directory(prootManager.filesRoot)
        .apply { environment().putAll(env) }
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        .start()

      val prev = processes.putIfAbsent(key, proc)
      if (prev != null && prev.isAlive) {
        proc.destroyForcibly()
        return@Coroutine mapOf("success" to true, "pid" to prev.getPid())
      }
      processes[key] = proc
      StropForegroundService.start(ctx, "Linux session: $key")

      if (!destroyed) {
        sendEvent(
          "onProotStarted",
          mapOf("sessionName" to key, "pid" to proc.getPid())
        )
      }

      Thread {
        try {
          val exitCode = proc.waitFor()
          processes.remove(key, proc)
          if (!destroyed) {
            sendEvent(
              "onProotStopped",
              mapOf("sessionName" to key, "exitCode" to exitCode)
            )
          }
          val ctx2 = appContext.reactContext
          if (ctx2 != null && processes.isEmpty()) {
            StropForegroundService.stop(ctx2)
          }
        } catch (e: InterruptedException) { /* destroyed */ }
      }.start()

      mapOf("success" to true, "pid" to proc.getPid())
    }

    Function("stopProot") {
      for ((key, proc) in processes.entries) {
        val socketPath = "/tmp/tmux-${key}.sock"
        try {
          val killCmd = prootManager.buildProotCommand(null, fakeRoot = false).toMutableList()
          killCmd.removeAt(killCmd.size - 1)
          killCmd.addAll(listOf("tmux", "-S", socketPath, "kill-server"))
          val p = ProcessBuilder(killCmd)
            .directory(prootManager.filesRoot)
            .apply { environment().putAll(prootManager.buildEnv() + prootManager.buildProotEnv()) }
            .start()
          p.waitFor(5, TimeUnit.SECONDS)
        } catch (_: Exception) {}
        proc.destroy()
        if (!proc.waitFor(3, TimeUnit.SECONDS)) {
          proc.destroyForcibly()
        }
      }
      processes.clear()
      appContext.reactContext?.let { StropForegroundService.stop(it) }
      true
    }

    Function("getProotStatus") {
      mapOf(
        "running" to processes.isNotEmpty(),
        "sessions" to processes.keys.toList()
      )
    }

    AsyncFunction("execCommand") Coroutine { cmd: String, cwd: String ->
      prootManager.ensureDirectories()
      val wrapped = buildString {
        append("cd ")
        append(shellQuote(cwd))
        append(" && ")
        append(cmd)
      }
      val env = prootManager.buildEnv() + prootManager.buildProotEnv()
      val pb = ProcessBuilder(prootManager.buildProotCommand(null))
        .directory(prootManager.filesRoot)
        .apply { environment().putAll(env) }
        .start()
      try {
        pb.outputStream.use { it.write((wrapped + "\nexit\n").toByteArray()) }
        val stdout = pb.inputStream.bufferedReader().use { it.readText() }
        val stderr = pb.errorStream.bufferedReader().use { it.readText() }
        val finished = pb.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
          pb.destroyForcibly()
          throw CodedException("ERR_TIMEOUT", "Command timed out after 30s", null)
        }
        mapOf("stdout" to stdout, "stderr" to stderr, "exitCode" to pb.exitValue())
      } finally {
        if (pb.isAlive) pb.destroyForcibly()
      }
    }

    Function("readFile") { path: String ->
      val f = File(prootManager.rootfsDir, path.removePrefix("/"))
      val canonical = f.canonicalPath
      if (!canonical.startsWith(prootManager.rootfsDir.canonicalPath + File.separator) && canonical != prootManager.rootfsDir.canonicalPath) {
        throw CodedException("ERR_EACCES", "Path escapes rootfs: $path", null)
      }
      if (!f.exists() || !f.isFile) throw CodedException("ERR_NOENT", "File not found: $path", null)
      if (f.length() > 5 * 1024 * 1024) throw CodedException("ERR_TOOBIG", "File too large: $path", null)
      f.readText()
    }

    Function("writeFile") { path: String, content: String ->
      val f = File(prootManager.rootfsDir, path.removePrefix("/"))
      val canonical = f.canonicalPath
      if (!canonical.startsWith(prootManager.rootfsDir.canonicalPath + File.separator) && canonical != prootManager.rootfsDir.canonicalPath) {
        throw CodedException("ERR_EACCES", "Path escapes rootfs: $path", null)
      }
      f.parentFile?.mkdirs()
      f.writeText(content)
      true
    }

    Function("listDir") { path: String ->
      val f = File(prootManager.rootfsDir, path.removePrefix("/"))
      val canonical = f.canonicalPath
      if (!canonical.startsWith(prootManager.rootfsDir.canonicalPath + File.separator) && canonical != prootManager.rootfsDir.canonicalPath) {
        throw CodedException("ERR_EACCES", "Path escapes rootfs: $path", null)
      }
      if (!f.exists() || !f.isDirectory) throw CodedException("ERR_NOENT", "Directory not found: $path", null)
      f.listFiles()?.map { it.name } ?: emptyList()
    }
  }

  private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
