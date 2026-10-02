package hu.baader.repl.mcp

import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class McpEndpointSettingsTest {
    private fun settings() = McpEndpointSettings(Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(Project::class.java)
    ) { _, method, _ ->
        check(method.name == "getLocationHash")
        "endpoint-test-project"
    } as Project)

    @Test fun listeningPortSurvivesXmlRoundTripAndInvalidValuesAreRejected() {
        val first = settings()
        first.rememberPort(54321)
        val xml = XmlSerializer.serialize(first.getState())
        val reopened = settings()
        reopened.loadState(XmlSerializer.deserialize(xml, McpEndpointSettings.State::class.java))
        assertEquals(54321, reopened.port)
        assertEquals(listOf("port"), xml.children.map { it.getAttributeValue("name") })
        // A returned snapshot must not allow callers to alter the remembered endpoint.
        reopened.getState().port = 1234
        assertEquals(54321, reopened.port)
        assertThrows(IllegalArgumentException::class.java) { reopened.rememberPort(0) }
        assertEquals(54321, reopened.port)
        reopened.loadState(McpEndpointSettings.State(70000))
        assertEquals(0, reopened.port)
    }

    @Test fun credentialsSurviveReconstructionAndStayIsolatedByProject() {
        val secrets = mutableMapOf<String, String>()
        var writes = 0
        fun credentials(key: String) = McpEndpointCredentials(key, { secrets[it].orEmpty() }, { k, v ->
            secrets[k] = v; writes++
        })
        val token = credentials("mcp:project-a").token()
        assertTrue(token.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertEquals(token, credentials("mcp:project-a").token())
        assertEquals(1, writes)
        assertNotEquals(token, credentials("mcp:project-b").token())
        assertEquals(2, writes)
    }

    @Test fun credentialStorageFailureDoesNotReturnAnUnrecoverableToken() {
        val denied = McpEndpointCredentials("mcp:project", { "" }, { _, _ -> error("Storage unavailable") })
        assertThrows(IllegalStateException::class.java) { denied.token() }
        val unreadable = McpEndpointCredentials("mcp:project", { error("Storage unavailable") }, { _, _ ->
            fail("A failed read must not overwrite the stored token")
        })
        assertThrows(IllegalStateException::class.java) { unreadable.token() }
    }
}
