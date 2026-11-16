package app.tennyo.host.engine.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Agent command lines come only from the user's local configuration files, in the three shapes they are kept. */
class AgentServersTest {
    @Test
    fun `vscode agent servers keep command args and env`() {
        val servers = agentServersFromMap(
            source = "vscode",
            json = """{"agent_servers":{"Claude Code":{"command":"npx","args":["-y","@zed-industries/claude-code-acp"],"env":{"A":"1"}}}}""",
        )

        assertEquals(
            listOf(AgentServer("vscode:Claude Code", "Claude Code", "npx", listOf("-y", "@zed-industries/claude-code-acp"), mapOf("A" to "1"))),
            servers,
        )
    }

    @Test
    fun `registry agents run through npx uvx or a local binary for this platform`() {
        val servers = agentServersFromRegistry(
            source = "devin",
            platform = "windows-x86_64",
            json = """{"agents":[
                {"id":"gemini","name":"Gemini CLI","distribution":{"npx":{"package":"@google/gemini-cli","args":["--experimental-acp"]}}},
                {"id":"py","name":"Py Agent","distribution":{"uvx":{"package":"py-agent"}}},
                {"id":"bin","name":"Bin","distribution":{"binary":{"windows-x86_64":{"cmd":"C:/agents/bin.exe","args":["acp"]}}}},
                {"id":"other","name":"Other","distribution":{"binary":{"darwin-aarch64":{"cmd":"/bin/x"}}}}
            ]}""",
        )

        assertEquals(
            listOf(
                AgentServer("devin:gemini", "Gemini CLI", "npx", listOf("-y", "@google/gemini-cli", "--experimental-acp")),
                AgentServer("devin:py", "Py Agent", "uvx", listOf("py-agent")),
                AgentServer("devin:bin", "Bin", "C:/agents/bin.exe", listOf("acp")),
            ),
            servers,
        )
    }

    @Test
    fun `vscode registry agents use its npx, skip disabled ones and archives it downloads itself`() {
        val disabled = jetBrainsDisabledAgents("""{"agents":{},"disabledAgents":["acp.registry.claude-acp"]}""")
        val servers = agentServersFromRegistry(
            source = "vscode",
            platform = "windows-x86_64",
            json = """{"agents":[
                {"id":"antigravity-acp","name":"Google Antigravity","distribution":{"npx":{"package":"antigravity-acp@1"}}},
                {"id":"claude-acp","name":"Claude","distribution":{"npx":{"package":"claude-acp"}}},
                {"id":"amp-acp","name":"Amp","distribution":{"binary":{"windows-x86_64":{"cmd":"./amp-acp.exe"}}}}
            ]}""",
            npx = "C:/ij/node/npx.cmd",
            excluded = disabled,
        )

        assertEquals(setOf("claude-acp"), disabled)
        assertEquals(
            listOf(AgentServer("vscode:antigravity-acp", "Google Antigravity", "C:/ij/node/npx.cmd", listOf("-y", "antigravity-acp@1"))),
            servers,
        )
    }

    @Test
    fun `an archive agent is offered only to a launcher that downloads it, and only over https`() {
        val json = """{"agents":[
            {"id":"agy","name":"Antigravity","version":"1.2.1","distribution":{"binary":{"windows-x86_64":
                {"archive":"https://dl.example/agy.zip","cmd":"./agy.exe","sha256":"ab"}}}},
            {"id":"plain","name":"Plain","distribution":{"binary":{"windows-x86_64":
                {"archive":"http://dl.example/p.zip","cmd":"./p.exe"}}}},
            {"id":"amp-acp","name":"Amp","version":"0.9.0","distribution":{"binary":{"windows-x86_64":
                {"archive":"https://dl.example/amp.zip","cmd":"amp-acp.exe"}}}}
        ]}"""

        assertTrue(agentServersFromRegistry("vscode", "windows-x86_64", json).isEmpty())
        assertEquals(
            listOf(
                AgentServer(
                    "vscode:agy", "Antigravity", "./agy.exe",
                    archive = AgentArchive("https://dl.example/agy.zip", "ab", "1.2.1"),
                ),
                AgentServer(
                    "vscode:amp-acp", "Amp", "amp-acp.exe",
                    archive = AgentArchive("https://dl.example/amp.zip", null, "0.9.0"),
                ),
            ),
            agentServersFromRegistry("vscode", "windows-x86_64", json, downloadsArchives = true),
        )
    }

    @Test
    fun `devin agents are the ones it switched on, started the way devin starts them`() {
        val json = """{"agents":[
            {"id":"codex-acp","name":"Codex","distribution":{"npx":{"package":"@agentclientprotocol/codex-acp@2.1.1"}}},
            {"id":"mistral-vibe","name":"Mistral Vibe","distribution":{"binary":{"windows-x86_64":
                {"archive":"https://dl.example/vibe.zip","cmd":"./vibe-acp.exe"}}}},
            {"id":"devin","name":"Devin","distribution":{"binary":{"windows-x86_64":
                {"archive":"https://dl.example/devin.zip","cmd":"./bin/devin.exe","args":["acp"]}}}},
            {"id":"gemini","name":"Gemini CLI","distribution":{"npx":{"package":"@google/gemini-cli"}}}
        ]}"""

        val servers = devinAgentServers(
            registryJson = json,
            platform = "windows-x86_64",
            enabled = setOf("codex-acp", "mistral-vibe", "devin-cli"),
            bundledDevin = "C:/Devin/devin.exe",
        )

        assertEquals(
            listOf(
                AgentServer("devin:codex-acp", "Codex", "npx", listOf("-y", "@agentclientprotocol/codex-acp@2.1.1")),
                AgentServer("devin:mistral-vibe", "Mistral Vibe", "vibe-acp.exe"),
                AgentServer("devin:devin-cli", "Devin Local", "C:/Devin/devin.exe", listOf("acp")),
            ),
            servers,
        )
    }

    @Test
    fun `devin keeps its own agents file under the code user directory on windows`() {
        val read = mutableListOf<String>()
        localAgentServers(home = "C:/Users/me", platform = "windows-x86_64") { path -> read += path; null }
        localAgentServers(home = "/home/me", platform = "linux-x86_64") { path -> read += path; null }

        assertTrue("C:/Users/me/AppData/Roaming/Code/User/acp/registry.json" in read)
        assertTrue("/home/me/.windsurf/acp/registry.json" in read)
    }

    @Test
    fun `malformed configuration yields no agents rather than failing`() {
        assertTrue(agentServersFromMap("vscode", "{not json").isEmpty())
        assertTrue(agentServersFromMap("vscode", """{"agent_servers":{"x":{"args":[]}}}""").isEmpty())
        assertTrue(agentServersFromRegistry("devin", "linux-x86_64", "[]").isEmpty())
    }
}
