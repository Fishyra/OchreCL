#!/usr/bin/env python3
"""Build and publish reviewed launcher updates, optionally with four full distributions."""
import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
import zipfile
from pathlib import Path

REPO = 'Fishyra/OchreCL'


def command(*args, cwd=None):
    """Use the user's configured CLI authentication without reading credentials."""
    if args[:2] == ('git', 'push'):
        args = ('git', '-c', 'http.version=HTTP/1.1', '-c', 'http.lowSpeedLimit=1', '-c', 'http.lowSpeedTime=45', *args[1:])
    return subprocess.check_output(args, cwd=cwd, text=True, timeout=600).strip()


def clean_repo(path, origin):
    """Reject wrong repositories, branches and uncommitted changes."""
    if command('git', 'remote', 'get-url', 'origin', cwd=path).removesuffix('.git') != origin:
        raise RuntimeError('仓库地址不正确：' + str(path))
    if command('git', 'branch', '--show-current', cwd=path) != 'main':
        raise RuntimeError('请切换到 main：' + str(path))
    if command('git', 'status', '--porcelain', cwd=path):
        raise RuntimeError('请先检查并提交仓库的本地修改：' + str(path))


def website_text(original, version, sha, assets=None):
    """Advertise the new updater without inventing nonexistent full installer assets."""
    if assets is not None:
        badge = re.search(r'<span class="version-tag">([0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.]+)?)', original)
        if badge is None: raise RuntimeError('官网版本标签未找到')
        result = original.replace(badge.group(1), version)
        result = re.sub(r'<!-- ochre-update-notice:start -->[\s\S]*?<!-- ochre-update-notice:end -->', '', result)
        def card(match):
            block = match.group(0)
            for asset in assets:
                if asset.name in block:
                    block = re.sub(r'约 [\d.]+ MiB', f'约 {asset.stat().st_size / 1048576:.1f} MiB', block)
            return block
        result = re.sub(r'<article class="download-card"[\s\S]*?</article>', card, result)
        links = re.findall(r'href="(https://github.com/Fishyra/OchreCL/releases/download/[^"]+)"', result)
        installers = [a.name for a in assets if a.name.endswith(('.dmg', '.zip', '.tar.gz')) and a.name not in {'OchreBridge-source.zip', 'OchreCL-source.zip'}]
        if len(links) != 4 or set(url.rsplit('/', 1)[1] for url in links) != set(installers):
            raise RuntimeError('官网四个平台的直链与安装包不对应')
        return result
    notice = ('<!-- ochre-update-notice:start -->\n'
              '<div class="download-note" role="note"><p><strong>启动器更新：' + version + '</strong><br>'
              '已安装 0.3.0-alpha.1 或更新版本？请在「关于 → 启动器更新」下载更新，重启生效。'
              '下面的完整安装包保留各自标注的版本；新用户安装后请检查更新。'
              '<br>更新包 SHA-256：<code>' + sha + '</code></p></div>\n'
              '<!-- ochre-update-notice:end -->')
    marker = r'<!-- ochre-update-notice:start -->[\s\S]*?<!-- ochre-update-notice:end -->'
    if re.search(marker, original):
        result = re.sub(marker, lambda _: notice, original)
    else:
        if original.count('<div class="download-grid">') != 1:
            raise RuntimeError('官网排版已变化，请先适配发布工具')
        result = original.replace('<div class="download-grid">', notice + '\n<div class="download-grid">')
    # Keep full-installer version badges and download links unchanged.
    if 'id="download-macos-intel"' not in result:
        raise RuntimeError('缺少预期的官网安装包卡片')
    return result


