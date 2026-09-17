package hu.baader.repl.runner

import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.application.ApplicationConfiguration
import org.junit.Assert.*
import org.junit.Test

class RunExtensionContractTest {
    @Test fun extensionHasTheSuperclassRequiredByJavaExtensionPoint() {
        assertTrue(RunConfigurationExtension::class.java.isAssignableFrom(SpringBootReplRunConfigurationExtension::class.java))
        assertNotNull(SpringBootReplRunConfigurationExtension())
    }
    @Test fun savedDedicatedConfigurationRetainsItsJavaSuperclass() {
        assertTrue(ApplicationConfiguration::class.java.isAssignableFrom(SpringBootReplRunConfiguration::class.java))
    }
    private fun configuration(typeId: String): com.intellij.execution.configurations.RunConfigurationBase<Any> {
        val project = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(com.intellij.openapi.project.Project::class.java)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString", "getName" -> "REPL test project"
                else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else null
            }
        } as com.intellij.openapi.project.Project
        return object : com.intellij.execution.configurations.RunConfigurationBase<Any>(project, null, "existing") {
            override fun getType() = object : com.intellij.execution.configurations.ConfigurationType {
                override fun getDisplayName() = typeId
                override fun getConfigurationTypeDescription() = typeId
                override fun getIcon(): javax.swing.Icon? = null
                override fun getId() = typeId
                override fun getConfigurationFactories() = emptyArray<com.intellij.execution.configurations.ConfigurationFactory>()
            }
            override fun getConfigurationEditor(): com.intellij.openapi.options.SettingsEditor<out com.intellij.execution.configurations.RunConfiguration> = error("Not used")
            override fun getState(executor: com.intellij.execution.Executor, environment: com.intellij.execution.runners.ExecutionEnvironment): com.intellij.execution.configurations.RunProfileState? = null
        }
    }
    private fun invoke(extension: SpringBootReplRunConfigurationExtension, method: String,
                       config: com.intellij.execution.configurations.RunConfigurationBase<*>, xml: org.jdom.Element? = null): Any? {
        val types = if (xml == null) arrayOf(com.intellij.execution.configurations.RunConfigurationBase::class.java)
            else arrayOf(com.intellij.execution.configurations.RunConfigurationBase::class.java, org.jdom.Element::class.java)
        val target = extension.javaClass.getDeclaredMethod(method, *types).apply { isAccessible = true }
        return if (xml == null) target.invoke(extension, config) else target.invoke(extension, config, xml)
    }
    @Test fun checkboxRoundTripsWithoutChangingTheExistingRunConfiguration() {
        val config=configuration("SpringBootApplicationConfigurationType")
        val extension=SpringBootReplRunConfigurationExtension()
        assertTrue(extension.isApplicableFor(config))
        assertFalse(extension.isEnabledFor(config,null))
        val stored=org.jdom.Element("extension").addContent(org.jdom.Element("springBootRepl").setAttribute("enabled","true"))
        invoke(extension,"readExternal",config,stored)
        assertTrue(extension.isEnabledFor(config,null))
        val saved=org.jdom.Element("extension").addContent(org.jdom.Element("unrelated").setText("keep"))
        invoke(extension,"writeExternal",config,saved)
        assertEquals("true",saved.getChild("springBootRepl").getAttributeValue("enabled"))
        assertEquals("keep",saved.getChildText("unrelated"))
        invoke(extension,"readExternal",config,org.jdom.Element("extension"))
        invoke(extension,"writeExternal",config,saved)
        assertNull(saved.getChild("springBootRepl"))
        assertEquals("existing",config.name)
    }
    @Test fun modernEditorKeepsItsCheckboxVisibleAndUnrelatedTypesStayUnaffected() {
        val config=configuration("SpringBootApplicationConfigurationType")
        val extension=SpringBootReplRunConfigurationExtension()
        val fragment=(invoke(extension,"createFragments",config) as List<*>).single() as com.intellij.execution.ui.SettingsEditorFragment<*, *>
        assertFalse(fragment.isCanBeHidden)
        assertEquals("Enable Spring Boot Debug REPL and MCP",(fragment.component() as com.intellij.ui.components.JBCheckBox).text)
        assertFalse(extension.isApplicableFor(configuration("Kotlin")))
        assertFalse(extension.isApplicableFor(configuration("ClojureREPL")))
        fragment.dispose()
    }
    @Test fun pluginUsesItsOwnLoaderAndDeclaresJavaDependency() {
        val xml=javaClass.getResourceAsStream("/META-INF/plugin.xml")!!.use { String(it.readAllBytes(),Charsets.UTF_8) }
        assertFalse(xml.contains("use-idea-classloader"))
        assertTrue(xml.contains("<depends>com.intellij.java</depends>"))
        assertTrue(xml.contains("<name>Spring Boot Debug REPL and MCP</name>"))
        assertTrue(xml.contains("<id>hu.baader.java-over-nrepl</id>"))
        assertTrue(xml.contains("toolWindow id=\"Spring Boot REPL\""))
        assertTrue(xml.contains("SpringBootReplRunConfigurationExtension"))
    }
}
