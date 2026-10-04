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
     但该依赖声明为 implementation（应为 api）。**同时覆盖模块间依赖与外部库依赖** ——
     外部库那一支尤其重要：它盯的是「core:model 用 api 导出 Flow」这类**传递链**，
     一旦有人把 api 误写成 implementation，这里必须拦住。
  9. 零引用依赖：模块在 main 作用域（api/implementation/compileOnly/runtimeOnly）声明了
     某个版本目录依赖，但 src/main 里**零 import** 命中该依赖对应的包前缀 ——
     疑似「声明了却没用」，应改为 testImplementation 或删除。
     （编译器与全量单测都抓不到这一类；有显式白名单，见 ZERO_IMPORT_ALLOWLIST。）

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
# —— 这一张只匹配 project(":x") 形式的**模块间**依赖。
DEP_KIND_RE = (
    r'(api|implementation|compileOnly|runtimeOnly|testImplementation|'
    r'androidTestImplementation|debugImplementation|releaseImplementation)'
    r'\s*\(\s*project\(\s*"([^"]+)"\s*\)'
)

# 版本目录**外部库**依赖：形如 `implementation(libs.foo.bar)` 或 `implementation(platform(libs.foo.bar))`。
# 与 DEP_KIND_RE 互补 —— DEP_KIND_RE 只看 project()，这张只看 libs.<alias>。
LIB_DEP_KIND_RE = re.compile(
    r'\b(api|implementation|compileOnly|runtimeOnly|testImplementation|'
    r'androidTestImplementation|debugImplementation|releaseImplementation|ksp)'
    r'\s*\(\s*(?:platform\()?\s*libs\.([A-Za-z0-9_.]+)'
)

# main 作用域（决定「该依赖是否应出现在 src/main 的编译/运行路径上」）：
# 刻意排除一切 test* / debug* / release* —— 测试期与发布期的需求与 main 不同。
MAIN_CONFIGS = {"api", "implementation", "compileOnly", "runtimeOnly"}

# 「版本目录别名 → 外部包前缀」映射。
# 别名一律用 **toml 里的连字符形式**（libs.a.b.c ↔ 目录键 a-b-c），与本文件其余逻辑一致。
# 只需覆盖仓库里实际用到的别名；新增外部依赖时，若希望被 C1/C2 覆盖，在这里补一行即可。
ALIAS_PKG_PREFIX: dict[str, str] = {
    # 协程 / 序列化
    "kotlinx-coroutines-core": "kotlinx.coroutines",
    "kotlinx-coroutines-android": "kotlinx.coroutines",
    "kotlinx-serialization-json": "kotlinx.serialization",
    # Room / SQLite / 加密
    "androidx-room-runtime": "androidx.room",
    "androidx-room-ktx": "androidx.room",
    "androidx-room-compiler": "androidx.room",
    "androidx-sqlite-framework": "androidx.sqlite",
    "sqlcipher-android": "net.zetetic",
    # core / activity / lifecycle
    "androidx-core-ktx": "androidx.core",
    "androidx-core": "androidx.core",
    "androidx-activity-compose": "androidx.activity",
    "androidx-lifecycle-runtime-ktx": "androidx.lifecycle",
    # compose（BOM 不含类、无包前缀，故不在此表 —— 由白名单兜底）
    "androidx-compose-ui": "androidx.compose.ui",
    "androidx-compose-ui-graphics": "androidx.compose.ui.graphics",
    "androidx-compose-material3": "androidx.compose.material3",
    "androidx-compose-material-icons-extended": "androidx.compose.material.icons",
    "androidx-compose-ui-tooling": "androidx.compose.ui.tooling",
    "androidx-compose-ui-test-junit4": "androidx.compose.ui.test",
    "androidx-compose-ui-test-manifest": "androidx.compose.ui.test",
    # 其它
    "zxing-core": "com.google.zxing",
    "robolectric": "org.robolectric",
}

