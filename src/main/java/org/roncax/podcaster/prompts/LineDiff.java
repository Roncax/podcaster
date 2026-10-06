package org.roncax.podcaster.prompts;

import java.util.ArrayList;
import java.util.List;

/** Minimal line diff (longest common subsequence) for showing prompt changes. */
public final class LineDiff {
    public record Line(char op, String text) {}

    private LineDiff() {}

    public static List<Line> diff(String before, String after) {
        String[] a = before.split("\n", -1);
        String[] b = after.split("\n", -1);
        int[][] lcs = new int[a.length + 1][b.length + 1];
        for (int i = a.length - 1; i >= 0; i--) {
            for (int j = b.length - 1; j >= 0; j--) {
                lcs[i][j] = a[i].equals(b[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Line> out = new ArrayList<>();
        int i = 0, j = 0;
        while (i < a.length && j < b.length) {
            if (a[i].equals(b[j])) {
                out.add(new Line(' ', a[i++]));
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.add(new Line('-', a[i++]));
            } else {
                out.add(new Line('+', b[j++]));
            }
        }
        while (i < a.length) out.add(new Line('-', a[i++]));
        while (j < b.length) out.add(new Line('+', b[j++]));
        return out;
    }
}
