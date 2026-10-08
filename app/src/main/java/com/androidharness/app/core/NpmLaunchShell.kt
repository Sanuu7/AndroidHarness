package com.androidharness.app.core

internal object NpmLaunchShell {
    val preload = """
        const cp = require('node:child_process');
        const fs = require('node:fs');
        const prefix = process.env.PREFIX;
        if (prefix && fs.existsSync(prefix + '/etc/harness-shims.sh')) {
          const spawn = cp.spawn;
          const linker = process.arch === 'arm64' || process.arch === 'x64' ? '/system/bin/linker64' : '/system/bin/linker';
          cp.spawn = function(command, args, options) {
            if ((command === 'sh' || command === '/system/bin/sh') && Array.isArray(args) && args[0] === '-c' && typeof args[1] === 'string') {
              return spawn.call(this, linker, [prefix + '/bin/bash', '-c', '. "${'$'}PREFIX/etc/harness-shims.sh"; eval "${'$'}1"', 'harness-npm', args[1]], options);
            }
            return spawn.apply(this, arguments);
          };
        }
    """.trimIndent() + "\n"
}