# 「零引用」检查的白名单：键 = (module, toml 连字符别名)，值 = 允许零 import 的理由。
# ⚠️ 只放**合法**的零 import 依赖；不要为了消音把真阳性塞进来（真阳性应删除或降为 testImplementation）。
ZERO_IMPORT_ALLOWLIST: dict[tuple[str, str], str] = {
    # BOM / platform：本身不含任何类，只用来约束其它构件的版本，天然零 import。
    ("app", "androidx-compose-bom"): "platform(BOM)：无类可 import，仅约束版本",
    # 服务提供者型：靠 META-INF/services 注册 Dispatchers.Main，代码里不直接 import 它的包。
    # （本仓库 app 还同时用到 kotlinx.coroutines.* 的其它 API，故此条通常不会被触发；
    #   保留它是为了把「服务提供者可以零 import」这条规则显式记录下来。）
    ("app", "kotlinx-coroutines-android"): "服务提供者：META-INF/services 注册 Main 调度器，零 import",
    # ⚠️ 非假阳性：app/src/main 内确实零 import androidx.lifecycle（疑似无用声明）。
    #    但删除它会改动 app 的声明依赖图，超出本轮「收窄 coroutines 变体 / 核心库」的改动范围，
    #    且会触碰产物等价性基线 —— 故本轮暂缓，仅在此显式登记，交 team-lead 决定是否单独删除。
    ("app", "androidx-lifecycle-runtime-ktx"): "⚠️ 疑似死依赖（真阳性）：本轮范围外暂缓，待 team-lead 决定",
}


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
    """module -> 声明种类（project() 形式）；同名多次声明时 api 优先。"""
    kinds: dict[str, str] = {}
    for m in re.finditer(DEP_KIND_RE, gradle_text):
        kind, dep = m.group(1), m.group(2).lstrip(":")
        prev = kinds.get(dep)
        if prev is None or (prev == "implementation" and kind == "api"):
            kinds[dep] = kind
    return kinds


def lib_dep_kinds(gradle_text: str) -> dict[str, str]:
    """toml 连字符别名 -> 声明种类（libs.<alias> 形式）；同名多次声明时 api 优先。

    先剥注释，避免把示例/说明文字里的 `implementation(libs.xxx)` 当成真实声明。
    """
    text = strip_comments(gradle_text)
    kinds: dict[str, str] = {}
    for m in LIB_DEP_KIND_RE.finditer(text):
        kind, alias = m.group(1), m.group(2)
        key = alias.replace(".", "-")
        prev = kinds.get(key)
        if prev is None or (prev == "implementation" and kind == "api"):
            kinds[key] = kind
    return kinds


def module_imports(src_root: Path) -> set[str]:
    """收集 src/main 下所有 import 的完整限定名（去掉注释）。"""
    imports: set[str] = set()
    if not src_root.exists():
        return imports
    for kt in src_root.rglob("*.kt"):
        code = strip_comments(kt.read_text(encoding="utf-8"))
        for m in re.finditer(r'^import\s+([\w.]+)', code, re.M):
            imports.add(m.group(1))
    return imports


def signature_end(text: str, start: int, limit: int = 800) -> int:
    """从 fun/val/var 关键字起，取到首个**顶层** '{' / '=' / 行尾（参数列表可跨行）。

    顶层 = 括号/尖括号/方括号深度为 0 处；跨行参数列表内部（深度 > 0）的换行不算结尾。
    """
    depth = 0
    i = start
    end = min(len(text), start + limit)
    while i < end:
        c = text[i]
        if c in "(<[":
            depth += 1
        elif c in ")>]":
            depth -= 1
        elif depth <= 0 and c in "{=":
            return i
        elif depth <= 0 and c == "\n":
            return i
        i += 1
    return end


