// SPDX-License-Identifier: GPL-3.0-only
package com.termux.app;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Pure decisions for {@link BootstrapInstaller}: which archive entries become executable, where a
 * SYMLINKS.txt link is created, and whether a path stays inside the staging prefix.
 *
 * <p>{@code ZipInputStream} drops the unix modes stored in the archive, so every extracted file
 * starts at 0600. Upstream only marks a fixed set of directories executable, which misses scripts
 * and binaries that bin/ symlinks point at (npm, npx and corepack link into lib/node_modules).
 * A regular file is therefore also made executable when its content starts with an ELF header or
 * a {@code #!} interpreter line.
 */
final class BootstrapEntries {

    /** Bytes of file content {@link #isExecutable} needs to see. */
    static final int HEADER_LENGTH = 4;

    private BootstrapEntries() {}

    /**
     * @param entryName archive entry name relative to the prefix
     * @param head the first bytes of the file content; only {@code headLength} bytes are valid
     * @param headLength number of valid bytes in {@code head} (the file may be shorter than 4 bytes)
     */
    static boolean isExecutable(String entryName, byte[] head, int headLength) {
        return isExecutablePath(entryName) || isElf(head, headLength) || isShebang(head, headLength);
    }

    /**
     * Copies {@code in} to {@code out} and keeps the first bytes in {@code head}.
     *
     * @return the number of valid bytes in {@code head}
     */
    static int copy(InputStream in, OutputStream out, byte[] buffer, byte[] head) throws IOException {
        int headLength = 0;
        int readBytes;
        while ((readBytes = in.read(buffer)) != -1) {
            int kept = Math.min(readBytes, head.length - headLength);
            System.arraycopy(buffer, 0, head, headLength, kept);
            headLength += kept;
            out.write(buffer, 0, readBytes);
        }
        return headLength;
    }

    /** Upstream termux-app's fixed list of executable locations. */
    static boolean isExecutablePath(String entryName) {
        return entryName.startsWith("bin/") || entryName.startsWith("libexec") ||
            entryName.startsWith("lib/apt/apt-helper") || entryName.startsWith("lib/apt/methods") ||
            entryName.equals("etc/termux/bootstrap/termux-bootstrap-second-stage.sh");
    }

    private static boolean isElf(byte[] head, int headLength) {
        return headLength >= 4 && head[0] == 0x7f && head[1] == 'E' && head[2] == 'L' && head[3] == 'F';
    }

    private static boolean isShebang(byte[] head, int headLength) {
        return headLength >= 2 && head[0] == '#' && head[1] == '!';
    }

    /**
     * Absolute path for a SYMLINKS.txt link destination. build-bootstraps.sh writes destinations
     * from {@code find . -type l}, so they start with "./"; one such prefix is removed. Escapes
     * are still rejected by {@link #requireInside}.
     */
    static String symlinkPath(String stagingPrefixPath, String linkPath) throws IOException {
        String relative = linkPath.startsWith("./") ? linkPath.substring(2) : linkPath;
        if (relative.isEmpty() || relative.startsWith("/"))
            throw new IOException("Invalid symlink path: " + linkPath);
        return stagingPrefixPath + "/" + relative;
    }

    /** Rejects archive paths that would escape the staging prefix (zip slip). */
    static void requireInside(String canonicalRootWithSeparator, File target) throws IOException {
        String path = target.getCanonicalPath();
        if (!(path + File.separator).startsWith(canonicalRootWithSeparator) && !path.startsWith(canonicalRootWithSeparator))
            throw new IOException("Bootstrap entry escapes the prefix: " + target.getPath());
    }
}
