package hu.baader.repl.trace

import hu.baader.repl.protocol.*
import hu.baader.repl.ui.WorkbookToolbar
import java.awt.*
import javax.swing.*
import javax.swing.tree.*

/** The ORM layer of the existing recording; all values are frozen metadata. */
class RecordingHibernatePanel(private val origin:(Long)->Unit,private val mapping:(String)->Unit) : JPanel(BorderLayout()) {
    private val tree=JTree(DefaultMutableTreeNode("Hibernate"))
    private val details=JTextArea().apply { isEditable=false;font=Font(Font.MONOSPACED,Font.PLAIN,12) }
    private val summary=JTextArea(4,20).apply { isEditable=false;lineWrap=true;wrapStyleWord=true;isOpaque=false }
    private val search=JTextField(15)
    private val kind=JComboBox((listOf("All events","Findings")+HibernateObservation.KINDS.sorted()).toTypedArray())
    private val panes=JSplitPane(JSplitPane.VERTICAL_SPLIT,JScrollPane(tree),JScrollPane(details)).apply { resizeWeight=.45 }
    private var paneHeight=0
    private var recordingId:String?=null
    private var current=HibernateSnapshot.disabled()
    private var sql=SqlSnapshot.disabled()
    private var baseline:HibernateSnapshot?=null
    private var selected:HibernateObservation?=null
    private data class Row(val label:String,val event:HibernateObservation?) { override fun toString()=label }
    override fun doLayout(){super.doLayout();if(panes.height>0&&paneHeight!=panes.height){paneHeight=panes.height;panes.dividerLocation=(panes.height*.45).toInt()}}
    init {
        val toolbar=WorkbookToolbar()
        toolbar.add(JLabel("Entity / relationship / source:"));toolbar.add(search);toolbar.add(kind)
        toolbar.add(JButton("Filter").apply{addActionListener{rebuild()}})
        toolbar.add(JButton("Pin Hibernate baseline").apply{addActionListener{baseline=current;updateSummary()}})
        toolbar.add(JButton("Clear baseline").apply{addActionListener{baseline=null;updateSummary()}})
        search.addActionListener{rebuild()};kind.addActionListener{rebuild()}
        add(JPanel(BorderLayout()).apply{add(toolbar,BorderLayout.NORTH);add(summary,BorderLayout.CENTER)},BorderLayout.NORTH)
        add(panes,BorderLayout.CENTER)
        add(WorkbookToolbar().apply {
            add(JButton("Open originating call").apply{addActionListener{selected?.let{origin(it.parent())}}})
            add(JButton("Open entity mapping").apply{toolTipText="Opens current project source; recorded metadata is historical.";addActionListener{selected?.entity()?.takeIf(String::isNotBlank)?.let(mapping)}})
        },BorderLayout.SOUTH)
        tree.addTreeSelectionListener{((tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? Row)?.event?.let(::show)}
    }
    fun display(record:CallRecording?,root:Long?) {
        if(recordingId!=record?.id){selected=null;details.text="";recordingId=record?.id}
        val parents=record?.calls?.filter{root==null||it.root()==root}?.map{it.id()}?.toSet().orEmpty()
        val next=record?.hibernate?.subtree(parents)?:HibernateSnapshot.disabled()
        val nextSql=record?.sql?.subtree(parents)?:SqlSnapshot.disabled()
        if(next==current&&sql==nextSql)return
        current=next;sql=nextSql;updateSummary();rebuild()
    }
    private fun updateSummary(){summary.text=baseline?.let{RecordingHibernate.compare(it,current)}?:RecordingHibernate.summary(current)}
    fun selectHibernate(id:Long) {
        current.events().firstOrNull{it.id()==id}?.let(::show)
        val nodes=(tree.model.root as DefaultMutableTreeNode).depthFirstEnumeration()
        while(nodes.hasMoreElements()) {
            val node=nodes.nextElement() as DefaultMutableTreeNode
            if((node.userObject as? Row)?.event?.id()==id){tree.selectionPath=TreePath(node.path);tree.scrollPathToVisible(TreePath(node.path));return}
        }
    }
    private fun show(e:HibernateObservation) {
        selected=e
        val related=current.sqlFor(e.id(),sql)
        val findings=current.findings(sql,sql.threshold()).filter{e.id() in it.events()}
        val explanation=findings.joinToString("\n"){"${it.kind()} · ${it.events().size} initializations · ${it.sqlIds().size} SQL executions\n${it.explanation()}"}
        details.text=(if(explanation.isEmpty()) "" else "$explanation\n\n")+"${e.kind()} #${e.id()}\nEntity: ${e.entity()}\nRelationship: ${e.role()}\n${e.detail()}\n"+
            "Session: ${e.session()}\nJava parent #${e.parent()} · ORM parent #${e.parentOrm()} · root #${e.root()} · thread ${e.thread()}\n"+
            "Source: ${e.sourceClass()}.${e.sourceMethod()} (${e.sourceFile()}:${e.sourceLine()})\nDuration: ${e.durationNanos()/1e6} ms · response handling: ${e.responsePhase()}\n${e.error()}\n\n"+
            related.joinToString("\n\n"){"SQL #${it.id()} · ${it.durationNanos()/1e6} ms\n${SqlText.format(it.sql())}"}+
            "\n\nMetadata only; entity IDs and field values are not captured here. ORM durations include nested JDBC work; do not sum overlapping spans."
        details.caretPosition=0
    }
    private fun rebuild() {
        val root=DefaultMutableTreeNode("Hibernate 6.6 · correlated session events")
        val findings=current.findings(sql,sql.threshold())
        val warning=findings.flatMap{it.events()}.toSet()
        val rows=current.events().filter{e->
            (kind.selectedItem=="All events"||kind.selectedItem==e.kind()||kind.selectedItem=="Findings"&&e.id() in warning)&&
                "${e.entity()} ${e.role()} ${e.detail()} ${e.sourceClass()} ${e.sourceMethod()}".contains(search.text.trim(),true)
        }
        rows.groupBy{"root #${it.root()} · ${it.role().ifEmpty{it.entity().ifEmpty{it.kind()}}}"}.forEach{(label,events)->
            val group=DefaultMutableTreeNode(Row("$label · ${events.size} events",null))
            events.forEach{e->group.add(DefaultMutableTreeNode(Row("${if(e.id() in warning) "⚠ " else ""}#${e.id()} ${e.kind()} · ${e.durationNanos()/1e6} ms",e)))}
            root.add(group)
        }
        tree.model=DefaultTreeModel(root);tree.expandRow(0)
        selected?.id()?.let{old->current.events().firstOrNull{it.id()==old}?.let(::show)}
    }
}