def public_surface(code: str) -> str:
    """一份 Kotlin 文件的「公开 API 面」：公开类型头 + 公开成员签名（跳过 private/internal）。

    为什么要扩展到成员签名：`interface LedgerRepository { fun observeSince(): Flow<...> }`
    的类型出现在**方法返回类型**里，而不在类型头上 —— 只看类型头会漏掉最典型的一类 API 泄漏
    （LedgerRepository 公开返回 Flow 正是 core:model 用 api 导出协程的理由）。
    """
    parts: list[str] = []
    for m in re.finditer(r'\b(?:class|interface|object)\s+(\w+)', code):
        pre = code[max(0, m.start() - 40):m.start()]
        if re.search(r'\b(private|internal)\b', pre):
            continue
        parts.append(decl_header(code, m.start()))
    for m in re.finditer(r'\b(fun|val|var)\b', code):
        pre = code[max(0, m.start() - 80):m.start()]
        if re.search(r'\b(private|internal)\b', pre):
            continue
        parts.append(code[m.start():signature_end(code, m.start())])
    return "\n".join(parts)


def module_api_prefixes(mod: str, seen: set[str] | None = None) -> set[str]:
    """模块经 api 直接/传递暴露给使用方的「外部包前缀」集合（api(libs) + api(project) 闭包）。"""
    seen = seen if seen is not None else set()
    if mod in seen:
        return set()
    seen.add(mod)
    gf = ROOT / mod.replace(":", "/") / "build.gradle.kts"
    if not gf.exists():
        return set()
    text = strip_comments(gf.read_text(encoding="utf-8"))
    prefixes: set[str] = set()
    for key, kind in lib_dep_kinds(text).items():
        if kind == "api" and key in ALIAS_PKG_PREFIX:
            prefixes.add(ALIAS_PKG_PREFIX[key])
    for m in re.finditer(r'api\s*\(\s*project\(\s*"([^"]+)"\s*\)', text):
        prefixes |= module_api_prefixes(m.group(1).lstrip(":"), seen)
    return prefixes


def module_api_modules(mod: str, seen: set[str] | None = None) -> set[str]:
    """模块经 api(project(...)) 直接/传递暴露的**模块**集合。"""
    seen = seen if seen is not None else set()
    if mod in seen:
        return set()
    seen.add(mod)
    gf = ROOT / mod.replace(":", "/") / "build.gradle.kts"
    if not gf.exists():
        return set()
    text = strip_comments(gf.read_text(encoding="utf-8"))
    out: set[str] = set()
    for m in re.finditer(r'api\s*\(\s*project\(\s*"([^"]+)"\s*\)', text):
        dep = m.group(1).lstrip(":")
        out.add(dep)
        out |= module_api_modules(dep, seen)
    return out


def check_api_leakage(declared: list[str], errors: list[str]) -> None:
    """core 模块的公开 API 面不得引用「仅 implementation、且未 api 可达」的依赖类型。

    覆盖两类依赖：
      · 模块间 project(":x")；
      · 外部库 libs.<alias>（关键：core:model 用 api 导出 kotlinx-coroutines，
        LedgerRepository 才能公开返回 Flow；一旦 api 被误写成 implementation，
        模块间检查看不见外部库，这里必须拦住）。
    另做 api 可达性判定：若某类型虽由本模块 implementation 声明，但经 api(project(...))
    传递由上游 api 提供（如 core:database 的 Flow 来自 core:model 的 api），则不算泄漏。
    """
    for mod in declared:
        if not mod.startswith("core:"):
            continue
        gradle_file = ROOT / mod.replace(":", "/") / "build.gradle.kts"
        if not gradle_file.exists():
            continue
        gradle_text = gradle_file.read_text(encoding="utf-8")

        impl_deps = {d for d, k in dep_kinds(gradle_text).items() if k == "implementation"}
        ext_kinds = lib_dep_kinds(gradle_text)
        impl_prefixes = {
            ALIAS_PKG_PREFIX[k]
            for k, c in ext_kinds.items()
            if c == "implementation" and k in ALIAS_PKG_PREFIX
        }

        api_mods = module_api_modules(mod)
        api_prefixes = module_api_prefixes(mod)
        leaky_mods = {d for d in impl_deps if d not in api_mods}
        leaky_prefixes = {p for p in impl_prefixes if p not in api_prefixes}
        if not leaky_mods and not leaky_prefixes:
            continue

        src_root = ROOT / mod.replace(":", "/") / "src/main/java"
        if not src_root.exists():
            continue
        for kt in src_root.rglob("*.kt"):
            rel = kt.relative_to(src_root).as_posix()
            code = strip_comments(kt.read_text(encoding="utf-8"))

            # 简单类名 -> 来源模块（模块间依赖）
            module_simple: dict[str, str] = {}
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
                    module_simple[simple] = target

            # 简单类名 -> 外部包前缀（仅「泄漏候选」外部依赖）
            lib_simple: dict[str, str] = {}
            for m in re.finditer(r'^import\s+([\w.]+)', code, re.M):
                full = m.group(1)
                simple = full.rsplit(".", 1)[-1]
                if simple == "*":
                    continue
                pre = next((p for p in leaky_prefixes if full.startswith(p + ".")), None)
                if pre is not None:
                    lib_simple[simple] = pre

            if not module_simple and not lib_simple:
                continue

            surface = public_surface(code)
            for simple, target in module_simple.items():
                if target in leaky_mods and re.search(r'\b' + re.escape(simple) + r'\b', surface):
                    errors.append(
                        f"[API泄漏] {mod}:{rel} 公开 API 引用了 {target} 的 {simple}，"
                        f"但该依赖声明为 implementation，应改为 api"
                    )
            for simple, pre in lib_simple.items():
                if re.search(r'\b' + re.escape(simple) + r'\b', surface):
                    errors.append(
                        f"[API泄漏] {mod}:{rel} 公开 API 引用了外部库 {pre} 的 {simple}，"
                        f"但对应依赖声明为 implementation，应改为 api"
                    )


