package hu.baader.repl.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe

object SecretStore {
    private fun attributes(key: String) = CredentialAttributes("Spring Boot REPL: " + key)
    fun read(key: String): String = PasswordSafe.instance.get(attributes(key))?.getPasswordAsString().orEmpty()
    fun write(key: String, value: String) {
        PasswordSafe.instance.set(attributes(key), value.takeIf(String::isNotBlank)?.let { Credentials("sb-repl", it) })
    }
    @Synchronized fun aiKey(): String {
        val settings = PluginSettingsState.getInstance()
        val legacy = settings.legacyApiKey
        if (legacy.isNotBlank()) {
            if (read("ai-key").isBlank()) write("ai-key", legacy)
            settings.legacyApiKey = ""
        }
        return read("ai-key")
    }
    fun setAiKey(key: String) { write("ai-key", key); PluginSettingsState.getInstance().legacyApiKey = "" }
}
