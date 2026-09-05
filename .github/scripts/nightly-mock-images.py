"""소비자 BOM에서 Nightly mock 이미지 태그를 결정하고 로컬 이미지를 검증한다."""

import argparse
import re
import subprocess
import tomllib
from pathlib import Path


def image_names(version: str) -> list[str]:
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ValueError("Nightly requires an official stable dependencies version")
    return [
        f"bluetape4k/{name}:{version}"
        for name in ("mock-web-server", "mock-webflux-server")
    ]


def read_version(catalog: Path) -> str:
    with catalog.open("rb") as source:
        version = tomllib.load(source)["versions"]["bluetape4k-dependencies-version"]
    image_names(version)
    return version


def inspect_images(version: str) -> None:
    subprocess.run(["docker", "image", "inspect", *image_names(version)], check=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--catalog", type=Path, default=Path("gradle/libs.versions.toml")
    )
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--inspect", action="store_true")
    args = parser.parse_args()
    version = read_version(args.catalog)
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as output:
            output.write(f"version={version}\n")
    if args.inspect:
        inspect_images(version)
    print(f"Nightly mock images: {', '.join(image_names(version))}")


if __name__ == "__main__":
    main()
