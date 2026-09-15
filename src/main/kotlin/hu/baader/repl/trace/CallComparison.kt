package hu.baader.repl.trace

import com.intellij.ui.JBColor
import hu.baader.repl.protocol.RecordedCall
import hu.baader.repl.protocol.ValueTree
import java.awt.*
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

object CallComparison {
    data class Change(val path: String, val kind: String, val before: String, val after: String)
    data class Result(val changes: List<Change>, val partial: Boolean, val truncated: Boolean)
    fun compare(before: RecordedCall, after: RecordedCall): Result {
        val a=CallPresentation(before);val b=CallPresentation(after)
        val changes=mutableListOf<Change>();var truncated=false
        var partial=a.partial || b.partial || a.unavailable || b.unavailable || before.status()=="INCOMPLETE" || after.status()=="INCOMPLETE"
        fun add(path: String, kind: String, left: String, right: String) {
            fun bound(text: String)=if(text.length>8192) text.take(8191)+"…" else text
            if(changes.size<2000) changes+=Change(path,kind,bound(left),bound(right)) else truncated=true
        }
        fun display(value: ValueTree?) = value?.let { "${it.type()} · ${CallPresentation.summary(it,max=8192)}" } ?: "(not captured / absent)"
        fun childKeys(node: ValueTree): Map<String,ValueTree> {
            val seen=mutableMapOf<String,Int>()
            return node.children().mapIndexed { index,child ->
                val segment=if(node.kind()=="ARRAY") "[$index]" else "['${child.label().replace("\\","\\\\").replace("'","\\'")}']"
                val occurrence=seen.merge(segment,1,Int::plus)!!
                (segment+if(occurrence>1) "#$occurrence" else "") to child
            }.toMap()
        }
        fun visit(path: String,left: ValueTree?,right: ValueTree?,uncertain: Boolean=false) {
            if(changes.size>=2000 || path.length>4096) { truncated=true;return }
            if(left==null || right==null) {
                if(left!=right) add(path,if(uncertain) "UNKNOWN" else if(left==null) "ADDED" else "REMOVED",display(left),display(right))
                return
            }
            if(left.kind() in setOf("LIMIT","ERROR","REFERENCE") || right.kind() in setOf("LIMIT","ERROR","REFERENCE")) {
                partial=true;add(path,"UNKNOWN",display(left),display(right));return
            }
            if(left.kind()!=right.kind() || left.type()!=right.type() || left.text()!=right.text())
                add(path,"CHANGED",display(left),display(right))
            val l=childKeys(left);val r=childKeys(right)
            val incomplete=uncertain || CallPresentation.limited(left) || CallPresentation.limited(right)
            (l.keys+r.keys).forEach { visit(path+it,l[it],r[it],incomplete) }
        }
        if(before.status()!=after.status()) add("status","CHANGED",before.status(),after.status())
        listOf(Triple("input",a.input,b.input),Triple("result",a.output,b.output),Triple("exception",a.exception,b.exception)).forEach { (path,left,right) ->
            // A failed/void call legitimately has no result, and a successful call no exception.
            val absentExpected=when(path) {
                "result" -> before.status() in setOf("ERROR","RUNNING") || after.status() in setOf("ERROR","RUNNING") ||
                    before.descriptor().endsWith(")V") || after.descriptor().endsWith(")V")
                "exception" -> before.status()!="ERROR" || after.status()!="ERROR"
                else -> false
            }
            val missing=(left==null || right==null) && !absentExpected
            if(missing) { partial=true;add(path,"UNKNOWN",display(left),display(right)) }
            else visit(path,left,right,a.partial || b.partial)
        }
        return Result(changes,partial,truncated)
    }
}

