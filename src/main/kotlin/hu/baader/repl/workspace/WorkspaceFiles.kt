package hu.baader.repl.workspace

import com.google.gson.JsonParser
import com.google.gson.Gson
import hu.baader.repl.protocol.WorkspaceArchive
import java.nio.file.Files
import java.nio.file.Path

object WorkspaceFiles {
    data class Opened(val document: WorkspaceDocument, val snapshots: Int)
    fun read(path: Path): Opened = WorkspaceArchive.read(path).use { archive ->
        val manifest = JsonParser.parseString(Files.readString(archive.files().getValue("manifest.json"))).asJsonObject
        require(manifest["format"]?.asString == "sbrepl-workspace" && manifest["formatVersion"]?.asInt == 1) { "Unsupported workspace format" }
        val source = archive.files().getValue("workspace.json")
        require(WorkspaceArchive.sha256(source) == manifest["workspaceSha256"]?.asString) { "Workspace metadata checksum mismatch" }
        val entries = manifest.getAsJsonArray("entries") ?: error("Missing workspace entries")
        require(entries.size() <= 1200)
        val objects = mutableSetOf("manifest.json", "workspace.json")
        val names = mutableSetOf<String>(); val currents = mutableSetOf<String>()
        entries.forEach {
            val e = it.asJsonObject; val name = e["name"].asString; val version = e["version"].asString
            require(name.length in 1..128 && version.matches(Regex("[a-f0-9]{64}")) && e["kind"].asString in setOf("DATA", "CASE", "RECIPE"))
            val file = "objects/$version.json"
            require(e["path"].asString == file && archive.files().containsKey(file) && names.add("$name\n$version"))
            require(e["current"].asJsonPrimitive.isBoolean)
            if (e["current"].asBoolean) require(currents.add(name))
            objects += file
        }
        require(objects == archive.files().keys) { "Unreferenced workspace object" }
        Opened(WorkspaceDocument.decode(Files.readString(source)), entries.size())
    }
    fun writeOffline(path: Path, document: WorkspaceDocument) {
        val temp = WorkspaceArchive.privateDirectory()
        try {
            val source = temp.resolve("workspace.json"); Files.writeString(source, document.encode())
            val manifest = temp.resolve("manifest.json")
            Files.writeString(manifest, Gson().toJson(mapOf("format" to "sbrepl-workspace", "formatVersion" to 1, "workspaceSha256" to WorkspaceArchive.sha256(source), "entries" to emptyList<String>())))
            WorkspaceArchive.write(path, mapOf("workspace.json" to source, "manifest.json" to manifest))
        } finally { WorkspaceArchive.removeDirectory(temp) }
    }
}
