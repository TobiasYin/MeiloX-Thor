package com.ljyh.mei.thor.artwork

import org.junit.Assert.assertEquals
import org.junit.Test

class CaseLabelsTest {
    @Test fun supplementaryNamesStayIntact() {
        assertEquals("A𠮷🎵", caseLabel("A𠮷🎵中", 3))
        assertEquals("A𠮷", caseLabel("A𠮷🎵中", 2))
    }

    @Test fun emptyAndUnlimitedLabelsStayStable() {
        assertEquals("", caseLabel("", 18))
        assertEquals("", caseLabel("音乐", 0))
        assertEquals("音乐", caseLabel("音乐", 18))
    }
}
