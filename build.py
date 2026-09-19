"""离线构建脚本：aapt2 -> javac -> d8 -> zipalign -> apksigner

用法: python build.py
输出: build/naviplayer.apk
"""
import os
import shutil
import subprocess
import sys
import zipfile

from devpath import ROOT, build_tools, platform_jar, jdk_tool, sdk_tool

BT = build_tools()
PLAT = platform_jar()
JAVA = jdk_tool("java")
JAVAC = jdk_tool("javac")
KEYTOOL = jdk_tool("keytool")
BUILD = os.path.join(ROOT, "build")


def run(cmd, quiet=True):
    r = subprocess.run(cmd, capture_output=True, text=True, errors="replace")
    if not quiet or r.returncode != 0:
        for ln in (r.stdout or "").splitlines():
            print("  out:", ln)
        for ln in (r.stderr or "").splitlines():
            print("  err:", ln)
        if r.returncode != 0:
            print("  !! exit", r.returncode, cmd[0])
    return r


def main():
    shutil.rmtree(BUILD, ignore_errors=True)
    os.makedirs(os.path.join(BUILD, "obj"), exist_ok=True)
    os.makedirs(os.path.join(BUILD, "gen"), exist_ok=True)

    print("== aapt2 compile ==")
    run([sdk_tool("aapt2"), "compile", "--dir", os.path.join(ROOT, "res"),
         "-o", os.path.join(BUILD, "res.zip")], quiet=False)

    print("== aapt2 link ==")
    run([sdk_tool("aapt2"), "link", "-I", PLAT,
         "--manifest", os.path.join(ROOT, "AndroidManifest.xml"),
         "-o", os.path.join(BUILD, "app.apk"), os.path.join(BUILD, "res.zip"),
         "--java", os.path.join(BUILD, "gen"), "--auto-add-overlay",
         "--min-sdk-version", "21", "--target-sdk-version", "27"], quiet=False)

    srcs = []
    for d in (os.path.join(ROOT, "src"), os.path.join(BUILD, "gen")):
        for dp, _, fns in os.walk(d):
            for f in fns:
                if f.endswith(".java"):
                    srcs.append(os.path.join(dp, f))
    print("== javac (%d files) ==" % len(srcs))
    r = run([JAVAC, "-source", "8", "-target", "8", "-encoding", "UTF-8",
             "-bootclasspath", PLAT, "-classpath", PLAT,
             "-d", os.path.join(BUILD, "obj"), "-nowarn"] + srcs, quiet=False)
    if r.returncode != 0:
        print("BUILD FAILED at javac")
        return 1

    classes = []
    for dp, _, fns in os.walk(os.path.join(BUILD, "obj")):
        for f in fns:
            if f.endswith(".class"):
                classes.append(os.path.join(dp, f))
    print("== d8 (%d classes) ==" % len(classes))
    run([JAVA, "-cp", os.path.join(BT, "lib", "d8.jar"), "com.android.tools.r8.D8",
         "--lib", PLAT, "--min-api", "21", "--output", BUILD] + classes, quiet=False)

    dex = os.path.join(BUILD, "classes.dex")
    if not os.path.exists(dex):
        print("BUILD FAILED: no classes.dex")
        return 1
    with zipfile.ZipFile(os.path.join(BUILD, "app.apk"), "a", zipfile.ZIP_DEFLATED) as z:
        z.write(dex, "classes.dex")

    print("== zipalign ==")
    run([sdk_tool("zipalign"), "-p", "-f", "4",
         os.path.join(BUILD, "app.apk"), os.path.join(BUILD, "aligned.apk")], quiet=False)

    ks = os.path.join(ROOT, "navi.jks")
    if not os.path.exists(ks):
        print("== keytool ==")
        run([KEYTOOL, "-genkeypair", "-v", "-keystore", ks, "-alias", "navi",
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10950",
             "-storepass", "android", "-keypass", "android",
             "-dname", "CN=Navi, OU=dev, O=tongsir, C=CN"], quiet=False)

    print("== sign ==")
    run([JAVA, "-cp", os.path.join(BT, "lib", "apksigner.jar"),
         "com.android.apksigner.ApkSignerTool", "sign", "--ks", ks,
         "--ks-pass", "pass:android", "--key-pass", "pass:android",
         "--ks-key-alias", "navi", "--out", os.path.join(BUILD, "naviplayer.apk"),
         os.path.join(BUILD, "aligned.apk")], quiet=False)

    print("== verify ==")
    run([JAVA, "-cp", os.path.join(BT, "lib", "apksigner.jar"),
         "com.android.apksigner.ApkSignerTool", "verify",
         os.path.join(BUILD, "naviplayer.apk")], quiet=False)

    apk = os.path.join(BUILD, "naviplayer.apk")
    if os.path.exists(apk):
        print("OK size=%d" % os.path.getsize(apk))
        return 0
    print("BUILD FAILED")
    return 1


if __name__ == "__main__":
    sys.exit(main())
