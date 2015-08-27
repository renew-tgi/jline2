package org.jline.console;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

import org.fusesource.jansi.Pty.Attributes;
import org.fusesource.jansi.Pty.Size;
import org.fusesource.jansi.WindowsAnsiOutputStream;
import org.fusesource.jansi.internal.Kernel32;
import org.fusesource.jansi.internal.Kernel32.INPUT_RECORD;
import org.fusesource.jansi.internal.Kernel32.KEY_EVENT_RECORD;
import org.fusesource.jansi.internal.WindowsSupport;
import org.jline.utils.Log;
import org.jline.utils.NonBlockingReader;

public class WindowsConsole extends AbstractConsole {

    private final String encoding;
    private final InputStream in;
    private final OutputStream out;
    private final NonBlockingReader reader;
    private final PrintWriter writer;

    public WindowsConsole() throws IOException {
        super("ansi");
        this.in = new DirectInputStream();
        this.out = new WindowsAnsiOutputStream(new FileOutputStream(FileDescriptor.out));
        this.encoding = getConsoleEncoding();
        if (encoding == null) {
            this.reader = new NonBlockingReader(new InputStreamReader(in));
            this.writer = new PrintWriter(new OutputStreamWriter(out));
        } else {
            this.reader = new NonBlockingReader(new InputStreamReader(in, encoding));
            this.writer = new PrintWriter(new OutputStreamWriter(out, encoding));
        }
    }

    private String getConsoleEncoding() {
        int codepage = Kernel32.GetConsoleOutputCP();
        //http://docs.oracle.com/javase/6/docs/technotes/guides/intl/encoding.doc.html
        String charsetMS = "ms" + codepage;
        if (java.nio.charset.Charset.isSupported(charsetMS)) {
            return charsetMS;
        }
        String charsetCP = "cp" + codepage;
        if (java.nio.charset.Charset.isSupported(charsetCP)) {
            return charsetCP;
        }
        return null;
    }

    public String getPtyName() {
        return "windows";
    }

    public NonBlockingReader reader() {
        return reader;
    }

    public PrintWriter writer() {
        return writer;
    }

    public InputStream getInput() {
        return in;
    }

    public OutputStream getOutput() {
        return out;
    }

    public String getEncoding() {
        return encoding;
    }

    public Attributes getAttributes() throws IOException {
        return null;
    }

    public void setAttributes(Attributes attr) throws IOException {

    }

    public void setAttributes(Attributes attr, int actions) throws IOException {

    }

    public Size getSize() throws IOException {
        Size size = new Size();
        size.setColumns(WindowsSupport.getWindowsTerminalWidth());
        size.setRows(WindowsSupport.getWindowsTerminalHeight());
        return size;
    }

    public void setSize(Size size) throws IOException {
        throw new UnsupportedOperationException("Can not resize windows console");
    }

    public void close() throws IOException {

    }

    private byte[] readConsoleInput() {
        // XXX does how many events to read in one call matter?
        INPUT_RECORD[] events = null;
        try {
            events = WindowsSupport.readConsoleInput(1);
        } catch (IOException e) {
            Log.debug("read Windows console input error: ", e);
        }
        if (events == null) {
            return new byte[0];
        }
        StringBuilder sb = new StringBuilder();
        for (INPUT_RECORD event : events) {
            KEY_EVENT_RECORD keyEvent = event.keyEvent;
            //Log.trace(keyEvent.keyDown? "KEY_DOWN" : "KEY_UP", "key code:", keyEvent.keyCode, "char:", (long)keyEvent.uchar);
            if (keyEvent.keyDown) {
                if (keyEvent.uchar > 0) {
                    // support some C1 control sequences: ALT + [@-_] (and [a-z]?) => ESC <ascii>
                    // http://en.wikipedia.org/wiki/C0_and_C1_control_codes#C1_set
                    final int altState = KEY_EVENT_RECORD.LEFT_ALT_PRESSED | KEY_EVENT_RECORD.RIGHT_ALT_PRESSED;
                    // Pressing "Alt Gr" is translated to Alt-Ctrl, hence it has to be checked that Ctrl is _not_ pressed,
                    // otherwise inserting of "Alt Gr" codes on non-US keyboards would yield errors
                    final int ctrlState = KEY_EVENT_RECORD.LEFT_CTRL_PRESSED | KEY_EVENT_RECORD.RIGHT_CTRL_PRESSED;
                    if (((keyEvent.uchar >= '@' && keyEvent.uchar <= '_') || (keyEvent.uchar >= 'a' && keyEvent.uchar <= 'z'))
                            && ((keyEvent.controlKeyState & altState) != 0) && ((keyEvent.controlKeyState & ctrlState) == 0)) {
                        sb.append('\u001B'); // ESC
                    }

                    sb.append(keyEvent.uchar);
                    continue;
                }
                // virtual keycodes: http://msdn.microsoft.com/en-us/library/windows/desktop/dd375731(v=vs.85).aspx
                // just add support for basic editing keys (no control state, no numpad keys)
                String escapeSequence = null;
                switch (keyEvent.keyCode) {
                    case 0x21: // VK_PRIOR PageUp
                        escapeSequence = "\u001B[5~";
                        break;
                    case 0x22: // VK_NEXT PageDown
                        escapeSequence = "\u001B[6~";
                        break;
                    case 0x23: // VK_END
                        escapeSequence = "\u001B[4~";
                        break;
                    case 0x24: // VK_HOME
                        escapeSequence = "\u001B[1~";
                        break;
                    case 0x25: // VK_LEFT
                        escapeSequence = "\u001B[D";
                        break;
                    case 0x26: // VK_UP
                        escapeSequence = "\u001B[A";
                        break;
                    case 0x27: // VK_RIGHT
                        escapeSequence = "\u001B[C";
                        break;
                    case 0x28: // VK_DOWN
                        escapeSequence = "\u001B[B";
                        break;
                    case 0x2D: // VK_INSERT
                        escapeSequence = "\u001B[2~";
                        break;
                    case 0x2E: // VK_DELETE
                        escapeSequence = "\u001B[3~";
                        break;
                    default:
                        break;
                }
                if (escapeSequence != null) {
                    for (int k = 0; k < keyEvent.repeatCount; k++) {
                        sb.append(escapeSequence);
                    }
                }
            } else {
                // key up event
                // support ALT+NumPad input method
                if (keyEvent.keyCode == 0x12/*VK_MENU ALT key*/ && keyEvent.uchar > 0) {
                    sb.append(keyEvent.uchar);
                }
            }
        }
        return sb.toString().getBytes();
    }

    private class DirectInputStream extends InputStream {
        private byte[] buf = null;
        int bufIdx = 0;

        @Override
        public int read() throws IOException {
            while (buf == null || bufIdx == buf.length) {
                buf = readConsoleInput();
                bufIdx = 0;
            }
            int c = buf[bufIdx] & 0xFF;
            bufIdx++;
            return c;
        }
    }
}