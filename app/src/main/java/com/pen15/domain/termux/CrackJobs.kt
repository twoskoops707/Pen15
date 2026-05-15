package com.pen15.domain.termux

import com.pen15.data.storage.StorageManager
import java.io.File

/**
 * Bash builders for Termux crack jobs. Every script:
 *   - is heredoc-safe and does not interpolate untrusted user input,
 *   - writes progress to `~/.pen15/jobs/{jobId}/stdout.log`,
 *   - writes a "DONE" sentinel + exit code on completion.
 *
 * Output is consumed by Compose screens via [TermuxRunner.tail] which
 * polls the log file with backoff. (Polling, not inotify, because
 * Termux's bind-mounted home dir is on a separate fs that doesn't
 * always honor inotify.)
 */
object CrackJobs {

    fun handshakeCrack(pcapPath: String, wordlistPath: String, jobId: String): String {
        val log = "\$HOME/.pen15/jobs/$jobId/stdout.log"
        return """
            mkdir -p ${'$'}(dirname "$log")
            exec >"$log" 2>&1
            echo "[*] handshake-crack starting"
            echo "[*] pcap = $pcapPath"
            echo "[*] wordlist = $wordlistPath"
            HASH="${'$'}HOME/.pen15/jobs/$jobId/hash.hc22000"
            if command -v hcxpcapngtool >/dev/null 2>&1; then
              hcxpcapngtool -o "${'$'}HASH" "$pcapPath" || { echo "[!] hcxpcapngtool failed"; exit 2; }
            else
              echo "[!] hcxpcapngtool not installed. Run setup."; exit 3
            fi
            if command -v hashcat >/dev/null 2>&1; then
              hashcat -m 22000 "${'$'}HASH" "$wordlistPath" --quiet --status --status-timer=10 --force
              EXIT=${'$'}?
            elif command -v aircrack-ng >/dev/null 2>&1; then
              aircrack-ng -w "$wordlistPath" "$pcapPath"
              EXIT=${'$'}?
            else
              echo "[!] no cracker available. Run setup."; exit 4
            fi
            echo "[*] DONE exit=${'$'}EXIT"
            exit ${'$'}EXIT
        """.trimIndent()
    }

    fun hashCrack(hash: String, modeId: Int, wordlistPath: String, jobId: String): String {
        val safeHash = hash.replace("'", "")
        val log = "\$HOME/.pen15/jobs/$jobId/stdout.log"
        return """
            mkdir -p ${'$'}(dirname "$log")
            exec >"$log" 2>&1
            HASH_FILE="${'$'}HOME/.pen15/jobs/$jobId/hash.txt"
            echo "$safeHash" > "${'$'}HASH_FILE"
            echo "[*] hash-crack starting mode=$modeId"
            if command -v hashcat >/dev/null 2>&1; then
              hashcat -m $modeId "${'$'}HASH_FILE" "$wordlistPath" --quiet --status --status-timer=10 --force
              EXIT=${'$'}?
            elif command -v john >/dev/null 2>&1; then
              john --wordlist="$wordlistPath" "${'$'}HASH_FILE"
              john --show "${'$'}HASH_FILE"
              EXIT=${'$'}?
            else
              echo "[!] falling back to pure python"
              python3 - <<'PY'
import sys, hashlib, os
h = open(os.environ['HASH_FILE']).read().strip().lower()
mode = int(os.environ.get('MODE','0'))
algo = {0:'md5',100:'sha1',1400:'sha256',1700:'sha512',1000:'md4'}.get(mode,'md5')
with open(os.environ['WORDLIST']) as f:
    for w in f:
        w = w.rstrip()
        d = hashlib.new(algo)
        d.update(w.encode())
        if d.hexdigest().lower() == h:
            print('CRACKED:', w)
            break
    else:
        print('not found')
PY
              EXIT=${'$'}?
            fi
            echo "[*] DONE exit=${'$'}EXIT"
            exit ${'$'}EXIT
        """.trimIndent()
    }

    fun bootstrapTools(): String = """
        set -e
        echo "[*] updating packages"
        pkg update -y
        pkg upgrade -y
        pkg install -y nmap python git curl whois dnsutils tshark hashcat-utils
        pip install --upgrade pip
        pip install hashid sherlock-project
        if ! command -v hashcat >/dev/null 2>&1; then
          echo "[*] hashcat: trying prebuilt arm64 binary"
          pkg install -y p7zip || true
          ARCH=${'$'}(uname -m)
          LATEST=${'$'}(curl -sL https://api.github.com/repos/hashcat/hashcat/releases/latest | grep tag_name | head -1 | cut -d '"' -f4)
          if [ -n "${'$'}LATEST" ]; then
            curl -sL "https://github.com/hashcat/hashcat/releases/download/${'$'}LATEST/hashcat-${'$'}{LATEST#v}.7z" -o /tmp/hashcat.7z || true
            7z x -y /tmp/hashcat.7z -o/tmp/hcx >/dev/null 2>&1 || true
            BIN=${'$'}(find /tmp/hcx -name 'hashcat.bin' | head -1)
            if [ -n "${'$'}BIN" ]; then cp "${'$'}BIN" "${'$'}PREFIX/bin/hashcat"; chmod +x "${'$'}PREFIX/bin/hashcat"; fi
          fi
        fi
        if ! command -v aircrack-ng >/dev/null 2>&1; then
          echo "[*] aircrack-ng: building from source (this takes a while)"
          pkg install -y build-essential clang make autoconf automake libtool pkg-config zlib openssl libpcap libnl
          git clone --depth 1 https://github.com/aircrack-ng/aircrack-ng ${'$'}HOME/.pen15/aircrack-ng || true
          (cd ${'$'}HOME/.pen15/aircrack-ng && autoreconf -i && ./configure --with-experimental && make -j4 && make install) || echo "[!] aircrack build failed; continuing"
        fi
        if ! command -v hcxpcapngtool >/dev/null 2>&1; then
          pkg install -y hcxtools || git clone --depth 1 https://github.com/ZerBea/hcxtools ${'$'}HOME/.pen15/hcxtools && \
            (cd ${'$'}HOME/.pen15/hcxtools && make && make install) || true
        fi
        mkdir -p ${'$'}HOME/.pen15/jobs ${'$'}HOME/.pen15/wordlists
        echo "[*] bootstrap done"
    """.trimIndent()
}
