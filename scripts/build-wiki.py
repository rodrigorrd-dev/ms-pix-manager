#!/usr/bin/env python3
"""Gera a Wiki do GitHub a partir de README.md, subida-producao.md e docs/**.md.

Uso: scripts/build-wiki.py <pasta-da-wiki> [owner/repo] [branch]

A pasta é limpa (menos .git) e recriada: a fonte da verdade é o repositório, a Wiki é só uma vitrine gerada.
Links entre documentos viram links de página da Wiki; links para outros arquivos (yaml, json, código) viram links
para o arquivo no GitHub. Chamado pelo workflow .github/workflows/wiki.yml a cada push na main.
"""
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LINK = re.compile(r"(?<!!)\[([^\]]*)\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
FENCE = re.compile(r"^(```|~~~)")


def repo_slug():
    if len(sys.argv) > 2:
        return sys.argv[2]
    url = subprocess.run(["git", "-C", str(ROOT), "remote", "get-url", "origin"], capture_output=True, text=True).stdout.strip()
    return re.sub(r"(\.git)?$", "", re.sub(r"^.*github\.com[:/]", "", url))


def page_name(rel: Path):
    s = rel.as_posix()
    if s == "README.md":
        return "Home"
    if s == "docs/runbooks/README.md":
        return "Runbooks"
    if s.startswith("docs/runbooks/"):
        return "Runbook-" + rel.stem
    return rel.stem


def collect():
    files = [Path("README.md"), Path("subida-producao.md")]
    files += sorted(p.relative_to(ROOT) for p in (ROOT / "docs").rglob("*.md"))
    return [f for f in files if (ROOT / f).exists()]


def main():
    out = Path(sys.argv[1]).resolve()
    slug = repo_slug()
    branch = sys.argv[3] if len(sys.argv) > 3 else "main"
    blob, tree = f"https://github.com/{slug}/blob/{branch}/", f"https://github.com/{slug}/tree/{branch}/"
    files = collect()
    pages = {f.as_posix(): page_name(f) for f in files}

    out.mkdir(parents=True, exist_ok=True)
    for item in out.iterdir():
        if item.name == ".git":
            continue
        shutil.rmtree(item) if item.is_dir() else item.unlink()

    def rewrite(src: Path, target: str):
        if re.match(r"^([a-z][a-z0-9+.-]*:|#|/)", target, re.I):
            return target
        path, _, anchor = target.partition("#")
        resolved = os.path.normpath((src.parent / path).as_posix())
        suffix = f"#{anchor}" if anchor else ""
        if resolved in pages:
            return pages[resolved] + suffix
        full = ROOT / resolved
        if full.is_dir():
            return tree + resolved + suffix
        if full.exists():
            return blob + resolved + suffix
        return target

    for f in files:
        lines, in_fence = [], False
        for line in (ROOT / f).read_text(encoding="utf-8").splitlines():
            if FENCE.match(line.strip()):
                in_fence = not in_fence
            if not in_fence:
                line = LINK.sub(lambda m: f"[{m.group(1)}]({rewrite(f, m.group(2))})", line)
            lines.append(line)
        banner = (f"> Página gerada automaticamente de [`{f.as_posix()}`]({blob}{f.as_posix()}). "
                  f"Não edite aqui: a edição é sobrescrita; altere o arquivo no repositório.\n\n")
        (out / f"{pages[f.as_posix()]}.md").write_text(banner + "\n".join(lines) + "\n", encoding="utf-8")

    def link(label, rel):
        return f"- [{label}]({pages[rel]})" if rel in pages else ""

    def title(rel):
        for line in (ROOT / rel).read_text(encoding="utf-8").splitlines():
            if line.startswith("# "):
                return line[2:].strip()
        return Path(rel).stem

    docs = [f.as_posix() for f in files if f.parent.as_posix() == "docs"]
    runbooks = [f.as_posix() for f in files if f.parent.as_posix() == "docs/runbooks" and f.name != "README.md"]
    side = ["**[Início](Home)**", "", "### Documentação"]
    side += [link(title(r), r) for r in docs]
    side += ["- [Subida para produção](subida-producao)", "", "### Runbooks", "- [Índice](Runbooks)"]
    side += [link(title(r), r) for r in runbooks]
    side += ["", f"[Repositório](https://github.com/{slug})"]
    (out / "_Sidebar.md").write_text("\n".join(x for x in side if x is not None) + "\n", encoding="utf-8")
    print(f"{len(files)} páginas geradas em {out}")


if __name__ == "__main__":
    main()
