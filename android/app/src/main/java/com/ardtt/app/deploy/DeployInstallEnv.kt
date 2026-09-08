package com.ardtt.app.deploy

/** Shell env + installer invocation uploaded to the VPS. Never includes SSH passwords. */
object DeployInstallEnv {
    const val CASCADE_DNS = "10.10.0.2"
    const val CASCADE_LISTEN_PORT = 51820
    const val PACKAGE_DIR = "/opt/ardtt/incoming"
    const val STAGING_DIR = "/opt/ardtt/staging"

    fun packageRemotePath(deployVersion: String, arch: String): String =
        "$PACKAGE_DIR/${DeployStackSource.serverAssetName(deployVersion, arch)}"

    fun command(
        publicHost: String,
        directPort: Int,
        bypassPort: Int,
        deployVersion: String,
        role: String,
        packagePath: String,
        packageSha256: String,
        cascadeEnabled: Boolean = false,
        cascadeListenPort: Int = CASCADE_LISTEN_PORT,
        cascadePeerEndpoint: String = "",
        cascadePeerPublicKey: String = "",
        cascadeDns: String = CASCADE_DNS,
        cascadePeerProvisionPort: Int = 9100,
        autoPorts: Boolean = true,
        provisionPort: Int = 9100,
        telemetryPort: Int = 9200,
    ): String = buildString {
        append("set -euo pipefail; ")
        append("PKG="); append(SshClient.shellQuote(packagePath)); append("; ")
        append("SHA="); append(SshClient.shellQuote(packageSha256.lowercase())); append("; ")
        append("STAGE="); append(SshClient.shellQuote(STAGING_DIR)); append("; ")
        append("echo \"\$SHA  \$PKG\" | sha256sum -c -; ")
        append("mkdir -p \"\$STAGE\"; ")
        append(SAFE_EXTRACT)
        append("ARDTT_PUBLIC_HOST="); append(SshClient.shellQuote(publicHost)); append(' ')
        append("ARDTT_DIRECT_PORT="); append(directPort); append(' ')
        append("ARDTT_BYPASS_PORT="); append(bypassPort); append(' ')
        append("ARDTT_PROVISION_PORT="); append(provisionPort); append(' ')
        append("ARDTT_TELEMETRY_PORT="); append(telemetryPort); append(' ')
        append("ARDTT_AUTO_PORTS="); append(if (autoPorts) "1" else "0"); append(' ')
        append("ARDTT_DEPLOY_VERSION="); append(SshClient.shellQuote(deployVersion)); append(' ')
        append("ARDTT_ROLE="); append(SshClient.shellQuote(role)); append(' ')
        append("ARDTT_CASCADE_ENABLED="); append(if (cascadeEnabled) "1" else "0"); append(' ')
        append("ARDTT_PACKAGE="); append(SshClient.shellQuote(packagePath)); append(' ')
        append("ARDTT_PACKAGE_SHA256="); append(SshClient.shellQuote(packageSha256.lowercase())); append(' ')
        append("ARDTT_PKG_DIR="); append(SshClient.shellQuote(STAGING_DIR)); append(' ')
        if (cascadeEnabled) {
            append("ARDTT_CASCADE_LISTEN_PORT="); append(cascadeListenPort); append(' ')
            append("ARDTT_CASCADE_DNS="); append(SshClient.shellQuote(cascadeDns)); append(' ')
            if (cascadePeerEndpoint.isNotBlank()) {
                append("ARDTT_CASCADE_PEER_ENDPOINT=")
                append(SshClient.shellQuote(cascadePeerEndpoint))
                append(' ')
            }
            if (cascadePeerPublicKey.isNotBlank()) {
                append("ARDTT_CASCADE_PEER_PUBLIC_KEY=")
                append(SshClient.shellQuote(cascadePeerPublicKey))
                append(' ')
            }
            if (cascadePeerProvisionPort in 1..65535) {
                append("ARDTT_CASCADE_PEER_PROVISION_PORT=")
                append(cascadePeerProvisionPort)
                append(' ')
            }
        }
        append("bash \"\$STAGE/install.sh\"")
    }

    fun uninstallCommand(purgeData: Boolean = true): String = buildString {
        append("set -euo pipefail; ")
        append("if [ -x /opt/ardtt/current/install.sh ]; then ")
        append("ARDTT_ACTION=uninstall ARDTT_PURGE_DATA=")
        append(if (purgeData) "1" else "0")
        append(" bash /opt/ardtt/current/install.sh; ")
        append("elif [ -x /opt/ardtt/staging/install.sh ]; then ")
        append("ARDTT_ACTION=uninstall ARDTT_PURGE_DATA=")
        append(if (purgeData) "1" else "0")
        append(" bash /opt/ardtt/staging/install.sh; ")
        append("else ")
        append(ServerUninstall.FALLBACK_SCRIPT)
        append("; fi")
    }

    fun peerEndpoint(host: String, listenPort: Int = CASCADE_LISTEN_PORT): String {
        val h = host.trim()
        if (h.isEmpty()) return ""
        return "$h:$listenPort"
    }

    fun publicKeyFromLine(line: String): String? {
        if (!line.startsWith("ARDTT_CASCADE_PUBLIC_KEY|")) return null
        return line.removePrefix("ARDTT_CASCADE_PUBLIC_KEY|").trim().takeIf { it.isNotEmpty() }
    }

    /** Key/value fields from an `ARDTT_DONE|a=1|b=2` protocol line. */
    fun doneFields(line: String): Map<String, String> {
        if (!line.startsWith("ARDTT_DONE|")) return emptyMap()
        return line.removePrefix("ARDTT_DONE|")
            .split('|')
            .mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) return@mapNotNull null
                part.substring(0, i).trim() to part.substring(i + 1).trim()
            }
            .filter { it.first.isNotEmpty() }
            .toMap()
    }

    fun intField(fields: Map<String, String>, key: String): Int? =
        fields[key]?.toIntOrNull()?.takeIf { it in 1..65535 }

    /**
     * Compact extractor run after the outer SHA-256 check. Rejects .. / absolute
     * paths / symlinks. Does not execute archive members.
     */
    private val SAFE_EXTRACT = """
python3 - "${'$'}PKG" "${'$'}STAGE" <<'PY'
import gzip, os, stat, sys, tarfile
src, dest = sys.argv[1], sys.argv[2]
os.makedirs(dest, exist_ok=True)
dest = os.path.abspath(dest)
with gzip.open(src, "rb") as gz, tarfile.open(fileobj=gz, mode="r|") as tar:
    for m in tar:
        n = m.name.replace("\\", "/")
        if n.startswith("/") or n.startswith("\\") or "/../" in "/"+n or n.endswith("/..") or n.startswith("../"):
            raise SystemExit("unsafe path "+n)
        if m.issym() or m.islnk():
            raise SystemExit("link not allowed "+n)
        target = os.path.abspath(os.path.join(dest, m.name))
        if not (target == dest or target.startswith(dest + os.sep)):
            raise SystemExit("escapes "+n)
        tar.extract(m, path=dest, set_attrs=False)
for root, dirs, files in os.walk(dest):
    for name in dirs + files:
        p = os.path.join(root, name)
        if stat.S_ISLNK(os.lstat(p).st_mode):
            raise SystemExit("symlink "+p)
print("extracted", dest)
PY
test -f "${'$'}STAGE/install.sh"
test -f "${'$'}STAGE/manifest.json"
"""
}
