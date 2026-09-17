package hu.baader.repl.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.Splitter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.event.ListSelectionListener
import javax.swing.table.AbstractTableModel

/** Edits saved HTTP requests and delegates explicit execution to the request runner. */
class HttpRequestsPanel(
    private val project: Project,
    private val insertSnippet: (String) -> Unit,
    private val runner: HttpRequestRunner,
    private val onCasesChanged: (() -> Unit)? = null
) : JPanel(BorderLayout()) {

    private val requestService = HttpRequestService.getInstance(project)
    private val listModel = DefaultListModel<HttpRequestCase>()
    private val requestList = JBList(listModel)

    private val caseIdField = JBTextField()
    private val nameField = JBTextField()
    private val topicField = JBTextField()
    private val versionField = JBTextField()
    private val methodField = com.intellij.openapi.ui.ComboBox(arrayOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"))
    private val urlField = JBTextField()
    private val descriptionArea = JBTextArea()
    private val bodyArea = JBTextArea()
    private val headersModel = HeaderTableModel()
    private val headersTable = JBTable(headersModel)

    private val detailComponents: List<JComponent>
    private var currentCase: HttpRequestCase? = null
    init {
        descriptionArea.lineWrap = true
        descriptionArea.wrapStyleWord = true
        bodyArea.lineWrap = true
        bodyArea.wrapStyleWord = true
        headersTable.tableHeader.reorderingAllowed = false
        headersTable.setShowGrid(false)
        headersTable.preferredScrollableViewportSize = Dimension(200, 96)
        headersTable.emptyText.text = "Nincsenek headerek"

        val splitter = Splitter(false, 0.32f)
        splitter.firstComponent = buildListPanel()
        splitter.secondComponent = buildDetailsPanel()
        add(splitter, BorderLayout.CENTER)

        detailComponents = listOf(
            caseIdField, nameField, topicField, versionField, methodField, urlField,
            descriptionArea, bodyArea, headersTable
        )
        setDetailsEnabled(false)

        loadRequests()
    }

    private fun buildListPanel(): JComponent {
        requestList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        requestList.emptyText.text = "Adj hozzá HTTP request eseteket"
        requestList.cellRenderer = object : SimpleListCellRenderer<HttpRequestCase>() {
            override fun customize(list: javax.swing.JList<out HttpRequestCase>, value: HttpRequestCase?, index: Int, selected: Boolean, hasFocus: Boolean) {
                text = value?.let {
                    val display = it.name.ifBlank { it.id.ifBlank { "(névtelen)" } }
                    "$display  —  ${it.method} ${it.url}"
                } ?: ""
            }
        }
        requestList.addListSelectionListener(ListSelectionListener { e ->
            if (e.valueIsAdjusting) return@ListSelectionListener
            handleSelectionChange()
        })
        requestList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && requestList.selectedIndex >= 0) {
                    runSelectedCase()
                }
            }
        })

        val decorator = ToolbarDecorator.createDecorator(requestList)
            .disableUpDownActions()
            .setAddAction { addCase() }
            .setRemoveAction { deleteCase() }
            .addExtraActions(
                object : com.intellij.ui.AnActionButton("Duplicate", AllIcons.Actions.Copy) {
                    override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                        duplicateCase()
                    }
                },
                object : com.intellij.ui.AnActionButton("Run Selected", AllIcons.Actions.Execute) {
                    override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                        runSelectedCase()
                    }
                }
            )
        return decorator.createPanel()
    }

    private fun buildDetailsPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createEmptyBorder(8, 10, 8, 10)

        val title = JBLabel("HTTP Request részletek")
        title.font = title.font.deriveFont(Font.BOLD, 14f)
        panel.add(title, BorderLayout.NORTH)

        val form = JPanel(java.awt.GridBagLayout())
        val gbc = java.awt.GridBagConstraints().apply {
            fill = java.awt.GridBagConstraints.HORIZONTAL
            anchor = java.awt.GridBagConstraints.NORTHWEST
            insets = java.awt.Insets(4, 4, 4, 4)
        }

        fun addRow(label: String, component: JComponent, row: Int, weightx: Double = 1.0) {
            gbc.gridx = 0
            gbc.gridy = row
            gbc.weightx = 0.0
            form.add(JBLabel(label).apply { horizontalAlignment = SwingConstants.RIGHT }, gbc)
            gbc.gridx = 1
            gbc.weightx = weightx
            form.add(component, gbc)
        }

        addRow("Case ID:", caseIdField, 0)
        addRow("Név:", nameField, 1)
        addRow("Fő téma:", topicField, 2)
        addRow("Verzió:", versionField, 3)

        val methodUrlPanel = JPanel(BorderLayout(8, 0))
        methodField.isEditable = false
        methodUrlPanel.add(methodField, BorderLayout.WEST)
        methodUrlPanel.add(urlField, BorderLayout.CENTER)
        addRow("HTTP:", methodUrlPanel, 4)

        val descScroll = JBScrollPane(descriptionArea)
        descScroll.preferredSize = Dimension(descScroll.preferredSize.width, 80)
        addRow("Leírás:", descScroll, 5)

        val headersPanel = JPanel(BorderLayout(4, 4))
        headersPanel.add(JBScrollPane(headersTable).apply {
            preferredSize = Dimension(preferredSize.width, 120)
        }, BorderLayout.CENTER)
        val headerButtons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        val addHeader = JButton("Header hozzáadása", AllIcons.General.Add)
        addHeader.addActionListener { headersModel.addRow() }
        val removeHeader = JButton("Header törlése", AllIcons.General.Remove)
        removeHeader.addActionListener {
            val index = headersTable.selectedRow.takeIf { it >= 0 } ?: headersModel.rowCount - 1
            if (index >= 0) headersModel.removeRow(index)
        }
        headerButtons.add(addHeader)
        headerButtons.add(removeHeader)
        headersPanel.add(headerButtons, BorderLayout.SOUTH)
        addRow("Headerek:", headersPanel, 6)

        val bodyScroll = JBScrollPane(bodyArea)
        bodyScroll.preferredSize = Dimension(bodyScroll.preferredSize.width, 160)
        addRow("JSON Body:", bodyScroll, 7)

        panel.add(form, BorderLayout.CENTER)

        val actions = JPanel(FlowLayout(FlowLayout.RIGHT))
        val saveButton = JButton("Mentés", AllIcons.Actions.MenuSaveall)
        saveButton.addActionListener { saveCurrentCase() }
        val runButton = JButton("Play", AllIcons.Actions.Execute)
        runButton.addActionListener { runSelectedCase() }
        val snippetButton = JButton("httpReq.perform snippet", AllIcons.Actions.Upload)
        snippetButton.toolTipText = "Helper osztály + httpReq.perform(...) kód beszúrása a REPL editorba"
        snippetButton.addActionListener { insertHelperSnippet() }
        actions.add(saveButton)
        actions.add(runButton)
        actions.add(snippetButton)

        panel.add(actions, BorderLayout.SOUTH)
        return panel
    }

    private fun loadRequests() {
        listModel.clear()
        requestService.getRequests().forEach { listModel.addElement(it) }

        if (listModel.size() == 0) {
            currentCase = null
            clearDetails()
            setDetailsEnabled(false)
            return
        }

        val targetId = requestService.getLastSelectedId()
        val index = (0 until listModel.size()).firstOrNull {
            listModel.getElementAt(it).id == targetId
        } ?: 0
        requestList.selectedIndex = index
        currentCase = requestList.selectedValue
        currentCase?.let { loadCase(it) }
        setDetailsEnabled(true)
    }

    private fun addCase() {
        storeCurrentFields()
        persistCurrentCase()

        val newCase = HttpRequestCase(
            id = generateCaseId("http-case"),
            name = "Új HTTP request",
            method = "POST",
            url = "http://localhost:8080/api",
            headers = mutableListOf(HttpHeaderEntry("Content-Type", "application/json")),
            topic = "",
            version = "",
            description = "Készítsd elő az init-data vagy hiányzó eset leírását"
        )
        listModel.addElement(newCase)
        requestList.selectedIndex = listModel.size() - 1
        loadCase(newCase)
        setDetailsEnabled(true)
    }

    private fun duplicateCase() {
        val selected = requestList.selectedValue ?: return
        val copy = selected.deepCopy().apply {
            id = generateCaseId("${selected.id}-copy")
            name = "${selected.name} (másolat)"
        }
        listModel.addElement(copy)
        requestList.selectedIndex = listModel.size() - 1
        loadCase(copy)
    }

    private fun deleteCase() {
        val selectedIndex = requestList.selectedIndex
        if (selectedIndex < 0) return
        val removed = listModel.getElementAt(selectedIndex)
        if (Messages.showYesNoDialog(
                project,
                "Biztosan törlöd a(z) '${removed.name.ifBlank { removed.id }}' request esetet?",
                "HTTP eset törlése",
                Messages.getQuestionIcon()
            ) != Messages.YES
        ) {
            return
        }

        requestService.deleteCase(removed.id)
        listModel.removeElementAt(selectedIndex)
        onCasesChanged?.invoke()
        if (listModel.size() == 0) {
            currentCase = null
            clearDetails()
            setDetailsEnabled(false)
        } else {
            val newIndex = if (selectedIndex >= listModel.size()) listModel.size() - 1 else selectedIndex
            requestList.selectedIndex = newIndex
            currentCase = requestList.selectedValue
            currentCase?.let { loadCase(it) }
        }
    }

    private fun clearDetails() {
        caseIdField.text = ""
        nameField.text = ""
        topicField.text = ""
        versionField.text = ""
        methodField.selectedItem = "GET"
        urlField.text = ""
        descriptionArea.text = ""
        bodyArea.text = ""
        headersModel.setHeaders(emptyList())
    }

    private fun loadCase(case: HttpRequestCase) {
        caseIdField.text = case.id
        nameField.text = case.name
        topicField.text = case.topic
        versionField.text = case.version
        methodField.selectedItem = case.method.ifBlank { "GET" }
        urlField.text = case.url
        descriptionArea.text = case.description
        bodyArea.text = case.body
        headersModel.setHeaders(case.headers)
        currentCase = case
        requestService.rememberLastSelected(case.id)
        setDetailsEnabled(true)
    }

    private fun handleSelectionChange() {
        storeCurrentFields()
        persistCurrentCase()
        val selected = requestList.selectedValue
        currentCase = selected
        if (selected == null) {
            clearDetails()
            setDetailsEnabled(false)
        } else {
            loadCase(selected)
        }
    }

    private fun setDetailsEnabled(enabled: Boolean) {
        detailComponents.forEach { it.isEnabled = enabled }
    }

    private fun saveCurrentCase() {
        storeCurrentFields()
        if (currentCase == null) {
            Messages.showWarningDialog(project, "Nincs kiválasztott HTTP eset", "Mentés")
            return
        }
        if (persistCurrentCase()) {
            notifyInfo("HTTP eset elmentve (${currentCase?.displayLabel()})")
            onCasesChanged?.invoke()
        }
    }

    private fun runSelectedCase() {
        storeCurrentFields()
        val case = currentCase ?: run {
            Messages.showWarningDialog(project, "Válassz ki egy HTTP esetet a futtatáshoz", "HTTP futtatás")
            return
        }
        if (case.url.isBlank()) {
            Messages.showWarningDialog(project, "URL nélkül nem lehet requestet futtatni", "HTTP futtatás")
            return
        }

        persistCurrentCase()
        runner.run(case)
    }

    private fun insertHelperSnippet() {
        storeCurrentFields()
        persistCurrentCase()
        val cases = allCases()
        if (cases.isEmpty()) {
            Messages.showWarningDialog(project, "Nincs elmentett HTTP eset", "Snippet generálás")
            return
        }
        val selectedId = currentCase?.id ?: cases.first().id
        val snippet = HttpSnippetBuilder.build(cases, selectedId)
        insertSnippet(snippet)
    }

    private fun storeCurrentFields() {
        val case = currentCase ?: return
        case.name = nameField.text.trim()
        case.topic = topicField.text.trim()
        case.version = versionField.text.trim()
        case.method = (methodField.selectedItem as? String)?.uppercase() ?: "GET"
        case.url = urlField.text.trim()
        case.description = descriptionArea.text.trim()
        case.body = bodyArea.text
        case.headers = headersModel.toHeaders()
    }

    private fun persistCurrentCase(): Boolean {
        val case = currentCase ?: return false
        var desiredId = caseIdField.text.trim()
        if (desiredId.isBlank()) {
            desiredId = case.name.ifBlank { case.topic }.ifBlank { "http-case" }
        }

        val previousId = case.id
        val uniqueId = ensureUniqueId(desiredId, case)
        case.id = uniqueId
        caseIdField.text = uniqueId

        try {
            requestService.saveCase(case)
        } catch (e: Exception) {
            case.id = previousId
            caseIdField.text = previousId
            Messages.showWarningDialog(project, "Could not save the HTTP case to PasswordSafe. Changes remain in the editor.", "HTTP case")
            return false
        }
        if (previousId.isNotBlank() && previousId != uniqueId) requestService.deleteCase(previousId)
        requestService.rememberLastSelected(case.id)
        requestList.repaint()
        return true
    }

    private fun ensureUniqueId(desired: String, case: HttpRequestCase): String =
        HttpRequestIds.unique(desired, allCases().filter { it !== case }.map { it.id })

    private fun generateCaseId(preferred: String): String =
        HttpRequestIds.unique(preferred, allCases().map { it.id })

    private fun allCases(): List<HttpRequestCase> = (0 until listModel.size()).map { listModel.getElementAt(it) }

    fun workspaceRequests(): List<HttpRequestCase> {
        storeCurrentFields()
        return allCases().map { it.deepCopy() }
    }
    fun importWorkspaceRequests(requests: List<HttpRequestCase>) {
        requests.forEach { original ->
            val copy = original.deepCopy().apply { id = "ws-" + java.util.UUID.randomUUID().toString(); name = "[Workspace] " + name }
            requestService.saveCase(copy)
        }
        loadRequests()
    }

    private fun notifyInfo(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Spring Boot REPL")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }
}

private class HeaderTableModel : AbstractTableModel() {
    private val headers = mutableListOf<HttpHeaderEntry>()

    override fun getRowCount(): Int = headers.size

    override fun getColumnCount(): Int = 2

    override fun getColumnName(column: Int): String = if (column == 0) "Header" else "Érték"

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = true

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any =
        if (columnIndex == 0) headers[rowIndex].name else headers[rowIndex].value

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        val text = (aValue as? String)?.trim().orEmpty()
        if (columnIndex == 0) {
            headers[rowIndex].name = text
        } else {
            headers[rowIndex].value = text
        }
        fireTableRowsUpdated(rowIndex, rowIndex)
    }

    fun setHeaders(values: List<HttpHeaderEntry>) {
        headers.clear()
        headers.addAll(values.map { it.copy() })
        fireTableDataChanged()
    }

    fun toHeaders(): MutableList<HttpHeaderEntry> = headers.map { it.copy() }.toMutableList()

    fun addRow() {
        headers.add(HttpHeaderEntry())
        val index = headers.size - 1
        fireTableRowsInserted(index, index)
    }

    fun removeRow(index: Int) {
        if (index in headers.indices) {
            headers.removeAt(index)
            fireTableRowsDeleted(index, index)
        }
    }
}
