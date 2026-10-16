#!/usr/bin/env python3
"""
Собирает весь код Spring Boot проекта в один файл.
usage: python collect.py [путь_к_проекту] [выходной_файл]
"""
import sys
import os
from pathlib import Path

EXCLUDE_DIRS = {
    ".git", ".idea", ".vscode", ".mvn", ".gradle",
    "target", "build", "out", "bin", "node_modules",
    ".settings",
}

INCLUDE_EXT = {
    ".java", ".kt", ".xml", ".yml", ".yaml",
    ".properties", ".gradle", ".kts", ".sql",
}

# Файлы с такими именами включаем отдельно (у них нет расширения)
INCLUDE_NAMES = {"Dockerfile", "Makefile"}


def should_include(path: Path) -> bool:
    if path.name in INCLUDE_NAMES:
        return True
    return path.suffix in INCLUDE_EXT


def collect_files(root: Path):
    files = []
    for dirpath, dirnames, filenames in os.walk(root):
        # фильтруем директории in-place, чтобы os.walk не спускался в них
        dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIRS]
        for name in filenames:
            p = Path(dirpath) / name
            if should_include(p):
                files.append(p)
    files.sort()
    return files


def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    output = Path(sys.argv[2] if len(sys.argv) > 2 else "project_dump.txt").resolve()

    if not root.is_dir():
        print(f"Не найдена директория: {root}")
        sys.exit(1)

    files = collect_files(root)
    print(f"Найдено файлов: {len(files)}")
    print(f"Собираю в: {output}")

    with output.open("w", encoding="utf-8") as out:
        for f in files:
            rel = f.relative_to(root)
            sep = "=" * 80
            out.write(f"{sep}\n")
            out.write(f"FILE: {rel.as_posix()}\n")
            out.write(f"{sep}\n")
            try:
                out.write(f.read_text(encoding="utf-8"))
            except UnicodeDecodeError:
                out.write("<<< Binary or non-UTF-8 file, пропущено >>>\n")
            out.write("\n\n")

        # Итоговый список файлов
        sep = "=" * 80
        out.write(f"{sep}\n")
        out.write(f"СПИСОК ФАЙЛОВ ({len(files)} шт.)\n")
        out.write(f"{sep}\n")
        for f in files:
            out.write(f.relative_to(root).as_posix() + "\n")

    print(f"Готово: {output}")


if __name__ == "__main__":
    main()