def publish(args):
    """Validate locally, then publish a draft and push a narrowly scoped website change."""
    config = json.loads(Path(args.config).read_text())
    source, site = Path(config['source']), Path(config['website'])
    gh = config['gh']
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-(?:alpha|beta|rc)\.\d+)?', args.version):
        raise RuntimeError('版本号格式示例：0.3.0-alpha.3 或 0.3.0')
    jar = Path(args.jar).resolve()
    if jar.suffix.lower() != '.jar' or not 0 < jar.stat().st_size <= 160 * 1024 * 1024:
        raise RuntimeError('请选择不超过 160 MiB 的更新 JAR')
    with zipfile.ZipFile(jar) as archive:
        markers = ['org/jackhuang/hmcl/Main.class', 'org/jackhuang/hmcl/ui/decorator/OchreShell.class']
        if any(name not in archive.namelist() for name in markers) or archive.testzip():
            raise RuntimeError('文件不是有效的 ØchreCL 更新包')
    sha = hashlib.sha256(jar.read_bytes()).hexdigest()
    clean_repo(source, 'https://github.com/Fishyra/OchreCL')
    clean_repo(site, 'https://github.com/Fishyra/fishyra.github.io')
    notes = Path(args.notes).read_text().strip()
    if not notes: raise RuntimeError('请填写更新说明与已知限制')
    from source_snapshot import prepare
    head = prepare(args.source, jar, source, args.version)
    homepage = site / 'ochrecl/index.html'
    original = homepage.read_text()
    notes = Path(args.notes).read_text().strip()
    if not notes:
        raise RuntimeError('请填写更新说明与已知限制')
    print('版本：' + args.version, flush=True)
    print('文件：' + jar.name + '（上传时固定命名 OchreCL-update.jar）', flush=True)
    print('源码提交：' + head, flush=True)
    print('SHA-256：' + sha, flush=True)
    print('发布到：Fishyra/OchreCL；官网：Fishyra/fishyra.github.io', flush=True)
    assets = None
    if args.full:
        from full_packages import build
        assets = build(config, jar, args.version, source, head)
        expected_sums = ''.join(hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + p.name + '\n' for p in assets[:-1])
        if assets[-1].read_text() != expected_sums: raise RuntimeError('打包校验清单不一致，请检查本地缓存')
        (assets[0].parent / 'Release-notes.md').write_text(notes + '\n')
        print('将发布四种新版完整安装包，并更新官网对应直链及大小。', flush=True)
    else:
        print('现有四种完整安装包及其链接保持不变；官网新增本次更新提示。', flush=True)
    changed = website_text(original, args.version, sha, assets)
    if not args.publish:
        print('预检通过。尚未上传或修改任何远程内容。', flush=True)
        return
    if command(gh, 'api', 'user', '--jq', '.login') != 'Fishyra':
        raise RuntimeError('请先以 Fishyra 登录 GitHub CLI')
    tag = 'v' + args.version
    with tempfile.TemporaryDirectory(prefix='ochre-release-') as tmp:
        folder = Path(tmp)
        payload = folder / 'OchreCL-update.jar'
        payload.write_bytes(jar.read_bytes())
        if hashlib.sha256(payload.read_bytes()).hexdigest() != sha:
            raise RuntimeError('文件在预检后发生变化')
        sums = folder / 'SHA256SUMS.txt'
        source_zip = folder / 'OchreCL-source.zip'
        if assets is None: command('git', 'archive', '--format=zip', '--prefix=OchreCL-' + args.version + '/', '--output=' + str(source_zip), head, cwd=source)
        sums.write_text(sha + '  OchreCL-update.jar\n' + (hashlib.sha256(source_zip.read_bytes()).hexdigest() + '  OchreCL-source.zip\n' if assets is None else ''))
        upload = assets if assets is not None else [payload, source_zip, sums]
        body = folder / 'notes.md'
        body.write_text(notes + ('\n\n提供 macOS ARM64、macOS Intel、Windows x64、Linux x64 完整包及启动器更新 JAR。完整包沿用已验证的运行环境模板；尚未新增跨平台原生实测。安装包未进行开发者签名与公证。\n' if args.full else '\n\n本次仅发布启动器更新 JAR。已有用户在关于页面检查更新，下载后重启生效。新用户请从官网下载当前完整安装包，再检查更新。\n'))
        expected = {p.name: 'sha256:' + hashlib.sha256(p.read_bytes()).hexdigest() for p in upload}
        releases = json.loads(command(gh, 'api', 'repos/' + REPO + '/releases?per_page=100'))
        def order(value):
            match = re.fullmatch(r'v?(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)\.(\d+))?', value)
            if not match: return None
            return tuple(map(int, match.group(1, 2, 3))) + ({'alpha': 0, 'beta': 1, 'rc': 2, None: 3}[match.group(4)], int(match.group(5) or 0))
        if any(not r['draft'] and order(r['tag_name']) is not None and order(r['tag_name']) > order(args.version) for r in releases):
            raise RuntimeError('已有更新的公开版本，请不要回退官网版本提示')
        existing = next((r for r in releases if r['tag_name'] == tag), None)
        if existing is not None and existing.get('target_commitish') != head:
            raise RuntimeError('已有草稿或版本指向不同源码；已停止，不覆盖。请换新版本号或检查旧草稿。')
        if existing is None:
            clean_repo(source, 'https://github.com/Fishyra/OchreCL')
            if command('git', 'rev-parse', 'HEAD', cwd=source) != head: raise RuntimeError('打包期间源码提交发生变化，请重新预检')
            print('正在推送已提交的源码…', flush=True)
            command('git', 'push', 'origin', 'main', cwd=source)
            remote = json.loads(command(gh, 'api', 'repos/' + REPO + '/git/ref/heads/main'))
            if remote['object']['sha'] != head:
                raise RuntimeError('远程源码提交不一致')
            refs = command('git', 'ls-remote', '--tags', 'origin', 'refs/tags/' + tag, 'refs/tags/' + tag + '^{}', cwd=source)
            if refs:
                matches = dict(line.split()[::-1] for line in refs.splitlines())
                if matches.get('refs/tags/' + tag + '^{}', matches.get('refs/tags/' + tag)) != head:
                    raise RuntimeError('已有标签指向其他源码；不会覆盖')
            print('正在创建草稿并上传更新包…', flush=True)
            command(gh, 'release', 'create', tag, *map(str, upload), '--repo', REPO,
                    '--target', head, '--title', 'ØchreCL ' + args.version, '--notes-file', str(body), '--draft')
            releases = json.loads(command(gh, 'api', 'repos/' + REPO + '/releases?per_page=100'))
            existing = next(r for r in releases if r['tag_name'] == tag)
        if {a['name']: a.get('digest') for a in existing['assets']} != expected:
            raise RuntimeError('该版本附件与本次不一致；已停止，不覆盖已有版本。请检查草稿或换新版本号')
        if existing['draft']:
            print('附件摘要一致，正在发布…', flush=True)
            command(gh, 'release', 'edit', tag, '--repo', REPO, '--draft=false', '--prerelease=' + str('-' in args.version).lower())
        release = json.loads(command(gh, 'api', 'repos/' + REPO + '/releases/tags/' + tag))
        if release['draft'] or {a['name']: a.get('digest') for a in release['assets']} != expected:
            raise RuntimeError('线上更新包验证失败，官网未修改')
    print('更新包已公开。正在更新官网…', flush=True)
    # Prepare this commit only after validating published assets. Do not stage unrelated files.
    clean_repo(site, 'https://github.com/Fishyra/fishyra.github.io')
    if homepage.read_text() != original:
        raise RuntimeError('操作期间官网文件已变化，请重新预检')
    docs_path = site / 'ochrecl/docs/index.html'
    if original != changed:
        homepage.write_text(changed)
    if args.full:
        previous = re.search(r'<span class="version-tag">([0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.]+)?)', original).group(1)
        docs_path.write_text(docs_path.read_text().replace(previous, args.version))
        (site / 'ochrecl/assets/SHA256SUMS.txt').write_bytes(assets[-1].read_bytes())
    if command('git', 'status', '--porcelain', cwd=site):
        command('git', 'diff', '--check', cwd=site)
        command('git', 'add', 'ochrecl/index.html', *(['ochrecl/docs/index.html', 'ochrecl/assets/SHA256SUMS.txt'] if args.full else []), cwd=site)
        command('git', 'commit', '-m', 'Announce launcher update ' + args.version, cwd=site)
    command('git', 'push', 'origin', 'main', cwd=site)
    print('发布完成：' + release['html_url'], flush=True)
    print('官网提交已推送，GitHub Pages 仍需等待部署。完整安装包版本未伪造。', flush=True)
    if args.full and getattr(args, 'cleanup_platforms', False):
        try:
            from full_packages import cleanup_platform_assets
            cleanup_platform_assets(config, assets)
        except Exception as error:
            print('发布已成功，但本地清理未完成：' + str(error), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('config', 'jar', 'source', 'version', 'notes'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--publish', action='store_true')
    parser.add_argument('--cleanup-platforms', action='store_true', help='Remove verified local installers only after successful release and website push')
    parser.add_argument('--full', action='store_true', help='Build, verify and publish all four complete distributions')
    try:
        publish(parser.parse_args())
    except Exception as error:
        print('操作停止：' + str(error), flush=True)
        raise SystemExit(1)
