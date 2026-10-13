package expo.modules.tavibridge

import android.content.Context
import android.net.ConnectivityManager
import android.system.Os
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Manages PRoot runtime from bundled jniLibs, rootfs extraction, and bootstrap.
 * PRoot binaries (libproot.so, libprootloader.so, libtalloc.so, libandroid-shmem.so)
 * are packaged as jniLibs and extracted by Android to nativeLibraryDir.
 */
class ProotManager(private val context: Context) {

  private val TAG = "ProotManager"

  companion object {
    const val BOOTSTRAP_VERSION = "v1"
    const val NODE_VERSION = "22.23.3"
    const val NODE_SHA256 = "a44aeb94849a299b22df10b9e622ec2f605c2183501bc40590705131de7c740f"
  }

  private fun getKernelRelease(): String {
    return try {
      val osRelease = File("/proc/sys/kernel/osrelease").readText().trim()
      // Extract just the kernel version part (e.g., "5.15.0" from "5.15.0-android15")
      val versionPart = osRelease.takeWhile { it != '-' }
      if (versionPart.isNotEmpty()) {
        "$versionPart-android"
      } else {
        "5.15.0-android"
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to read kernel release, using fallback", e)
      "5.15.0-android"
    }
  }

  val filesRoot: File get() = context.filesDir
  val rootfsDir: File get() = File(filesRoot, "rootfs")
  val workspaceDir: File get() = File(rootfsDir, "workspace")
  val tmpDir: File get() = File(rootfsDir, "tmp")
  val cacheDir: File get() = File(context.cacheDir, "proot-tmp")

  private val nativeLibDir: File get() = File(context.applicationInfo.nativeLibraryDir)
  private val prootBinary: File get() = File(nativeLibDir, "libproot.so")
  private val loaderBinary: File get() = File(nativeLibDir, "libprootloader.so")
  private val ldLibraryPath: String get() = nativeLibDir.absolutePath

  val markerFile: File get() = File(filesRoot, ".tavi-initialized")
  val isInitialized: Boolean get() = markerFile.exists() && prootBinary.exists() && rootfsDir.exists()

  val bootstrapMarkerFile: File get() = File(filesRoot, ".tavi-bootstrap-complete")
  val bootstrapVersionFile: File get() = File(filesRoot, ".tavi-bootstrap-version")
  val rootfsVersionFile: File get() = File(filesRoot, ".tavi-rootfs-version")

  fun markInitialized(rootfsVersion: String = "1") {
    markerFile.writeText("v1")
    rootfsVersionFile.writeText(rootfsVersion)
  }

  fun isBootstrapped(): Boolean {
    return bootstrapMarkerFile.exists() &&
           bootstrapVersionFile.exists() &&
           bootstrapVersionFile.readText().trim() == BOOTSTRAP_VERSION
  }

  fun isRootfsCurrent(expectedVersion: String): Boolean {
    return rootfsVersionFile.exists() &&
           rootfsVersionFile.readText().trim() == expectedVersion
  }

  fun ensureDirectories() {
    rootfsDir.mkdirs()
    workspaceDir.mkdirs()
    tmpDir.mkdirs()
    cacheDir.mkdirs()

    // Set sticky bit (1777) on tmp directory
    try {
      tmpDir.setExecutable(true, false)
      tmpDir.setReadable(true, false)
      tmpDir.setWritable(true, false)
      Runtime.getRuntime().exec(arrayOf("chmod", "1777", tmpDir.absolutePath)).waitFor()
    } catch (e: Exception) {
      Log.w(TAG, "Could not chmod 1777 on tmp dir", e)
    }

    // Create empty selinux dir for binding
    File(cacheDir, "selinux").mkdirs()
  }

  /**
   * Verifies SHA256 of a file.
   */
  fun verifySha256(filePath: String, expected: String): Boolean {
    return try {
      val digest = MessageDigest.getInstance("SHA-256")
      val fis = FileInputStream(filePath)
      val buffer = ByteArray(8192)
      var read: Int
      while (fis.read(buffer).also { read = it } != -1) {
        digest.update(buffer, 0, read)
      }
      fis.close()
      val actual = digest.digest().joinToString("") { "%02x".format(it) }
      actual.equals(expected, ignoreCase = true)
    } catch (e: Exception) {
      Log.e(TAG, "SHA256 verification failed", e)
      false
    }
  }

  /**
   * Extracts the rootfs tarball on the host (no guest shell exists yet).
   * Hardlinks are materialized as copies because f2fs rejects link(2).
   * Uses atomic extraction (extract to temp, then rename) for durability.
   */
  fun extractRootfs(tarballPath: String, expectedSha256: String): Boolean {
    if (!verifySha256(tarballPath, expectedSha256)) {
      Log.e(TAG, "Rootfs SHA256 mismatch: $tarballPath")
      return false
    }

    return try {
      // Extract to temp directory first for atomicity
      val tempRootfs = File(rootfsDir.parentFile, "rootfs.tmp.${System.currentTimeMillis()}")
      tempRootfs.mkdirs()
      extractTarGz(File(tarballPath), tempRootfs)

      // Atomic swap: delete old, rename new
      rootfsDir.deleteRecursively()
      tempRootfs.renameTo(rootfsDir)

      true
    } catch (e: Exception) {
      Log.e(TAG, "Rootfs extraction failed", e)
      // Clean up temp dir on failure
      rootfsDir.parentFile?.listFiles()?.filter { it.name.startsWith("rootfs.tmp.") }?.forEach { it.deleteRecursively() }
      false
    }
  }

  private fun extractTarGz(tarball: File, dest: File) {
    GZIPInputStream(BufferedInputStream(FileInputStream(tarball), 1 shl 16)).use { input ->
      val header = ByteArray(512)
      var longName: String? = null
      var longLink: String? = null
      var paxPath: String? = null
      var paxLink: String? = null

      while (true) {
        if (!readHeader(input, header)) break
        if (header.all { it == 0.toByte() }) break

        val size = octal(header, 124, 12)
        val typeflag = header[156].toInt().toChar()
        val rawName = cString(header, 0, 100)
        val magic = cString(header, 257, 6)
        val prefix = if (magic.startsWith("ustar")) cString(header, 345, 155) else ""
        val name = paxPath ?: longName ?: if (prefix.isEmpty()) rawName else "$prefix/$rawName"
        val linkName = paxLink ?: longLink ?: cString(header, 157, 100)
        val mode = octal(header, 100, 8).toInt()

        when (typeflag) {
          'L', 'K', 'x', 'g' -> {
            val data = readBytes(input, size)
            skipPadding(input, size)
            when (typeflag) {
              'L' -> longName = cString(data, 0, data.size)
              'K' -> longLink = cString(data, 0, data.size)
              'x' -> {
                val pax = parsePax(data)
                paxPath = pax["path"] ?: paxPath
                paxLink = pax["linkpath"] ?: paxLink
              }
            }
            continue
          }
        }

        longName = null
        longLink = null
        paxPath = null
        paxLink = null

        val relName = normalize(name)
        if (relName.isNotEmpty()) {
          val target = safeTarget(dest, relName)
          when (typeflag) {
            '0', '\u0000', '7' -> {
              target.parentFile?.mkdirs()
              target.delete()
              FileOutputStream(target).use { out -> copyBytes(input, out, size) }
              target.setReadable(true, false)
              target.setWritable(true, true)
              target.setExecutable((mode and 0b001_001_001) != 0, false)
            }
            '5' -> target.mkdirs()
            '2' -> {
              target.parentFile?.mkdirs()
              target.delete()
              Os.symlink(linkName, target.absolutePath)
            }
            '1' -> {
              val source = safeTarget(dest, normalize(linkName))
              target.parentFile?.mkdirs()
              target.delete()
              FileInputStream(source).use { src ->
                FileOutputStream(target).use { out -> src.copyTo(out) }
              }
              target.setExecutable(source.canExecute(), false)
            }
            else -> skipBytes(input, size)
          }
        } else {
          skipBytes(input, size)
        }
        skipPadding(input, size)
      }
    }
  }

  private fun normalize(name: String): String =
    name.trimStart('/').removePrefix("./").trimEnd('/').removeSuffix("/.").let { if (it == ".") "" else it }

  private fun safeTarget(root: File, relName: String): File {
    if (relName.split('/').any { it == ".." }) throw IOException("Unsafe tar path: $relName")
    return File(root, relName)
  }

  private fun parsePax(data: ByteArray): Map<String, String> {
    val out = mutableMapOf<String, String>()
    var pos = 0
    while (pos < data.size) {
      var space = pos
      while (space < data.size && data[space] != ' '.code.toByte()) space++
      if (space >= data.size) break
      val len = String(data, pos, space - pos, Charsets.US_ASCII).toIntOrNull() ?: break
      if (len <= 0 || pos + len > data.size) break
      val kv = String(data, space + 1, pos + len - space - 1, Charsets.UTF_8).removeSuffix("\n")
      val eq = kv.indexOf('=')
      if (eq > 0) out[kv.substring(0, eq)] = kv.substring(eq + 1)
      pos += len
    }
    return out
  }

  private fun readHeader(input: InputStream, buf: ByteArray): Boolean {
    var off = 0
    while (off < buf.size) {
      val n = input.read(buf, off, buf.size - off)
      if (n < 0) {
        if (off == 0) return false
        throw IOException("Truncated tar header")
      }
      off += n
    }
    return true
  }

  private fun readBytes(input: InputStream, size: Long): ByteArray {
    val buf = ByteArray(size.toInt())
    var off = 0
    while (off < buf.size) {
      val n = input.read(buf, off, buf.size - off)
      if (n < 0) throw IOException("Truncated tar entry")
      off += n
    }
    return buf
  }

  private fun copyBytes(input: InputStream, out: OutputStream, size: Long) {
    val buf = ByteArray(65536)
    var remaining = size
    while (remaining > 0) {
      val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
      if (n < 0) throw IOException("Truncated tar entry")
      out.write(buf, 0, n)
      remaining -= n
    }
  }

  private fun skipBytes(input: InputStream, size: Long) {
    val buf = ByteArray(8192)
    var remaining = size
    while (remaining > 0) {
      val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
      if (n < 0) throw IOException("Truncated tar entry")
      remaining -= n
    }
  }

  private fun skipPadding(input: InputStream, size: Long) {
    val pad = ((512 - size % 512) % 512).toLong()
    if (pad > 0) skipBytes(input, pad)
  }

  private fun octal(buf: ByteArray, off: Int, len: Int): Long {
    var v = 0L
    for (i in off until off + len) {
      val c = buf[i].toInt()
      if (c in '0'.code..'7'.code) v = v * 8 + (c - '0'.code)
    }
    return v
  }

  private fun cString(buf: ByteArray, off: Int, len: Int): String {
    var end = off
    while (end < off + len && buf[end] != 0.toByte()) end++
    return String(buf, off, end - off, Charsets.UTF_8)
  }

  /**
   * Runs the bootstrap sequence inside the extracted rootfs:
   * 1. Write /etc/resolv.conf
   * 2. apt-get update
   * 3. apt-get install core pack (git, curl, tmux, ca-certificates, xz-utils)
   * 4. Download and install Node.js 22 LTS with checksum verification
   */
  fun writeResolvConf() {
    val servers = hostDnsServers().ifEmpty { listOf("8.8.8.8", "1.1.1.1") }
    val resolv = File(rootfsDir, "etc/resolv.conf")
    resolv.parentFile?.mkdirs()
    val content = servers.joinToString("") { "nameserver $it\n" }
    resolv.writeText(content)
    Log.i(TAG, "wrote resolv.conf: $content")
  }

  private fun hostDnsServers(): List<String> {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
      ?: return emptyList()
    val network = cm.activeNetwork ?: return emptyList()
    val props = cm.getLinkProperties(network) ?: return emptyList()
    return props.dnsServers.mapNotNull { it.hostAddress }
  }

  fun bootstrap(onOutput: (String) -> Unit): Boolean {
    // NEW: ensure all directories exist before building the PRoot command
    // (cacheDir can be cleared by Android between sessions, leaving the selinux bind target missing)
    ensureDirectories()

    // Check if already bootstrapped
    if (isBootstrapped()) {
      onOutput("Bootstrap already complete (version $BOOTSTRAP_VERSION)")
      return true
    }

    writeResolvConf()
    val bootstrapScript = """
      set -euo pipefail
      export PATH=/usr/local/bin:/usr/bin:/bin
      export DEBIAN_FRONTEND=noninteractive
      export HOME=/root
      # Guest must not see host PRoot env vars (PROOT_TMP_DIR points outside the rootfs,
      # breaking mktemp in package postinst scripts)
      unset PROOT_TMP_DIR PROOT_LOADER PROOT_LOADER_32 PROOT_NO_SECCOMP LD_LIBRARY_PATH || true
      export TMPDIR=/tmp

      # 1. apt update (with retry)
      for i in 1 2 3; do
        apt-get update -y && break
        sleep 2
      done

      # 2. Core pack
      apt-get install -y -qq --no-install-recommends \
        git curl tmux ca-certificates xz-utils

      # 3. Node.js 22 LTS
      cd /tmp
      NODE_URL="https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION-linux-arm64.tar.xz"
      NODE_SHA256="$NODE_SHA256"
      curl -fsSL "${'$'}NODE_URL" -o node.tar.xz
      echo "${'$'}NODE_SHA256  node.tar.xz" | sha256sum -c -
      tar -xJf node.tar.xz -C /usr/local --strip-components=1
      rm node.tar.xz

      # Verify
      node --version
      npm --version
    """.trimIndent()

    val cmd = buildProotCommand(null).toMutableList()
    cmd.removeAt(cmd.size - 1) // drop trailing bash; bootstrap appends its own /bin/bash -c
    cmd.add("/bin/bash")
    cmd.add("-c")
    cmd.add(bootstrapScript)

    val env = buildProotEnv() + buildEnv() + mapOf(
      "DEBIAN_FRONTEND" to "noninteractive"
    )

    return try {
      val proc = ProcessBuilder(cmd)
        .directory(filesRoot)
        .apply { environment().putAll(env) }
        .redirectErrorStream(true)
        .start()

      proc.inputStream.bufferedReader().useLines { lines -> lines.forEach(onOutput) }
      val exit = proc.waitFor()
      if (exit != 0) {
        Log.e(TAG, "Bootstrap failed with exit code $exit")
        return false
      }

      // Mark bootstrap as complete
      bootstrapMarkerFile.writeText("complete")
      bootstrapVersionFile.writeText(BOOTSTRAP_VERSION)
      true
    } catch (e: Exception) {
      Log.e(TAG, "Bootstrap exception", e)
      false
    }
  }

  fun buildProotCommand(
  sessionName: String?
): MutableList<String> {
    val cmd = mutableListOf(
      prootBinary.absolutePath,
      "--kill-on-exit",
      "--link2symlink",
      "--sysvipc",
      "--kernel-release=${getKernelRelease()}",
      // -0 is an alias of --change-id=0:0; passing both triggers
      // "option -i/-0/-S was already specified" warnings
      "--change-id=0:0",
      "-L",
      "-r", rootfsDir.absolutePath,
      // Set guest working directory so PRoot doesn't try to enter the bound host CWD
      "-w", "/root",
    )

    // Android system binds - filter by world-executable check
    val systemBindDirs = listOf("/system", "/apex", "/product", "/vendor", "/system_ext", "/odm")
      .filter { shouldBindSystemDir(it) }

    for (dir in systemBindDirs) {
      cmd.add("-b")
      cmd.add(dir)
    }

    // Linker configs (files, not directories)
    val linkerConfigs = listOf(
      "/linkerconfig/ld.config.txt",
      "/linkerconfig/com.android.art/ld.config.txt"
    ).filter { File(it).exists() }
    for (file in linkerConfigs) {
      cmd.add("-b")
      cmd.add("$file:$file")
    }

    // Property context files
    val propertyFiles = listOf("/plat_property_contexts", "/property_contexts")
      .filter { File(it).exists() }
    for (file in propertyFiles) {
      cmd.add("-b")
      cmd.add("$file:$file")
    }

    // Device binds - CRITICAL FIXES
    cmd.add("-b")
    cmd.add("/dev")
    cmd.add("-b")
    cmd.add("/dev/urandom:/dev/random")
    cmd.add("-b")
    cmd.add("/proc/self/fd:/dev/fd")
    cmd.add("-b")
    cmd.add("/proc/self/fd/0:/dev/stdin")
    cmd.add("-b")
    cmd.add("/proc/self/fd/1:/dev/stdout")
    cmd.add("-b")
    cmd.add("/proc/self/fd/2:/dev/stderr")

    // Proc and sys
    cmd.add("-b")
    cmd.add("/proc")
    cmd.add("-b")
    cmd.add("/sys")

    // Working directories
    cmd.add("-b")
    cmd.add("${tmpDir.absolutePath}:/tmp")
    cmd.add("-b")
    cmd.add("${workspaceDir.absolutePath}:/workspace")

    // /dev/shm bind with sticky bit
    cmd.add("-b")
    cmd.add("${tmpDir.absolutePath}:/dev/shm")

    // Hide SELinux from guest
    cmd.add("-b")
    cmd.add("${cacheDir.absolutePath}/selinux:/sys/fs/selinux")

    // Clean guest environment: /usr/bin/env -i resets everything inherited from the
    // host (Android's TMPDIR/PROOT_* would break guest tools like mktemp), and proot
    // itself still reads the vars it needs from the ProcessBuilder environment.
    cmd.add("/usr/bin/env")
    cmd.add("-i")
    cmd.add("HOME=/root")
    cmd.add("PATH=/usr/local/bin:/usr/bin:/bin")
    cmd.add("TERM=xterm-256color")
    cmd.add("LANG=C.UTF-8")
    cmd.add("TMPDIR=/tmp")

    if (sessionName != null) {
      // Detached tmux session: new-session -d starts (or reuses) the server and
      // returns; the trailing bash keeps the proot process alive so the tmux
      // server (--kill-on-exit) survives for later attach/exec.
      // Explicit -S socket path: the default /tmp/tmux-<uid>/default socket does a
      // kernel-level peer-credential check that fails when the faked-root (proot
      // --change-id=0:0) server and a separate proot instance's client compare
      // getuid()=0 against the kernel-reported real app uid -> "access not allowed".
      // A shared explicit socket under /tmp owned by the server process avoids that.
      val safeName = sessionName.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
      cmd.add("/bin/sh")
      cmd.add("-c")
      cmd.add("tmux -S /tmp/tmux-default.sock new-session -d -s '$safeName'; exec bash")
    } else {
      // stdin-driven callers (execCommand) need a live shell; callers that
      // append their own /bin/bash -c (bootstrap) ignore this trailing bash.
      // Without a trailing program, env -i ... would just print the env.
      cmd.add("bash")
    }
    return cmd
  }

  private fun shouldBindSystemDir(path: String): Boolean {
    return try {
      val file = File(path)
      file.exists() && file.isDirectory && file.canExecute()
    } catch (e: Exception) {
      false
    }
  }

  fun buildProotEnv(): Map<String, String> {
    ensureTallocShim()
    return mapOf(
      "PROOT_TMP_DIR" to cacheDir.absolutePath,
      "PROOT_LOADER" to loaderBinary.absolutePath,
      "LD_LIBRARY_PATH" to "${libShimDir.absolutePath}:$ldLibraryPath"
    )
  }

  /**
   * Host-side env for ProcessBuilder: a sane baseline the guest clean-env
   * command (/usr/bin/env -i) does not inherit directly, but PRoot itself may use.
   * LD_LIBRARY_PATH here is required — proot links libtalloc.so.2 via the shim dir.
   * Guest cleanliness is handled entirely by /usr/bin/env -i; do NOT blank these
   * vars in the ProcessBuilder env or proot itself fails to link.
   */
  fun buildEnv(): Map<String, String> {
    return mapOf(
      "HOME" to "/root",
      "PATH" to "/usr/local/bin:/usr/bin:/bin",
      "TERM" to "xterm-256color",
      "LANG" to "C.UTF-8",
      "TMPDIR" to "/tmp"
    )
  }

  private val libShimDir: File get() = File(filesRoot, "lib")

  private fun ensureTallocShim() {
    val link = File(libShimDir, "libtalloc.so.2")
    val target = File(nativeLibDir, "libtalloc.so")
    // Recreate unconditionally: nativeLibraryDir changes on every app update,
    // leaving a dangling symlink from the previous install.
    libShimDir.mkdirs()
    link.delete()
    if (target.exists()) {
      Os.symlink(target.absolutePath, link.absolutePath)
    } else {
      Log.e(TAG, "libtalloc.so missing from nativeLibraryDir: ${target.absolutePath}")
    }
  }
}
