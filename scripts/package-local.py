#!/usr/bin/env python3
"""Package explicitly supplied, separately compiled production outputs. This does not replace Gradle verification."""
import argparse
import hashlib
import io
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

parser = argparse.ArgumentParser()
parser.add_argument('--runtime-classes', required=True, type=Path)
parser.add_argument('--plugin-classes', required=True, type=Path)
parser.add_argument('--bridge-classes', type=Path)
parser.add_argument('--dependency-classpath', required=True, type=Path)
parser.add_argument('--output', required=True, type=Path)
args = parser.parse_args()
repo = Path(__file__).resolve().parents[1]
version = re.search(r'^version = "([^"]+)"', (repo / 'build.gradle.kts').read_text(), re.M)[1]
properties = dict(re.findall(r'^\s*([^#!\s=]+)\s*=\s*(.*?)\s*$',
                             (repo / 'gradle.properties').read_text(), re.M))
since_build = properties['pluginSinceBuild']
until_build = properties['pluginUntilBuild']
args.output.mkdir(parents=True, exist_ok=True)

def add_tree(jar, root, suffix=None):
    for file in sorted(root.rglob('*')):
        if file.is_file() and (suffix is None or file.suffix == suffix):
            jar.write(file, file.relative_to(root).as_posix())

manifest = ('Manifest-Version: 1.0\r\nPremain-Class: com.baader.devrt.Agent\r\n'
            'Agent-Class: com.baader.devrt.Agent\r\nCan-Redefine-Classes: true\r\n'
            'Can-Retransform-Classes: true\r\nSB-Repl-Protocol: 1\r\n\r\n')
agent = args.output / ('dev-runtime-agent-' + version + '.jar')
with ZipFile(agent, 'w', ZIP_DEFLATED) as jar:
    jar.writestr('META-INF/MANIFEST.MF', manifest)
    for file in sorted(args.runtime_classes.rglob('*.class')):
        name = file.relative_to(args.runtime_classes).as_posix()
        assert name.startswith(('com/baader/devrt/', 'hu/baader/repl/protocol/')), name
        assert 'Test' not in file.name and 'SmokeApp' not in name, 'Production outputs must be separate from tests'
        jar.write(file, name)
    libs = [Path(p) for p in args.dependency_classpath.read_text().strip().split(':') if Path(p).name.startswith('byte-buddy-')]
    assert len(libs) == 2, 'Supply exactly the matching Byte Buddy and Byte Buddy agent libraries'
    for lib in libs:
        jar.write(lib, 'agent-libs/' + lib.name)

protocol = io.BytesIO()
with ZipFile(protocol, 'w', ZIP_DEFLATED) as jar:
    for file in sorted((args.runtime_classes / 'hu/baader/repl/protocol').rglob('*.class')):
        jar.write(file, file.relative_to(args.runtime_classes).as_posix())

plugin = io.BytesIO()
with ZipFile(plugin, 'w', ZIP_DEFLATED) as jar:
    add_tree(jar, args.plugin_classes)
    for file in sorted((repo / 'src/main/resources').rglob('*')):
        if not file.is_file():
            continue
        name = file.relative_to(repo / 'src/main/resources').as_posix()
        if name == 'META-INF/plugin.xml':
            xml = ET.fromstring(file.read_text())
            assert xml.attrib.get('use-idea-classloader') is None
            version_node = xml.find('version')
            if version_node is None:
                version_node = ET.SubElement(xml, 'version')
            version_node.text = version
            compatibility = xml.find('idea-version')
            if compatibility is None:
                compatibility = ET.SubElement(xml, 'idea-version')
            compatibility.set('since-build', since_build)
            compatibility.set('until-build', until_build)
            jar.writestr(name, ET.tostring(xml, encoding='utf-8', xml_declaration=True))
        else:
            jar.write(file, name)
    add_tree(jar, repo / 'docs')
    jar.write(agent, 'agent/dev-runtime-agent.jar')
    jar.writestr('META-INF/sb-repl-build.txt',
                'Locally compiled with javac and Kotlin compiler; Gradle and Plugin Verifier were not run successfully.\n'
                f'Declared IDE range: {since_build} through {until_build}. This is not proof of IDE compatibility.\n'
                'See IDEA_2025_2_0_13_1.md for verification and limitations; MCP_0_13.md for MCP features.\n')

archive = args.output / ('Spring-Boot-REPL-' + version + '-local.zip')
with ZipFile(archive, 'w', ZIP_DEFLATED) as zip:
    zip.writestr('Spring Boot REPL/lib/sb-repl-' + version + '.jar', plugin.getvalue())
    zip.writestr('Spring Boot REPL/lib/repl-protocol-' + version + '.jar', protocol.getvalue())
artifacts = [agent, archive]
if args.bridge_classes:
    bridge = args.output / ('sb-repl-bridge-' + version + '-local.jar')
    with ZipFile(bridge, 'w', ZIP_DEFLATED) as jar:
        jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\nImplementation-Version: ' + version + '\r\n\r\n')
        add_tree(jar, args.bridge_classes, '.class')
        add_tree(jar, repo / 'sb-repl-bridge/src/main/resources')
        jar.writestr('META-INF/sb-repl-build.txt', 'Direct javac build; see REPL_WORKFLOW_0_12.md.\n')
    artifacts.append(bridge)
for file in artifacts:
    digest = hashlib.sha256(file.read_bytes()).hexdigest()
    file.with_suffix(file.suffix + '.sha256').write_text(digest + '  ' + file.name + '\n')
    print(str(file), file.stat().st_size, 'bytes', digest)
