package com.androidharness.app.repomap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoMapTest {

    @Test
    fun `extract kotlin class and function symbols`() {
        val code = """
            package com.example.app
            
            import android.os.Bundle
            
            data class User(val id: String, val name: String)
            
            class UserService {
                fun fetchUser(id: String): User {
                    return User(id, "test")
                }
                
                val count: Int = 10
            }
        """.trimIndent()

        val symbols = RepoSymbolExtractor.extract("src/UserService.kt", code)
        assertTrue(symbols.any { it.name == "User" && it.kind.contains("class") })
        assertTrue(symbols.any { it.name == "UserService" && it.kind.contains("class") })
        assertTrue(symbols.any { it.name == "fetchUser" && it.kind == "fun" })
        assertTrue(symbols.any { it.name == "count" && it.kind == "val" })
    }

    @Test
    fun `extract python class and def symbols`() {
        val code = """
            import os
            
            class Engine:
                def __init__(self, name):
                    self.name = name
                    
                async def run_task(self, timeout: int = 10):
                    pass
                    
            def standalone_function():
                pass
        """.trimIndent()

        val symbols = RepoSymbolExtractor.extract("scripts/engine.py", code)
        assertTrue(symbols.any { it.name == "Engine" && it.kind == "class" })
        assertTrue(symbols.any { it.name == "__init__" && it.kind == "def" })
        assertTrue(symbols.any { it.name == "run_task" && it.kind == "async def" })
        assertTrue(symbols.any { it.name == "standalone_function" && it.kind == "def" })
    }

    @Test
    fun `extract typescript export functions and interfaces`() {
        val code = """
            export interface Config {
                port: number;
                host: string;
            }
            
            export class Server {
                listen() {}
            }
            
            export async function bootstrap(config: Config): Promise<Server> {
                return new Server();
            }
        """.trimIndent()

        val symbols = RepoSymbolExtractor.extract("src/server.ts", code)
        assertTrue(symbols.any { it.name == "Config" && it.kind == "interface" })
        assertTrue(symbols.any { it.name == "Server" && it.kind == "class" })
        assertTrue(symbols.any { it.name == "bootstrap" && it.kind == "function" })
    }

    @Test
    fun `progressive compression respects maxChars budget`() {
        val entries = listOf(
            FileEntry(
                relPath = "src/main/A.kt",
                symbols = (1..20).map { SymbolInfo("fun$it", "fun", "fun fun$it(arg: String): Int", it) },
            ),
            FileEntry(
                relPath = "src/main/B.kt",
                symbols = (1..20).map { SymbolInfo("Class$it", "class", "class Class$it", it) },
            ),
        )

        val tightBudget = RepoMapGenerator.generate(entries, maxChars = 200)
        assertTrue(tightBudget.length <= 200)
        assertTrue(tightBudget.contains("src/main/"))
    }
}
