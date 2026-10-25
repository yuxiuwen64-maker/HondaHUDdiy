import sys, zipfile, shutil

base, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.copyfile(base, out)
with zipfile.ZipFile(out, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
print("dex added:", dex)
