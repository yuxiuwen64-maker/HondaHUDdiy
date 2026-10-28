# Copyright (C) 2026 yuxiuwen64-maker
# SPDX-License-Identifier: AGPL-3.0-or-later
import sys, zipfile, shutil

base, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.copyfile(base, out)
with zipfile.ZipFile(out, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
print("dex added:", dex)
