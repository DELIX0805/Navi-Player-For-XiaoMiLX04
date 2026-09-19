# -*- coding: utf-8 -*-
"""构建/调试脚本共用的工具链路径解析。

仓库里不写死任何一台机器的绝对路径：优先读环境变量，其次按平台常见位置
自动查找。支持 Windows / Linux / macOS（可执行文件后缀自动处理）。

可用环境变量：
  ANDROID_HOME / ANDROID_SDK_ROOT   Android SDK 根目录
  ANDROID_BUILD_TOOLS               指定 build-tools 版本（默认取已安装的最新版）
  ANDROID_API                       编译用的 android.jar API 级别（默认 34）
  JAVA_HOME / JDK_HOME              JDK 根目录（需要 17 及以上）
  ADB                               adb 可执行文件（不设则自动找）
"""
import os
from shutil import which

ROOT = os.path.dirname(os.path.abspath(__file__))
IS_WIN = os.name == "nt"

_PROPS = None


def prop(key, default=""):
    """读取仓库根目录下的 local.properties（不进版本库，用来记住本机路径）"""
    global _PROPS
    if _PROPS is None:
        _PROPS = {}
        p = os.path.join(ROOT, "local.properties")
        if os.path.exists(p):
            with open(p, encoding="utf-8", errors="replace") as f:
                for ln in f:
                    ln = ln.strip()
                    if ln and not ln.startswith("#") and "=" in ln:
                        k, v = ln.split("=", 1)
                        _PROPS[k.strip()] = v.strip()
    return _PROPS.get(key, default)


def exe(name):
    """按平台补可执行文件后缀"""
    return name + ".exe" if IS_WIN else name


def _first_dir(*cands):
    for c in cands:
        if c and os.path.isdir(c):
            return c
    return ""


def sdk_dir():
    env = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if env:
        return env
    local_sdk = prop("sdk.dir")
    if local_sdk and os.path.isdir(local_sdk):
        return local_sdk
    home = os.path.expanduser("~")
    local = os.environ.get("LOCALAPPDATA") or os.path.join(home, "AppData", "Local")
    return _first_dir(
        os.path.join(local, "Android", "Sdk"),
        os.path.join(home, "Android", "Sdk"),
        os.path.join(home, "Library", "Android", "sdk"),
        os.path.join(home, "Android"),
    ) or os.path.join(local, "Android", "Sdk")


def build_tools():
    """build-tools 目录；未指定版本时取已安装的最新版"""
    d = os.path.join(sdk_dir(), "build-tools")
    v = os.environ.get("ANDROID_BUILD_TOOLS")
    if v:
        return os.path.join(d, v)
    if os.path.isdir(d):
        vs = [x for x in sorted(os.listdir(d), reverse=True)
              if os.path.isdir(os.path.join(d, x))]
        if vs:
            return os.path.join(d, vs[0])
    return os.path.join(d, "34.0.0")


def platform_jar():
    """编译用的 android.jar；指定的 API 没装则退到已装的最新版"""
    d = os.path.join(sdk_dir(), "platforms")
    api = os.environ.get("ANDROID_API") or "34"
    p = os.path.join(d, "android-%s" % api, "android.jar")
    if os.path.exists(p):
        return p
    if os.path.isdir(d):
        vs = [x for x in sorted(os.listdir(d), reverse=True)
              if x.startswith("android-")
              and os.path.exists(os.path.join(d, x, "android.jar"))]
        if vs:
            return os.path.join(d, vs[0], "android.jar")
    return p


def jdk_home():
    for k in ("JAVA_HOME", "JDK_HOME"):
        v = os.environ.get(k)
        if v and os.path.isdir(v):
            return v
    local_jdk = prop("jdk.dir")
    if local_jdk and os.path.isdir(local_jdk):
        return local_jdk
    home = os.path.expanduser("~")
    p = _first_dir(
        os.path.join(home, "jdk17"),
        "/usr/lib/jvm/java-17-openjdk-amd64",
        "/Library/Java/JavaVirtualMachines",
    )
    if p:
        return p
    raise SystemExit(
        "找不到 JDK：请设置 JAVA_HOME 指向 JDK 17 及以上，例如\n"
        "  Windows: set JAVA_HOME=C:\\path\\to\\jdk17\n"
        "  Linux/Mac: export JAVA_HOME=/path/to/jdk-17")


def jdk_tool(name):
    """JDK bin 下的工具：java / javac / keytool"""
    return os.path.join(jdk_home(), "bin", exe(name))


def sdk_tool(name):
    """build-tools 下的工具：aapt2 / zipalign 等"""
    return os.path.join(build_tools(), exe(name))


def adb_path():
    """adb 可执行文件；优先 $ADB，其次 SDK 默认位置，最后 PATH"""
    v = os.environ.get("ADB")
    if v:
        return v
    p = os.path.join(sdk_dir(), "platform-tools", exe("adb"))
    if os.path.exists(p):
        return p
    w = which(exe("adb")) or which("adb")
    if w:
        return w
    return p   # 不存在就让调用方报出明确错误
