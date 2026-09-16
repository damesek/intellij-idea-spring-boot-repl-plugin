package hu.baader.repl.ui

import com.google.gson.*
import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.Messages
import hu.baader.repl.nrepl.NreplService
import java.awt.BorderLayout
import javax.swing.*
import javax.swing.table.DefaultTableModel

class BeanExplorerPanel(private val service: NreplService, private val insert: (String)->Unit): JPanel(BorderLayout()), Disposable {
    private val query=JTextField(24)
    private val model=object:DefaultTableModel(arrayOf("Bean","Declared type"),0){override fun isCellEditable(row:Int,column:Int)=false}
    private val table=JTable(model)
    private val metadata=JTextArea().apply{isEditable=false;lineWrap=true;wrapStyleWord=true}
    private data class Method(val name:String,val descriptor:String,val signature:String,val types:List<String>){override fun toString()=signature}
    private val methods=JComboBox<Method>()
    private val status=JTextArea("Search definitions without creating beans. Select a method to prepare a call.",2,50).apply{isEditable=false;isOpaque=false;lineWrap=true}
    private val dependencies=DefaultListModel<String>()
    private val dependencyList=JList(dependencies)
    private var offset=0;private var total=0;private var revision=0;private var disposed=false;private var selected=""
    private val listener=service.onMessage { if(it["op"]=="session/reset"||it["op"]=="connection"&&it["state"] in setOf("DISCONNECTED","CONNECTING","FAILED")){revision++;model.rowCount=0;methods.removeAllItems();dependencies.clear();metadata.text="";selected=""} }
    init {
        val bar=WorkbookToolbar().apply{
            add(JLabel("Name / type:"));add(query)
            add(JButton("Search / refresh").apply{addActionListener{offset=0;refresh()}})
            add(JButton("Previous").apply{addActionListener{offset=(offset-100).coerceAtLeast(0);refresh()}})
            add(JButton("Next").apply{addActionListener{if(offset+100<total){offset+=100;refresh()}}})
        }
        query.addActionListener{offset=0;refresh()};table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.selectionModel.addListSelectionListener{if(!it.valueIsAdjusting&&table.selectedRow>=0)info(model.getValueAt(table.convertRowIndexToModel(table.selectedRow),0).toString())}
        dependencyList.addMouseListener(object:java.awt.event.MouseAdapter(){override fun mouseClicked(e:java.awt.event.MouseEvent){if(e.clickCount==2)dependencyList.selectedValue?.substringAfter(": ")?.let(::info)}})
        val detail=JPanel(BorderLayout()).apply{
            add(JTabbedPane().apply{addTab("Definition / proxy",JScrollPane(metadata));addTab("Dependencies (double-click to follow)",JScrollPane(dependencyList))},BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply{add(methods,BorderLayout.CENTER);add(JButton("Prepare method call…").apply{addActionListener{prepare()}},BorderLayout.SOUTH)},BorderLayout.SOUTH)
        }
        add(bar,BorderLayout.NORTH);add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT,JScrollPane(table),detail).apply{resizeWeight=0.4},BorderLayout.CENTER);add(status,BorderLayout.SOUTH)
    }
    fun refresh(){val ticket=++revision;service.request("beans/list",mapOf("query" to query.text,"offset" to offset.toString()),{
        if(disposed||ticket!=revision)return@request
        model.rowCount=0;JsonParser.parseString(it["beans-json"]).asJsonArray.forEach{row->val b=row.asJsonObject;model.addRow(arrayOf(b["name"].asString,b["type"].asString))}
        total=it["total"]?.toIntOrNull()?:0;status.text=if(model.rowCount==0)"No definitions on this page." else "${offset+1}–${offset+model.rowCount} of $total definitions. Lazy beans remain uninitialized."
    },::error)}
    private fun info(name:String){val ticket=++revision;selected=name;methods.removeAllItems();metadata.text="";dependencies.clear();status.text="Loading $name…";service.request("beans/info",mapOf("bean" to name),{
        if(disposed||ticket!=revision)return@request
        val bean=JsonParser.parseString(it.getValue("bean-json")).asJsonObject
        methods.removeAllItems();bean["methods"].asJsonArray.forEach{entry->val m=entry.asJsonObject;if(m["supported"].asBoolean)methods.addItem(Method(m["name"].asString,m["descriptor"].asString,m["signature"].asString,m["parameters"].asJsonArray.map{p->p.asString}))}
        bean.remove("methods");metadata.text=GsonBuilder().setPrettyPrinting().create().toJson(bean);metadata.caretPosition=0
        dependencies.clear();for(key in listOf("dependencies","dependents"))bean[key].asJsonArray.forEach{entry->dependencies.addElement("$key: ${entry.asString}")}
        status.text="Selected $name. Metadata and dependency navigation do not call the bean."
    },::error)}
    private fun prepare(){
        val method=methods.selectedItem as? Method?:return;val bean=selected;val ticket=revision;val inputs=mutableListOf<String>()
        fun next(index:Int){
            if(disposed||ticket!=revision)return
            if(index==method.types.size){service.request("beans/prepare",mapOf("bean" to bean,"method" to method.name,"descriptor" to method.descriptor,"inputs-json" to Gson().toJson(inputs)),{
                if(!disposed&&ticket==revision){insert(it.getValue("code"));status.text=it["value"]}
            },::error);return}
            service.request("beans/compatible-data",mapOf("bean" to bean,"method" to method.name,"descriptor" to method.descriptor,"parameter" to index.toString()),{
                if(disposed||ticket!=revision)return@request
                val names=JsonParser.parseString(it["snapshots-json"]).asJsonArray.map{row->row.asJsonObject["name"].asString}.toTypedArray()
                if(names.isEmpty()){error("No compatible DATA for argument $index (${method.types[index]}). Freeze or edit a DATA snapshot with that declared type first.");return@request}
                val chosen=Messages.showChooseDialog("DATA for argument $index: ${method.types[index]}","Prepare $bean.${method.name}",names,names[0],null)
                if(chosen>=0){inputs+=names[chosen];next(index+1)}
            },::error)
        }
        next(0)
    }
    private fun error(text:String){if(!disposed)status.text=text}
    override fun dispose(){disposed=true;revision++;listener.dispose()}
}
