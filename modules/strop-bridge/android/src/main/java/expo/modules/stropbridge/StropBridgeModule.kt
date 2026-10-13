package expo.modules.stropbridge

import android.content.Context
import android.util.Log
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.functions.Coroutine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "StropBridge"

class StropBridgeModule : Module() {
  private val prootManager by lazy { ProotManager(appContext.reactContext!!) }
  private val processes = ConcurrentHashMap<String, Process>()

  private fun Process.getPid(): Int {
    return try {
      val f = this.javaClass.getDeclaredField("pid")
      f.isAccessible = true
      f.getInt(this)
    } catch (e: Exception) {
      -1
    }
  }

  override fun definition() = ModuleDefinition {
    Name("StropBridge")

    Events(
      "onProotStarted",
      "onProotStopped",
      "onProotError",
      "onSessionOutput",
      "onSessionExited",
      "onBootstrapOutput"
    )

    OnDestroy {
      processes.values.forEach { it.destroy() }
      processes.clear()
    }

    Function("sha256") { path: String ->
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

    AsyncFunction("initialize") Coroutine { rootfsPath: String, rootfsSha256: String, rootfsVersion: String ->
      Dispatchers.IO
      prootManager.ensureDirectories()

      val extracted = prootManager.extractRootfs(rootfsPath, rootfsSha256)
      if (!extracted) {
        throw CodedException(
          "ERR_ROOTFS_EXTRACT",
          "Rootfs extraction failed (SHA256 verified OK before extraction). " +
          "Check proot logs for details.",
          null
        )
      }

      prootManager.markInitialized(rootfsVersion)
      true
    }

    AsyncFunction("bootstrap") Coroutine { ->
      Dispatchers.IO
      if (!prootManager.isInitialized) {
        throw CodedException("ERR_NOT_INITIALIZED", "Call initialize() first", null)
      }
      val ctx = appContext.reactContext ?: throw CodedException("ERR_NO_CONTEXT", "React context lost", null)
      StropForegroundService.start(ctx, "Bootstrapping Linux environment...")

      val ok = prootManager.bootstrap { line ->
        sendEvent("onBootstrapOutput", mapOf("line" to line))
      }
      if (!ok) {
        throw CodedException("ERR_BOOTSTRAP", "Bootstrap failed — see onBootstrapOutput events", null)
      }
      true
    }

    Function("isInitialized") {
      prootManager.isInitialized
    }

    AsyncFunction("startProot") Coroutine { sessionName: String? ->
      Dispatchers.IO
      val ctx = appContext.reactContext ?: throw CodedException("ERR_NO_CONTEXT", "React context lost", null)

      // Check initialization
      if (!prootManager.isInitialized) {
        throw CodedException("ERR_NOT_INITIALIZED", "Call initialize() first", null)
      }

      prootManager.ensureDirectories()

      val key = sessionName ?: "default"
      if (processes.containsKey(key)) {
        return@Coroutine mapOf("success" to true, "pid" to processes[key]?.getPid())
      }
      val cmd = prootManager.buildProotCommand(sessionName, fakeRoot = false)
      val env = prootManager.buildEnv() + prootManager.buildProotEnv()
      val proc = ProcessBuilder(cmd)
        .directory(prootManager.filesRoot)
        .apply { environment().putAll(env) }
        .start()
      processes[key] = proc
      StropForegroundService.start(ctx, "Linux session: ${sessionName ?: "default"}")

      sendEvent(
        "onProotStarted",
        mapOf("sessionName" to (sessionName ?: "default"), "pid" to proc.getPid())
      )

      Thread {
        try {
          val exitCode = proc.waitFor()
          processes.remove(key)
          sendEvent(
            "onProotStopped",
            mapOf("sessionName" to (sessionName ?: "default"), "exitCode" to exitCode)
          )
          val ctx2 = appContext.reactContext
          if (ctx2 != null && processes.isEmpty()) {
            StropForegroundService.stop(ctx2)
          }
        } catch (e: InterruptedException) { /* destroyed */ }
      }.start()

      mapOf("success" to true, "pid" to proc.getPid())
    }

    Function("stopProot") {
      processes.values.forEach { it.destroy() }
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
      Dispatchers.IO
      prootManager.ensureDirectories()
      val wrapped = buildString {
        append("cd ")
        append(shellQuote(cwd))
        append(" && ")
        append(cmd)
      }
      val env = prootManager.buildEnv() + prootManager.buildProotEnv()
      val pb = ProcessBuilder(prootManager.buildProotCommand(null))
        .apply { environment().putAll(env) }
        .redirectErrorStream(true)
      val proc = pb.start()
      try {
        proc.outputStream.use { it.write((wrapped + "\nexit\n").toByteArray()) }
        val stdout = proc.inputStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        mapOf("stdout" to stdout, "stderr" to "", "exitCode" to exitCode)
      } finally {
        proc.destroy()
      }
    }

    Function("readFile") { path: String ->
      val f = File(prootManager.rootfsDir, path.removePrefix("/"))
      if (!f.exists()) throw CodedException("ERR_NOENT", "File not found: $path", null)
      f.readText()
    }

    Function("writeFile") { path: String, content: String ->
      val f = File(prootManager.rootfsDir, path.removePrefix("/"))
      f.parentFile?.mkdirs()
      f.writeText(content)
      true
    }

    Function("listDir") { path: String ->
      val f = File(prootManager.rootfsDir, path.removePrefix("/"))
      if (!f.exists() || !f.isDirectory) throw CodedException("ERR_NOENT", "Directory not found: $path", null)
      f.listFiles()?.map { it.name } ?: emptyList()
    }
  }

  private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}