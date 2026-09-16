package hu.baader.repl.ui

import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.Messages
import hu.baader.repl.nrepl.NreplService
import java.awt.BorderLayout
import javax.swing.*
import javax.swing.table.DefaultTableModel

class WatchPanel(private val service:NreplService):JPanel(BorderLayout()),Disposable {
    private val model=object:DefaultTableModel(arrayOf("Expression","State","Run","Time"),0){override fun isCellEditable(row:Int,column:Int)=false}
    private val table=JTable(model);private val ids=mutableListOf<String>()
    private val before=StructuredValuePanel();private val after=StructuredValuePanel();private val diff=JTextArea().apply{isEditable=false}
    private val status=JTextArea("Watches update after your REPL runs. No background reevaluation.",2,40).apply{isEditable=false;isOpaque=false;lineWrap=true}
    private var revision=0;private var disposed=false
    private val listener=service.onMessage{
        when{
            it["op"]=="session/reset"||it["op"]=="connection"&&it["state"] in setOf("CONNECTING","DISCONNECTED","FAILED")-> {revision++;ids.clear();model.rowCount=0;before.clear();after.clear();diff.text=""}
            it["op"] in setOf("eval","java-eval","watch/refresh","watch/add","watch/remove")-> refresh()
        }
    }
    init{
        add(WorkbookToolbar().apply{
            add(JButton("Pin watch…").apply{addActionListener{pin()}})
            add(JButton("Refresh values").apply{addActionListener{service.request("watch/refresh",onResult={refresh()},onError=::error)}})
            add(JButton("Remove").apply{addActionListener{selected()?.let{id->service.request("watch/remove",mapOf("watch-id" to id),{refresh()},::error)}}})
        },BorderLayout.NORTH)
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.selectionModel.addListSelectionListener{if(!it.valueIsAdjusting)selected()?.let(::show)}
        table.setDefaultRenderer(Any::class.java,object:javax.swing.table.DefaultTableCellRenderer(){
            override fun getTableCellRendererComponent(t:JTable,v:Any?,selected:Boolean,focus:Boolean,row:Int,col:Int):java.awt.Component{
                putClientProperty("html.disable",true);super.getTableCellRendererComponent(t,v,selected,focus,row,col)
                if(!selected){foreground=if(model.getValueAt(t.convertRowIndexToModel(row),1)=="CHANGED")com.intellij.ui.JBColor.BLUE else com.intellij.ui.JBColor.foreground()};return this
            }
        })
        val details=JTabbedPane().apply{addTab("Current",after);addTab("Previous",before);addTab("Changed fields",JScrollPane(diff))}
        add(JSplitPane(JSplitPane.VERTICAL_SPLIT,JScrollPane(table),details).apply{resizeWeight=0.35},BorderLayout.CENTER);add(status,BorderLayout.SOUTH)
    }
    fun pin(initial:String="last1"){
        val expression=JTextField(initial,40);val java=JCheckBox("Allow Java expression / method calls (runs after each REPL evaluation)")
        val form=JPanel(BorderLayout()).apply{add(JLabel("Path, e.g. last1.items[0].total or last1.items.size():"),BorderLayout.NORTH);add(expression,BorderLayout.CENTER);add(java,BorderLayout.SOUTH)}
        if(JOptionPane.showConfirmDialog(this,form,"Pin watch",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return
        service.request("watch/add",mapOf("expression" to expression.text.trim(),"allow-java" to java.isSelected.toString()),{status.text=it["value"];refresh()},::error)
    }
    private fun selected()=ids.getOrNull(table.selectedRow.takeIf{it>=0}?.let(table::convertRowIndexToModel)?:-1)
    fun refresh(){val ticket=++revision;val selected=selected();service.request("watch/list",onResult={
        if(disposed||ticket!=revision)return@request
        ids.clear();model.rowCount=0
        JsonParser.parseString(it["watches-json"]).asJsonArray.forEach{entry->val w=entry.asJsonObject;ids+=w["id"].asString;model.addRow(arrayOf(w["expression"].asString,w["state"].asString,w["sequence"].asLong,w["time"].asLong.let { time->if(time==0L)"Not sampled" else java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) }))}
        val at=ids.indexOf(selected)
        if(at>=0)table.setRowSelectionInterval(at,at)
        else {before.clear();after.clear();diff.text="";status.text=if(ids.isEmpty())"No pinned watches in this session." else "Select a watch to see its latest observation."}
    },onError=::error)}
    private fun show(id:String){val ticket=revision;service.request("watch/get",mapOf("watch-id" to id),{
        if(disposed||ticket!=revision||selected()!=id)return@request
        listOf("before-view" to before,"after-view" to after).forEach{(key,panel)->val value=it[key].orEmpty();if(value.isBlank())panel.clear()else panel.showValue(mapOf("view-data" to value),"")}
        diff.text=it["diff"].orEmpty();status.text="${it["state"]}: ${it["error"].orEmpty()} · displayed values are bounded; PARTIAL cannot establish equality."
    },::error)}
    private fun error(text:String){if(!disposed)status.text=text}
    override fun dispose(){disposed=true;revision++;listener.dispose()}
}
