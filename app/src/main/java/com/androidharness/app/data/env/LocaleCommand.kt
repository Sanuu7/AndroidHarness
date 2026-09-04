package com.androidharness.app.data.env

import java.io.File

/** Minimal locale inspection for the Android toolchain; does not install locale data. */
internal object LocaleCommand {
    private const val MARKER = "# AndroidHarness locale compatibility v1"

    val script = """
        #!/system/bin/sh
        $MARKER
        charmap() {
          case "${'$'}{LC_ALL:-${'$'}{LC_CTYPE:-${'$'}{LANG:-C}}}" in
            C|POSIX) printf 'ASCII' ;;
            C.UTF-8|en_US.UTF-8) printf 'UTF-8' ;;
            *) printf '%s\n' 'locale: unsupported LC_CTYPE locale' >&2; return 1 ;;
          esac
        }
        case "${'$'}#:${'$'}*" in
          0:)
            printf 'LANG=%s\n' "${'$'}{LANG-}"
            for category in LC_CTYPE LC_NUMERIC LC_TIME LC_COLLATE LC_MONETARY LC_MESSAGES; do
              eval 'value=${'$'}{'"${'$'}category"'-}'
              value=${'$'}{LC_ALL:-${'$'}{value:-${'$'}{LANG:-C}}}
              printf '%s="%s"\n' "${'$'}category" "${'$'}value"
            done
            printf 'LC_ALL=%s\n' "${'$'}{LC_ALL-}"
            ;;
          1:-a|1:--all-locales) printf 'C\nC.UTF-8\nen_US.UTF-8\nPOSIX\n' ;;
          1:-m|1:--charmaps) printf 'ASCII\nUTF-8\n' ;;
          1:charmap) value=${'$'}(charmap) || exit 1; printf '%s\n' "${'$'}value" ;;
          '2:-k charmap') value=${'$'}(charmap) || exit 1; printf 'charmap="%s"\n' "${'$'}value" ;;
          1:--help)
            printf '%s\n' 'Usage: locale [-a | -m | [-k] charmap]' 'Reports the Android toolchain locale environment; locale data generation is not supported.'
            ;;
          *) printf '%s\n' 'locale: unsupported query; use --help' >&2; exit 1 ;;
        esac
    """.trimIndent() + "\n"

    fun ensureInstalled(prefix: File) {
        val command = File(prefix, "bin/locale")
        // Keep a real locale executable if a user has installed one.
        if (command.exists()) {
            val header = command.inputStream().use { input ->
                val bytes = ByteArray(128)
                val count = input.read(bytes).coerceAtLeast(0)
                String(bytes, 0, count, Charsets.UTF_8)
            }
            if (!header.contains("# AndroidHarness locale compatibility")) return
        }
        command.parentFile?.mkdirs()
        if (!command.exists() || command.readText() != script) command.writeText(script)
        command.setExecutable(true, false)
    }
}
