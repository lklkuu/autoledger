#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AutoLedger 静态一致性校验器。

本机没有 JDK / Android SDK 时，用它替代编译器做交叉检查：
  1. 每个 module 都有 build.gradle.kts，且在 settings.gradle.kts 里声明
  2. build.gradle.kts 里引用的 project(":x") 必须真实存在
  3. Gradle 版本目录别名 libs.xxx.yyy 必须在 gradle/libs.versions.toml 里定义
  4. Kotlin 文件的 package 声明必须与目录路径一致
  5. 跨模块 import 必须有对应的 project() 依赖声明（防止"能编译但依赖方向乱了"）
  6. 分层约束：core 层不允许依赖 feature 层，feature 层不允许依赖 app 层
  7. 不允许出现 TODO() 占位实现
  8. API 泄漏：core 模块的公开声明（class/interface/object 头）引用了某依赖的类型，
     但该依赖在 build.gradle.kts 里声明为 implementation（应为 api）

用法： python3 tools/static_check.py
退出码 0 = 通过，1 = 发现阻塞级问题。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

MODULE_PKGS = {
    "core:model": "com.autoledger.core.model",
    "core:crypto": "com.autoledger.core.crypto",
    "core:database": "com.autoledger.core.database",
    "core:backup": "com.autoledger.core.backup",
    "feature:capture": "com.autoledger.feature.capture",
    "feature:classify": "com.autoledger.feature.classify",
    "feature:dedup": "com.autoledger.feature.dedup",
    "feature:stats": "com.autoledger.feature.stats",
    "feature:refund": "com.autoledger.feature.refund",
    "feature:platform": "com.autoledger.feature.platform",
    "feature:transfer": "com.autoledger.feature.transfer",
    "feature:ai": "com.autoledger.feature.ai",
    "app": "com.autoledger.app",
}

JVM_MODULES = {"core:model", "feature:dedup", "feature:stats", "feature:refund", "feature:platform"}

# 依赖声明种类（用于 API 泄漏检查：只有 api 才会传递给使用方）
DEP_KIND_RE = (
    r'(api|implementation|compileOnly|runtimeOnly|testImplementation|'
    r'androidTestImplementation|debugImplementation|releaseImplementation)'
    r'\s*\(\s*project\(\s*"([^"]+)"\s*\)'
)


