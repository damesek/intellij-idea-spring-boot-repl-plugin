package hu.baader.repl.ai

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import hu.baader.repl.nrepl.NreplService
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class AiContextMetadataStore(project: Project) : com.intellij.openapi.Disposable {
    private val beansRef: AtomicReference<List<NreplService.BeanInfo>> = AtomicReference(emptyList())
    private val lastUpdatedRef: AtomicReference<Instant?> = AtomicReference(null)
    private val subscription = NreplService.getInstance(project).onMessage {
        if (it["op"] == "connection" && (it["state"] in setOf("DISCONNECTED", "CONNECTING", "FAILED") || it["detail"].orEmpty().contains("context changed", true)))
            update(emptyList())
    }
    override fun dispose() { subscription.dispose() }

    fun update(beans: List<NreplService.BeanInfo>) {
        beansRef.set(beans.toList())
        lastUpdatedRef.set(Instant.now())
    }

    fun snapshot(): List<NreplService.BeanInfo> = beansRef.get()

    fun lastUpdated(): Instant? = lastUpdatedRef.get()

    companion object {
        fun getInstance(project: Project): AiContextMetadataStore = project.service()
    }
}
