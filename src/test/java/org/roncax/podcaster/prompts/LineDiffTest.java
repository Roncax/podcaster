package org.roncax.podcaster.prompts;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class LineDiffTest {
    @Test
    void marksAddedRemovedAndUnchangedLines() {
        List<LineDiff.Line> diff = LineDiff.diff("a\nb\nc", "a\nc\nd");
        assertEquals(List.of(
                new LineDiff.Line(' ', "a"),
                new LineDiff.Line('-', "b"),
                new LineDiff.Line(' ', "c"),
                new LineDiff.Line('+', "d")), diff);
    }

    @Test
    void identicalTextsHaveNoChanges() {
        assertTrue(LineDiff.diff("x\ny", "x\ny").stream().allMatch(l -> l.op() == ' '));
    }
}