def strip_comments(text: str) -> str:
    """去掉块注释与行注释，保留换行以维持行首 ^ 匹配（import / package）。"""
    text = re.sub(r'/\*.*?\*/', lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    text = re.sub(r'//[^\n]*', '', text)
    return text


def decl_header(text: str, start: int, limit: int = 600) -> str:
    """取一条声明的“头部”：从声明关键字到第一个顶层 '{' 之间（含构造参数列表）。"""
    depth = 0
    i = start
    end = min(len(text), start + limit)
    while i < end:
        c = text[i]
        if c in "(<":
            depth += 1
        elif c in ")>":
            depth -= 1
        elif c == "{" and depth <= 0:
            return text[start:i]
        i += 1
    return text[start:end]


def dep_kinds(gradle_text: str) -> dict[str, str]:
    """module -> 声明种类；同名多次声明时 api 优先。"""
    kinds: dict[str, str] = {}
    for m in re.finditer(DEP_KIND_RE, gradle_text):
        kind, dep = m.group(1), m.group(2).lstrip(":")
        prev = kinds.get(dep)
        if prev is None or (prev == "implementation" and kind == "api"):
            kinds[dep] = kind
    return kinds


def check_api_leakage(declared: list[str], errors: list[str]) -> None:
    """core 模块的公开声明不得引用 implementation 依赖的类型（应为 api）。"""
    for mod in declared:
        if not mod.startswith("core:"):
            continue
        gradle_file = ROOT / mod.replace(":", "/") / "build.gradle.kts"
        if not gradle_file.exists():
            continue
        impl_deps = {d for d, k in dep_kinds(gradle_file.read_text(encoding="utf-8")).items() if k == "implementation"}
        if not impl_deps:
            continue
        src_root = ROOT / mod.replace(":", "/") / "src/main/java"
        if not src_root.exists():
            continue
        for kt in src_root.rglob("*.kt"):
            rel = kt.relative_to(src_root).as_posix()
            code = strip_comments(kt.read_text(encoding="utf-8"))

            external: dict[str, str] = {}
            for m in re.finditer(r'^import\s+com\.autoledger\.([\w.]+)', code, re.M):
                simple = m.group(1).rsplit(".", 1)[-1]
                if simple == "*":
                    continue
                target = next(
                    (mm for mm, pp in MODULE_PKGS.items()
                     if mm != mod and ("com.autoledger." + m.group(1)).startswith(pp + ".")),
                    None,
                )
                if target:
                    external[simple] = target
            if not external:
                continue

            for m in re.finditer(r'\b(?:class|interface|object)\s+(\w+)', code):
                prefix = code[max(0, m.start() - 40):m.start()]
                if re.search(r'\b(private|internal)\b', prefix):
                    continue
                header = decl_header(code, m.start())
                for simple, target in external.items():
                    if target in impl_deps and re.search(r'\b' + re.escape(simple) + r'\b', header):
                        errors.append(
                            f"[API泄漏] {mod}:{rel} 公开声明引用了 {target} 的 {simple}，"
                            f"但该依赖声明为 implementation，应改为 api"
                        )


def main() -> int:
    errors: list[str] = []

    settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
    declared = sorted({m.lstrip(":") for m in re.findall(r'include\("([^"]+)"\)', settings)})

    # ---- 1. 模块落地检查
    for mod in declared:
        if mod not in MODULE_PKGS:
            errors.append(f"[模块] settings 里的 :{mod} 没有对应的包约定，无法做依赖检查")
            continue
        rel = mod.replace(":", "/")
        if not (ROOT / rel / "build.gradle.kts").exists():
            errors.append(f"[模块] {mod} 缺少 {rel}/build.gradle.kts")
        if mod not in JVM_MODULES and not (ROOT / rel / "src/main/AndroidManifest.xml").exists():
            errors.append(f"[模块] {mod} 是 Android 模块但缺少 AndroidManifest.xml")

    # ---- 3. 版本目录
    toml = (ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8")
    catalog: dict[str, set[str]] = {}
    section = None
    for line in toml.splitlines():
        s = line.strip()
        if s.startswith("["):
            section = s.strip("[]").strip()
            catalog.setdefault(section, set())
            continue
        if section and "=" in s and not s.startswith("#"):
            key = s.split("=")[0].strip().strip('"')
            catalog[section].add(key)

    def alias_resolved(alias: str) -> bool:
        """libs.plugins.x.y -> plugins 段 + `x-y`；libs.a.b.c -> libraries 段 + `a-b-c`"""
        if alias.startswith("plugins."):
            rest = alias[len("plugins."):]
            return rest.replace(".", "-") in catalog.get("plugins", set())
        return alias.replace(".", "-") in catalog.get("libraries", set())

    for mod in declared:
        gradle_file = ROOT / mod.replace(":", "/") / "build.gradle.kts"
        if not gradle_file.exists():
            continue
        text = gradle_file.read_text(encoding="utf-8")

        # ---- 2. 依赖声明完整性
        for dep in re.findall(r'project\("([^"]+)"\)', text):
            if dep.lstrip(":") not in declared:
                errors.append(f"[依赖] {mod} 依赖了未声明的模块 {dep}")

        for alias in re.findall(r'libs\.([A-Za-z0-9_.]+)', text):
            if not alias_resolved(alias):
                errors.append(f"[版本目录] {mod} 使用的 libs.{alias} 在 toml 中不存在")

    # ---- 4/5/6/7. 源码检查
    for mod, pkg in MODULE_PKGS.items():
        src_root = ROOT / mod.replace(":", "/") / "src/main/java"
        if not src_root.exists():
            continue
        gradle_file = ROOT / mod.replace(":", "/") / "build.gradle.kts"
        gradle_text = gradle_file.read_text(encoding="utf-8") if gradle_file.exists() else ""
        declared_deps = {d.lstrip(":") for d in re.findall(r'project\("([^"]+)"\)', gradle_text)}

        for kt in src_root.rglob("*.kt"):
            rel_path = kt.relative_to(src_root).as_posix()
            content = kt.read_text(encoding="utf-8")

            # 4. package 必须与目录一致
            package_match = re.search(r'^package\s+([\w.]+)', content, re.M)
            if not package_match:
                errors.append(f"[包名] {mod}:{rel_path} 缺少 package 声明")
                continue
            actual_pkg = package_match.group(1)
            expected_pkg = rel_path.rsplit("/", 1)[0].replace("/", ".")
            if actual_pkg != expected_pkg:
                errors.append(f"[包名] {mod}:{rel_path} package={actual_pkg} 与路径不符（应为 {expected_pkg}）")
            if not actual_pkg.startswith(pkg):
                errors.append(f"[包名] {mod}:{rel_path} 的 package 不属于模块约定 {pkg}")

            if "TODO()" in content:
                errors.append(f"[占位] {mod}:{rel_path} 含 TODO() 未实现代码")

            # 5/6. 跨模块 import 必须有依赖声明
            for imp in re.findall(r'^import\s+(com\.autoledger\.[\w.]+)', content, re.M):
                target_mod = next(
                    (m for m, p in MODULE_PKGS.items() if imp.startswith(p + ".") and m != mod),
                    None,
                )
                if target_mod is None:
                    continue
                if target_mod not in declared_deps:
                    errors.append(f"[依赖] {mod} 用了 {target_mod}（{imp}）但未在 build.gradle.kts 声明")
                if mod.startswith("core:") and target_mod.startswith("feature:"):
                    errors.append(f"[分层] core 层 {mod} 不允许依赖 feature 层 {target_mod}")
                if mod.startswith("feature:") and target_mod == "app":
                    errors.append(f"[分层] feature 层 {mod} 不允许依赖 app")

    # ---- 8. API 泄漏（core 模块公开声明引用 implementation 依赖）
    check_api_leakage(declared, errors)

    if errors:
        print(f"== 发现 {len(errors)} 个阻塞级问题 ==")
        for e in errors:
            print("  x " + e)
        return 1

    print("静态一致性校验通过：模块、依赖、包名、分层、版本目录全部自洽")
    return 0


if __name__ == "__main__":
    sys.exit(main())
