// SPDX-License-Identifier: GPL-3.0-only
package com.termux.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import org.junit.Test;

public class BootstrapEntriesTest {

    private static final String PREFIX = "/data/data/com.commandroid_line/files/usr";

    private static boolean executable(String name, byte[] content) throws IOException {
        byte[] head = new byte[BootstrapEntries.HEADER_LENGTH];
        int headLength = BootstrapEntries.copy(new ByteArrayInputStream(content), new ByteArrayOutputStream(),
            new byte[8096], head);
        return BootstrapEntries.isExecutable(name, head, headLength);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void upstreamPathListStaysExecutable() throws IOException {
        for (String name : new String[] {"bin/bash", "libexec/git-core/git-add", "lib/apt/apt-helper",
            "lib/apt/methods/https", "etc/termux/bootstrap/termux-bootstrap-second-stage.sh"})
            assertTrue(name, executable(name, bytes("data")));
    }

    @Test
    public void npmCliShebangTargetIsExecutable() throws IOException {
        // bin/npm -> ../lib/node_modules/npm/bin/npm-cli.js (SYMLINKS.txt from Node's install.py).
        assertTrue(executable("lib/node_modules/npm/bin/npm-cli.js", bytes("#!" + PREFIX + "/bin/env node\n")));
        assertTrue(executable("lib/node_modules/corepack/dist/corepack.js", bytes("#!/usr/bin/env node\n")));
    }

    @Test
    public void elfOutsideBinIsExecutable() throws IOException {
        byte[] elf = new byte[64];
        elf[0] = 0x7f; elf[1] = 'E'; elf[2] = 'L'; elf[3] = 'F';
        assertTrue(executable("lib/node_modules/npm/node_modules/node-gyp/gyp-helper", elf));
    }

    @Test
    public void ordinaryFilesStayPrivate() throws IOException {
        assertFalse(executable("lib/node_modules/npm/package.json", bytes("{\"name\":\"npm\"}")));
        assertFalse(executable("etc/profile", bytes("export PATH=" + PREFIX + "/bin\n")));
        assertFalse(executable("share/doc/readme", bytes("# not a shebang\n")));
        assertFalse(executable("share/empty", new byte[0]));
        assertFalse(executable("share/one", bytes("#")));
        assertFalse(executable("share/elf-prefix", new byte[] {0x7f, 'E', 'L'}));
    }

    @Test
    public void headerSurvivesShortReads() throws IOException {
        byte[] content = bytes("#!/bin/sh\necho hi\n");
        InputStream oneByteAtATime = new ByteArrayInputStream(content) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(len, 1));
            }
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] head = new byte[BootstrapEntries.HEADER_LENGTH];
        int headLength = BootstrapEntries.copy(oneByteAtATime, out, new byte[8096], head);
        assertEquals(4, headLength);
        assertArrayEquals(Arrays.copyOf(content, 4), head);
        assertArrayEquals(content, out.toByteArray());
        assertTrue(BootstrapEntries.isExecutable("lib/x", head, headLength));
    }

    @Test
    public void symlinkPathDropsFindDotPrefix() throws IOException {
        // build-bootstraps.sh writes `$(readlink "$link")←${link}` over `find . -type l`.
        assertEquals(PREFIX + "-staging/bin/npm", BootstrapEntries.symlinkPath(PREFIX + "-staging", "./bin/npm"));
        assertEquals(PREFIX + "-staging/bin/sh", BootstrapEntries.symlinkPath(PREFIX + "-staging", "bin/sh"));
    }

    @Test
    public void symlinkPathRejectsEmptyAndAbsolute() {
        for (String link : new String[] {"", "./", "/bin/sh", ".//bin/sh"})
            assertThrows(link, IOException.class, () -> BootstrapEntries.symlinkPath(PREFIX, link));
    }

    @Test
    public void dotPrefixedEscapesAreStillRejected() throws IOException {
        File staging = Files.createTempDirectory("bootstrap-staging").toFile();
        try {
            String root = staging.getCanonicalPath() + File.separator;
            BootstrapEntries.requireInside(root, new File(BootstrapEntries.symlinkPath(staging.getPath(), "./bin/npm")));
            BootstrapEntries.requireInside(root, new File(staging, "lib/node_modules/npm/bin/npm-cli.js"));
            for (String link : new String[] {"./../outside", "../outside", "./bin/../../outside"}) {
                String path = BootstrapEntries.symlinkPath(staging.getPath(), link);
                assertThrows(link, IOException.class, () -> BootstrapEntries.requireInside(root, new File(path)));
            }
            assertThrows(IOException.class, () -> BootstrapEntries.requireInside(root, new File(staging, "../outside")));
            File sibling = new File(staging.getPath() + "-evil", "x");
            assertThrows(IOException.class, () -> BootstrapEntries.requireInside(root, sibling));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            staging.delete();
        }
    }
}
