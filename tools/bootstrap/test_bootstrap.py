"""Safety checks using synthetic, temporary zip fixtures; no bootstrap is built."""

import contextlib
import importlib.util
import io
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("neoterm_bootstrap", Path(__file__).with_name("bootstrap.py"))
BOOTSTRAP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BOOTSTRAP)


class ArtifactValidationTest(unittest.TestCase):
    def setUp(self):
        self.config = BOOTSTRAP.configuration()
        prefix = self.config["prefix"].encode()
        elf = bytearray(64)
        elf[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<H", elf, 18, 183)
        self.files = {name: bytes(elf) + prefix for name in ("bin/bash", "bin/apt", "bin/dpkg", "bin/ls")}
        self.files.update({
            "SYMLINKS.txt": b"bash\xe2\x86\x90bin/sh\n",
            "etc/apt/sources.list": b"# NeoTerm repository is not configured\n",
            "var/lib/dpkg/status": b"Package: bash\n",
            "etc/termux/bootstrap/termux-bootstrap-second-stage.sh":
                b"#!" + prefix + b'/bin/bash\nexport TERMUX_PACKAGE_MANAGER="apt"\n'
                b'export TERMUX_PACKAGE_ARCH="aarch64"\n',
        })

    def validate(self):
        with tempfile.TemporaryDirectory(prefix="neoterm-bootstrap-unit-") as directory:
            archive = Path(directory) / "bootstrap-aarch64.zip"
            with zipfile.ZipFile(archive, "w") as output:
                for name, data in self.files.items():
                    output.writestr(name, data)
            with contextlib.redirect_stdout(io.StringIO()):
                BOOTSTRAP.verify_artifact(archive, "aarch64", self.config)

    def test_accepts_consistent_static_fixture(self):
        self.validate()

    def test_rejects_old_runtime_prefix(self):
        self.files["bin/bash"] += b"/data/data/com.termux/files/usr/lib"
        with self.assertRaisesRegex(ValueError, "Official Termux private prefix"):
            self.validate()

    def test_rejects_wrong_elf_architecture(self):
        self.files["bin/bash"] = self.files["bin/bash"][:18] + struct.pack("<H", 62) + self.files["bin/bash"][20:]
        with self.assertRaisesRegex(ValueError, "Wrong ELF architecture"):
            self.validate()

    def test_rejects_path_traversal(self):
        self.files["../outside"] = b"bad"
        with self.assertRaisesRegex(ValueError, "Unsafe or non-relative"):
            self.validate()

    def test_rejects_symlink_escape(self):
        self.files["SYMLINKS.txt"] = "../../outside←bin/sh\n".encode()
        with self.assertRaisesRegex(ValueError, "Relative symlink escapes"):
            self.validate()

    def test_rejects_foreign_app_symlink(self):
        self.files["SYMLINKS.txt"] = "/data/data/com.termux/files/usr/bin/bash←bin/sh\n".encode()
        with self.assertRaisesRegex(ValueError, "Symlink escapes"):
            self.validate()

    def test_rejects_absolute_symlink_traversal(self):
        self.files["SYMLINKS.txt"] = (self.config["prefix"] + "/../../outside←bin/sh\n").encode()
        with self.assertRaisesRegex(ValueError, "Symlink escapes"):
            self.validate()

    def test_rejects_active_repository(self):
        self.files["etc/apt/sources.list"] = b"deb https://packages.termux.dev/apt/termux-main stable main\n"
        with self.assertRaisesRegex(ValueError, "Unexpected active apt source"):
            self.validate()

    def test_rejects_missing_second_stage(self):
        del self.files["etc/termux/bootstrap/termux-bootstrap-second-stage.sh"]
        with self.assertRaisesRegex(ValueError, "Required full bootstrap files"):
            self.validate()

    def test_rejects_unconfigured_second_stage(self):
        key = "etc/termux/bootstrap/termux-bootstrap-second-stage.sh"
        self.files[key] = self.files[key].replace(b'export TERMUX_PACKAGE_MANAGER="apt"',
                                                b'export TERMUX_PACKAGE_MANAGER=""')
        with self.assertRaisesRegex(ValueError, "Second-stage package manager"):
            self.validate()

    def test_rejects_script_instead_of_binary(self):
        self.files["bin/bash"] = b"#!/system/bin/sh\n"
        with self.assertRaisesRegex(ValueError, "compiled ELF executable"):
            self.validate()


if __name__ == "__main__":
    unittest.main()
