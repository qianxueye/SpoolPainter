#!/usr/bin/env python3
"""Export and replay the POS adaptation without overwriting a dirty checkout."""
from __future__ import annotations
import argparse
from pathlib import Path
import shutil
import subprocess
import sys

class PatchError(RuntimeError): pass

def git(repo: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    result = subprocess.run(['git', '-C', str(repo), *args], text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if check and result.returncode:
        raise PatchError(result.stderr.strip() or result.stdout.strip() or 'Git operation failed')
    return result

def commit(repo: Path, ref: str) -> str:
    return git(repo, 'rev-parse', '--verify', '--end-of-options', ref + '^{commit}').stdout.strip()

def clean(repo: Path) -> None:
    if git(repo, 'status', '--porcelain').stdout.strip():
        raise PatchError('Checkout has local changes. Commit/stash them explicitly; no files were reset.')
    state = Path(git(repo, 'rev-parse', '--git-path', 'rebase-apply').stdout.strip())
    if not state.is_absolute(): state = repo / state
    if state.exists(): raise PatchError('An existing git am/rebase operation must be resolved first.')

def export(repo: Path, base: str, head: str, out: Path) -> None:
    clean(repo)
    baseline, target = commit(repo, base), commit(repo, head)
    if git(repo, 'merge-base', '--is-ancestor', baseline, target, check=False).returncode:
        raise PatchError('Patch baseline is not an ancestor of the requested adaptation.')
    if out.exists() and any(out.iterdir()): raise PatchError('Output directory is not empty; refusing to overwrite it.')
    out.mkdir(parents=True, exist_ok=True)
    git(repo, 'format-patch', '--binary', '--full-index', '--no-signature', '-o', str(out), baseline + '..' + target)
    patches = sorted(out.glob('*.patch'))
    if not patches: raise PatchError('No adaptation commits exist after this baseline.')
    (out / 'series').write_text(''.join(p.name + '\n' for p in patches), encoding='utf-8')
    (out / 'base-ref').write_text(baseline + '\n', encoding='utf-8')
    (out / 'target-ref').write_text(target + '\n', encoding='utf-8')
    shutil.copy2(__file__, out / 'pos_patchset.py')
    (out / 'README.md').write_text('''# POS 适配补丁包

基于 SpoolPainter v2.4.1；该版本来自上游 v2 线路，不能直接当成较旧 main 的补丁。
在完整上游 Git 仓库中获取新版本，先确认工作区干净，再用新分支应用：

    python3 pos_patchset.py apply --repo /path/to/SpoolPainter --bundle /path/to/bundle --onto upstream/v2 --branch codex/pos-update

应用保留提交作者，使用 git am --3way。发生冲突时留在新分支，不重置或删除你的工作。
查看 git status，解决冲突后执行 git am --continue；放弃本次应用可显式执行 git am --abort。
完成后运行项目单元测试、lint、APK 构建及硬件验收。脚本不保证任意新版本无冲突。
升级 APK 需沿用已有签名，才能保留设备数据。厂家 SDK/固件与签名密钥不在此补丁包中。
''', encoding='utf-8')
    print(f'Exported {len(patches)} ordered patches to {out}')

def apply(repo: Path, bundle: Path, onto: str, branch: str) -> None:
    clean(repo)
    git(repo, 'check-ref-format', '--branch', branch)
    if git(repo, 'show-ref', '--verify', '--quiet', 'refs/heads/' + branch, check=False).returncode == 0:
        raise PatchError('Requested branch already exists; choose a fresh task branch.')
    baseline = commit(repo, (bundle / 'base-ref').read_text(encoding='utf-8').strip())
    target = commit(repo, onto)
    if git(repo, 'merge-base', '--is-ancestor', baseline, target, check=False).returncode:
        raise PatchError('Selected upstream revision predates/diverges from the required baseline. Choose its newer release/v2 lineage.')
    paths = []
    for name in (bundle / 'series').read_text(encoding='utf-8').splitlines():
        if not name or Path(name).name != name or name.startswith('-') or not name.endswith('.patch'):
            raise PatchError('Invalid patch series entry.')
        path = bundle / name
        if not path.is_file() or path.is_symlink(): raise PatchError('Missing or unsafe patch entry.')
        paths.append(str(path))
    if not paths: raise PatchError('Patch series is empty.')
    git(repo, 'switch', '-c', branch, target)
    result = git(repo, 'am', '--3way', '--', *paths, check=False)
    if result.returncode:
        raise PatchError('Patch application stopped on the new branch. Resolve with git am --continue, or explicitly use git am --abort.\n' + result.stderr.strip())
    print(f'Applied {len(paths)} patches on {branch}. Run the application verification and hardware checks before release.')

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    exp = commands.add_parser('export'); exp.add_argument('--repo', type=Path, default=Path.cwd()); exp.add_argument('--base', default='v2.4.1'); exp.add_argument('--head', default='HEAD'); exp.add_argument('--out', type=Path, required=True)
    app = commands.add_parser('apply'); app.add_argument('--repo', type=Path, required=True); app.add_argument('--bundle', type=Path, required=True); app.add_argument('--onto', required=True); app.add_argument('--branch', required=True)
    args = parser.parse_args()
    try:
        if args.command == 'export': export(args.repo.resolve(), args.base, args.head, args.out.resolve())
        else: apply(args.repo.resolve(), args.bundle.resolve(), args.onto, args.branch)
    except (PatchError, OSError) as error:
        print(str(error), file=sys.stderr); sys.exit(1)

if __name__ == '__main__': main()
