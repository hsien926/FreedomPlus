# Python · verify_ci_apk.py · Python 3 / Androguard 4.1.4 · APK 元数据与真实 DEX 回调校验
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path
from zipfile import ZipFile

CALLBACKS = {
    'system': 'io.github.fplus.core.hook.HPowerKeeperHighRefreshRate$install$1$5',
    'application': 'io.github.fplus.core.hook.HHighRefreshRate$hook_methods$1$1',
}


def project_version(config: Path, suffix: str) -> dict:
    text = config.read_text(encoding='utf-8')
    def field(name: str, pattern: str) -> str:
        matches = re.findall(r'^\s*' + name + r'\s+' + pattern, text, re.MULTILINE)
        if len(matches) != 1:
            raise ValueError(f'版本配置字段不唯一：{name}')
        return matches[0]
    version = field('versionName', r'"([^"\r\n]+)"')
    code = field('versionCode', r'(\d+)\b')
    package = field('applicationId', r'"([^"\r\n]+)"')
    if suffix and not re.fullmatch(r'[0-9a-f]{4,12}', suffix):
        raise ValueError('提交后缀必须为 4 至 12 位小写十六进制')
    return {'package_name': package, 'version_name': version + ('.' + suffix if suffix else ''),
            'version_code': code}


def parse_badging(line: str) -> dict:
    fields = re.findall(r"(?:^|\s)(name|versionCode|versionName)='([^']*)'", line)
    result = dict(fields)
    if len(fields) != 3 or set(result) != {'name', 'versionCode', 'versionName'}:
        raise ValueError('aapt 包元数据缺失或重复')
    return result


def inspect(apk: Path, expected: dict, aapt: str, mapping_path: Path | None) -> dict:
    from androguard.core.dex import DEX
    from loguru import logger
    logger.disable('androguard')
    result = subprocess.run([aapt, 'dump', 'badging', str(apk)], check=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    badging = result.stdout.decode('utf-8', errors='replace').splitlines()[0]
    metadata = parse_badging(badging)
    for key, field in [('package_name', 'name'), ('version_name', 'versionName'), ('version_code', 'versionCode')]:
        if metadata.get(field) != expected[key]:
            raise ValueError(f'APK {key} 不一致：{metadata.get(field)!r} / {expected[key]!r}')
    mapping = {}
    if mapping_path:
        mapping = dict(re.findall(r'^(\S+) -> (\S+):$', mapping_path.read_text(encoding='utf-8'), re.MULTILINE))
    targets = {'entry': ('io.github.fplus.HookInit', 'handleLoadPackage'),
               **{key: (name, 'beforeHookedMethod') for key, name in CALLBACKS.items()}}
    descriptors = {key: 'L' + mapping.get(name, name).replace('.', '/') + ';'
                   for key, (name, _) in targets.items()}
    found = {}
    with ZipFile(apk) as archive:
        damaged = archive.testzip()
        if damaged:
            raise ValueError(f'APK ZIP 校验失败：{damaged}')
        if archive.read('assets/xposed_init').decode('utf-8').strip() != 'io.github.fplus.HookInit':
            raise ValueError('Xposed 入口资产不一致')
        if archive.read('assets/version').decode('utf-8').strip() != f"{expected['version_name']}-{expected['version_code']}":
            raise ValueError('模块版本资产与 APK 元数据不一致')
        for name in archive.namelist():
            if not re.fullmatch(r'classes\d*\.dex', name):
                continue
            dex = DEX(archive.read(name))
            for cls in dex.get_classes():
                key = next((key for key, descriptor in descriptors.items() if descriptor == cls.get_name()), None)
                if key is None:
                    continue
                if key in found:
                    raise ValueError(f'DEX 出现重复目标类：{key}')
                method_name = targets[key][1]
                methods = [method for method in cls.get_methods() if method.get_name() == method_name]
                bodies = [[instruction.get_name() for instruction in method.get_instructions()] for method in methods]
                # 实际调用/读写必须存在；只保留入口字符串、类声明或空覆盖方法不算通过。
                live_body = any(any(instruction.startswith(('invoke-', 'iget', 'iput', 'sget', 'sput'))
                                    for instruction in body) for body in bodies)
                if not live_body:
                    raise ValueError(f'真实 DEX 回调缺失或为空：{key}.{method_name}')
                found[key] = {'class': cls.get_name(), 'method': method_name, 'nonempty': live_body}
    if set(found) != set(targets):
        raise ValueError(f'DEX 缺少目标回调：{sorted(set(targets) - set(found))}')
    return {'apk': str(apk), **expected, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
            'bytes': apk.stat().st_size, 'dex_callbacks': found, 'passed': True}


def main() -> None:
    parser = argparse.ArgumentParser(description='断言 APK 包名、版本资产、入口及系统/应用高刷 DEX 回调。')
    parser.add_argument('--config', type=Path, default=Path('app/build.gradle'))
    parser.add_argument('--suffix', default='')
    parser.add_argument('--print-version', action='store_true')
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--mapping', type=Path)
    parser.add_argument('--aapt', default='aapt')
    parser.add_argument('--out', type=Path)
    args = parser.parse_args()
    expected = project_version(args.config, args.suffix)
    if args.print_version:
        for key, value in expected.items():
            print(f'{key}={value}')
        return
    if not args.apk or not args.out:
        parser.error('校验 APK 必须指定 --apk 和 --out')
    if args.out.exists():
        raise FileExistsError(args.out)
    result = inspect(args.apk.resolve(), expected, args.aapt, args.mapping)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
