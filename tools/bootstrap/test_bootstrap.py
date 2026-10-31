"""Safety checks using synthetic, temporary zip fixtures; no bootstrap is built."""

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("commandroid_line_bootstrap", Path(__file__).with_name("bootstrap.py"))
BOOTSTRAP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BOOTSTRAP)


class ConfigurationTest(unittest.TestCase):
    def configure(self, **changes):
        data = json.loads(BOOTSTRAP.CONFIG_PATH.read_text())
        data.update(changes)
        original = BOOTSTRAP.CONFIG_PATH
        with tempfile.TemporaryDirectory(prefix="commandroid-line-config-unit-") as directory:
            BOOTSTRAP.CONFIG_PATH = Path(directory) / "config.json"
            try:
                BOOTSTRAP.CONFIG_PATH.write_text(json.dumps(data))
                return BOOTSTRAP.configuration()
            finally:
                BOOTSTRAP.CONFIG_PATH = original

    def test_identity_is_commandroid_line(self):
        config = BOOTSTRAP.configuration()
        self.assertEqual(config["package_name"], "com.commandroid_line")
        self.assertEqual(config["prefix"], "/data/data/com.commandroid_line/files/usr")
        self.assertEqual(config["home"], "/data/data/com.commandroid_line/files/home")
        self.assertEqual(config["app_namespace"], "com.termux")
        self.assertEqual(config["extra_packages"], ["nodejs-lts", "git"])

    def test_rejects_official_prefix(self):
        with self.assertRaisesRegex(ValueError, "Unsafe package_name"):
            self.configure(package_name="com.termux")
        with self.assertRaisesRegex(ValueError, "Unsafe prefix"):
            self.configure(prefix="/data/data/com.termux/files/usr")

    def test_rejects_old_neoterm_identity(self):
        with self.assertRaisesRegex(ValueError, "Unsafe package_name"):
            self.configure(package_name="com.neoterm")
        with self.assertRaisesRegex(ValueError, "Unsafe data_dir"):
            self.configure(data_dir="/data/data/com.neoterm")

    def test_rejects_unsafe_extra_package_names(self):
        with self.assertRaisesRegex(ValueError, "extra_packages"):
            self.configure(extra_packages=["git;rm -rf /"])
        with self.assertRaisesRegex(ValueError, "extra_packages"):
            self.configure(extra_packages="nodejs-lts,git")
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            self.configure(extra_packages=["git", "git"])

    def test_extra_packages_are_optional(self):
        self.assertEqual(self.configure(extra_packages=[])["extra_packages"], [])

    def test_build_args_include_extra_packages(self):
        config = BOOTSTRAP.configuration()
        self.assertEqual(BOOTSTRAP.build_args("x86_64", config),
                         ["--architectures", "x86_64", "--add", "nodejs-lts,git"])
        self.assertEqual(BOOTSTRAP.build_args("aarch64", config, include_extra=False),
                         ["--architectures", "aarch64"])
        with self.assertRaisesRegex(ValueError, "Unsupported architecture"):
            BOOTSTRAP.build_args("mips", config)


