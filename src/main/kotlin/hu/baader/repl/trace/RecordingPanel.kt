package hu.baader.repl.trace

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import hu.baader.repl.protocol.RecordedCall
import hu.baader.repl.ui.WorkbookToolbar
import hu.baader.repl.workspace.WorkspaceStore
import java.awt.*
import java.awt.event.ActionEvent
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class RecordingPanel(private val project: Project, private val inspect: (Map<String,String>) -> Unit) : JPanel(BorderLayout()), Disposable {
    private val controller=RecordingController.get(project)
    private data class Choice(val id: Long?, val text: String) { override fun toString()=text }
    private val roots=JComboBox<Choice>()
    private val threads=JComboBox<Choice>()
    private val search=JTextField(20).apply { toolTipText="Search class, method, parameter names and captured values";accessibleContext.accessibleName="Search recorded calls" }
    private val errors=JCheckBox("Errors only")
    private val minimum=JSpinner(SpinnerNumberModel(0.0,0.0,1_000_000_000.0,10.0)).apply { preferredSize=Dimension(86,28);toolTipText="Minimum recorded duration in milliseconds" }
    private val cache=CallPresentationCache()
    private var presentations=emptyMap<Long,CallPresentation>()
    private val zoomLabel=JLabel("100%")
    private val graph=CallGraph({ selectNode(it.id()) }, { updateZoom();pauseFollowing() })
    private val timeline=CallTimeline({ selectNode(it) }, { range=it;filterChanged() })
    private val graphTabs=JTabbedPane()
    private val values=JPanel(BorderLayout())
    private val comparison=CallComparisonPanel()
    private val detailTabs=JTabbedPane()
    private val sqlPanel=RecordingSqlPanel { controller.select(it) }
    private val hibernatePanel=RecordingHibernatePanel({ controller.select(it) }) { name ->
        val type=com.intellij.psi.JavaPsiFacade.getInstance(project).findClass(name.replace('$','.'),com.intellij.psi.search.GlobalSearchScope.allScope(project))
        if(type?.canNavigate()==true)type.navigate(true) else controller.error("Entity mapping source is not available in this project: $name")
    }
    private val groupSql=JCheckBox("Group SQL",true)
    private var sqlSelected: Long?=null
    private val status=textArea(2)
    private val sourceNote=textArea(2)
    private val filterNote=JLabel()
    private val breadcrumb=JPanel(FlowLayout(FlowLayout.LEFT,2,0))
    private val breadcrumbScroll=JScrollPane(breadcrumb,JScrollPane.VERTICAL_SCROLLBAR_NEVER,JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED).apply { preferredSize=Dimension(500,46);minimumSize=Dimension(100,46);border=null }
    private val follow=JCheckBox("Follow latest",true)
    private val inline=JCheckBox("Values in source",true)
    private var updating=false
    private var disposed=false
    private var shown: RecordedCall?=null
    private var shownRecording: String?=null
    private var lastSelection: Long?=null
    private var reference: RecordedCall?=null
    private var range: CallTimeRange?=null
    private var focus: Long?=null
    private var matches=emptyList<RecordedCall>()
    private val start=JButton("New recording…")
    private val stop=JButton("Stop recording")
    private val save=JButton("Save recording…")
    private val load=JButton("Open recording…")
    private val live=JButton("Inspect live")
    private val experiment=JButton("Create CASE from call…")
    private val previous=JButton("Previous")
    private val next=JButton("Next")
    private val nextError=JButton("Next error")
    private val caller=JButton("Caller")
    private val pin=JButton("Pin reference")
    private val clearReference=JButton("Clear reference")
    private val clearRange=JButton("Clear time range")
    private val split=JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
    private var pendingDivider=false
    private val browser=JPanel(BorderLayout())
    private val browserHost=JPanel(BorderLayout())
    private var window: DialogWrapper?=null
    private val searchTimer=Timer(180) { filterChanged() }.apply { isRepeats=false }
    private val listener=controller.listen(::refresh)
    private val configure: (String) -> Unit = { configure(it) }
    private val navigate: (RecordedCall,Boolean) -> Unit = { call,open ->
        if (open) sourceNote.text=RecordedCallInlays.get(project).show(call,controller.recording?.source(call),true) {
            controller.select(call.id(),false);controller.showPanel?.invoke()
        }
    }
    init {
        controller.navigate=navigate;controller.configurePanel=configure
        val recordingBar=WorkbookToolbar()
        start.addActionListener { configure() };stop.addActionListener { controller.stop() }
        save.addActionListener { save() };load.addActionListener { load() }
        listOf(start,stop,save,load).forEach(recordingBar::add)
        live.toolTipText="Inspect the current live object; it may have changed since recording and expires after five minutes."
        live.addActionListener { controller.selectedCall()?.takeIf(controller::live)?.let { inspect(mapOf("event" to it.event().toString())) } }
        recordingBar.add(live)
        experiment.addActionListener { createCase() };recordingBar.add(experiment)
        graph.addMouseListener(object:java.awt.event.MouseAdapter(){
            private fun popup(e:java.awt.event.MouseEvent){
                if(!e.isPopupTrigger)return
                val node=graph.nodes.firstOrNull { it.bounds.contains(java.awt.geom.Point2D.Double(e.x/graph.zoom,e.y/graph.zoom)) }?:return
                selectNode(node.call.id())
                JPopupMenu().apply{add(JMenuItem("Create CASE from this call…").apply{isEnabled=!controller.offline && node.call.id()<RecordingHibernate.BASE && node.call.className() !in setOf("async.Task","http.Request");addActionListener{createCase()}});show(graph,e.x,e.y)}
            }
            override fun mousePressed(e:java.awt.event.MouseEvent)=popup(e)
            override fun mouseReleased(e:java.awt.event.MouseEvent)=popup(e)
        })
        add(recordingBar,BorderLayout.NORTH)

        val searchBar=WorkbookToolbar()
        roots.preferredSize=Dimension(270,28);threads.preferredSize=Dimension(180,28)
        roots.addActionListener { if (!updating) { focus=null;filterChanged() } }
        threads.addActionListener { if (!updating) filterChanged() }
        fun field(label: String,component: JComponent)=JPanel(FlowLayout(FlowLayout.LEFT,2,0)).apply { add(JLabel(label));add(component) }
        searchBar.add(field("Search:",search));searchBar.add(field("Root:",roots));searchBar.add(field("Thread:",threads))
        search.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) { if(!updating) searchTimer.restart() }
            override fun removeUpdate(e: DocumentEvent) { if(!updating) searchTimer.restart() }
            override fun changedUpdate(e: DocumentEvent) { if(!updating) searchTimer.restart() }
        })
        val filterBar=WorkbookToolbar()
        errors.addActionListener { filterChanged() };minimum.addChangeListener { if(!updating) filterChanged() }
        groupSql.addActionListener { redraw() };filterBar.add(groupSql)
        filterBar.add(errors);filterBar.add(JLabel("Min ms:"));filterBar.add(minimum)
        follow.addActionListener { controller.followLatest=follow.isSelected };filterBar.add(follow)
        inline.addActionListener { controller.inline=inline.isSelected };filterBar.add(inline)
        clearRange.addActionListener { range=null;filterChanged() };filterBar.add(clearRange)
        filterBar.add(button("Clear filters") { clearFilters() })

        val viewBar=WorkbookToolbar()
        viewBar.add(button("−","Zoom out (also − key)") { graph.setZoom(graph.zoom/1.2) })
        viewBar.add(zoomLabel)
        viewBar.add(button("+","Zoom in (also = key or Ctrl/Cmd + wheel)") { graph.setZoom(graph.zoom*1.2) })
        viewBar.add(button("Fit graph","Fit all visible calls (Home)") { graph.fitAll() })
        viewBar.add(button("Show selected","Reveal the selected call; clear filters if needed") {
            if(graph.nodes.none { it.call.id()==controller.selected }) clearFilters()
            graphTabs.selectedIndex=0;graph.revealSelection(true)
        })
        viewBar.add(button("Collapse all") { graph.collapseAll() })
        viewBar.add(button("Expand all") { graph.expandAll() })
        viewBar.add(button("Focus branch") { controller.selected?.let { focus=it;filterChanged() } })
        viewBar.add(button("Detach window…") { detachWindow() })
        viewBar.add(button("Clear source values") { RecordedCallInlays.get(project).clear() })

        val navigation=WorkbookToolbar()
        previous.addActionListener { step(-1) };next.addActionListener { step(1) };nextError.addActionListener { step(1,true) }
        caller.addActionListener { controller.selectedCall()?.parent()?.takeIf { it>0 }?.let { controller.select(it) } }
        listOf(previous,next,nextError,caller).forEach(navigation::add)
        navigation.add(button("Open source") { controller.selected?.let { controller.select(it) } })
        pin.addActionListener {
            controller.selectedCall()?.takeIf { controller.downloaded(it) }?.let {
                reference=it;comparison.showComparison(reference,it);detailTabs.selectedIndex=1;updateNavigation()
            }
        }
        clearReference.addActionListener { reference=null;comparison.showComparison(null,controller.selectedCall());updateNavigation() }
        navigation.add(pin);navigation.add(clearReference)
        // BorderLayout queries each wrapping toolbar's current preferred height.
        // BoxLayout caches a single-row maximum height and clips wrapped controls.
        val controls=listOf(searchBar,filterBar,viewBar,navigation,breadcrumbScroll,filterNote)
            .asReversed().fold(JPanel(BorderLayout())) { rest,component ->
                JPanel(BorderLayout()).apply { add(component,BorderLayout.NORTH);if(rest.componentCount>0) add(rest,BorderLayout.CENTER) }
            }
        browser.add(controls,BorderLayout.NORTH)
        graphTabs.addTab("Call graph",JScrollPane(graph).apply {
            minimumSize=Dimension(150,120)
            val manual=object : java.awt.event.MouseAdapter() { override fun mousePressed(e: java.awt.event.MouseEvent) { pauseFollowing() } }
            verticalScrollBar.addMouseListener(manual);horizontalScrollBar.addMouseListener(manual)
            addMouseWheelListener { pauseFollowing() }
        })
        graphTabs.addTab("Timeline",JScrollPane(timeline).apply { minimumSize=Dimension(150,120) })
        detailTabs.addTab("Values",values);detailTabs.addTab("Compare calls",comparison);detailTabs.addTab("SQL & N+1",sqlPanel);detailTabs.addTab("Hibernate",hibernatePanel)
        detailTabs.addChangeListener { pendingDivider=true;orient() }
        val detail=JPanel(BorderLayout()).apply { add(detailTabs,BorderLayout.CENTER);add(sourceNote,BorderLayout.SOUTH);minimumSize=Dimension(180,120) }
        split.leftComponent=graphTabs;split.rightComponent=detail;split.resizeWeight=0.5;split.dividerLocation=520
        browser.add(split,BorderLayout.CENTER);browserHost.add(browser,BorderLayout.CENTER)
        add(browserHost,BorderLayout.CENTER);add(status,BorderLayout.SOUTH)
        browser.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) { orient() }
        })
        fun bind(key: String,name: String,action: () -> Unit) {
            browser.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key),name)
            browser.actionMap.put(name,object : AbstractAction() { override fun actionPerformed(e: ActionEvent?) = action() })
        }
        bind("alt LEFT","previousCall") { step(-1) };bind("alt RIGHT","nextCall") { step(1) }
        bind("alt DOWN","nextError") { step(1,true) };bind("alt UP","caller") { caller.doClick() }
        refresh()
    }
    private fun button(text: String,tip: String?=null,action: () -> Unit) = JButton(text).apply { toolTipText=tip;addActionListener { action() } }
    private fun updateZoom() { zoomLabel.text=if(graph.zoom<0.1) "${"%.1f".format(Locale.ROOT,graph.zoom*100)}%" else "${(graph.zoom*100).toInt()}%" }
    private fun pauseFollowing() { controller.followLatest=false;follow.isSelected=false }
    private fun orient() {
        if(browser.width<=0) return
        val vertical=browser.width<950
        val orientation=if(vertical) JSplitPane.VERTICAL_SPLIT else JSplitPane.HORIZONTAL_SPLIT
        if(split.orientation!=orientation) { split.orientation=orientation;pendingDivider=true }
        if(pendingDivider && split.height>0 && split.width>0) {
            split.dividerLocation=if(vertical) (split.height*(if(detailTabs.selectedIndex>=2) 0.30 else 0.48)).toInt() else split.width/2
            pendingDivider=false
        }
    }
    override fun doLayout() { super.doLayout();orient() }
    private fun detachWindow() {
        window?.let { it.window.toFront();return }
        browserHost.remove(browser)
        browserHost.add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Call browser is open in a separate window."))
            add(button("Return to tool window") { window?.close(DialogWrapper.OK_EXIT_CODE) })
        },BorderLayout.CENTER)
        val dialog=object : DialogWrapper(project,false) {
            init { title="Recorded calls";isModal=false;isResizable=true;init() }
            override fun createCenterPanel(): JComponent = browser
            override fun createActions(): Array<Action> = emptyArray()
            override fun dispose() {
                browser.parent?.remove(browser);this@RecordingPanel.window=null
                if(!this@RecordingPanel.disposed) { browserHost.removeAll();browserHost.add(browser,BorderLayout.CENTER);browserHost.revalidate();browserHost.repaint() }
                super.dispose()
            }
        }
        window=dialog;dialog.setSize(1280,900);browserHost.revalidate();browserHost.repaint();dialog.show()
    }
    private fun currentFilter() = CallFilter(search.text,errors.isSelected,(minimum.value as Number).toDouble(),
        (threads.selectedItem as? Choice)?.id,range,focus)
    private fun selectNode(id: Long) {
        if(id >= RecordingSql.BASE) {
            sqlSelected=id;detailTabs.selectedIndex=2;sqlPanel.selectSql(id-RecordingSql.BASE);redraw()
        } else if(id>=RecordingHibernate.BASE) {
            sqlSelected=id;detailTabs.selectedIndex=3;hibernatePanel.selectHibernate(id-RecordingHibernate.BASE);redraw()
        } else { sqlSelected=null;controller.select(id) }
    }
    private fun filterChanged() {
        if(updating || disposed) return
        controller.followLatest=false;follow.isSelected=false;redraw()
    }
    private fun clearFilters() {
        updating=true
        try { search.text="";errors.isSelected=false;minimum.value=0.0;roots.selectedIndex=if(roots.itemCount>0) 0 else -1;threads.selectedIndex=if(threads.itemCount>0) 0 else -1;range=null;focus=null }
        finally { updating=false }
        searchTimer.stop();filterChanged()
    }
    private fun step(direction: Int,errorOnly: Boolean=false) {
        CallNavigation.adjacent(matches,controller.selected,direction,errorOnly)?.let { selectNode(it.id()) }
    }
    private fun updateNavigation() {
        previous.isEnabled=CallNavigation.adjacent(matches,controller.selected,-1)!=null
        next.isEnabled=CallNavigation.adjacent(matches,controller.selected,1)!=null
        nextError.isEnabled=CallNavigation.adjacent(matches,controller.selected,1,true)!=null
        caller.isEnabled=(controller.selectedCall()?.parent() ?: 0)>0
        pin.isEnabled=controller.selectedCall()?.let { controller.downloaded(it) && it.status()!="RUNNING" }==true
        pin.text=reference?.let { "Repin reference (#${it.id()})" } ?: "Pin reference"
        clearReference.isEnabled=reference!=null;clearRange.isEnabled=range!=null
    }
    fun configure(initial: String = "") {
        val input=JTextArea(initial,7,54)
        val jdbc=JCheckBox("Record JDBC/SQL and synchronous Spring MVC requests",true)
        val hibernate=JCheckBox("Record Hibernate 6.6 entity / session events",true)
        val async=JCheckBox("Link Executor / @Async / CompletableFuture tasks (recording identity only)",true)
        val captureData=JCheckBox("Capture replay DATA (uses snapshot serializers; 2 MiB per input/result)",false)
        jdbc.addActionListener { hibernate.isEnabled=jdbc.isSelected }
        val threshold=JSpinner(SpinnerNumberModel(5,2,1000,1))
        val dialog=object : DialogWrapper(project) {
            init { title="Record application class calls";init() }
            override fun createCenterPanel()=JPanel(BorderLayout(0,8)).apply {
                add(JPanel(GridLayout(6,1)).apply { add(JLabel("Exact Java class names, one per line (1–8):"));add(jdbc);add(hibernate);add(captureData);add(async);add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(JLabel("Suspected N+1 repetition threshold:"));add(threshold) }) },BorderLayout.NORTH)
                add(JScrollPane(input),BorderLayout.CENTER)
                add(JTextArea("Captures the next 200 method calls, up to 32 MiB of value previews.\nUse the running application after starting. Save the current recording to keep it.\nValues may contain application data. Recording adds work to the calling thread.").apply { isEditable=false;isOpaque=false },BorderLayout.SOUTH)
            }
            override fun getPreferredFocusedComponent()=input
        }
        if (dialog.showAndGet()) controller.start(input.text.lines().map(String::trim).filter(String::isNotEmpty).distinct(),jdbc.isSelected,(threshold.value as Number).toInt(),jdbc.isSelected&&hibernate.isSelected,captureData.isSelected,async.isSelected)
    }
    private fun createCase(){
        val call=controller.selectedCall()?:return
        if(controller.offline){controller.error("Full replay DATA belongs to the connected recording. Saved recordings contain display previews.");return}
        val service=hu.baader.repl.nrepl.NreplService.getInstance(project)
        val fields=mapOf("recording" to call.recording(),"call-id" to call.id().toString())
        service.request("trace/case-info",fields,{ info ->
            if(info["ready"]!="true"){controller.error(info["detail"].orEmpty());return@request}
            val name=JTextField("${call.method()}-${call.id()}",28)
            val bean=JComboBox(info["beans"].orEmpty().lines().filter(String::isNotBlank).toTypedArray()).apply{isEditable=true}
            val panel=JPanel(BorderLayout()).apply{
                add(JPanel(GridLayout(2,2)).apply{add(JLabel("New CASE name:"));add(name);add(JLabel("Spring bean (empty for static):"));add(bean)},BorderLayout.CENTER)
                add(JTextArea("Creates input/expected DATA and a CASE without rerunning the method.\nReview generated Jackson code and expectations in Cases / Reload.\nEntry arguments are frozen before the call; profiles/security context are not restored.").apply{isEditable=false;isOpaque=false},BorderLayout.SOUTH)
            }
            if(JOptionPane.showConfirmDialog(this,panel,"Create CASE from call #${call.id()}",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)
                service.request("trace/case-create",fields+mapOf("name" to name.text.trim(),"bean" to bean.selectedItem?.toString().orEmpty()),{
                    controller.error("Created ${it["name"]}. Open Cases / Reload to review and run it.")
                },controller::error)
        },controller::error)
    }
    private fun refresh() {
        val recording=controller.recording
        if(recording?.id!=shownRecording) {
            val wasFollowing=controller.followLatest
            shownRecording=recording?.id;shown=null;lastSelection=null;reference=null;sqlSelected=null;cache.clear()
            values.removeAll();sourceNote.text="";graph.resetView();clearFilters();controller.followLatest=wasFollowing
        }
        val root=(roots.selectedItem as? Choice)?.id;val thread=(threads.selectedItem as? Choice)?.id
        updating=true
        try {
            val options=listOf(Choice(null,"All root calls")) + recording?.calls.orEmpty().filter { it.parent()==0L }.map {
                Choice(it.id(),"#${it.id()} · ${TIME.format(Instant.ofEpochMilli(it.startedAt()))} · ${it.className().substringAfterLast('.')}.${it.method()}")
            }
            roots.model=DefaultComboBoxModel(options.toTypedArray());roots.selectedItem=options.firstOrNull { it.id==root } ?: options.first()
            val threadOptions=listOf(Choice(null,"All threads"))+recording?.calls.orEmpty().distinctBy { it.threadId() }.map { Choice(it.threadId(),"${it.threadId()} · ${it.threadName()}") }
            threads.model=DefaultComboBoxModel(threadOptions.toTypedArray());threads.selectedItem=threadOptions.firstOrNull { it.id==thread } ?: threadOptions.first()
            follow.isSelected=controller.followLatest;inline.isSelected=controller.inline
        } finally { updating=false }
        start.isEnabled=!controller.active && !controller.starting;stop.isEnabled=controller.active
        save.isEnabled=recording!=null && (controller.offline || controller.complete());load.isEnabled=!controller.active && !controller.starting
        live.isEnabled=controller.selectedCall()?.let(controller::live)==true
        experiment.isEnabled=!controller.offline && controller.selectedCall()?.let { it.status() in setOf("SUCCESS","ERROR") && it.className() !in setOf("async.Task","http.Request") }==true
        status.text=controller.message+(recording?.async?.takeIf { it.enabled }?.let { " · Async: ${it.pending} queued, ${it.dropped} unlinked" } ?: "")+" · "+(recording?.let { RecordingSql.summary(it.sql) } ?: "")+"\nSelected classes only · explicit Java / async handoffs · recorded previews, not a complete execution profile."
        redraw()
        val call=controller.selectedCall()
        if(shown!=call) {
            val oldTab=(values.components.firstOrNull() as? JTabbedPane)?.selectedIndex ?: 0
            shown=call;values.removeAll()
            if(call!=null) values.add(RecordingSource.values(call).apply { selectedIndex=oldTab.coerceAtMost(tabCount-1) })
            values.revalidate();values.repaint()
        }
        comparison.showComparison(reference,call)
        if(lastSelection!=controller.selected) {
            lastSelection=controller.selected;graph.revealSelection();updateBreadcrumb()
        }
    }
    private fun updateBreadcrumb() {
        breadcrumb.removeAll()
        CallNavigation.path(controller.recording?.calls.orEmpty(),controller.selected).forEachIndexed { i,call ->
            if(i>0) breadcrumb.add(JLabel("→"))
            breadcrumb.add(button("#${call.id()} ${call.className().substringAfterLast('.')}.${call.method()}",
                presentations[call.id()]?.signature) { controller.select(call.id()) })
        }
        if(breadcrumb.componentCount==0) breadcrumb.add(JLabel("Select a call to open its source and captured values."))
        breadcrumb.revalidate();breadcrumb.repaint()
    }
    private fun redraw() {
        val record=controller.recording
        val javaCalls=record?.calls.orEmpty()
        val ormCalls=record?.let(RecordingHibernate::nodes).orEmpty()
        val calls=javaCalls + ormCalls + (record?.let { RecordingSql.nodes(it,groupSql.isSelected) } ?: emptyList())
        val findings=record?.sql?.findings().orEmpty()
        val affected=findings.flatMap { it.events().map { e -> e.parent() } }.distinct().flatMap { CallNavigation.path(javaCalls,it).map { c -> c.id() } }.toSet()
        val index=javaCalls.associateBy { it.id() };val counts=mutableMapOf<Long,Int>()
        record?.sql?.events()?.filter { it.kind()=="SQL" }?.forEach { event ->
            var call=index[event.parent()]
            while(call!=null) { counts[call.id()]=(counts[call.id()] ?: 0)+1;call=index[call.parent()] }
        }
        val ormFindings=record?.hibernate?.findings(record.sql,record.sql.threshold()).orEmpty()
        val ormWarnings=ormFindings.flatMap{f->f.events().map{(RecordingHibernate.BASE+it) to f.kind()}}.groupBy({it.first},{it.second})
        presentations=cache.update(calls).mapValues { (id,p) ->
            if(id in counts) CallPresentation(p.call,"${counts[id]} SQL"+if(id in affected) " · ⚠ suspected N+1" else "") else if(id in ormWarnings) CallPresentation(p.call,"⚠ "+ormWarnings.getValue(id).distinct().joinToString(" · ")) else p
        }
        val root=(roots.selectedItem as? Choice)?.id;val filter=currentFilter()
        matches=CallGraphLayout.nodes(calls,root,filter=filter,presentations=presentations).filter { !it.contextOnly }.map { it.call }
        graph.display(calls,root,sqlSelected ?: controller.selected,filter,presentations)
        sqlPanel.display(record,root)
        hibernatePanel.display(record,root)
        val timeCalls=javaCalls + ormCalls + (record?.let { RecordingSql.nodes(it,false) } ?: emptyList())
        timeline.display(timeCalls,sqlSelected ?: controller.selected,range,CallGraphLayout.nodes(timeCalls,root,filter=filter).filter { !it.contextOnly }.map { it.call.id() }.toSet(),timeCalls.associate { it.id() to CallPresentation(it) })
        val rangeText=range?.let { " · ${"%.1f".format(Locale.ROOT,it.startMs)}–${"%.1f".format(Locale.ROOT,it.endMs)} ms" }.orEmpty()
        filterNote.text="${matches.size}/${calls.size} matching calls"+rangeText+
            (focus?.let { " · branch #$it" }.orEmpty())+
            if(filter.active) " · dashed nodes are caller paths" else " · drag to pan · Ctrl/Cmd + wheel to zoom"
        updateNavigation()
    }
    private fun save() {
        val recording=controller.recording ?: return
        val chooser=FileChooserFactory.getInstance().createSaveFileDialog(FileSaverDescriptor("Save recorded calls","Stores captured source, call tree and bounded display values.","sbrepl-recording"),project)
        val file=chooser.save(null as com.intellij.openapi.vfs.VirtualFile?,"calls-${recording.id.take(8)}.sbrepl-recording")?.file?.toPath() ?: return
        background({ WorkspaceStore.write(file,recording.encode());"Recording saved: ${file.fileName}" },{ controller.error(it) })
    }
    private fun load() {
        FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileDescriptor("sbrepl-recording"),project,null) { file ->
            background({
                val path=Path.of(file.path);require(Files.size(path)<=CallRecording.MAX_BYTES) { "Recording exceeds 64 MiB" }
                CallRecording.decode(Files.readString(path))
            },{ controller.open(it) })
        }
    }
    private fun <T> background(work: () -> T,success: (T) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result=runCatching(work)
            ApplicationManager.getApplication().invokeLater { if(!project.isDisposed && !disposed) {
                runCatching { result.getOrThrow().let(success) }.onFailure { controller.error(it.message ?: "Recording file operation failed") }
            } }
        }
    }
    override fun dispose() {
        disposed=true;searchTimer.stop();window?.close(DialogWrapper.CANCEL_EXIT_CODE)
        listener.dispose();if(controller.navigate===navigate) controller.navigate=null;if(controller.configurePanel===configure) controller.configurePanel=null
    }
    companion object {
        private val TIME=DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
        private fun textArea(rows: Int)=JTextArea().apply { isEditable=false;lineWrap=true;wrapStyleWord=true;this.rows=rows;isOpaque=false }
    }
}
