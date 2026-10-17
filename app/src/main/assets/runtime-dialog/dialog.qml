import QtQuick
import QtQuick.Controls
import Quickshell
import Quickshell.Io

ShellRoot {
    id: root
    readonly property var spec: JSON.parse(Quickshell.env("OMAMOBILE_DIALOG_SPEC"))
    property bool answering: false
    property string selectedLabel: ""

    function choose(label) {
        if (answering) return
        answering = true
        selectedLabel = label
        response.running = true
    }

    function reveal(button) {
        const top = button.mapToItem(body, 0, 0).y
        const flick = scroll.contentItem
        if (top < flick.contentY) flick.contentY = top
        else if (top + button.height > flick.contentY + scroll.availableHeight)
            flick.contentY = top + button.height - scroll.availableHeight
    }

    Process {
        id: response
        // The label is one argument, never part of the fixed shell program.
        // >&3 uses the inherited descriptor directly, including pipes/sockets/files.
        command: ["/bin/sh", "-c", "exec /usr/bin/printf '%s\\n' \"$1\" >&3", "omamobile-dialog", root.selectedLabel]
        onExited: (code, status) => {
            if (code !== 0) console.error("Dialog response failed:", code)
            Qt.quit()
        }
        // FailedToStart has no exited signal in qs 0.3.2. Close without a choice.
        onRunningChanged: if (root.answering && !running) Qt.quit()
    }

    FloatingWindow {
        id: window
        title: root.spec.apptitle
        color: "#1e1e2e"
        implicitWidth: Math.min(620, Math.max(1, (screen ? screen.width : 640) - 24))
        implicitHeight: Math.min(body.implicitHeight + 32, Math.max(1, (screen ? screen.height : 480) - 48))
        // guiutils uses equal size hints so the compositor floats this dialog.
        minimumSize: Qt.size(implicitWidth, implicitHeight)
        maximumSize: Qt.size(implicitWidth, implicitHeight)
        onClosed: Qt.quit()

        FocusScope {
            anchors.fill: parent
            focus: true
            Keys.onEscapePressed: Qt.quit()

            ScrollView {
                id: scroll
                anchors.fill: parent
                anchors.margins: 16
                clip: true
                contentWidth: availableWidth

                Column {
                    id: body
                    width: scroll.availableWidth
                    spacing: 16

                    Text {
                        width: parent.width
                        text: root.spec.title
                        textFormat: Text.PlainText
                        wrapMode: Text.Wrap
                        color: "#cdd6f4"
                        font.pixelSize: 22
                        font.bold: true
                        visible: text.length > 0
                    }
                    Text {
                        width: parent.width
                        text: root.spec.text
                        textFormat: Text.PlainText
                        wrapMode: Text.Wrap
                        color: "#cdd6f4"
                        font.pixelSize: 16
                        visible: text.length > 0
                    }
                    Flow {
                        id: actions
                        width: parent.width
                        spacing: 10
                        Repeater {
                            model: root.spec.buttons
                            Button {
                                id: button
                                required property string modelData
                                text: modelData
                                width: Math.min(Math.max(96, implicitWidth), actions.width)
                                enabled: !root.answering
                                focusPolicy: Qt.StrongFocus
                                padding: 12
                                onClicked: root.choose(text)
                                onActiveFocusChanged: if (activeFocus) root.reveal(button)
                                background: Rectangle {
                                    implicitWidth: 96
                                    implicitHeight: 40
                                    radius: 6
                                    color: button.down ? "#45475a" : "#313244"
                                    border.width: button.activeFocus ? 2 : 1
                                    border.color: button.activeFocus ? "#89b4fa" : "#585b70"
                                }
                                contentItem: Text {
                                    text: button.text
                                    textFormat: Text.PlainText
                                    wrapMode: Text.Wrap
                                    horizontalAlignment: Text.AlignHCenter
                                    verticalAlignment: Text.AlignVCenter
                                    color: "#cdd6f4"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
