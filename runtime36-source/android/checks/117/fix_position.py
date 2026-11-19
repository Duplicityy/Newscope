"""Apply the Android 117 HD interface correction to the staged owner plugin bundle."""
from pathlib import Path
from zipfile import ZipFile
import hashlib, sys
assets = Path(sys.argv[1])
jar = assets / 'owner-plugins.jar'
replacements = {
    'rs117/hd/vert.glsl': [('out ivec3 vPosition;', 'out highp vec3 vPosition;'),
                           ('vPosition = vertex;', 'vPosition = vec3(vertex);')],
    'rs117/hd/geom.glsl': [('in ivec3 vPosition[];', 'in highp vec3 vPosition[];')],
}
temp = jar.with_suffix('.new')
with ZipFile(jar) as src, ZipFile(temp, 'w') as dst:
    for name in replacements:
        assert src.namelist().count(name) == 1
    for entry in src.infolist():
        data = src.read(entry)
        for old, new in replacements.get(entry.filename, []):
            assert data.count(old.encode()) == 1, (entry.filename, old)
            data = data.replace(old.encode(), new.encode())
        dst.writestr(entry, data)
temp.replace(jar)
tag = 'newscape-117-link-' + hashlib.sha256(jar.read_bytes()).hexdigest()[:24]
(assets/'runtime.tag').write_text(tag+'\n', encoding='ascii')
