package hu.baader.repl.trace

import hu.baader.repl.protocol.*
import hu.baader.repl.ui.WorkbookToolbar
import java.awt.*
import javax.swing.*
import javax.swing.tree.*

/** SQL groups expand to individual observed executions and their actual parent calls. */
class RecordingSqlPanel(private val select: (Long) -> Unit) : JPanel(BorderLayout()) {
    private val tree=JTree(DefaultMutableTreeNode("SQL"))
    private val detail=JTextArea().apply { isEditable=false; font=Font(Font.MONOSPACED,Font.PLAIN,12) }
    private val panes=JSplitPane(JSplitPane.VERTICAL_SPLIT,JScrollPane(tree),JScrollPane(detail)).apply { resizeWeight=.45 }
    private var paneHeight=0
    override fun doLayout() {
        super.doLayout()
        if(panes.height>0 && panes.height!=paneHeight) { paneHeight=panes.height;panes.dividerLocation=(panes.height*.45).toInt() }
    }
    private val summary=JTextArea(3,20).apply { isEditable=false;lineWrap=true;wrapStyleWord=true;isOpaque=false }
    private val search=JTextField(18)
    private val alerts=JCheckBox("Suspected N+1 only")
    private var current=SqlSnapshot.disabled()
    private var reference: SqlSnapshot?=null
    private var recordId: String?=null
    private data class Row(val label:String,val events:List<SqlObservation>) { override fun toString()=label }
    init {
        val toolbar=WorkbookToolbar()
        toolbar.add(JLabel("SQL / datasource / source:"));toolbar.add(search);toolbar.add(alerts)
        toolbar.add(JButton("Filter").apply { addActionListener { rebuild() } })
        search.addActionListener { rebuild() };alerts.addActionListener { rebuild() }
        toolbar.add(JButton("Pin SQL baseline").apply { toolTipText="Freezes the current root filter; retained across recordings.";addActionListener { reference=current;updateSummary() } })
        toolbar.add(JButton("Clear baseline").apply { addActionListener { reference=null;updateSummary() } })
        val north=JPanel(BorderLayout()).apply { add(toolbar,BorderLayout.NORTH);add(summary,BorderLayout.CENTER) }
        add(north,BorderLayout.NORTH)
        add(panes,BorderLayout.CENTER)
        tree.addTreeSelectionListener {
            val row=(tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? Row ?: return@addTreeSelectionListener
            show(row.events)
        }
        val open=JButton("Open originating call and source").apply { addActionListener {
            ((tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? Row)?.events?.firstOrNull()?.let { select(it.parent()) }
        } }
        add(open,BorderLayout.SOUTH)
    }
    fun display(record: CallRecording?, root: Long?) {
        val next=record?.let { if(root==null) it.sql else it.sql.subtree(it.calls.filter { c -> c.root()==root }.map { c -> c.id() }.toSet()) } ?: SqlSnapshot.disabled()
        if(next==current && record?.id==recordId) return
        current=next;recordId=record?.id;updateSummary();rebuild()
    }
    fun selectSql(id: Long) {
        val root=tree.model.root as DefaultMutableTreeNode
        val nodes=root.depthFirstEnumeration()
        while(nodes.hasMoreElements()) {
            val node=nodes.nextElement() as DefaultMutableTreeNode
            val row=node.userObject as? Row ?: continue
            if(row.events.size==1 && row.events[0].id()==id) { tree.selectionPath=TreePath(node.path);tree.scrollPathToVisible(TreePath(node.path));return }
        }
        current.events().firstOrNull { it.id()==id }?.let { show(listOf(it)) }
    }
    private fun show(events: List<SqlObservation>) {
        val e=events.first()
        detail.text=SqlText.format(e.sql())+"\n\n${events.size} execution(s) · ${"%.3f".format(java.util.Locale.ROOT,events.sumOf { it.durationNanos() }/1e6)} ms\n"+
            "Datasource: ${e.datasource()}\nSource: ${e.sourceClass()}.${e.sourceMethod()} (${e.sourceFile()}:${e.sourceLine()})\n"+
            events.joinToString("\n") { "SQL #${it.id()} → call #${it.parent()} · root #${it.root()} · thread ${it.thread()} · ${it.operation()} · ${it.durationNanos()/1e6} ms ${it.error()}" }+
            "\n\nSQL literals are replaced with ?. Bound parameter values and ResultSet rows are not captured. JDBC client duration includes driver/network work. Batch = one executeBatch call."
        detail.caretPosition=0
    }
    private fun updateSummary() { summary.text=reference?.let { RecordingSql.compare(it,current) } ?: RecordingSql.summary(current) }
    private fun rebuild() {
        val expanded=mutableSetOf<String>()
        tree.getExpandedDescendants(TreePath(tree.model.root))?.asIterator()?.forEachRemaining { expanded += it.lastPathComponent.toString() }
        val root=DefaultMutableTreeNode("Observed JDBC calls; repetition is a diagnostic hint, not proof of N+1")
        val findings=current.findings()
        val warningKeys=findings.map { "${it.root()}:${it.fingerprint()}" }.toSet()
        val groups=if(alerts.isSelected) findings else current.groups()
        for(group in groups) {
            val e=group.sample(); val query=search.text.trim()
            if(query.isNotEmpty() && !(e.sql()+e.datasource()+e.sourceClass()+e.sourceMethod()).contains(query,true)) continue
            val warning="${group.root()}:${group.fingerprint()}" in warningKeys
            val node=DefaultMutableTreeNode(Row("${if(warning) "⚠ suspected N+1 · " else ""}${group.events().size}× · root #${e.root()} · ${e.sql().take(180)}",group.events()))
            group.events().forEach { node.add(DefaultMutableTreeNode(Row("#${it.id()} → call #${it.parent()} · ${it.durationNanos()/1e6} ms ${it.error()}",listOf(it)))) }
            root.add(node)
        }
        if(!alerts.isSelected) current.events().filter { it.kind()=="CONNECTION" }.forEach { root.add(DefaultMutableTreeNode(Row("Connection → call #${it.parent()} · ${it.datasource()}",listOf(it)))) }
        tree.model=DefaultTreeModel(root)
        val nodes=root.depthFirstEnumeration()
        while(nodes.hasMoreElements()) { val node=nodes.nextElement() as DefaultMutableTreeNode; if(node.toString() in expanded) tree.expandPath(TreePath(node.path)) }
        tree.expandRow(0)
    }
}