/** Pins an immutable captured call, then compares each newly selected call without a server request. */
class CallComparisonPanel : JPanel(BorderLayout()) {
    private val title=JTextArea("Pin a reference call, then select another call.").apply { isEditable=false;lineWrap=true;wrapStyleWord=true;isOpaque=false;rows=2 }
    private val note=JTextArea().apply { isEditable=false;lineWrap=true;wrapStyleWord=true;isOpaque=false;rows=2 }
    private val model=object : DefaultTableModel(arrayOf("Field","Change","Reference","Selected"),0) { override fun isCellEditable(row: Int,column: Int)=false }
    private val table=JTable(model)
    private val full=JTextArea().apply { isEditable=false;lineWrap=true;wrapStyleWord=true;font=Font(Font.MONOSPACED,Font.PLAIN,12);rows=5 }
    private var changes=emptyList<CallComparison.Change>()
    private var shown: Pair<RecordedCall?,RecordedCall?>?=null
    init {
        table.autoResizeMode=JTable.AUTO_RESIZE_OFF;table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        listOf(270,100,300,300).forEachIndexed { i,width -> table.columnModel.getColumn(i).preferredWidth=width }
        table.setDefaultRenderer(Any::class.java,object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable,value: Any?,selected: Boolean,focus: Boolean,row: Int,column: Int): Component {
                putClientProperty("html.disable",true)
                super.getTableCellRendererComponent(t,value,selected,focus,row,column)
                if(!selected) {
                    background=when(changes.getOrNull(t.convertRowIndexToModel(row))?.kind) {
                        "ADDED" -> JBColor(Color(231,246,235),Color(40,68,48))
                        "REMOVED" -> JBColor(Color(255,235,235),Color(75,42,42))
                        "UNKNOWN" -> JBColor(Color(248,241,220),Color(72,63,39))
                        else -> JBColor(Color(232,241,255),Color(43,57,76))
                    }
                    foreground=JBColor.foreground()
                }
                text=value?.toString()?.replace('\n',' ')?.take(240).orEmpty()
                return this
            }
        })
        table.selectionModel.addListSelectionListener {
            if(!it.valueIsAdjusting) changes.getOrNull(table.selectedRow)?.let { c ->
                full.text="${c.path} · ${c.kind}\nReference:\n${c.before}\n\nSelected:\n${c.after}";full.caretPosition=0
            }
        }
        val tableScroll=JScrollPane(table).apply { setColumnHeaderView(table.tableHeader) }
        val split=JSplitPane(JSplitPane.VERTICAL_SPLIT,tableScroll,JScrollPane(full)).apply { resizeWeight=0.7;dividerLocation=280 }
        add(title,BorderLayout.NORTH);add(split,BorderLayout.CENTER);add(note,BorderLayout.SOUTH)
    }
    fun showComparison(reference: RecordedCall?, selected: RecordedCall?) {
        val key=reference to selected
        if(shown==key) return
        shown=key;changes=emptyList();model.rowCount=0;full.text=""
        if(reference==null || selected==null) {
            title.text="Pin a reference call, then select another call.";note.text="Compares captured input, result and exception fields."
            return
        }
        val result=CallComparison.compare(reference,selected);changes=result.changes
        title.text="Reference #${reference.id()} ${reference.className().substringAfterLast('.')}.${reference.method()} → Selected #${selected.id()} ${selected.className().substringAfterLast('.')}.${selected.method()}\n" +
            "${CallPresentation.duration(reference)} ms → ${CallPresentation.duration(selected)} ms" +
            if(reference.className()!=selected.className() || reference.method()!=selected.method() || reference.descriptor()!=selected.descriptor()) " · Different methods / overloads" else ""
        changes.forEach { model.addRow(arrayOf(it.path,it.kind,it.before,it.after)) }
        note.text=when {
            result.truncated -> "Comparison reached a display limit; results are incomplete."
            result.partial -> "${changes.size} reported field changes/unknowns. Partial or unavailable previews cannot establish equality."
            changes.isEmpty() -> "No differences in captured fields. This compares display previews, not complete live objects."
            else -> "${changes.size} differences in captured fields. Select a row for larger before/after text."
        }
    }
}
