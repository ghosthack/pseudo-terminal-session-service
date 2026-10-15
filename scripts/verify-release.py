#!/usr/bin/env python3
"""Validate the Maven release artifacts without uploading them."""
import argparse
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def verify(signatures=False):
    pom = ET.parse(ROOT / "pom.xml").getroot()
    version = pom.findtext("m:version", namespaces=NS)
    assert version and not version.endswith("-SNAPSHOT") and "/" not in version
    assert pom.findtext("m:groupId", namespaces=NS) == "io.github.ghosthack"
    assert pom.findtext("m:artifactId", namespaces=NS) == "jmux"
    assert pom.findtext("m:licenses/m:license/m:name", namespaces=NS) == "MIT License"
    assert pom.findtext("m:scm/m:tag", namespaces=NS) == "v" + version
    expected_license = (ROOT / "LICENSE").read_bytes()
    files = []
    for suffix in (".jar", "-cli.jar", "-sources.jar", "-javadoc.jar"):
        path = ROOT / "target" / ("jmux-" + version + suffix)
        with ZipFile(path) as jar:
            names = jar.namelist()
            prefix = "doc-files/" if suffix == "-javadoc.jar" else "META-INF/"
            assert jar.read(prefix + "LICENSE") == expected_license, f"Incorrect MIT notice in {path}"
            assert prefix + "THIRD-PARTY.md" in names, f"Missing third-party notices in {path}"
            if suffix != "-javadoc.jar":
                for notice in ("JEDITERM-NOTICE.txt", "PTY4J-LICENSE", "PTY4J-NOTICE"):
                    assert "META-INF/licenses/" + notice in names, f"Missing {notice} in {path}"
            if suffix == "-cli.jar":
                assert "io/github/ghosthack/jmux/Main.class" in names
                assert "com/jediterm/terminal/model/JediTerminal.class" in names
                assert "com/pty4j/PtyProcess.class" in names
                manifest = jar.read("META-INF/MANIFEST.MF").decode()
                assert "Main-Class: io.github.ghosthack.jmux.Main" in manifest
                assert "Implementation-Version: " + version in manifest
            elif suffix == "-sources.jar":
                assert "io/github/ghosthack/jmux/Main.java" in names
            elif suffix == "-javadoc.jar":
                assert "index.html" in names
        files.append(path)
    if signatures:
        files.append(ROOT / "target" / ("jmux-" + version + ".pom"))
        for path in files:
            subprocess.run(["gpg", "--batch", "--verify", str(path) + ".asc", str(path)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    print("Verified io.github.ghosthack:jmux:" + version + (" including all signatures" if signatures else " release packaging"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--signatures", action="store_true")
    verify(parser.parse_args().signatures)