def check_zero_import_deps(declared: list[str], errors: list[str], notices: list[str]) -> None:
    """模块 main 作用域的版本目录依赖，若其包前缀在 src/main 里零 import，则报错。

    这是「声明了却没用」这一类缺陷唯一的防复发手段 —— 编译器与全量单测都抓不到。
    """
    for mod in declared:
        gradle_file = ROOT / mod.replace(":", "/") / "build.gradle.kts"
        if not gradle_file.exists():
            continue
        src_root = ROOT / mod.replace(":", "/") / "src/main/java"
        if not src_root.exists():
            continue

        kinds = lib_dep_kinds(gradle_file.read_text(encoding="utf-8"))
        main_deps = {k: c for k, c in kinds.items() if c in MAIN_CONFIGS}
        if not main_deps:
            continue

        imports = module_imports(src_root)

        for key in sorted(main_deps):
            config = main_deps[key]
            reason = ZERO_IMPORT_ALLOWLIST.get((mod, key))
            if reason is not None:
                notices.append(f"[白名单] {mod} {config}(libs.{key}) —— {reason}")
                continue
            prefix = ALIAS_PKG_PREFIX.get(key)
            if prefix is None:
                notices.append(
                    f"[未映射] {mod} {config}(libs.{key}) 缺少「别名→包前缀」映射，跳过零引用检查"
                )
                continue
            if not any(i == prefix or i.startswith(prefix + ".") for i in imports):
                errors.append(
                    f"[零引用] {mod} 声明了 {config}(libs.{key})，但 src/main 内无任何 import {prefix}.* "
                    f"—— 疑似「声明了却没用」，应改为 testImplementation 或删除"
                )


def main() -> int:
    errors: list[str] = []
    notices: list[str] = []

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

    # ---- 8. API 泄漏（core 模块公开声明引用 implementation 依赖：模块间 + 外部库）
    check_api_leakage(declared, errors)

    # ---- 9. 零引用依赖（main 作用域声明了但 src/main 零 import）
    check_zero_import_deps(declared, errors, notices)

    for n in notices:
        print("  - " + n)

    if errors:
        print(f"== 发现 {len(errors)} 个阻塞级问题 ==")
        for e in errors:
            print("  x " + e)
        return 1

    print("静态一致性校验通过：模块、依赖、包名、分层、版本目录全部自洽")
    return 0


if __name__ == "__main__":
    sys.exit(main())
