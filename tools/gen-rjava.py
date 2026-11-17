#!/usr/bin/env python3
"""Generate R.java from an aapt2-linked APK (Termux aapt2 cannot create --java dirs)."""
import re
import subprocess
import sys

apk, out = sys.argv[1], sys.argv[2]
badging = subprocess.run(["aapt2", "dump", "badging", apk], capture_output=True, text=True,
                         check=True).stdout
package = re.search(r"package: name='([^']+)'", badging).group(1)
text = subprocess.run(["aapt2", "dump", "resources", apk], capture_output=True, text=True,
                      check=True).stdout
ids, order, current = {}, [], None
for line in text.splitlines():
    match = re.match(r"\s+type (\w+) id=", line)
    if match:
        current = match.group(1)
        if current not in order:
            order.append(current)
        continue
    match = re.match(r"\s+resource 0x([0-9a-fA-F]+) (\w+)/([\w.]+)", line)
    if match and current:
        value, kind, name = int(match.group(1), 16), match.group(2), match.group(3).replace(".", "_")
        ids.setdefault((kind, name), value)

lines = ["package %s;" % package, "", "public final class R {"]
for kind in order:
    lines.append("    public static final class %s {" % kind)
    for (k, name), value in ids.items():
        if k == kind:
            lines.append("        public static final int %s=0x%08x;" % (name, value))
    lines.append("    }")
lines.append("}")
open(out, "w", encoding="utf-8").write("\n".join(lines) + "\n")
print("wrote %s (%d symbols, %d types)" % (out, len(ids), len(order)))
