from pathlib import Path
import hashlib,sys,zipfile
root=Path(sys.argv[1])
vm=root/"android/kronos/src/main/assets/jvm"
base=root/"android-build/baseline"
stage=root/"android-classes"
client="net/runelite/standalone/"
def patch(jar, replacements):
    with zipfile.ZipFile(base/jar) as old, zipfile.ZipFile(vm/jar,"w") as new:
        remaining=dict(replacements)
        for info in old.infolist():
            data=remaining.pop(info.filename,None)
            new.writestr(info, old.read(info) if data is None else data)
        for name,data in remaining.items():
            new.writestr(name,data,compress_type=zipfile.ZIP_DEFLATED)

apk=Path("D:/Newscape-Map-Confirm/dist/Newscape-Android-World-Map-v37.apk")
with zipfile.ZipFile(apk) as z:
    for name in ("bridge.jar","owner-plugins.jar","client.jar"):
        (base/name).write_bytes(z.read("assets/jvm/"+name))
with zipfile.ZipFile(base/"client.jar") as old:
    (base/"WorldMapData_1.class").write_bytes(old.read(client+"WorldMapData_1.class"))
if len(sys.argv)==2: sys.exit(0)
native={client+f.name:f.read_bytes() for f in (stage/client).glob("OwnerAutomationClient*.class")}
native[client+"WorldMapData_1.class"]=(stage/client/"WorldMapData_1.class").read_bytes()
patch("client.jar",native)
bridge_entry=[name for name in ("bridge.jar","owner-plugins.jar") if "NewscapePluginBridge.class" in zipfile.ZipFile(base/name).namelist()]
assert len(bridge_entry)==1,bridge_entry
patch(bridge_entry[0], {"NewscapePluginBridge.class":(stage/"NewscapePluginBridge.class").read_bytes()})
dig=hashlib.sha256((vm/"client.jar").read_bytes()+(vm/bridge_entry[0]).read_bytes()).hexdigest()[:24]
(vm/"runtime.tag").write_text("newscape-owner-automation-38-"+dig+"\n",encoding="ascii")
print("Automation transport staged over signed v37",bridge_entry[0],dig)
