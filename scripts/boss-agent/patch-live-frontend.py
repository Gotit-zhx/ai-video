#!/usr/bin/env python3
"""Add only the HR workspace to a captured live frontend release."""

import argparse
import pathlib
import re


def once(text, old, new):
    if text.count(old) != 1:
        raise ValueError(f"Expected exactly one live anchor: {old[:90]!r}")
    return text.replace(old, new, 1)


def patch_app(text):
    if "data-hr-route" in text:
        raise ValueError("Live app already has the HR route")
    anchor = """  if ((window.location.hash === "#shops" """
    insert = """  if (window.location.hash.startsWith('#hr-') && globalThis.HrWorkspace?.allowed()) {
    switchWorkspace('hr'); globalThis.HrWorkspace.enter(window.location.hash.slice(4)); return;
  }
"""
    # This anchor is present once in showDashboard and once in hashchange.
    text = once(text, anchor, insert + anchor)
    anchor = '  document.querySelectorAll("[data-knowledge-route]").forEach(link => { link.hidden = !canAccessKnowledge(); });\n'
    text = once(text, anchor, anchor + """  document.querySelectorAll("[data-hr-route]").forEach(link => {
    link.hidden = !(currentUser?.platformAdmin === true && currentUser?.username === "13800000000");
  });
""")
    anchor = "  document.querySelector('#knowledge-page').hidden = !isKnowledge;\n"
    text = once(text, anchor, anchor + """  const isHr = page === 'hr';
  document.querySelector('#hr-page').hidden = !isHr;
  document.querySelectorAll('[data-hr-route]').forEach(a => a.classList.toggle('active', isHr && a.hash === window.location.hash));
""")
    text = once(text, 'isKnowledge ? "知识库" : isShops ? "店铺管理" :',
                'isKnowledge ? "知识库" : isHr ? "人力资源" : isShops ? "店铺管理" :')
    text = once(text, "  if (isKnowledge) {\n    rememberProductCaptureRoute(false);",
                "  if (isKnowledge || isHr) {\n    rememberProductCaptureRoute(false);")
    text = once(text, 'window.location.hash.startsWith("#knowledge-")) {',
                'window.location.hash.startsWith("#knowledge-") || window.location.hash.startsWith("#hr-")) {')
    anchor = '  if (window.location.hash === "#shops" && currentUser && hasAnyPermission("product:view", "product:publish")) {\n'
    text = once(text, anchor, insert + anchor)
    return text


NAV = '''        <details class="nav-group" data-nav-group="hr" hidden><summary title="人力资源"><span aria-hidden="true">♧</span><span class="nav-group-label">人力资源</span><i aria-hidden="true">⌄</i></summary><div class="nav-children">
          <a href="#hr-overview" class="nav-item" data-hr-route="overview" data-nav-label="招聘工作台" title="招聘工作台"><span aria-hidden="true">▦</span><span class="nav-label">招聘工作台</span></a>
          <a href="#hr-jobs" class="nav-item" data-hr-route="jobs" data-nav-label="职位管理" title="职位管理"><span aria-hidden="true">▤</span><span class="nav-label">职位管理</span></a>
          <a href="#hr-talent" class="nav-item" data-hr-route="talent" data-nav-label="人才寻源" title="人才寻源"><span aria-hidden="true">◎</span><span class="nav-label">人才寻源</span></a>
          <a href="#hr-applications" class="nav-item" data-hr-route="applications" data-nav-label="投递申请" title="投递申请"><span aria-hidden="true">▣</span><span class="nav-label">投递申请</span></a>
          <a href="#hr-chats" class="nav-item" data-hr-route="chats" data-nav-label="沟通记录" title="沟通记录"><span aria-hidden="true">◌</span><span class="nav-label">沟通记录</span></a>
          <a href="#hr-resume" class="nav-item" data-hr-route="resume" data-nav-label="在线简历" title="在线简历"><span aria-hidden="true">▧</span><span class="nav-label">在线简历</span></a>
        </div></details>
'''


def patch_index(text):
    if "data-hr-route" in text:
        raise ValueError("Live index already has HR links")
    css = re.search(r'^  <link rel="stylesheet" href="/knowledge-workspace.css[^"]+">\n', text, re.M)
    if not css:
        raise ValueError("Knowledge stylesheet anchor missing")
    text = once(text, css.group(), css.group() + '  <link rel="stylesheet" href="/hr-workspace.css?v=20260924-1">\n')
    anchor = '        <details class="nav-group" data-nav-group="automation">'
    text = once(text, anchor, NAV + anchor)
    anchor = '      <section id="knowledge-page" class="workspace-body" hidden></section>\n'
    text = once(text, anchor, anchor + '      <section id="hr-page" class="workspace-body" hidden></section>\n')
    js = re.search(r'^  <script src="/app.js[^"]+" defer></script>\n', text, re.M)
    if not js:
        raise ValueError("App script anchor missing")
    text = once(text, js.group(), js.group() + '  <script src="/hr-workspace.js?v=20260924-1" defer></script>\n')
    return text


HR_PROXY = """    # Private recruiter bridge; its server validates the existing login session.
    location ^~ /api/v1/hr/ {
        access_log off;
        proxy_pass http://172.18.0.1:8765;
        proxy_set_header Cookie $http_cookie;
        proxy_set_header Host $host;
        proxy_connect_timeout 5s;
        proxy_read_timeout 100s;
        proxy_cache off;
        add_header Cache-Control "no-store" always;
    }

"""


def patch_nginx(text):
    if "location ^~ /api/v1/hr/" in text:
        raise ValueError("Live Nginx already has an HR location")
    return once(text, "    location /api/ {\n", HR_PROXY + "    location /api/ {\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=pathlib.Path)
    parser.add_argument("output", type=pathlib.Path)
    parser.add_argument("--kind", choices=("app", "index", "nginx"), required=True)
    args = parser.parse_args()
    patched = {"app": patch_app, "index": patch_index, "nginx": patch_nginx}[args.kind](
        args.input.read_text())
    args.output.write_text(patched)


if __name__ == "__main__":
    main()
