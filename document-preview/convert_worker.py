"""Private LibreOffice UNO worker. Its parent kills the entire process group at 90 seconds."""
import os
from pathlib import Path
import resource
import subprocess
import sys
import time
import uuid


def main():
    if len(sys.argv) != 4:
        return 2
    import uno
    import unohelper
    from com.sun.star.beans import PropertyValue
    from com.sun.star.task import XInteractionHandler

    class RejectInteraction(unohelper.Base, XInteractionHandler):
        def handle(self, request):
            for continuation in request.getContinuations():
                abort = continuation.queryInterface(uno.getTypeByName("com.sun.star.task.XInteractionAbort"))
                if abort is not None:
                    abort.select()
                    return

    def property_value(name, value):
        return PropertyValue(name, 0, value, 0)

    source, output, profile = [Path(value).resolve() for value in sys.argv[1:]]
    if source.suffix not in (".ppt", ".pptx") or not source.is_file() or source.stat().st_size > 50 * 1024 * 1024:
        return 2
    # Bound individual generated files, including output PDF and intermediate profile files.
    resource.setrlimit(resource.RLIMIT_FSIZE, (50 * 1024 * 1024, 50 * 1024 * 1024))
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    (profile / "user").mkdir(parents=True)
    (profile / "user" / "registrymodifications.xcu").write_text('''<?xml version="1.0" encoding="UTF-8"?>
<oor:items xmlns:oor="http://openoffice.org/2001/registry">
<item oor:path="/org.openoffice.Office.Common/Security/Scripting"><prop oor:name="MacroSecurityLevel" oor:op="fuse"><value>3</value></prop></item>
</oor:items>''')
    pipe = "knowledge-preview-" + uuid.uuid4().hex
    office = subprocess.Popen(["/usr/bin/soffice", "-env:UserInstallation=" + profile.as_uri(),
        "--headless", "--invisible", "--nologo", "--nodefault", "--norestore", "--nolockcheck",
        "--accept=pipe,name=" + pipe + ";urp;StarOffice.ServiceManager"],
        stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    document = None
    desktop = None
    try:
        context = uno.getComponentContext()
        resolver = context.ServiceManager.createInstanceWithContext("com.sun.star.bridge.UnoUrlResolver", context)
        connected = None
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            if office.poll() is not None:
                return 3
            try:
                connected = resolver.resolve("uno:pipe,name=" + pipe + ";urp;StarOffice.ComponentContext")
                break
            except Exception:
                time.sleep(0.1)
        if connected is None:
            return 3
        desktop = connected.ServiceManager.createInstanceWithContext("com.sun.star.frame.Desktop", connected)
        properties = tuple(property_value(key, value) for key, value in (
            ("Hidden", True), ("ReadOnly", True), ("Silent", True),
            ("MacroExecutionMode", uno.Any("short", uno.getConstantByName("com.sun.star.document.MacroExecMode.NEVER_EXECUTE"))),
            ("UpdateDocMode", uno.Any("short", uno.getConstantByName("com.sun.star.document.UpdateDocMode.NO_UPDATE"))),
            ("InteractionHandler", RejectInteraction()),
            ("FilterName", "MS PowerPoint 97" if source.suffix == ".ppt" else "Impress MS PowerPoint 2007 XML")))
        document = desktop.loadComponentFromURL(source.as_uri(), "_blank", 0, properties)
        if document is None or not document.supportsService("com.sun.star.presentation.PresentationDocument"):
            return 4
        document.storeToURL(output.as_uri(), (property_value("FilterName", "impress_pdf_Export"),
                                             property_value("Overwrite", True)))
        return 0 if output.is_file() else 4
    finally:
        if document is not None:
            try:
                document.close(True)
            except Exception:
                pass
        if desktop is not None:
            try:
                desktop.terminate()
            except Exception:
                pass
        try:
            office.wait(timeout=5)
        except subprocess.TimeoutExpired:
            office.kill()
            office.wait()


if __name__ == "__main__":
    try:
        result = main()
    except Exception:
        result = 5
    sys.exit(result)