class ArtifactValidationTest(unittest.TestCase):
    def setUp(self):
        self.config = BOOTSTRAP.configuration()
        prefix = self.config["prefix"].encode()
        elf = bytearray(64)
        elf[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<H", elf, 18, 183)
        self.files = {name: bytes(elf) + prefix
                      for name in ("bin/bash", "bin/apt", "bin/dpkg", "bin/ls", "bin/node", "bin/git")}
        self.files.update({
            "SYMLINKS.txt": b"bash\xe2\x86\x90bin/sh\n",
            "etc/apt/sources.list": b"# commandroid-line repository is not configured\n",
            "var/lib/dpkg/status": b"Package: bash\nStatus: install ok installed\n\n"
                                   b"Package: nodejs-lts\nStatus: install ok installed\n\n"
                                   b"Package: git\nStatus: install ok installed\n\n",
            "etc/termux/bootstrap/termux-bootstrap-second-stage.sh":
                b"#!" + prefix + b'/bin/bash\nexport TERMUX_PACKAGE_MANAGER="apt"\n'
                b'export TERMUX_PACKAGE_ARCH="aarch64"\n',
        })

    def validate(self, include_extra=True):
        with tempfile.TemporaryDirectory(prefix="commandroid-line-bootstrap-unit-") as directory:
            archive = Path(directory) / "bootstrap-aarch64.zip"
            with zipfile.ZipFile(archive, "w") as output:
                for name, data in self.files.items():
                    output.writestr(name, data)
            with contextlib.redirect_stdout(io.StringIO()):
                BOOTSTRAP.verify_artifact(archive, "aarch64", self.config, include_extra)

    def test_accepts_consistent_static_fixture(self):
        self.validate()

    def test_accepts_java_namespace_strings(self):
        # The app keeps the com.termux Java namespace; only private data paths are foreign.
        self.files["bin/bash"] += b"com.commandroid_line/com.termux.app.TermuxActivity\0/data/data/com.termux.api\0"
        self.validate()

    def test_rejects_official_runtime_prefix(self):
        self.files["bin/bash"] += b"/data/data/com.termux/files/usr/lib"
        with self.assertRaisesRegex(ValueError, "Foreign private prefix com.termux in bin/bash"):
            self.validate()

    def test_rejects_official_data_dir_without_trailing_path(self):
        self.files["lib/libtermux-exec.so"] = self.files["bin/ls"] + b"/data/data/com.termux\0"
        with self.assertRaisesRegex(ValueError, "Foreign private prefix com.termux in lib/libtermux-exec.so"):
            self.validate()

    def test_rejects_old_neoterm_runtime_prefix(self):
        self.files["etc/profile"] = b"export PATH=/data/data/com.neoterm/files/usr/bin\n"
        with self.assertRaisesRegex(ValueError, "Foreign private prefix com.neoterm in etc/profile"):
            self.validate()

    def test_rejects_secondary_user_official_prefix(self):
        self.files["var/lib/dpkg/info/bash.list"] = b"/data/user/10/com.termux/files/usr/bin/bash\n"
        with self.assertRaisesRegex(ValueError, "Foreign private prefix com.termux"):
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

    def test_rejects_old_neoterm_symlink(self):
        self.files["SYMLINKS.txt"] = "/data/data/com.neoterm/files/usr/bin/bash←bin/sh\n".encode()
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

    def test_rejects_official_mirror_definitions(self):
        self.files["etc/termux/mirrors/default"] = b'MAIN="https://packages-cf.termux.dev/apt/termux-main"\n'
        with self.assertRaisesRegex(ValueError, "Official Termux mirror definition"):
            self.validate()

    def test_rejects_chosen_mirror_link(self):
        self.files["SYMLINKS.txt"] += "mirrors/europe←etc/termux/chosen_mirrors\n".encode()
        with self.assertRaisesRegex(ValueError, "Official Termux mirror definition"):
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

    def test_rejects_missing_extra_package(self):
        self.files["var/lib/dpkg/status"] = b"Package: bash\nStatus: install ok installed\n\n"
        with self.assertRaisesRegex(ValueError, "Requested extra package nodejs-lts is not installed"):
            self.validate()

    def test_rejects_missing_extra_executable(self):
        del self.files["bin/node"]
        with self.assertRaisesRegex(ValueError, "nodejs-lts lacks bin/node"):
            self.validate()

    def test_rejects_script_as_extra_executable(self):
        self.files["bin/git"] = b"#!/system/bin/sh\n"
        with self.assertRaisesRegex(ValueError, "compiled ELF executable at bin/git"):
            self.validate()

    def test_default_set_archive_without_extra_packages(self):
        del self.files["bin/node"], self.files["bin/git"]
        self.files["var/lib/dpkg/status"] = b"Package: bash\nStatus: install ok installed\n\n"
        self.validate(include_extra=False)


if __name__ == "__main__":
    unittest.main()
