package com.termux.app.terminal.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Fork key bar: arrows are hjkl, compose sends one bracketed paste. */
public class KeyBarTest {

    @Test
    public void arrowsAreHjkl() {
        assertEquals("h", TermuxTerminalExtraKeys.arrowLetter("LEFT"));
        assertEquals("j", TermuxTerminalExtraKeys.arrowLetter("DOWN"));
        assertEquals("k", TermuxTerminalExtraKeys.arrowLetter("UP"));
        assertEquals("l", TermuxTerminalExtraKeys.arrowLetter("RIGHT"));
        assertNull(TermuxTerminalExtraKeys.arrowLetter("PGUP"));
    }

    @Test
    public void composeIsOneBracketedPaste() {
        assertEquals("\033[200~one\rtwo\rthree\033[201~",
            ComposeOverlay.bracketedPaste("one\ntwo\r\nthree"));
        // an ESC inside the text could end the paste early; it is dropped
        assertEquals("\033[200~a[201~b\033[201~", ComposeOverlay.bracketedPaste("a\033[201~b"));
    }

    @Test
    public void composeRunsOnlyWithATrailingLineBreak() {
        // no line break at the end: pasted onto the command line, no Enter
        assertEquals("\033[200~echo ok\033[201~", ComposeOverlay.output("echo ok"));
        // trailing line break: pasted without it, then Enter
        assertEquals("\033[200~echo ok\033[201~\r", ComposeOverlay.output("echo ok\n"));
        assertEquals("\033[200~a\rb\033[201~\r", ComposeOverlay.output("a\nb\r\n"));
        assertEquals("\r", ComposeOverlay.output("\n"));
        assertEquals("", ComposeOverlay.output(""));
    }
}
