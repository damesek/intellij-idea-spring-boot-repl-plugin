package hu.baader.repl.ui

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.intellij.openapi.ui.DialogWrapper
import hu.baader.repl.nrepl.NreplService
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

object DataCopyEditor {
    fun open(service: NreplService, name: String, changed: () -> Unit, report: (String) -> Unit) {
        service.request("snapshot/edit-read", mapOf("name" to name), { data ->
            val json = JTextArea(GsonBuilder().setPrettyPrinting().create().toJson(JsonParser.parseString(data.getValue("json"))), 25, 90)
            val target = JTextField("$name-copy", 24)
            val type = JTextField(data["type"], 35)
            val status = JTextArea("Edits a detached copy. Type validation invokes DTO constructors/deserializers. The original DATA and live object are preserved.", 3, 60).apply { isEditable=false; lineWrap=true; wrapStyleWord=true; isOpaque=false }
            val dialog = object : DialogWrapper(true) {
                var pending=false
                init { title="Edit DATA copy: $name"; init() }
                fun fields()=mapOf("name" to name,"version" to data.getValue("version"),"target" to target.text.trim(),"type" to type.text.trim(),"json" to json.text)
                override fun createCenterPanel() = JPanel(BorderLayout(0,8)).apply {
                    preferredSize=Dimension(860,600)
                    add(WorkbookToolbar().apply {
                        add(JLabel("New DATA name:"));add(target);add(JLabel("Restore type:"));add(type)
                        add(JButton("Validate type").apply { addActionListener {
                            if(pending)return@addActionListener
                            val request=fields();pending=true;setOKActionEnabled(false)
                            service.request("snapshot/edit-validate",request,{
                                pending=false;setOKActionEnabled(true);status.text=if(fields()==request)it["value"] else "Edited since validation; saving validates the current JSON again."
                            },{pending=false;setOKActionEnabled(true);status.text=it})
                        } })
                    },BorderLayout.NORTH)
                    add(JScrollPane(json),BorderLayout.CENTER);add(status,BorderLayout.SOUTH)
                }
                override fun doOKAction() {
                    if(pending)return
                    pending=true;setOKActionEnabled(false)
                    service.request("snapshot/edit-copy",fields(),{
                        pending=false;changed();report(it["value"].orEmpty());close(OK_EXIT_CODE)
                    },{pending=false;setOKActionEnabled(true);status.text=it})
                }
            }
            dialog.show()
        },report)
    }
}
