package com.androidharness.app.router

/** Compatibility for 9Router's lazy npm installs; never changes the shared toolchain or its launchers. */
internal object NineRouterNodeRuntime {
    fun preload(useLinker: Boolean): String = """
        const cp = require('node:child_process');
        const fs = require('node:fs');
        const prefix = process.env.PREFIX;
        if (prefix && fs.existsSync(prefix + '/bin/node')) {
          const spawn = cp.spawn;
          const linker = ${if (useLinker) "(process.arch === 'arm64' || process.arch === 'x64' ? '/system/bin/linker64' : '/system/bin/linker')" else "null"};
          const node = prefix + '/bin/node';
          const npm = prefix + '/lib/node_modules/npm/bin/npm-cli.js';
          cp.spawn = function(command, args, options) {
            // The shared npm script's shebang points to the shell-UID deployment.
            // Read it with this process's Node instead of executing that shebang.
            if ((command === 'npm' || command === prefix + '/bin/npm') && fs.existsSync(npm)) {
              if (!Array.isArray(args)) { options = args; args = []; }
              return spawn.call(this, linker || node,
                [...(linker ? [node] : []), '--require', __filename, npm, ...args], options);
            }
            // npm's own lifecycle commands need the same app-UID shell shims.
            if ((command === 'sh' || command === '/system/bin/sh') && Array.isArray(args) &&
                args[0] === '-c' && typeof args[1] === 'string' &&
                fs.existsSync(prefix + '/etc/harness-shims.sh')) {
              const bash = prefix + '/bin/bash';
              return spawn.call(this, linker || bash,
                [...(linker ? [bash] : []), '-c', '. "${'$'}PREFIX/etc/harness-shims.sh"; eval "${'$'}1"',
                 'harness-9router-npm', args[1]], options);
            }
            return spawn.apply(this, arguments);
          };
          require('node:module').syncBuiltinESMExports();
        }
    """.trimIndent() + "\n"
}
